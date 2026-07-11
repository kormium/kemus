# Benchmark history — closing the gap to Redis

Each stage records the numbers *after* a change, so the dynamics are visible. Keep appending; never
rewrite past stages.

**Setup (constant across stages unless noted):** valueSize=256, loopback, Lettuce client. Redis is a
**native** `redis-server` (v8.x) started with `--save '' --appendonly no`, reached via
`KEMUS_REDIS_URI=redis://localhost:6379` (so kemus and Redis are on equal footing — both native, no
Docker proxy). Throughput in ops/s. Numbers are representative single runs (JMH `-f1`, a few
iterations) — directional, not publication-grade; error bars noted where wide.

Macro takeaway so far: the engine itself is ~6–14M ops/s, so the entire gap to Redis is **transport**.

---

## Stage 0 — HTTP/REST path (`KemusServerBenchmark`)

The original kemus-server: `KemusClient` → HTTP/JSON → ktor CIO → engine. With connection reuse (Java
engine; the CIO client does *not* reuse — see ReuseProbe).

| Op  | kemus HTTP | native Redis | Redis faster |
|-----|-----------:|-------------:|-------------:|
| GET |    ~7,360  |     ~41,100  |        ~5.6× |
| SET |    ~7,360  |     ~42,500  |        ~5.8× |
| INCR|    ~5,960  |     ~41,700  |        ~7.0× |

Pipelining: **impossible** (one command per POST). The HTTP envelope + per-request handling caps the
path at a few thousand ops/s — ~6× slower than Redis and structurally unable to pipeline.

---

## Stage 1 — native RESP-over-TCP server (`kemus-resp-server`, `KemusRespBenchmark`)

New module: real Redis wire protocol over raw TCP (ktor-network), reusing the `Resp` codec. Same
Lettuce client as Redis — only the server differs.

Single connection / 1 thread:

| Op             | kemus RESP | native Redis | Redis faster |
|----------------|-----------:|-------------:|-------------:|
| GET            |   ~21,560  |     ~41,100  |        ~1.9× |
| SET            |   ~22,130  |     ~42,500  |        ~1.9× |
| INCR           |   ~22,330  |     ~41,700  |        ~1.9× |
| GET pipelined  |  ~558,000  |    ~698,000  |       ~1.25× |
| SET pipelined  |  ~473,000  |    ~654,000  |       ~1.38× |

Concurrency sweep (SET, shared connection, `-t N`):

| threads | kemus RESP | native Redis |
|--------:|-----------:|-------------:|
|       1 |    ~20,700 |     ~42,300  |
|       8 |   ~129,000 |    ~216,000  |
|      16 |   ~173,000 |    ~280,000  |

**Result: the gap closed from ~6× (HTTP) to ~1.9× single-op / ~1.3× pipelined vs native Redis.**
Throughput scales with concurrency but plateaus from 8→16 threads — the single global `Mutex`
(single-writer execution) is the ceiling. Beating Redis on aggregate requires sharding that lock.

---

## Stage 2 — sharded store (`ShardedKemus`) — measured, then **deprioritised**

Added `ShardedKemus`: N independent `Kemus` shards, each with its own `Mutex`, keys routed by hash
(cross-shard atomicity dropped, like Redis Cluster). Correct + tested. `RespServer` now takes
`KemusCommands` so it can serve a sharded store.

We expected sharding to break a mutex-bound plateau. **The clean engine-level data (no network) says
the mutex is _not_ the bottleneck for the wire path.** `EngineConcurrencyBenchmark.set` (200k ops per
invocation, SET over a 10k keyspace, `Dispatchers.Default`):

| concurrency | shardCount=1 (one Mutex) | shardCount=16 |
|------------:|-------------------------:|--------------:|
|           1 |               ~4,640,000 |    ~4,250,000 |
|           4 |               ~2,180,000 |    ~4,160,000 |
|           8 |               ~2,310,000 |    ~4,040,000 |
|          16 |               ~2,370,000 |    ~4,040,000 |

Reading:
- One global `Mutex` **collapses ~2× under concurrency** (4.6M→2.3M) — suspend/resume contention.
- Sharding removes that penalty (holds ~4.0M) → **~1.7× at concurrency=16** — so it works.
- But sharding does **not** scale *above* the single-coroutine rate (~4M flat), i.e. there is a shared
  ceiling behind the lock — almost certainly **allocation/GC** per command (`Entry`/`Str`/record list
  + `changeBus.tryEmit`).

