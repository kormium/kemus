# kemus

An embedded, Kotlin Multiplatform in-memory data store with **Redis-flavoured commands** — running
everywhere Kotlin runs (JVM, Android, iOS, desktop/server native, and the **browser via JS/Wasm**).

The point: **one API, two backends.** Application code talks to the `KemusCommands` interface; that
interface is implemented both by the **embedded** in-process engine (works offline, on the client)
and by a **remote client** that speaks to a `kemus-server`. You can develop against the embedded
store, run offline on the client, and point the same code at a shared server — without rewriting it.

> Status: **0.1.0, early.** Engine, REST server and remote client are in place and tested on JVM and
> native; every module compiles for web (JS/Wasm). APIs are not yet stable.

---

## Modules

| Module              | Kind                    | What it is                                                           |
|---------------------|-------------------------|---------------------------------------------------------------------|
| `kemus-core`        | KMP library             | The embedded engine + the `KemusCommands` API, RESP codec and DTOs. |
| `kemus-client`      | KMP library             | `KemusClient` — the **same** `KemusCommands`, backed by a server.   |
| `kemus-ktor-plugin` | KMP library (JVM+native)| `KemusPlugin` + REST routes exposing a store. Embed in any ktor app.|
| `kemus-server`      | KMP app (JVM+native)    | Thin runnable standalone REST server (CIO) that installs the plugin.|
| `kemus-resp-server` | KMP library (JVM+native)| `RespServer` — the **real Redis wire protocol** (RESP) over raw TCP, for `redis-cli`/Lettuce/etc.|
| `kemus-benchmarks`  | JVM app (JMH)           | Harness comparing the engine, HTTP server, RESP server and Redis ([readme](kemus-benchmarks/README.md)).|

Every library module is multiplatform. The two server-side modules are built on ktor-server + the
CIO engine, which run on the JVM and native — there is no HTTP server in a browser, so they do not
target js/wasmJs. `kemus-benchmarks` is JVM-only (JMH).

## Targets

| Targets                                                              | core / client | ktor-plugin / server |
|---------------------------------------------------------------------|:-------------:|:--------------------:|
| **JVM** (toolchain 21)                                              | ✅            | ✅                   |
| **Android** (`minSdk` 24, `compileSdk` 36)                          | ✅            | —                    |
| **iOS** (`iosX64`, `iosArm64`, `iosSimulatorArm64`)                 | ✅            | —                    |
| **Native desktop/server** (`linuxX64`, `macosX64/Arm64`, `mingwX64`)| ✅            | ✅                   |
| **Web** (`js`, `wasmJs` — browser + Node)                           | ✅            | —                    |

## Dependencies / backends

Built with **Kotlin 2.4.0** / **Gradle 9.5.1**.

- **Concurrency** — `kotlinx-coroutines-core`. A single write lock (`Mutex`) serialises every
  command, i.e. a single-writer event loop: correct on real threads (JVM/Native) and the single
  JS/Wasm event loop, with no per-platform code.
- **Time / TTL** — `kotlinx-datetime` (`Clock`, injectable for testing).
- **Serialization** — `kotlinx-serialization-json` for the REST DTOs; a hand-rolled **RESP** codec
  for the command/AOF/remote-reply wire format.
- **Persistence backends** (pluggable `Persistence`):
  | Backend                | Where                                  | Notes                                    |
  |------------------------|----------------------------------------|------------------------------------------|
  | `NoPersistence`        | all targets (default)                  | purely volatile                          |
  | `InMemoryPersistence`  | all targets                            | volatile, exercises the AOF format       |
  | `FilePersistence`      | JVM, Android, native (via **okio**)    | append-only file + atomic snapshot       |

  The web targets have no filesystem, so they use the in-memory backend. `filePersistence(path)` is
  the convenience factory for the okio-backed AOF.
- **Server** — `ktor-server-core` + the multiplatform **CIO** engine (JVM + native), JSON via
  `ktor-serialization-kotlinx-json`, streaming via `ktor-server-sse`.
- **Remote client** — `ktor-client-core` (engine supplied by the caller) + content negotiation + SSE.

## Data types & features

Strings, lists, sets, hashes, sorted sets — with `TTL`/expiration, `Pub/Sub` and pattern
subscriptions (`PSUBSCRIBE`), all on the Redis command vocabulary (`SET`/`GET`/`MGET`/`INCR`, `HSET`,
`LPUSH`/`LRANGE`, `SADD`, `ZADD`/`ZRANGE`, `EXPIRE`, `KEYS`, `PUBLISH`, …).

