package io.github.kemus

/**
 * Durability backend for the store. The engine appends every mutating command (AOF style) and
 * replays the log on startup. [rewrite] compacts the log to a snapshot of the current state.
 *
 * Filesystem-backed implementations live in the `fsMain` source set (okio); web targets use
 * [InMemoryPersistence] or [NoPersistence].
 */
interface Persistence {
    /** Replay the persisted log, invoking [apply] for each recorded command, in order. */
    suspend fun loadInto(apply: suspend (List<String>) -> Unit)

    /** Durably record a single mutating command. */
    suspend fun append(command: List<String>)

    /** Atomically replace the whole log with [commands] (snapshot / compaction). */
    suspend fun rewrite(commands: List<List<String>>)

    /** Flush and release any held resources. */
    suspend fun close()
}

/** Discards all writes; the store is purely volatile. The default. */
object NoPersistence : Persistence {
    override suspend fun loadInto(apply: suspend (List<String>) -> Unit) {}
    override suspend fun append(command: List<String>) {}
    override suspend fun rewrite(commands: List<List<String>>) {}
    override suspend fun close() {}
}

/**
 * Keeps the command log in memory. Survives store restarts only within the same process, but is
 * useful on web targets (no filesystem) and in tests. Encodes commands through [Resp] so it
 * exercises the same wire format as the on-disk AOF.
 */
class InMemoryPersistence : Persistence {
    private val log = ArrayList<ByteArray>()

    override suspend fun loadInto(apply: suspend (List<String>) -> Unit) {
        for (entry in log.toList()) {
            val reader = Resp.Reader(entry)
            var cmd = reader.next()
            while (cmd != null) {
                apply(cmd)
                cmd = reader.next()
            }
        }
    }

    override suspend fun append(command: List<String>) {
        log.add(Resp.encodeCommand(command))
    }

    override suspend fun rewrite(commands: List<List<String>>) {
        log.clear()
        for (c in commands) log.add(Resp.encodeCommand(c))
    }

    override suspend fun close() {}
}