Decisive point: the engine does **~4M ops/s**, the RESP wire path does **~21k ops/s** — the engine is
**~200× faster than the transport**. So the wire plateau (~174k on a shared connection in Stage 1) is
the single read-coroutine / per-command transport cost (syscalls, parse, encode, flush), **not** the
`Mutex`. Confirmed by the network sharding run (noisy, but shardCount 16 ≈ 1: 67k vs 64k at 8
threads). **Sharding fixes a problem we don't have yet.**

Kept the code (correct, tested) — it pays off once transport is fast enough that the engine becomes
the bottleneck. **Re-prioritised next:** cut per-command transport cost (RESP-encode + engine
allocations, `TCP_NODELAY`, unix socket) — lowers latency *and* raises single-op throughput — then
drain-based pipelined-reply batching.

---

## Stage 3 — `TCP_NODELAY` + allocation-light RESP reply encoder

Two transport changes in `kemus-resp-server`: enable `TCP_NODELAY` on accepted sockets (no Nagle),
and replace `Resp.encodeReply` (StringBuilder → String → ByteArray, value encoded twice) with
`Buffer.writeReply` — raw bytes for prefixes/CRLF, ASCII digits for integers with no `toString`, and
the bulk payload encoded exactly once into a reusable per-connection `kotlinx.io.Buffer`.

Before → after, RESP single-op (valueSize=256, native Redis on the same box):

| Metric        | before | after  | Δ      |
|---------------|-------:|-------:|-------:|
| GET p50 (µs)  |  45.2  |  44.1  |   ~0   |
| GET p99 (µs)  |  92.7  |  76.9  | −17%   |
| GET p999 (µs) | 163.8  | 105.0  | −36%   |
| SET p99 (µs)  |  81.2  |  75.0  |  −8%   |
| SET p999 (µs) | 123.1  |  98.3  | −20%   |
| GET thrpt     | ~21,560| ~22,420| +4%    |
| SET thrpt     | ~22,130| ~22,110| ~0     |
| GET pipelined | ~558k  | ~565k  | +1%    |
| SET pipelined | ~473k  | ~482k  | +2%    |

Reading (honest): **tail latency improved clearly (p99 −8…−17%, p999 −20…−36%)** — fewer allocations
= less GC jitter, plus no Nagle spikes. But **median latency and throughput barely moved**: at p50 the
cost is the loopback round-trip itself (syscalls + scheduling ~44µs), not reply encoding, so removing
write-side allocations doesn't shift the median. Single-op throughput = 1/median, hence ~flat.

Lesson for next stage: the **read path** is now the bigger allocation source — per command it does
`readUTF8Line` for the header + per arg `{readUTF8Line, readByteArray (allocates), readByte×2}`, i.e.
many small channel reads. Cutting those (drain-parse the available buffer; avoid per-arg allocation)
and/or a **unix-domain socket** (cheaper than TCP loopback) are the levers for p50/throughput. Then
drain-based pipelined-reply batching for the pipelined column.

vs native Redis after Stage 3: single-op still ~1.9× (median round-trip bound on both); pipelined
~1.25–1.35×.

---

## Stage 4 — read-path allocation cut (one dead end, then a win)

The read path was the bigger remaining allocation source: per command `readUTF8Line` for the header +
per arg `{readUTF8Line, readByteArray (allocates), readByte×2}`.

**Attempt 1 — `readBuffer(len+2)` per arg (REJECTED).** Idea: pull payload+CRLF into a kotlinx-io
`Buffer` and `readString` it, skipping the ByteArray. It allocates a fresh `Buffer` per arg, which
under pipelining cost more than it saved: **pipelined regressed ~31%** (GET 565k→392k, SET 482k→330k)
for only ~4% off p50. Reverted.

**Attempt 2 — reusable per-connection scratch array + `readFully` (KEPT).** One growable `ByteArray`
per connection; read payload+CRLF into it with a single `readFully`, `decodeToString` just the
payload. No per-arg allocation, one read call instead of three.