**Binary values.** Store raw bytes with the typed `setBytes(key, ByteArray)` / `getBytes(key)` (or
the `SETB`/`GETB` commands). Bytes live as a `ByteArray` in memory — no UTF‑16 tax — and are base64‑encoded
only at the text boundaries (AOF, change‑feed, RESP wire), so they get the same TTL, `maxmemory`
accounting, persistence and offline→online sync as every other value. Handy for blobs like a
serialized search index (e.g. [kromus](https://github.com/kormium/kromus)).

---

## Quick start — embedded (client / offline)

```kotlin
import io.github.kemus.*

val store = Kemus()                       // volatile, all platforms
// val store = Kemus.open(filePersistence("data/kemus.aof"))   // durable (JVM/Android/native)

store.set("user:1", "Ada")
store.get("user:1")                       // "Ada"
store.expire("user:1", 30.seconds)

store.rpush("queue", "a", "b", "c")
store.lrange("queue", 0, -1)              // [a, b, c]

store.execute(listOf("ZADD", "board", "10", "ada"))   // full command set via raw argv

launch { store.subscribe("news").collect(::println) } // pub/sub
store.publish("news", "hello")
```

## Quick start — remote backend instead of embedded

`KemusClient` implements the same `KemusCommands` interface, so the only thing that changes is how
you construct the store:

```kotlin
import io.github.kemus.*
import io.github.kemus.client.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO     // pick a platform engine: CIO, Darwin, OkHttp, Js, …

val http = HttpClient(CIO) { kemusClient() }   // installs JSON + SSE
val store: KemusCommands = KemusClient(http, "http://localhost:6390")

// identical API and typed helpers as the embedded engine:
store.set("user:1", "Ada")
store.get("user:1")                       // "Ada" — fetched from the server
store.rpush("queue", "a", "b", "c")
store.subscribe("news").collect(::println)
```

Because both `Kemus` and `KemusClient` are `KemusCommands`, code can take the interface and stay
agnostic about whether it runs against an in-process store (offline) or a shared server:

```kotlin
suspend fun warmCache(store: KemusCommands) { store.set("ready", "1") }

warmCache(Kemus())                                       // offline / embedded
warmCache(KemusClient(http, "http://localhost:6390"))    // online / server
```

Replies use the lossless `application/resp` encoding over HTTP, so a `GET` comes back as a bulk
string, an `INCR` as an integer, an `LRANGE` as an array — exactly as from the embedded engine.

## Offline → online: roll your own sync

kemus deliberately ships **no** sync engine — and doesn't need to. Because both the embedded store
and the remote client are the same `KemusCommands`, you already have everything to reconcile them
*your way*: read and write both sides from one function and decide what "consistent" means for your
data. There is no built-in conflict policy to fight; you write the algorithm.

The one thing the engine adds is a **change-feed** — `Kemus.changes()` — so you don't have to
brute-force a full diff to learn what changed offline. It emits the canonical command behind every
committed mutation (`INCR` surfaces as the resulting `SET`, a TTL'd `SET` also emits `PEXPIREAT`),
which is exactly the key-level "what's dirty" signal a sync needs:

```kotlin
val local = Kemus.open(filePersistence("data/kemus.aof"))   // works offline

// Track keys mutated while offline (best-effort feed; see the caveat below).
val dirty = mutableSetOf<String>()
scope.launch { local.changes().collect { it.key?.let(dirty::add) } }

// ...later, when the network is back, reconcile however you like:
suspend fun reconcile(local: KemusCommands, remote: KemusCommands, dirty: Set<String>) {
    for (key in dirty) {
        val mine = local.get(key)
        if (mine != null) remote.set(key, mine)   // e.g. last-writer-wins, client side
        else remote.del(key)
    }
    dirty.clear()
}

reconcile(local, KemusClient(http, "http://localhost:6390"), dirty.toSet())
```

`changes()` delivery is **best-effort** (like pub/sub: non-blocking, oldest-dropped under
backpressure), so treat it as a live hint, not a durable log.

For the *server* side of the diff — "which keys changed since this device last connected" — construct
the store with `trackChanges = true` and pull through `ChangeSource.changesSince`. Both `Kemus` and
`KemusClient` implement `ChangeSource`, so a device diffs local against remote uniformly:

```kotlin
val remote = KemusClient(http, "http://localhost:6390")   // a ChangeSource

// Pull the delta in pages, fetch the changed values in one batch (MGET), apply, advance the cursor.
// `cursor` is whatever you persist locally between runs: a (since, epoch) pair, initially (0, null).
suspend fun pullFrom(local: Kemus, remote: KemusClient, cursor: Cursor) {
    while (true) {
        val page = remote.changesSince(cursor.since, cursor.epoch, limit = 500)
        // resyncRequired (first sync, server history compacted past us, or — only for a volatile
        // server — a restart): `page` already holds the full keyspace from 0; we just adopt its epoch.

        val live = page.changes.filterNot { it.deleted }
        val values = remote.mget(*live.map { it.key }.toTypedArray())   // one round trip for all values
        live.forEachIndexed { i, c -> values[i]?.let { local.set(c.key, it) } }
        page.changes.filter { it.deleted }.forEach { local.del(it.key) }

        cursor.save(since = page.cursor, epoch = page.epoch)           // resume here next time
        if (page.changes.size < 500) break                            // short page ⇒ caught up
    }
}
```

Enable `hashContents = true` (or `KEMUS_SYNC_HASH=1`) and every live `ChangeEntry` carries a stable
content fingerprint. Equal values hash equal across any kemus store, so a sync can **skip keys whose
two sides already agree** — filter the page to the keys whose `hash` differs from your local copy
before the `mget`, so you fetch only what actually changed. The hash is also a cursor-independent
anti-entropy check. It costs an `O(value-size)` hash per write, so it is opt-in on top of tracking.

The change index is **opt-in** (off by default; enabled per store, or via `KEMUS_SYNC=1` on the
standalone server) and, when the store is durable (`KEMUS_AOF`), the index is **persisted in the same
log**: a restart preserves the epoch and versions, so existing cursors keep pulling incrementally —
**no mass resync**. (A volatile store with no AOF loses the index on restart, like its data.) Pulls are
`O(log n + page)` regardless of keyspace size, paginated by `limit`. Deleted keys leave tombstones;
bound their memory with `compactChanges()` (periodically) or `tombstoneLimit` /
`KEMUS_SYNC_TOMBSTONE_LIMIT` (auto-compact once that many accumulate) — clients synced past the dropped
deletions are unaffected, ones still behind resync. Detecting *when* the network returns is still
platform-specific and yours to wire.

## Run the server

On the JVM:

```bash
KEMUS_PORT=6390 KEMUS_AOF=data/kemus.aof ./gradlew :kemus-server:run
```

Or as a native binary (no JVM needed) — build with `linkReleaseExecutable<Target>` and run the
produced `.kexe`:

```bash
./gradlew :kemus-server:linkReleaseExecutableMacosArm64
KEMUS_PORT=6390 ./kemus-server/build/bin/macosArm64/releaseExecutable/kemus-server.kexe
```

```
GET    /kv/{key}                 -> { "value": ... }   (404 if missing)
PUT    /kv/{key}                 <- { "value": ..., "ttlSeconds": ? }
DELETE /kv/{key}                 -> { "deleted": n }
GET    /keys?pattern=*           -> ["k1", "k2", ...]
GET    /changes?since=0&epoch=?  -> { "epoch", "cursor", "resyncRequired", "changes":[...] }  (sync; needs trackChanges)
POST   /command                  <- { "args": ["ZADD","board","10","ada"] }
                                    -> friendly JSON, or lossless RESP for Accept: application/resp
POST   /publish/{channel}        <- { "message": ... }  -> { "subscribers": n }
GET    /subscribe/{channel}      -> Server-Sent Events stream
GET    /psubscribe?pattern=u:*   -> Server-Sent Events stream (glob pattern subscription)
```

Or embed it in an existing ktor app via the plugin:

```kotlin
fun Application.myApp(store: Kemus) {
    install(KemusPlugin) {
        this.store = store
        path = "/cache"        // optional mount prefix
    }
    // ...or wire the routes by hand: routing { kemusRoutes(store) }
}
```

### Docker

CI publishes a **native (no-JVM)** multi-arch (`linux/amd64` + `linux/arm64`) `kemus-server` image
to Docker Hub — the Kotlin/Native binary on a distroless base, so the image is tiny:

```bash
docker run --rm -p 6390:6390 <dockerhub-user>/kemus-server
# durable: mount a volume and point the AOF at it
docker run --rm -p 6390:6390 -e KEMUS_AOF=/data/kemus.aof -v kemus-data:/data <dockerhub-user>/kemus-server
```

Build it yourself — stage the prebuilt binaries (cross-compiled from any host) where the Dockerfile
expects them, then buildx both arches:

```bash
./gradlew :kemus-server:linkReleaseExecutableLinuxX64 :kemus-server:linkReleaseExecutableLinuxArm64
mkdir -p docker/amd64 docker/arm64
cp kemus-server/build/bin/linuxX64/releaseExecutable/kemus-server.kexe   docker/amd64/kemus-server
cp kemus-server/build/bin/linuxArm64/releaseExecutable/kemus-server.kexe docker/arm64/kemus-server
docker buildx build --platform linux/amd64,linux/arm64 -t kemus-server .
```

## CI / CD

| Workflow      | Trigger                          | Does                                                            |
|---------------|----------------------------------|-----------------------------------------------------------------|
| `ci.yml`      | every push / PR                  | JVM + native (linux) tests, web compile, Windows cross-compile; Android/iOS + macOS native on a mac runner |
| `docs.yml`    | Markdown changes                 | offline internal-link check + (non-blocking) external links     |
| `publish.yml` | manual dispatch                  | publishes the libraries to Maven Central (from macOS, all targets) |
| `docker.yml`  | `v*` tags / manual dispatch      | builds the native binary and pushes the `kemus-server` image to Docker Hub |

Required repository secrets: `MAVEN_CENTRAL_USERNAME`/`MAVEN_CENTRAL_PASSWORD` and
`SIGNING_IN_MEMORY_KEY`/`SIGNING_IN_MEMORY_KEY_PASSWORD` (publishing); `DOCKERHUB_USERNAME`/
`DOCKERHUB_TOKEN` (Docker Hub).

## Building

```bash
./gradlew :kemus-core:jvmTest :kemus-ktor-plugin:jvmTest     # JVM (engine + server + remote client)
./gradlew :kemus-core:macosArm64Test                         # native (host)
./gradlew :kemus-core:compileKotlinJs :kemus-core:compileKotlinWasmJs        # web (core)
./gradlew :kemus-client:compileKotlinJs :kemus-client:compileKotlinWasmJs    # web (client)
./gradlew :kemus-server:linkDebugExecutableMacosArm64        # native server binary
```

## Benchmarks

`kemus-benchmarks` is a JMH harness that compares three layers — the embedded engine, kemus-server
over HTTP, and Redis (RESP) — so a "we're slower" turns into "we're slower *here*". Redis runs in a
Testcontainers container, so a Docker daemon is required.

```bash
./gradlew :kemus-benchmarks:jmh        # SET/GET/INCR across engine vs kemus-server vs Redis
```

The gap between kemus-server and the raw engine is our HTTP/serialization tax; the gap to Redis is
what's left to close (and motivates the RESP wire-protocol server on the roadmap). See
[kemus-benchmarks/README.md](kemus-benchmarks/README.md) for how to read the numbers and run without
Docker.

## Gradle install (BOM)

Pin every kemus library with one coordinate via the BOM, then depend on the modules without
versions:

```kotlin
dependencies {
    implementation(platform("io.github.kemus:kemus-bom:0.1.0"))
    implementation("io.github.kemus:kemus-core")        // embedded engine
    implementation("io.github.kemus:kemus-client")      // remote client
    implementation("io.github.kemus:kemus-ktor-plugin") // server-side ktor plugin
}
```

## Roadmap

- [x] **Offline → online sync — primitives, not a framework**: the engine exposes a live change-feed
      (`Kemus.changes()`) plus an opt-in, cursor-based change index (`ChangeSource.changesSince`, the
      `GET /changes` endpoint) so a reconnecting device can pull "keys changed since…". Reconciling
      the two stores is still a *pattern* you write over the shared `KemusCommands` interface (see
      "Offline → online" above), not a built-in strategy engine. The index is **durable** (persisted in
      the AOF — a restart preserves the epoch/cursors, no mass resync), **paginated** (`limit`, pulls
      are `O(log n + page)` via a version-ordered skiplist), batch-fetched (`MGET`), compacts tombstones
      (`compactChanges()` / `tombstoneLimit`) and can fingerprint values for hash-based reconciliation
      (`hashContents`). The push/merge half is still yours to write.
- [ ] More commands (ZRANGEBYSCORE, SCAN, HINCRBY, transactions/`MULTI`).
- [x] Skiplist-backed sorted set (`SortedSetIndex`) — `ZRANGE` is 5.5–15.8× faster than the previous
      sort-on-read (O(log n) insert/remove, O(n) ordered walk instead of O(n·log n) per read).
- [x] Eviction policies — `maxmemory` with the Redis `maxmemory-policy` set (`allkeys-lru`,
      `volatile-lru`, LFU, TTL, random variants) via sampled eviction.
- [x] RESP wire-protocol server (`kemus-resp-server`) for native/JVM — real Redis clients connect
      directly. On equal footing (both native loopback) it is within **~1.9× of native Redis
      single-op** and **~1.3× pipelined**, closing the HTTP path's ~6× gap. Next: batch pipelined
      replies, then shard the single-writer to scale across cores.
- [ ] `PUBSUB NUMPAT` / counting pattern subscribers in `PUBLISH`.
- [x] Maven Central publishing (`publish.yml`) + Docker Hub image (`docker.yml`).
- [x] A BOM module (`kemus-bom`) + multi-arch (`linux/amd64` + `linux/arm64`) Docker image.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
