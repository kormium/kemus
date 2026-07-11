package io.github.kemus.resp

import io.github.kemus.Reply
import kotlinx.io.Buffer

/**
 * Allocation-light RESP reply encoder: writes a [Reply] straight into a reusable [Buffer], avoiding
 * the `StringBuilder` → `String` → `ByteArray` round trip (and the double value-encode) that
 * `Resp.encodeReply` does. Type prefixes and CRLF go out as raw bytes; integers as ASCII digits with
 * no boxing/`toString`; only a bulk string's payload is encoded to bytes — exactly once.
 *
 * The hot path (`+OK`, `$<len>…`, `:<n>`) allocates nothing per reply except the unavoidable UTF-8
 * bytes of string content.
 */
internal fun Buffer.writeReply(reply: Reply) {
    when (reply) {
        is Reply.Nil -> { writeByte(DOLLAR); writeDecimal(-1); crlf() }
        is Reply.SimpleString -> { writeByte(PLUS); writeUtf8(reply.value); crlf() }
        is Reply.Error -> { writeByte(MINUS); writeUtf8(reply.message); crlf() }
        is Reply.Integer -> { writeByte(COLON); writeDecimal(reply.value); crlf() }
        is Reply.Double -> { writeByte(COMMA); writeUtf8(reply.value.toString()); crlf() }
        is Reply.BulkString -> {
            val bytes = reply.value.encodeToByteArray()
            writeByte(DOLLAR); writeDecimal(bytes.size.toLong()); crlf()
            write(bytes, 0, bytes.size); crlf()
        }
        is Reply.Array -> {
            writeByte(STAR); writeDecimal(reply.items.size.toLong()); crlf()
            for (item in reply.items) writeReply(item)
        }
    }
}

private fun Buffer.crlf() {
    writeByte(CR)
    writeByte(LF)
}

private fun Buffer.writeUtf8(s: String) {
    val bytes = s.encodeToByteArray()
    write(bytes, 0, bytes.size)
}

/** Write [value] as ASCII decimal digits with no intermediate `String`. */
private fun Buffer.writeDecimal(value: Long) {
    if (value == 0L) { writeByte(ZERO.toByte()); return }
    var v = value
    if (v < 0) { writeByte(MINUS); v = -v }
    // Up to 20 digits for a 64-bit value; fill from the end.
    val tmp = ByteArray(20)
    var i = tmp.size
    while (v > 0) {
        tmp[--i] = (ZERO + (v % 10).toInt()).toByte()
        v /= 10
    }
    write(tmp, i, tmp.size)
}

private const val CR: Byte = '\r'.code.toByte()
private const val LF: Byte = '\n'.code.toByte()
private const val PLUS: Byte = '+'.code.toByte()
private const val MINUS: Byte = '-'.code.toByte()
private const val COLON: Byte = ':'.code.toByte()
private const val DOLLAR: Byte = '$'.code.toByte()
private const val STAR: Byte = '*'.code.toByte()
private const val COMMA: Byte = ','.code.toByte()
private const val ZERO: Int = '0'.code