| Metric        | Stage 3 | Stage 4 | Δ      |
|---------------|--------:|--------:|-------:|
| GET p50 (µs)  |   44.1  |   42.8  |  −3%   |
| GET p99 (µs)  |   76.9  |   75.3  |  ~0    |
| GET p999 (µs) |  105.0  |  102.8  |  ~0    |
| GET thrpt     | ~22,420 | ~22,590 |  ~0    |
| GET pipelined | ~565k   | ~573k   |  +1%   |
| SET pipelined | ~482k   | ~540k   | **+12%** |

The win is in the throughput regime — **pipelined SET +12%** — because SET carries a 256-byte value
whose per-command `readByteArray` allocation is now gone (GC pressure drops). p50 only nudged (−3%):
again, the single-op median is the loopback round-trip, not parsing. Single-op throughput ~flat.

vs native Redis now: single-op ~1.8–1.9× (round-trip bound on both); **pipelined SET ~1.2×** (540k vs
~654k), pipelined GET ~1.25×. Lesson restated: median/single-op throughput is **syscall/round-trip
bound** — to move it needs fewer syscalls per op (unix-domain socket; or drain-based reply batching
that also removes a flush per command under load), not more parsing tricks.

---

## Stage 5 — drain-based reply batching (REJECTED — no effect, reverted)

Idea: stop flushing per command; flush only when `input.availableForRead == 0`, so a lone command
ships immediately (no latency cost) but a pipeline coalesces into one flush (fewer write syscalls).

Measured (vs Stage 4): single-op latency unchanged (good), but **pipelined did not improve — slightly
down** (GET 573k→560k, SET 540k→522k, within noise). Why: `availableForRead` reflects ktor's *internal*
read buffer, and ktor reads **on demand** — after `readFully` consumes exactly one command's bytes the
buffer is empty (`==0`) even when 63 more commands sit in the kernel socket buffer. So the check almost
always says "drained" and we flush per command anyway; the batching never engages, and the extra check
costs a hair. Reverted to Stage 4.

To actually batch, the server would need to **read ahead** — pull a whole socket chunk and parse all
complete commands from it before replying (handling commands split across chunk boundaries), i.e. a
buffer-oriented parser rather than the current read-one-command loop. Deferred: bigger rewrite, and at
~1.2–1.25× native Redis pipelined the payoff is modest. Single-op (the ~1.9× gap) is round-trip bound,
so the remaining lever there is a **unix-domain socket** (cheaper than TCP loopback) — the next thing
to try for the median.

### Standing after Stage 4 (current head), vs native Redis, valueSize=256, loopback

| Op            | kemus RESP | native Redis | Redis faster |
|---------------|-----------:|-------------:|-------------:|
| GET           |    ~22,600 |     ~41,900  |        ~1.85× |
| SET           |    ~22,300 |     ~42,500  |        ~1.9× |
| GET pipelined |    ~573k   |     ~697k    |        ~1.22× |
| SET pipelined |    ~540k   |     ~656k    |        ~1.21× |

---

## Stage 6 — skiplist-backed sorted set (structural, not transport)

A different axis from the transport stages: `KSortedSet.ordered()` used to `sortedWith` the whole set
on **every** read, so `ZRANGE` was O(n·log n) per call (even `ZRANGE 0 9` re-sorted everything).
Replaced with a `SortedSetIndex` skiplist that keeps `(score, member)` order incrementally (O(log n)
insert/remove, O(n) ordered walk), paired with the member→score hash for O(1) `ZSCORE`. `scores` is now
a read-only view; only `ZADD`/`ZREM` mutators changed.

Engine-level (no network), ops/s by set size:

| Benchmark      | size  | before (sort) | after (skiplist) | speedup |
|----------------|------:|--------------:|-----------------:|--------:|
| zrangeAll      |   100 |      ~255,000 |       ~1,395,000 |   ~5.5× |
| zrangeAll      |  1000 |       ~17,200 |          ~96,100 |   ~5.6× |
| zrangeAll      | 10000 |          ~991 |           ~9,360 |   ~9.4× |
| zrangeTop10    |   100 |      ~292,000 |       ~1,942,000 |   ~6.7× |
| zrangeTop10    |  1000 |       ~20,400 |         ~227,000 |  ~11.1× |
| zrangeTop10    | 10000 |        ~1,027 |          ~16,260 |  ~15.8× |
| zadd           |   100 |    ~3,160,000 |       ~2,020,000 |   ~0.6× |
| zadd           | 10000 |       ~37,000 |          ~54,000 |  ~noisy |

