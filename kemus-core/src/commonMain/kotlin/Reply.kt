package io.github.kemus

/**
 * The result of executing a command. Models the RESP reply types so [Kemus.execute] can drive
 * both AOF replay and (later) a wire-protocol server, while typed helpers unwrap it into Kotlin
 * values.
 */
sealed interface Reply {
    object Nil : Reply
    data class SimpleString(val value: String) : Reply
    data class BulkString(val value: String) : Reply
    data class Integer(val value: Long) : Reply
    data class Double(val value: kotlin.Double) : Reply
    data class Array(val items: List<Reply>) : Reply
    data class Error(val message: String) : Reply

    companion object {
        val OK = SimpleString("OK")
        fun of(value: String?): Reply = if (value == null) Nil else BulkString(value)
        fun ofInt(value: Int): Reply = Integer(value.toLong())
        fun ofInt(value: Long): Reply = Integer(value)
        fun bools(value: Boolean): Reply = Integer(if (value) 1 else 0)
    }
}

/** Thrown by typed helpers when a command returns [Reply.Error]. */
class KemusException(message: String) : RuntimeException(message)

internal fun Reply.asString(): String? = when (this) {
    is Reply.Nil -> null
    is Reply.BulkString -> value
    is Reply.SimpleString -> value
    is Reply.Integer -> value.toString()
    is Reply.Double -> value.toString()
    is Reply.Error -> throw KemusException(message)
    is Reply.Array -> throw KemusException("expected scalar, got array")
}

internal fun Reply.asLong(): Long = when (this) {
    is Reply.Integer -> value
    is Reply.Error -> throw KemusException(message)
    else -> throw KemusException("expected integer reply, got $this")
}

internal fun Reply.asList(): List<String> = when (this) {
    is Reply.Array -> items.map { it.asString() ?: "" }
    is Reply.Nil -> emptyList()
    is Reply.Error -> throw KemusException(message)
    else -> throw KemusException("expected array reply, got $this")
}
