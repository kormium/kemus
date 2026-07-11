package io.github.kemus

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShardedKemusTest {

    @Test
    fun routesSingleKeyConsistently() = runTest {
        val store = ShardedKemus(shardCount = 8)
        // Enough keys that several shards are exercised; each must read back what it wrote.
        for (i in 0 until 200) store.set("k$i", "v$i")
        for (i in 0 until 200) assertEquals("v$i", store.get("k$i"))
        assertEquals(1L, store.incr("counter"))
        assertEquals(2L, store.incr("counter"))
    }

    @Test
    fun dbsizeSumsAcrossShards() = runTest {
        val store = ShardedKemus(shardCount = 4)
        repeat(100) { store.set("key$it", "x") }
        assertEquals(100L, store.execute(listOf("DBSIZE")).asLong())
    }

    @Test
    fun keysUnionsAcrossShards() = runTest {
        val store = ShardedKemus(shardCount = 4)
        val written = (0 until 50).map { "user:$it" }.toSet()
        written.forEach { store.set(it, "1") }
        store.set("other", "1")
        assertEquals(written, store.keys("user:*").toSet())
    }

    @Test
    fun delAndExistsSpanShards() = runTest {
        val store = ShardedKemus(shardCount = 8)
        val keys = (0 until 20).map { "d$it" }
        keys.forEach { store.set(it, "1") }
        assertEquals(20L, store.exists(*keys.toTypedArray()))
        assertEquals(20L, store.del(*keys.toTypedArray()))
        assertEquals(0L, store.exists(*keys.toTypedArray()))
    }

    @Test
    fun flushallClearsEveryShard() = runTest {
        val store = ShardedKemus(shardCount = 4)
        repeat(50) { store.set("k$it", "v") }
        store.execute(listOf("FLUSHALL"))
        assertEquals(0L, store.execute(listOf("DBSIZE")).asLong())
        assertNull(store.get("k0"))
    }

    @Test
    fun pubSubSharesOneBus() = runTest {
        // Publishing without subscribers returns 0; this just proves pub/sub is wired (shard 0).
        val store = ShardedKemus(shardCount = 4)
        assertEquals(0, store.publish("news", "hello"))
    }

    @Test
    fun singleShardBehavesLikePlainKemus() = runTest {
        val store = ShardedKemus(shardCount = 1)
        store.set("a", "1")
        assertEquals(2L, store.incr("a"))
        assertTrue(store.exists("a") == 1L)
    }
}
