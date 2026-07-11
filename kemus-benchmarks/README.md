# kemus-benchmarks

A JVM-only [JMH](https://github.com/openjdk/jmh) harness for answering one question: **how far is
kemus-server from Redis, and where does the gap come from?**

kemus-server speaks **HTTP/REST** (ktor CIO) while Redis speaks **RESP over TCP**, so this is a
client-perceived comparison — throughput/latency for the same command through each real path. To
turn "we're slower" into "we're slower *here*", the suite measures three layers:

| Benchmark               | Path                                                            | Isolates                          |
|-------------------------|----------------------------------------------------------------|-----------------------------------|
| `EngineBenchmark`       | `Kemus.execute(...)` directly, in-process                       | the engine floor (maps + `Mutex`) |
| `KemusServerBenchmark`  | `KemusClient` → HTTP/JSON → CIO ktor server → engine → RESP     | protocol + serialization + ktor   |
| `KemusRespBenchmark`    | Lettuce → RESP/TCP → `RespServer` (in-process) → engine          | our native RESP server vs Redis   |
| `RedisBenchmark`        | Lettuce → RESP/TCP → Redis (native via `KEMUS_REDIS_URI`, else container) | the baseline to beat     |

`KemusRespBenchmark` and `RedisBenchmark` use the **same** Lettuce client over the **same** RESP
protocol — only the server differs — so they are the most direct kemus-vs-Redis comparison. Read it
as a subtraction:

```
KemusServerBenchmark  -  EngineBenchmark   =  our HTTP/serialization tax
KemusRespBenchmark    -  EngineBenchmark   =  the native RESP transport cost (much smaller)
KemusRespBenchmark    -  RedisBenchmark    =  the remaining gap to Redis
```

The engine floor is already ~1000× faster than any transport (both are in-memory maps), so the whole
gap is transport — which is why the **native RESP server** (`kemus-resp-server`) exists and why it is
~3× the HTTP path.

Each benchmark sweeps a `valueSize` `@Param` (`16`, `256`, `4096` bytes). A flat curve means
fixed-cost bound (per-request overhead); a rising curve means per-byte bound (serialization/copy) —
that distinction tells you whether to cut round-trips or cut copies.

## Commands measured

`SET`, `GET`, `INCR` — one command per round trip, one connection, one client thread, no pipelining.
This matches `redis-benchmark`'s default mental model so the numbers are comparable.

## Running

Requires a running **Docker daemon** (Testcontainers starts Redis automatically):

```bash
./gradlew :kemus-benchmarks:jmh
```

Results are written to `kemus-benchmarks/build/results/jmh/results.json` (and printed as a table).

### Fair Redis baseline (native redis-server)

By default `RedisBenchmark` falls back to a Testcontainers `redis:7` container — convenient, but its
Docker network proxy adds latency and **understates** Redis (a warning is printed). For a fair fight,
point the benchmark at a native `redis-server` on the host via the `KEMUS_REDIS_URI` env var (env is
used because it is inherited by JMH's forked JVM):

```bash
redis-server --save '' --appendonly no &        # or: brew services start redis
KEMUS_REDIS_URI=redis://localhost:6379 \
  java -jar kemus-benchmarks/build/libs/kemus-benchmarks-*-jmh.jar 'RedisBenchmark' -f1
```

On macOS/Windows the container path is *always* proxied (Docker runs in a VM), so a host-native Redis
is the only way to get the real bar. The pipelined number below shows why this matters.

### Latency percentiles (p50/p99) and concurrency

The same benchmark methods run in other JMH modes via flags — no code change:

```bash
# latency distribution in microseconds (p0.50 / p0.99 / p0.999 ...)
java -jar kemus-benchmarks/build/libs/kemus-benchmarks-*-jmh.jar -bm sample -tu us -f1 -p valueSize=256

# concurrency: N client threads against the shared connection/server
java -jar kemus-benchmarks/build/libs/kemus-benchmarks-*-jmh.jar -t 8 -f1 -p valueSize=256
```

`*.pipelined*` benchmarks (Redis, depth 64) report the pipelined throughput ceiling. The HTTP path
has **no** pipelined counterpart — one command per POST — which is itself the finding: closing the
throughput gap needs the RESP wire-protocol server plus pipelining, not engine tuning.

### Without Docker

Skip the Redis layer and benchmark only the engine + HTTP server by setting the JMH include filter
in `build.gradle.kts` (`jmh { includes = listOf("Engine", "KemusServer") }`), or run the built jar
directly with a regex:

```bash
./gradlew :kemus-benchmarks:jmhJar
java -jar kemus-benchmarks/build/libs/kemus-benchmarks-*-jmh.jar 'Engine|KemusServer' -f1
```

### Faster feedback

The standalone jar takes the usual JMH flags — fewer/shorter iterations for a quick look:

```bash
java -jar kemus-benchmarks/build/libs/kemus-benchmarks-*-jmh.jar \
  -wi 1 -i 2 -r 2s -w 2s -f 1 -p valueSize=256
```

## Caveats

- The server runs **in the same JVM** over loopback, so these numbers exclude real network latency
  and reflect protocol/CPU overhead, not a deployed topology. That is deliberate — it keeps the
  comparison about the code we control.
- `runBlocking` wraps each networked op (the client API is `suspend`). For the engine, where a single
  op is sub-microsecond, `EngineBenchmark.setBatch` amortises that cost across 1,000 ops to show the
  true ceiling; treat the engine single-op numbers as harness-comparable, not absolute.
- One client thread measures latency-bound throughput. Raise `jmh { threads = N }` to probe how the
  single-writer `Mutex` and the CIO server behave under concurrency.
- **Redis runs in a Testcontainers container, behind Docker Desktop's network proxy** (vpnkit/gVisor
  on macOS/Windows), which adds real latency to every RESP round trip — so the Redis column
  *understates* Redis and must not be read as "kemus already matches Redis". For a fair fight, point
  `RedisBenchmark` at a native `redis-server` on the host; expect it to be several× faster than the
  containerised number.
- **The ktor CIO *client* does not reuse keep-alive connections** (ktor 3.5.0): it opens a fresh TCP
  connection per request, capping throughput and exhausting ephemeral ports under load
  (`java.net.BindException`). `ReuseProbe` demonstrates it (CIO fails at ~16k sequential requests;
  the `java.net.http` engine completes 30k). `KemusServerBenchmark` therefore uses the Java engine to
  measure the achievable path — but out of the box, kemus-client's documented CIO setup pays a
  connection handshake per command, which is a concrete competitiveness fix to make in kemus-client.

## What the numbers say (sample run, valueSize=256, 1 conn / 1 thread, loopback)

Equal footing — kemus RESP and Redis are **both native on loopback, same Lettuce client** (Redis via
`KEMUS_REDIS_URI=redis://localhost:6379`):

| Op             | engine (direct) | kemus HTTP (reused) | **kemus RESP** | Redis (native) | Redis faster |
|----------------|----------------:|--------------------:|---------------:|---------------:|-------------:|
| GET            |        ~13.7M/s |            ~7,360/s |   **~21,560/s** |       ~41,110/s |        ~1.9× |
| SET            |         ~9.4M/s |            ~7,360/s |   **~22,130/s** |       ~42,470/s |        ~1.9× |
| INCR           |         ~6.0M/s |            ~5,960/s |   **~22,330/s** |       ~41,700/s |        ~1.9× |
| GET pipelined  |               — |     — (no pipeline) |  **~558,000/s** |      ~698,000/s |       ~1.25× |
| SET pipelined  |               — |     — (no pipeline) |  **~473,000/s** |      ~654,000/s |       ~1.38× |

The native RESP server closed the gap from **~6× (HTTP)** to **~1.9× single-op** and **~1.3×
pipelined** vs native Redis — a hand-tuned C server. The engine is ~3 orders of magnitude faster than
any transport, so the remaining gap is pure per-command transport cost: Kotlin coroutine/`Mutex`
overhead, the RESP encode allocations, and a per-reply `flush`. Remaining levers, in order: **batch
pipelined replies** (the server flushes per reply today — closes most of the pipelined gap), then
**shard the single-writer `Mutex`** across cores (Redis executes single-threaded — this is the path
to beating it on aggregate throughput).