**ZRANGE is 5.5–15.8× faster** — the sort-on-read pathology is gone. `ZADD` costs a little more at
small sizes (skiplist insert vs a plain hash put) — the expected, Redis-matching trade for fast
ordered reads.

Two notes:
- `zrangeTop10` still builds the full ordered list then slices, so it's O(n) not O(k); a skiplist
  range-walk for `ZRANGE [s..e]` would make top-k truly O(k) — a further win, deferred.
- Separately, `zadd` degrades with set size in **both** versions: `reconcileMemory` (the maxmemory
  accounting) re-sums **all** entries on every write — an O(n)-per-write pathology independent of the
  sort fix. Worth a follow-up (track size incrementally).

---

## Stage 7 — O(k) `ZRANGE` range-walk + O(1) memory accounting (both follow-ups from Stage 6)

1. **`ZRANGE` top-k is now O(k).** Added `SortedSetIndex.range(start, end)` (walk only the requested
   slice) and made `ZRANGE` call it instead of materialising the whole ordered set.
2. **`reconcileMemory` is now O(1).** Each collection (`KList`/`KSet`/`KHash`/`KSortedSet`) keeps a
   running `byteSize`, updated incrementally in its add/remove methods; `valueSize` reads it instead of
   re-summing every element. (Eviction tests pass unchanged — the incremental sum matches the old
   full sum exactly.)

Engine-level ops/s by set size (cumulative: sort → skiplist → this):

| Benchmark   | size  | sort   | skiplist | **+O(k)/O(1)** | total speedup |
|-------------|------:|-------:|---------:|---------------:|--------------:|
| zrangeTop10 |   100 |   ~292k | ~1,942k  |    ~4,588,000  |       ~15.7× |
| zrangeTop10 |  1000 |  ~20.4k |  ~227k   |    ~4,627,000  |        ~226× |
| zrangeTop10 | 10000 |  ~1,027 | ~16,260  |    ~4,485,000  |      ~4,370× |
| zadd        |   100 | ~3,160k | ~2,020k  |    ~3,010,000  |         ~1.0× |
| zadd        |  1000 |   ~480k |  ~469k   |    ~2,230,000  |        ~4.6× |
| zadd        | 10000 |  ~37,000 | ~54,000 |    ~1,472,000  |        ~40×  |

`zrangeTop10` is now **flat ~4.5M ops/s regardless of set size** — true O(k); it no longer touches the
other n−k members. `zadd` no longer collapses with size (the O(n) memory re-sum is gone); the residual
decline is the skiplist's O(log n) insert + GC, not O(n). `zrangeAll` stays O(n) (it must return all n
members) — unchanged.

---

## Stage 8 — fair **native vs native** (the JVM benchmarks were flattering)

Every prior stage measured kemus on the **JVM** (the JMH harness is JVM-only) vs a **native** Redis —
unfair to no one's benefit but misleading. Here both are native: a release Kotlin/Native
`kemus-server.kexe` (RESP on :6400) and native `redis-server` (:6379), driven by the **same external
`redis-benchmark`** (256B values).

| Test                | kemus-native | redis-native | Redis faster |
|---------------------|-------------:|-------------:|-------------:|
| SET, 1 connection   |      ~25,700 |      ~63,500 |        ~2.5× |
| GET, 1 connection   |      ~26,700 |      ~64,900 |        ~2.4× |
| SET, 50 connections |      ~12,100 |     ~254,500 |         ~21× |
| GET, 50 connections |      ~12,900 |     ~253,800 |         ~20× |
| INCR, 50 connections|      ~11,500 |     ~254,500 |         ~22× |
| SET, pipeline ×64   |      ~87,100 |   ~2,645,700 |         ~30× |
| GET, pipeline ×64   |      ~71,800 |   ~3,676,700 |         ~51× |

**The JVM numbers (~1.9× single-op) were optimistic.** Native vs native the gap is ~2.4× at *one*
connection and blows out to **20–51× under concurrency / pipelining**.

