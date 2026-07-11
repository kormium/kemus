package io.github.kemus

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class EvictClock(var ms: Long = 0) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(ms)
}

/** Pull a single `field:value` line out of an INFO bulk reply. */
private fun Reply.infoField(name: String): String? {
    val body = (this as Reply.BulkString).value
    return body.split("\r\n").firstOrNull { it.startsWith("$name:") }?.substringAfter(':')
}

class EvictionTest {

    // Small string keys cost the same estimated size; a budget of 320 holds two of them but not
    // three (~152 bytes each), so the third write tips the store over the limit.
    private val twoKeyBudget = 320L

    @Test
    fun parseMemoryUnits() {
        assertEquals(100L, parseMemory("100"))
        assertEquals(1024L, parseMemory("1kb"))
        assertEquals(2L * 1024 * 1024, parseMemory("2mb"))
        assertEquals(1L * 1024 * 1024 * 1024, parseMemory("1gb"))
        assertEquals(512L, parseMemory("512b"))
        assertNull(parseMemory("abc"))
        assertNull(parseMemory("12xb"))
    }

    @Test
    fun noevictionRejectsWritesAtLimit() = runTest {
        val k = Kemus(maxMemory = 100, maxMemoryPolicy = EvictionPolicy.NOEVICTION)
        k.set("a", "1")
        val reply = k.execute(listOf("SET", "b", "2"))
        assertTrue(reply is Reply.Error && reply.message.startsWith("OOM"))
        assertEquals("1", k.get("a")) // existing key untouched
        assertNull(k.get("b"))
    }

    @Test
    fun allkeysLruEvictsLeastRecentlyUsed() = runTest {
        val clock = EvictClock(0)
        val k = Kemus(clock = clock, maxMemory = twoKeyBudget, maxMemoryPolicy = EvictionPolicy.ALLKEYS_LRU)
        k.set("a", "1")          // t=0
        clock.ms = 10; k.set("b", "2")
        clock.ms = 20; k.get("a") // a is now the most recently used
        clock.ms = 30; k.set("c", "3") // pushes over the limit (no eviction yet — checked pre-write)
        clock.ms = 40; k.set("d", "4") // pre-write check trips: evicts the LRU key, which is b

        assertNull(k.get("b"))
        assertEquals("1", k.get("a"))
        assertEquals("3", k.get("c"))
    }

    @Test
    fun volatileLruOnlyEvictsKeysWithTtl() = runTest {
        val clock = EvictClock(0)
        val k = Kemus(clock = clock, maxMemory = twoKeyBudget, maxMemoryPolicy = EvictionPolicy.VOLATILE_LRU)
        k.execute(listOf("SET", "a", "1", "EX", "1000")) // volatile, t=0
        clock.ms = 10; k.set("b", "2")                    // no TTL — protected from eviction
        clock.ms = 20; k.execute(listOf("SET", "c", "3", "EX", "1000")) // over limit
        clock.ms = 30; k.execute(listOf("SET", "d", "4", "EX", "1000")) // trips eviction

        assertNull(k.get("a"))           // oldest volatile key evicted
        assertEquals("2", k.get("b"))    // non-volatile key survives
        assertEquals("3", k.get("c"))
    }

    @Test
    fun volatileLruOomWhenNoVolatileKeys() = runTest {
        val k = Kemus(maxMemory = 100, maxMemoryPolicy = EvictionPolicy.VOLATILE_LRU)
        k.set("a", "1") // no TTL
        val reply = k.execute(listOf("SET", "b", "2"))
        assertTrue(reply is Reply.Error && reply.message.startsWith("OOM"))
        assertEquals("1", k.get("a"))
    }

    @Test
    fun allkeysRandomStaysBoundedNearLimit() = runTest {
        val k = Kemus(maxMemory = twoKeyBudget, maxMemoryPolicy = EvictionPolicy.ALLKEYS_RANDOM)
        repeat(20) { k.set("k$it", "v$it") } // eviction runs pre-write, so usage hovers near the budget
        // Eviction happens *before* each write, so the latest write can leave the store one entry over
        // the limit — but usage must stay bounded near the budget, not grow with the number of writes
        // (20 un-evicted keys would be ~3000 bytes).
        val used = k.execute(listOf("INFO", "memory")).infoField("used_memory")!!.toLong()
        assertTrue(used <= twoKeyBudget + 200, "used=$used should stay bounded near $twoKeyBudget")
    }

    @Test
    fun configGetAndSet() = runTest {
        val k = Kemus()
        assertEquals(Reply.OK, k.execute(listOf("CONFIG", "SET", "maxmemory", "1mb")))
        assertEquals(
            Reply.Array(listOf(Reply.BulkString("maxmemory"), Reply.BulkString("1048576"))),
            k.execute(listOf("CONFIG", "GET", "maxmemory")),
        )
        assertEquals(Reply.OK, k.execute(listOf("CONFIG", "SET", "maxmemory-policy", "allkeys-lru")))
        assertEquals(
            Reply.Array(listOf(Reply.BulkString("maxmemory-policy"), Reply.BulkString("allkeys-lru"))),
            k.execute(listOf("CONFIG", "GET", "maxmemory-policy")),
        )
        assertTrue(k.execute(listOf("CONFIG", "SET", "maxmemory-policy", "bogus")) is Reply.Error)
    }

    @Test
    fun configSetMaxmemoryEvictsImmediately() = runTest {
        val k = Kemus(maxMemoryPolicy = EvictionPolicy.ALLKEYS_RANDOM)
        repeat(5) { k.set("k$it", "v$it") }
        val before = k.execute(listOf("INFO", "memory")).infoField("used_memory")!!.toLong()
        assertTrue(before > twoKeyBudget)

        k.execute(listOf("CONFIG", "SET", "maxmemory", twoKeyBudget.toString()))
        val after = k.execute(listOf("INFO", "memory")).infoField("used_memory")!!.toLong()
        assertTrue(after <= twoKeyBudget, "after=$after should be <= $twoKeyBudget")
    }

    @Test
    fun infoMemoryReportsUsageAndPolicy() = runTest {
        val k = Kemus(maxMemory = 1024, maxMemoryPolicy = EvictionPolicy.ALLKEYS_LFU)
        k.set("a", "1")
        val info = k.execute(listOf("INFO", "memory"))
        assertNotNull(info.infoField("used_memory"))
        assertTrue(info.infoField("used_memory")!!.toLong() > 0)
        assertEquals("1024", info.infoField("maxmemory"))
        assertEquals("allkeys-lfu", info.infoField("maxmemory_policy"))
    }

    @Test
    fun deleteAndFlushReclaimMemory() = runTest {
        val k = Kemus()
        k.set("a", "1")
        k.set("b", "2")
        val used = k.execute(listOf("INFO", "memory")).infoField("used_memory")!!.toLong()
        k.del("a")
        val afterDel = k.execute(listOf("INFO", "memory")).infoField("used_memory")!!.toLong()
        assertTrue(afterDel < used)
        k.execute(listOf("FLUSHALL"))
        assertEquals(0L, k.execute(listOf("INFO", "memory")).infoField("used_memory")!!.toLong())
    }
}
