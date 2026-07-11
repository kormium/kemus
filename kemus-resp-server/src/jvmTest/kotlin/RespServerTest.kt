package io.github.kemus.resp

import io.github.kemus.Kemus
import io.lettuce.core.RedisClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives the RESP server with a real Redis client (Lettuce). If these pass, the wire protocol is
 * genuinely Redis-compatible — the same client that talks to Redis talks to kemus unchanged.
 */
class RespServerTest {
    private lateinit var server: RespServer
    // serve() is an endless accept loop, so it must run in a scope independent of runBlocking (which
    // would otherwise wait for it forever). Cancelled in teardown.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var client: RedisClient
    private lateinit var conn: io.lettuce.core.api.StatefulRedisConnection<String, String>
    private val redis get() = conn.sync()

    @BeforeTest
    fun start() {
        server = RespServer(Kemus(), host = "127.0.0.1", port = 0, dispatcher = Dispatchers.IO)
        runBlocking { server.bind() }
        scope.launch { server.serve(scope) }
        client = RedisClient.create("redis://127.0.0.1:${server.boundPort}")
        conn = client.connect()
    }

    @AfterTest
    fun stop() {
        conn.close()
        client.shutdown()
        scope.cancel()
        server.close()
    }

    @Test
    fun ping() {
        assertEquals("PONG", redis.ping())
    }

    @Test
    fun stringRoundTrip() {
        assertEquals("OK", redis.set("user:1", "Ada"))
        assertEquals("Ada", redis.get("user:1"))
        assertEquals(null, redis.get("missing"))
    }

    @Test
    fun incr() {
        assertEquals(1L, redis.incr("counter"))
        assertEquals(2L, redis.incr("counter"))
        assertEquals(12L, redis.incrby("counter", 10))
    }

    @Test
    fun listsAndRange() {
        redis.rpush("q", "a", "b", "c")
        assertEquals(listOf("a", "b", "c"), redis.lrange("q", 0, -1))
        assertEquals(3L, redis.llen("q"))
    }

    @Test
    fun binarySafeValueWithCrlf() {
        // The value contains CRLF and a '$' — proves the bulk-string framing is length-prefixed,
        // not line-delimited.
        val payload = "line1\r\nline2 \$weird"
        assertEquals("OK", redis.set("blob", payload))
        assertEquals(payload, redis.get("blob"))
    }
}
