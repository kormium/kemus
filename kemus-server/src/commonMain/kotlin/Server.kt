package io.github.kemus.server

import io.github.kemus.Kemus
import io.github.kemus.Persistence
import io.github.kemus.ktor.kemus
import io.github.kemus.resp.RespServer
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Read an environment variable. */
expect fun envVar(name: String): String?

private fun String?.isTruthy(): Boolean =
    this != null && this.lowercase() in setOf("1", "true", "yes", "on")

/** Open the platform's filesystem-backed persistence at [path] (JVM + native both support it). */
expect fun openPersistence(path: String): Persistence

/**
 * Build the store (durable when `KEMUS_AOF` is set) and start the CIO REST server, blocking until
 * it stops. Each platform's `main` calls this inside `runBlocking`.
 *
 * Environment:
 *   KEMUS_PORT  — listen port (default 6390)
 *   KEMUS_AOF   — path to an append-only file for durability; omit for a volatile store
 *   KEMUS_SYNC  — set to a truthy value to enable the change index (the GET /changes endpoint used
 *                 for offline→online sync); off by default
 *   KEMUS_SYNC_TOMBSTONE_LIMIT — auto-compact the change index once this many tombstones accumulate
 *                 (bounds its memory); 0/unset disables auto-compaction
 *   KEMUS_SYNC_HASH — set to a truthy value to include a content hash per changed key (lets a sync
 *                 skip keys that already agree); costs an O(value-size) hash per write
 *   KEMUS_RESP_PORT — also expose the native RESP wire protocol (real Redis clients) on this TCP
 *                 port; unset disables it. The REST and RESP servers share the one store.
 */
suspend fun runKemusServer() {
    val port = envVar("KEMUS_PORT")?.toIntOrNull() ?: 6390
    val respPort = envVar("KEMUS_RESP_PORT")?.toIntOrNull()
    val aofPath = envVar("KEMUS_AOF")
    val trackChanges = envVar("KEMUS_SYNC").isTruthy()
    val tombstoneLimit = envVar("KEMUS_SYNC_TOMBSTONE_LIMIT")?.toIntOrNull() ?: 0
    val hashContents = envVar("KEMUS_SYNC_HASH").isTruthy()

    val store = if (aofPath != null) {
        Kemus.open(openPersistence(aofPath), trackChanges = trackChanges, tombstoneLimit = tombstoneLimit, hashContents = hashContents)
    } else {
        Kemus(trackChanges = trackChanges, tombstoneLimit = tombstoneLimit, hashContents = hashContents)
    }

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    store.startExpiryCycle(scope)

    if (respPort != null) {
        // KEMUS_RESP_SHARDS>1 serves the RESP port from a sharded store (independent per-shard locks)
        // — a knob for probing whether mutex contention limits concurrency on native.
        val shards = envVar("KEMUS_RESP_SHARDS")?.toIntOrNull() ?: 1
        val respStore: io.github.kemus.KemusCommands = if (shards > 1) io.github.kemus.ShardedKemus(shards) else store
        val resp = RespServer(respStore, port = respPort).also { it.bind() }
        scope.launch { resp.serve(scope) }
    }

    embeddedServer(CIO, port = port) {
        kemus(store)
    }.start(wait = true)
}
