package io.github.kemus

import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

/**
 * AOF (append-only file) durability backed by okio. Available on every filesystem-capable target
 * (JVM, Android, all native). Each mutating command is appended in RESP format; [rewrite] replaces
 * the file atomically with a fresh snapshot.
 *
 * Commands are serialised by the engine under its write lock, so appends are already ordered and
 * non-concurrent — a single buffered sink is reused.
 */
class FilePersistence(
    private val fileSystem: FileSystem,
    private val path: Path,
) : Persistence {
    private var sink: BufferedSink? = null

    private fun openSink(): BufferedSink =
        sink ?: fileSystem.appendingSink(path).buffer().also { sink = it }

    override suspend fun loadInto(apply: suspend (List<String>) -> Unit) {
        if (!fileSystem.exists(path)) return
        val source = fileSystem.source(path).buffer()
        val bytes = try { source.readByteArray() } finally { source.close() }
        if (bytes.isEmpty()) return
        val reader = Resp.Reader(bytes)
        var cmd = reader.next()
        while (cmd != null) {
            apply(cmd)
            cmd = reader.next()
        }
    }

    override suspend fun append(command: List<String>) {
        val s = openSink()
        s.write(Resp.encodeCommand(command))
        s.flush()
    }

    override suspend fun rewrite(commands: List<List<String>>) {
        sink?.close()
        sink = null
        val tmp = "${path}.tmp".toPath()
        val out = fileSystem.sink(tmp).buffer()
        try {
            for (c in commands) out.write(Resp.encodeCommand(c))
        } finally {
            out.close()
        }
        fileSystem.atomicMove(tmp, path)
    }

    override suspend fun close() {
        sink?.flush()
        sink?.close()
        sink = null
    }
}

/** Open an [AOF][FilePersistence] at [path] on the platform's real filesystem. */
fun filePersistence(path: String): Persistence {
    val p = path.toPath()
    p.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
    return FilePersistence(FileSystem.SYSTEM, p)
}
