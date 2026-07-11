package io.github.kemus

/**
 * Minimal RESP (REdis Serialization Protocol) codec.
 *
 * Commands are encoded as arrays of bulk strings (used for the on-disk AOF and reusable for a wire
 * protocol). Replies are encoded losslessly so a remote client can reconstruct the exact [Reply]
 * type — this backs the `application/resp` content type the REST `/command` endpoint speaks to the
 * [io.github.kemus] client.
 */
object Resp {
    /** Content type used to request/return a RESP-encoded reply over HTTP. */
    const val CONTENT_TYPE = "application/resp"

    private const val CR = '\r'.code.toByte()
    private const val LF = '\n'.code.toByte()

    /** Encode a command (`argv`) as a RESP array of bulk strings. */
    fun encodeCommand(args: List<String>): ByteArray {
        val sb = StringBuilder()
        sb.append('*').append(args.size).append("\r\n")
        for (arg in args) {
            val bytesLen = arg.encodeToByteArray().size
            sb.append('$').append(bytesLen).append("\r\n").append(arg).append("\r\n")
        }
        return sb.toString().encodeToByteArray()
    }

    /**
     * Incremental reader that yields successive commands from a RESP byte buffer (e.g. a loaded
     * AOF). Returns `null` once the buffer is exhausted. Throws on a malformed buffer.
     */
    class Reader(private val bytes: ByteArray) {
        private var pos = 0

        fun next(): List<String>? {
            if (pos >= bytes.size) return null
            require(bytes[pos] == '*'.code.toByte()) { "expected '*' at $pos" }
            pos++
            val count = readIntLine()
            val args = ArrayList<String>(count)
            repeat(count) {
                require(pos < bytes.size && bytes[pos] == '$'.code.toByte()) { "expected '\$' at $pos" }
                pos++
                val len = readIntLine()
                val end = pos + len
                require(end <= bytes.size) { "truncated bulk string at $pos" }
                args.add(bytes.decodeToString(pos, end))
                pos = end
                expectCrlf()
            }
            return args
        }

        private fun readIntLine(): Int {
            val start = pos
            while (pos < bytes.size && bytes[pos] != CR) pos++
            val n = bytes.decodeToString(start, pos).toInt()
            expectCrlf()
            return n
        }

        private fun expectCrlf() {
            require(pos + 1 < bytes.size && bytes[pos] == CR && bytes[pos + 1] == LF) {
                "expected CRLF at $pos"
            }
            pos += 2
        }
    }

    // --- replies ---------------------------------------------------------------------------------

    /** Encode a [Reply] losslessly (the type prefix preserves Integer vs BulkString vs Array, …). */
    fun encodeReply(reply: Reply): ByteArray =
        StringBuilder().also { appendReply(it, reply) }.toString().encodeToByteArray()

    private fun appendReply(sb: StringBuilder, reply: Reply) {
        when (reply) {
            is Reply.Nil -> sb.append("$-1\r\n")
            is Reply.SimpleString -> sb.append('+').append(reply.value).append("\r\n")
            is Reply.Error -> sb.append('-').append(reply.message).append("\r\n")
            is Reply.Integer -> sb.append(':').append(reply.value).append("\r\n")
            is Reply.Double -> sb.append(',').append(reply.value).append("\r\n")
            is Reply.BulkString -> {
                val len = reply.value.encodeToByteArray().size
                sb.append('$').append(len).append("\r\n").append(reply.value).append("\r\n")
            }
            is Reply.Array -> {
                sb.append('*').append(reply.items.size).append("\r\n")
                for (item in reply.items) appendReply(sb, item)
            }
        }
    }

    /** Decode a single RESP-encoded [Reply] (the inverse of [encodeReply]). */
    fun decodeReply(bytes: ByteArray): Reply = ReplyReader(bytes).read()

    private class ReplyReader(private val bytes: ByteArray) {
        private var pos = 0

        fun read(): Reply {
            require(pos < bytes.size) { "empty reply" }
            val type = bytes[pos++].toInt().toChar()
            return when (type) {
                '+' -> Reply.SimpleString(readLine())
                '-' -> Reply.Error(readLine())
                ':' -> Reply.Integer(readLine().toLong())
                ',' -> Reply.Double(readLine().toDouble())
                '$' -> {
                    val len = readLine().toInt()
                    if (len < 0) return Reply.Nil
                    val end = pos + len
                    require(end <= bytes.size) { "truncated bulk string at $pos" }
                    val s = bytes.decodeToString(pos, end)
                    pos = end
                    expectCrlf()
                    Reply.BulkString(s)
                }
                '*' -> {
                    val n = readLine().toInt()
                    if (n < 0) return Reply.Nil
                    Reply.Array((0 until n).map { read() })
                }
                else -> Reply.Error("ERR protocol error: unexpected '$type'")
            }
        }

        private fun readLine(): String {
            val start = pos
            while (pos < bytes.size && bytes[pos] != CR) pos++
            val s = bytes.decodeToString(start, pos)
            expectCrlf()
            return s
        }

        private fun expectCrlf() {
            require(pos + 1 < bytes.size && bytes[pos] == CR && bytes[pos + 1] == LF) {
                "expected CRLF at $pos"
            }
            pos += 2
        }
    }
}
