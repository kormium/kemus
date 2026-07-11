package io.github.kemus.resp

import io.github.kemus.KemusCommands
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.tcpNoDelay
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlin.coroutines.CoroutineContext

/**
 * A server that speaks the **real Redis wire protocol (RESP)** over raw TCP, backed by any
 * [KemusCommands] store (`Kemus` or a sharded `ShardedKemus`) — so `redis-cli`, `redis-benchmark`,
 * Lettuce, Jedis, … talk to it directly. Unlike the HTTP path it pays no JSON envelope, no HTTP
 * framing and no per-request connection handshake: a request is parsed straight into `argv`, run on
 * the store, and the [io.github.kemus.Reply] is written back with the existing [Resp] codec.
 *
 * One coroutine per connection (so distinct connections run in parallel and, against a sharded store,
 * execute on different shards concurrently). One command per round trip; batched pipelined replies
 * are a follow-up. Multiplatform (JVM + native) like the CIO engine.
 */
class RespServer(
    private val store: KemusCommands,
    private val host: String = "0.0.0.0",
    private val port: Int = 6379,
    dispatcher: CoroutineContext = Dispatchers.Default,
) {
    private val selector = SelectorManager(dispatcher)
    private var serverSocket: ServerSocket? = null

    /** The actually-bound port (resolves `port = 0` to the OS-assigned ephemeral port). */
    val boundPort: Int
        get() = (serverSocket?.localAddress as? InetSocketAddress)?.port ?: port

    /** Bind the listening socket. Call before [serve]; [serve] binds lazily if you don't. */
    suspend fun bind(): RespServer {
        // TCP_NODELAY: disable Nagle so a reply ships immediately instead of waiting to coalesce —
        // exactly what Redis does. Set on the builder so accepted connections inherit it.
        serverSocket = aSocket(selector).tcp().tcpNoDelay().bind(InetSocketAddress(host, port))
        return this
    }

    /**
     * Accept connections until [scope] is cancelled, handling each in its own coroutine launched in
     * [scope]. Suspends for the lifetime of the listener.
     */
    suspend fun serve(scope: CoroutineScope) {
        val server = serverSocket ?: bind().serverSocket!!
        while (scope.isActive) {
            val socket = try {
                server.accept()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (scope.isActive) continue else break
            }
            scope.launch { handle(socket) }
        }
    }

    private suspend fun handle(socket: Socket) {
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = false)
        // One reusable buffer per connection: the reply is encoded into it and drained by writeBuffer.
        val replyBuf = Buffer()
        // One reusable read scratch per connection: bulk payloads are read into it, grown as needed,
        // so steady-state command parsing allocates nothing but the decoded argument strings.
        val scratch = ReadScratch()
        try {
            while (true) {
                val args = readCommand(input, scratch) ?: break
                if (args.isEmpty()) continue
                val reply = store.execute(args)
                replyBuf.writeReply(reply)
                output.writeBuffer(replyBuf)
                output.flush()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Client dropped or sent a malformed frame — just close the connection.
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Read one command. Handles the RESP "multi bulk" framing (`*N` array of `$len` bulk strings)
     * that real clients use, and falls back to inline (whitespace-split) commands for telnet/nc.
     * Returns `null` at end of stream.
     */
    private suspend fun readCommand(input: ByteReadChannel, scratch: ReadScratch): List<String>? {
        val header = input.readUTF8Line() ?: return null
        if (header.isEmpty()) return emptyList()
        if (header[0] != '*') {
            return header.trim().split(' ').filter { it.isNotEmpty() }
        }
        val count = parseRespLen(header)
        if (count < 0) throw IOException("bad multibulk header: $header")
        val args = ArrayList<String>(count)
        repeat(count) {
            val lenLine = input.readUTF8Line() ?: throw IOException("unexpected end of stream")
            if (lenLine.isEmpty() || lenLine[0] != '$') throw IOException("expected bulk string, got: $lenLine")
            val len = parseRespLen(lenLine)
            if (len < 0) throw IOException("bad bulk length: $lenLine")
            // Read the payload + trailing CRLF into the reusable scratch in one call, then decode just
            // the payload in place — no per-arg ByteArray allocation.
            val need = len + 2
            scratch.ensure(need)
            input.readFully(scratch.bytes, 0, need)
            args.add(scratch.bytes.decodeToString(0, len))
        }
        return args
    }

    /** A growable, reusable read buffer owned by one connection. */
    private class ReadScratch {
        var bytes = ByteArray(512)
        fun ensure(n: Int) {
            if (bytes.size < n) bytes = ByteArray(maxOf(n, bytes.size * 2))
        }
    }

    /** Parse the integer after a RESP type byte (`*N`, `$N`) without allocating a substring. */
    private fun parseRespLen(line: String): Int {
        var i = 1
        var negative = false
        if (i < line.length && line[i] == '-') { negative = true; i++ }
        var n = 0
        while (i < line.length) {
            val c = line[i]
            if (c < '0' || c > '9') break
            n = n * 10 + (c - '0')
            i++
        }
        return if (negative) -n else n
    }

    /** Stop listening and release the selector. In-flight connections finish on their own. */
    fun close() {
        serverSocket?.close()
        selector.close()
    }
}
