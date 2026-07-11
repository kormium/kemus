package io.github.kemus

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * The unified command surface of a kemus store. Implemented by the embedded engine ([Kemus]) and
 * by the remote client (`KemusClient` in kemus-client), so the same code — including every typed
 * helper below — works against an in-process store or a remote `kemus-server` unchanged.
 */
interface KemusCommands {
    /** Execute a raw command (`argv`, Redis-style) and return its RESP [Reply]. */
    suspend fun execute(args: List<String>): Reply

    /** Subscribe to a channel; the cold flow delivers messages published after collection begins. */
    fun subscribe(channel: String): Flow<String>

    /** Pattern subscription (`PSUBSCRIBE`): messages from every channel matching the glob [pattern]. */
    fun psubscribe(pattern: String): Flow<String>

    /** Publish a message to a channel. Returns the number of (exact-channel) subscribers. */
    suspend fun publish(channel: String, message: String): Int
}

/**
 * Typed convenience API over [KemusCommands.execute]. These mirror the most common Redis commands
 * and unwrap the [Reply] into Kotlin values, throwing [KemusException] on a server error.
 */

suspend fun KemusCommands.set(key: String, value: String) {
    execute(listOf("SET", key, value)).asString()
}

suspend fun KemusCommands.set(key: String, value: String, ttl: Duration) {
    execute(listOf("SET", key, value, "PX", ttl.inWholeMilliseconds.toString())).asString()
}

suspend fun KemusCommands.get(key: String): String? = execute(listOf("GET", key)).asString()

/**
 * Batch get: returns one value per [keys] in order, `null` for a missing or non-string key — a single
 * round trip instead of one [get] per key. Handy for a sync that has just learned which keys changed.
 */
suspend fun KemusCommands.mget(vararg keys: String): List<String?> {
    val reply = execute(listOf("MGET", *keys))
    if (reply is Reply.Error) throw KemusException(reply.message)
    return (reply as Reply.Array).items.map { (it as? Reply.BulkString)?.value }
}

suspend fun KemusCommands.del(vararg keys: String): Long = execute(listOf("DEL", *keys)).asLong()

suspend fun KemusCommands.exists(vararg keys: String): Long = execute(listOf("EXISTS", *keys)).asLong()

suspend fun KemusCommands.expire(key: String, ttl: Duration): Boolean =
    execute(listOf("PEXPIRE", key, ttl.inWholeMilliseconds.toString())).asLong() == 1L

suspend fun KemusCommands.ttl(key: String): Long = execute(listOf("PTTL", key)).asLong()

suspend fun KemusCommands.incr(key: String): Long = execute(listOf("INCR", key)).asLong()

suspend fun KemusCommands.incrBy(key: String, by: Long): Long = execute(listOf("INCRBY", key, by.toString())).asLong()

suspend fun KemusCommands.type(key: String): String = execute(listOf("TYPE", key)).asString() ?: "none"

suspend fun KemusCommands.keys(pattern: String = "*"): List<String> = execute(listOf("KEYS", pattern)).asList()

// Hashes
suspend fun KemusCommands.hset(key: String, field: String, value: String): Long =
    execute(listOf("HSET", key, field, value)).asLong()

suspend fun KemusCommands.hget(key: String, field: String): String? = execute(listOf("HGET", key, field)).asString()

suspend fun KemusCommands.hgetAll(key: String): Map<String, String> {
    val flat = execute(listOf("HGETALL", key)).asList()
    return buildMap { var i = 0; while (i + 1 < flat.size) { put(flat[i], flat[i + 1]); i += 2 } }
}

// Lists
suspend fun KemusCommands.lpush(key: String, vararg values: String): Long = execute(listOf("LPUSH", key, *values)).asLong()
suspend fun KemusCommands.rpush(key: String, vararg values: String): Long = execute(listOf("RPUSH", key, *values)).asLong()
suspend fun KemusCommands.lpop(key: String): String? = execute(listOf("LPOP", key)).asString()
suspend fun KemusCommands.rpop(key: String): String? = execute(listOf("RPOP", key)).asString()
suspend fun KemusCommands.lrange(key: String, start: Int, stop: Int): List<String> =
    execute(listOf("LRANGE", key, start.toString(), stop.toString())).asList()

// Sets
suspend fun KemusCommands.sadd(key: String, vararg members: String): Long = execute(listOf("SADD", key, *members)).asLong()
suspend fun KemusCommands.smembers(key: String): List<String> = execute(listOf("SMEMBERS", key)).asList()
suspend fun KemusCommands.sisMember(key: String, member: String): Boolean = execute(listOf("SISMEMBER", key, member)).asLong() == 1L

// Sorted sets
suspend fun KemusCommands.zadd(key: String, score: Double, member: String): Long =
    execute(listOf("ZADD", key, score.toString(), member)).asLong()
suspend fun KemusCommands.zrange(key: String, start: Int, stop: Int): List<String> =
    execute(listOf("ZRANGE", key, start.toString(), stop.toString())).asList()