The headline problem isn't the absolute single-op number — it's that **kemus-native scales
*negatively* with connections**: 26k at 1 connection but only **12k at 50** (slower than one!), with
wildly erratic latency (avg 1.8–94 ms, p50 0.663 ms vs Redis 0.103 ms). Redis instead scales 64k→254k.
So on native, throwing connections at kemus makes it *worse*. Likely Kotlin/Native under concurrent
allocation: the native memory manager/GC + the single global `Mutex` + ktor-network's native thread
model — all of which the JVM's mature JIT/GC hid. This is the thing to profile next (Stage 9).

---

## Stage 9 — diagnosing the native collapse: it's the runtime/GC, **not** the lock

Connection sweep (kemus-native, SET, 256B) — it scales to ~core count then collapses:

| connections |   1   |   2   |   4   |   8 (peak) |  16  |  32  |  50  |
|-------------|------:|------:|------:|-----------:|-----:|-----:|-----:|
| rps         | 25,400| 44,200| 58,300|     63,800 |51,200|37,900| 8,900|

It peaks at **~64k rps at 8 connections (≈ the 10-core count, and ≈ Redis's *single*-connection
throughput)**, then falls off a cliff once connections exceed cores.

To split the two suspects (mutex contention vs GC/runtime), the RESP server was pointed at a
**`ShardedKemus(16)`** (independent per-shard locks) via `KEMUS_RESP_SHARDS=16` and re-swept:

| connections |   1   |  8   |  16  |  32  |  50  |
|-------------|------:|-----:|-----:|-----:|-----:|
| unsharded   | 25,400|63,800|51,200|37,900| 8,900|
| sharded ×16 | 25,500|65,400|17,900|10,900|11,000|

**Sharding did not help — it was if anything worse.** So the collapse is **not** `Mutex` contention;
removing the shared lock changed nothing. The remaining cause is the **Kotlin/Native runtime**: GC
under per-command allocation pressure (every command allocates an argv list, argument strings, reply
bytes, plus per-connection buffers) compounded by thread oversubscription when connections > worker
threads. This matches the earlier engine finding (the ~4M-ops/s plateau was allocation/GC-bound, not
lock-bound). The JVM's mature JIT/GC hid all of this.

Implication: the lever to beat Redis on native is **drastically fewer allocations per command** (pool
argv/strings/buffers) and/or GC tuning — not the data structure and not sharding. It's also partly
bounded by the maturity of the Kotlin/Native GC. The `KEMUS_RESP_SHARDS` knob is kept as a diagnostic
but is not a fix.

---

## Stage 10 — cut read-path header allocations (REJECTED — didn't fix the collapse, reverted)

First concrete shot at "fewer allocations": parse the `*N`/`$len` integer headers **byte by byte**
(`readByte` loop) instead of `readUTF8Line`, removing 1 + N intermediate `String`s per command.

Native SET connection sweep, byte-parser vs the `readUTF8Line` baseline:

| connections |   1   |   2   |   4   |   8   |  16  |  32  |  50  |
|-------------|------:|------:|------:|------:|-----:|-----:|-----:|
| baseline    | 25,400| 44,200| 58,300| 63,800|51,200|37,900| 8,900|
| byte-parser | 25,800| 45,200| 58,200| 67,500|33,400| 8,500| 8,000|

Peak nudged up ~6% (c=8: 67.5k vs 63.8k) but the **collapse got worse, not better** (c=16: 33k vs 51k;
c=32: 8.5k vs 38k). So header `String`s were not the GC driver — and the byte-by-byte `readByte`
*added* cost under concurrency: each `readByte` that actually suspends (frequent under contention)
allocates a coroutine continuation, so trading 1+N `String`s for many continuations is a net loss
exactly where it hurts. Reverted to the `readUTF8Line` + reusable-scratch read path.

Takeaway: the native collapse is **not** addressable by trimming individual read-path allocations; the
dominant cost is deeper (unavoidable argument strings, engine-side `Entry`/`Str`/reply/`KemusChange`
allocations, and coroutine continuations under contention) and ultimately the Kotlin/Native GC under
concurrent load. Meaningfully beating it likely needs an allocation-free command pipeline end-to-end
(reuse argv, intern keys, pool replies — and a change feed that doesn't retain argv) and/or GC tuning,
which is a large effort bounded by the runtime. **The single-connection ~2.4× and per-core peak ~64k
(≈ Redis single-conn) is the honest current native standing.**

---

## Stage 11 — Tier-A engine allocation cuts (big JVM win; native collapse untouched — kept)

Three safe, contained allocation cuts in the engine: (1) reuse a single per-command `pending` record
collector instead of a fresh `ArrayList` per `execute` (safe — `execute` is serialised by the mutex);
(2) **reuse the existing `Entry` on overwrite** in `put` instead of remove+new; (3) only allocate/emit
a `KemusChange` when the change feed actually has a subscriber. (Eviction tests pass — the Entry-reuse
keeps memory accounting correct.)

JVM engine throughput (`EngineConcurrencyBenchmark.set`, shardCount=1, SET into a pre-seeded 10k
keyspace so every op overwrites):

| concurrency | before  | after Tier A | Δ     |
|------------:|--------:|-------------:|------:|
|           1 | ~4.64M  |      ~7.93M  | +71%  |
|           4 | ~2.18M  |      ~3.18M  | +46%  |
|           8 | ~2.31M  |      ~3.18M  | +38%  |
|          16 | ~2.37M  |      ~3.16M  | +34%  |

**The engine ceiling was allocation-bound — Tier A lifts it ~34–71% on the JVM.**

Native SET connection sweep, after Tier A vs the readUTF8Line baseline:

| connections |   1   |   2   |   4   |   8   |  16  |  32  |  50  |
|-------------|------:|------:|------:|------:|-----:|-----:|-----:|
| baseline    | 25,400| 44,200| 58,300| 63,800|51,200|37,900| 8,900|
| Tier A      | 26,700| 46,200| 60,500| 66,700|17,700| 6,500| 8,500|

On native it's only +4–5% at low concurrency (peak 66.7k vs 63.8k) and the **collapse is unchanged**
(if anything noisier in the unstable collapse region). Confirms once more: the native collapse is the
**Kotlin/Native runtime/GC under contention**, not per-command engine allocations. Kept the change — it
is a real, correct JVM win and a small native low-concurrency gain.

**Strategic conclusion (after Stages 8–11):** the JVM build is the throughput-competitive deployment
(engine now ~8M ops/s single-thread; concurrency handled gracefully by the mature JIT/GC); the native
build is for tiny footprint / instant startup but collapses past ~core-count connections on the
current Kotlin/Native runtime. Closing the native concurrency gap is a deep, runtime-bounded effort
(end-to-end allocation-free pipeline + GC tuning), not a quick win.

---

## Stage 12 — three-way table, one tool (`redis-benchmark`, 256B, Tier-A code)

All three measured by the **same external `redis-benchmark`** (kemus-JVM warmed up first). rps:

### SET
| scenario     | kemus-JVM | kemus-native |    redis | redis vs best-kemus |
|--------------|----------:|-------------:|---------:|--------------------:|
| 1 connection |    11,575 |       19,608 |   63,816 |               ~3.3× |
| 8 connections|    52,521 |       65,020 |  240,964 |               ~3.7× |
| 50 connections|  110,132 |       12,146 |  246,002 |               ~2.2× |
| pipeline ×64 |   190,843 |       89,751 |2,762,608 |              ~14.5× |

### GET
| scenario     | kemus-JVM | kemus-native |    redis | redis vs best-kemus |
|--------------|----------:|-------------:|---------:|--------------------:|
| 1 connection |    10,686 |       26,323 |   65,147 |               ~2.5× |
| 8 connections|    75,873 |       64,475 |  240,385 |               ~3.2× |
| 50 connections|  105,876 |       16,671 |  244,499 |               ~2.3× |
| pipeline ×64 |   215,522 |       71,378 |3,649,869 |              ~16.9× |

Reading:
- **1–8 connections:** native beats JVM (no per-op JIT warmup) — both peak ~65k at c=8.
- **50 connections (realistic):** **JVM scales to ~110k, native collapses to ~12k** (JVM ~9× native
  here). JVM is ~2.2–2.3× behind Redis; native ~20×.
- **Pipelining:** Redis dominates (2.7–3.6M); JVM 190–216k, native 71–90k — the widest gap (~15–17×).
- Redis leads everywhere; its margin is smallest at concurrent single-ops (~2.2×) and largest on
  pipelining.

**Bottom line:** for concurrent throughput, **kemus-JVM** is the deployment to ship (~2.2× behind
Redis, no collapse); **kemus-native** only wins at 1–8 connections (and on footprint/startup).
Pipelining is the biggest weakness of both kemus builds.

---

## Stage 13 — is the native collapse the GC? Diagnostic: **no.**

Cheap, definitive test before any GC tuning or a Rust rewrite: rebuild native with the GC **fully
disabled** (`-Pkotlin.native.binary.gc=noop`) and re-sweep. If the collapse vanishes, GC is the cause
and tuning (`gc=cms`, parallel marking, allocator) is the lever; if it persists, GC is exonerated.

Native SET sweep, `gc=noop` vs the normal (GC-on) baseline:

| connections |   1   |   4   |   8   |  16  |  32  |  50  |
|-------------|------:|------:|------:|-----:|-----:|-----:|
| GC on       | 25,400| 58,300| 63,800|51,200|37,900| 8,900|
| gc=noop     | 25,600| 58,300| 65,600|34,900| 6,800| 4,800|

**The collapse persists with no GC at all — in fact worse at high concurrency** (heap balloons without
collection → allocator/page pressure). So **GC is NOT the cause.** Combined with Stage 9 (sharding
didn't help → not the `Mutex`), both prime suspects are now ruled out by experiment:

- ❌ not the mutex (sharding changed nothing)
- ❌ not the GC (disabling it changed nothing — collapse remains)
- ✅ it's the **coroutine + ktor-network scheduling model under connection oversubscription**:
  continuation churn, coroutine context-switching and the selector when connections > worker threads.
  (Also why the Stage-10 byte-parser, which added `readByte` suspends, made high-concurrency *worse*.)

Strategic consequence: **GC/compiler tuning is a dead end for this problem** — answered empirically.
The fix is to replace the async model itself. A Rust thread-per-core, shared-nothing server with its
own event loop (io_uring/epoll) targets exactly this — not because it lacks a GC (disabling ours
didn't help) but because it has **no coroutine-dispatcher-over-shared-pool to collapse under
oversubscription**. This is the strongest technical argument for the Rust-core direction so far.

---

## Direction change (2026-06) — Kotlin-only, embedded-first, server = sync

Decided: kemus stays **Kotlin-only** (Rust core rejected — it breaks the identity), **embedded-first**
(the in-process engine is the product, ~8M ops/s), with the **server secondary and aimed at
synchronisation**, not Redis-throughput parity. Consequence: the Stages 0–13 RESP-vs-Redis chase was
optimising the secondary use case against the wrong competitor on the wrong metric. The server, its
benchmarks and tests stay intact (no regression), but *new* work targets sync metrics. (See the
`kemus-direction` memory.)

## Stage 14 — sync pull is now O(delta), not O(keyspace)

The real server metric: how fast a reconnecting client pulls the small delta of changed keys via
`changesSince(cursor)`. It used to scan + sort the **whole** change index every call
(`changeByKey.values.filter{…}.sortedBy{…}`) → O(keyspace). Replaced `changeByKey` with a
`HashMap<key, ChangeNode>` over a **doubly-linked list ordered by version** (monotonic versions ⇒ a
bump appends at the tail; re-bumping unlinks the old node). `changesSince` now walks back from the tail
until it crosses the cursor — O(delta). All sync tests pass (versions, tombstones, FLUSHALL, epoch,
compaction + stale-cursor resync, auto-compaction, hashing, persistence) + the client integration test.

`ChangeSyncBenchmark.pullDelta` (engine-level, 10-key delta, by keyspace size, ops/s):

| keyspace | before (O(keyspace)) | after (O(delta)) | speedup |
|---------:|---------------------:|-----------------:|--------:|
|    1,000 |             ~497,800 |      ~16,083,600 |    ~32× |
|   10,000 |              ~37,400 |      ~16,082,300 |   ~430× |
|  100,000 |               ~2,165 |      ~15,783,500 |  ~7,290× |

**Now flat at ~16M pulls/s regardless of store size** — the incremental sync pull no longer scales
with the keyspace. (Mirrors the Stage 6/7 ZRANGE fix: kill the scan-everything-on-every-read.)

Remaining sync gaps (next, by value): change index is **volatile** → server restart bumps the epoch
and forces every client to full-resync (persist/derive it); no **batched value fetch** (client must
GET each changed key — no MGET); no **pagination** for huge deltas / first sync; **push/merge/conflict**
is still "roll your own" (by design). Server perf/benchmarks/tests untouched.

## Stage 15 — durable change index (server restart no longer forces a mass resync)

Sync gap #2 from Stage 14: the change index lived only in memory, so a server restart regenerated the
epoch and renumbered versions → every client's cursor was rejected → full resync for everyone.

Fixed by making the index **durable inside the existing AOF** (design option (b) — no `Persistence`
interface change; meta-records are just commands whose name starts with `#`, which can't collide with
a real command):

- `#EPOCH <epoch> <counter> <floor>` — the journal's stable name + high-water version + floor.
- `#CHG <key> <version> <deleted>` — one per indexed key (live + tombstones), in version order.
- `#END` — closes the snapshot region.

`snapshotCommands()` (compaction) now leads with `#EPOCH`, then the data, then the `#CHG` records and
`#END`. `replay()` restores the index from those records and, crucially, **re-counts only the commands
outside the snapshot region** (a legacy log, or the tail appended after the last snapshot) via the
same `recordChange`, reproducing the exact live version sequence. The epoch is also persisted on first
open (as `#EPOCH`+`#END`) so even a never-compacted store keeps its epoch across restarts. Content
hashes are recomputed for live keys after load.

Result: a client's `(cursor, epoch)` taken before a restart is still honoured **incrementally** — only
the delta comes back, no mass resync. Two new tests cover it (compaction+reopen preserves
epoch/versions/hashes; epoch survives a plain restart) plus the whole existing sync suite, eviction,
persistence and native compile — all green. Server perf/benchmarks untouched.

Remaining sync gaps: batched value fetch (no MGET — client GETs each changed key), pagination for huge
deltas/first sync, and push/merge/conflict (still "roll your own").

## Stage 16 — MGET (batch value fetch for the sync pull)

Sync gap #2: after `changesSince` tells a client which keys changed, it had to `GET` each one (N round
trips). Added `MGET k1 k2 …` (engine dispatch, read-only; missing/non-string key → nil, 1:1 with the
requested keys, as in Redis) + typed helper `KemusCommands.mget(...)`. Chosen over inlining values in
the change page because the page already carries content hashes — the client skips keys that already
agree and `mget`s only the differing subset in one round trip. Works embedded, over the RESP server,
and over HTTP. Test + native compile green; server perf/benchmarks untouched.

## Stage 17 — paginated changesSince (version-ordered skiplist)

Sync gap #3: `changesSince` returned the whole delta in one page — a long-offline catch-up / first sync
of a big store = one giant response. Added a `limit` (engine + `ChangeSource` + REST `?limit=` +
client); a capped page's `cursor` is its last version, so the client pulls again from there until a
short page means "caught up".

Doing pagination *right* needs an O(log n) jump to an arbitrary cursor (a linked list gives O(n²)
across the pages of a first sync). So the change index moved from the Stage-14 doubly-linked list to a
**version-ordered skiplist** (`ChangeLog`): O(log n) insert/remove, O(log n + page) "entries after a
cursor" — efficient for **both** incremental and paginated pulls. `changeByKey` (key→entry) stays for
O(1) bump lookups; the skiplist mirrors the entries by version.

Incremental `pullDelta` (10-key delta), skiplist vs the linked list:

| keyspace | linked list (St.14) | skiplist (St.17) |
|---------:|--------------------:|-----------------:|
|    1,000 |             ~16.1M  |           ~9.1M  |
|   10,000 |             ~16.1M  |           ~8.9M  |
|  100,000 |             ~15.8M  |           ~7.6M  |

~2× slower incremental (the log-n descent + per-node cost) but still **~8M pulls/s** — orders of
magnitude beyond any real sync, which is network-bound; and still ~3,500× the original O(keyspace)
pathology (2,165/s at 100k). In exchange, pagination is now O(log n + page) instead of impossible.
All sync tests (incl. a new pagination test: chunks of 4 over 10 keys, exact order, caught-up cursor),
durability, eviction + native compile green. Server perf/benchmarks untouched.

Remaining sync gap: push/merge/conflict — still "roll your own" by design.
