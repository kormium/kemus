package io.github.kemus

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class TestClock(var ms: Long = 0) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(ms)
}

class KemusTest {

    @Test
    fun stringsAndDelete() = runTest {
        val k = Kemus()
        k.set("a", "1")
        assertEquals("1", k.get("a"))
        assertEquals(2L, k.incr("a"))
        assertEquals(12L, k.incrBy("a", 10))
        assertEquals(1L, k.del("a"))
        assertNull(k.get("a"))
    }

    @Test
    fun ttlExpiresLazily() = runTest {
        val clock = TestClock(0)
        val k = Kemus(clock = clock)
        k.execute(listOf("SET", "s", "v", "PX", "100"))
        assertEquals("v", k.get("s"))
        clock.ms = 50
        assertEquals("v", k.get("s"))
        clock.ms = 150
        assertNull(k.get("s"))
        assertEquals(0L, k.exists("s"))
    }

    @Test
    fun collectionsTypes() = runTest {
        val k = Kemus()
        k.rpush("l", "a", "b", "c")
        assertEquals(listOf("a", "b", "c"), k.lrange("l", 0, -1))
        assertEquals("a", k.lpop("l"))

        k.hset("h", "f1", "v1")
        k.hset("h", "f2", "v2")
        assertEquals(mapOf("f1" to "v1", "f2" to "v2"), k.hgetAll("h"))

        k.sadd("set", "x", "y", "x")
        assertEquals(2, k.smembers("set").size)
        assertTrue(k.sisMember("set", "x"))

        k.zadd("z", 2.0, "b")
        k.zadd("z", 1.0, "a")
        assertEquals(listOf("a", "b"), k.zrange("z", 0, -1))
    }

    @Test
    fun sortedSetOrderingAndUpdates() = runTest {
        val k = Kemus()
        // Insert out of order, with a score tie that must break by member lexicographically.
        k.zadd("z", 3.0, "c")
        k.zadd("z", 1.0, "b")
        k.zadd("z", 1.0, "a") // tie with b at score 1 -> a before b
        k.zadd("z", 2.0, "d")
        assertEquals(listOf("a", "b", "d", "c"), k.zrange("z", 0, -1))
        assertEquals(4L, k.execute(listOf("ZCARD", "z")).asLong())
        assertEquals("1", k.execute(listOf("ZSCORE", "z", "a")).asString())

        // Updating a score must re-order, not duplicate; ZADD returns 0 added on update.
        assertEquals(0L, k.execute(listOf("ZADD", "z", "10", "a")).asLong())
        assertEquals(4L, k.execute(listOf("ZCARD", "z")).asLong())
        assertEquals(listOf("b", "d", "c", "a"), k.zrange("z", 0, -1))

        // Sub-range and removal.
        assertEquals(listOf("b", "d"), k.zrange("z", 0, 1))
        assertEquals(1L, k.execute(listOf("ZREM", "z", "d")).asLong())
        assertEquals(listOf("b", "c", "a"), k.zrange("z", 0, -1))

        // Negative scores order before positives.
        k.zadd("z", -5.0, "neg")
        assertEquals("neg", k.zrange("z", 0, 0).single())
    }

    @Test
    fun typeMismatchIsError() = runTest {
        val k = Kemus()
        k.set("a", "1")
        val reply = k.execute(listOf("LPUSH", "a", "x"))
        assertTrue(reply is Reply.Error)
    }

    @Test
    fun persistenceReplaysState() = runTest {
        val p = InMemoryPersistence()
        val first = Kemus.open(p)
        first.set("a", "1")
        first.rpush("l", "x", "y")
        first.hset("h", "f", "v")

        val second = Kemus.open(p)
        assertEquals("1", second.get("a"))
        assertEquals(listOf("x", "y"), second.lrange("l", 0, -1))
        assertEquals("v", second.hget("h", "f"))
    }

    @Test
    fun snapshotCompactsLog() = runTest {
        val p = InMemoryPersistence()
        val k = Kemus.open(p)
        repeat(100) { k.incr("counter") }
        k.save()
        val restored = Kemus.open(p)
        assertEquals("100", restored.get("counter"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pubSubDeliversMessages() = runTest {
        val k = Kemus()
        val flow = k.subscribe("news")
        val received = async { flow.first() }
        runCurrent() // let the collector register before publishing
        val subscribers = k.publish("news", "hello")
        assertEquals(1, subscribers)
        assertEquals("hello", received.await())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun patternSubscribeMatchesChannels() = runTest {
        val k = Kemus()
        val received = async { k.psubscribe("user:*").first() }
        runCurrent()
        k.publish("order:1", "ignored")
        k.publish("user:42", "hello")
        assertEquals("hello", received.await())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun subscriberCountIsReleasedOnUnsubscribe() = runTest {
        val k = Kemus()
        val job = launch { k.subscribe("c").collect { } }
        runCurrent()
        assertEquals(1, k.publish("c", "x"))
        job.cancelAndJoin()
        assertEquals(0, k.publish("c", "y"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun changeFeedEmitsCanonicalMutations() = runTest {
        val clock = TestClock(0)
        val k = Kemus(clock = clock)
        val seen = mutableListOf<KemusChange>()
        val job = launch { k.changes().collect { seen.add(it) } }
        runCurrent() // let the collector register before mutating

        k.set("a", "1")
        k.incr("a")                                  // canonicalised to SET a 2
        k.execute(listOf("SET", "s", "v", "PX", "100")) // SET + PEXPIREAT
        k.del("a")
        runCurrent()

        assertEquals(
            listOf(
                listOf("SET", "a", "1"),
                listOf("SET", "a", "2"),
                listOf("SET", "s", "v"),
                listOf("PEXPIREAT", "s", "100"),
                listOf("DEL", "a"),
            ),
            seen.map { it.command },
        )
        assertEquals("a", seen.first().key)
        job.cancelAndJoin()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun changeFeedSkipsReadsAndFailedCommands() = runTest {
        val k = Kemus()
        val seen = mutableListOf<KemusChange>()
        val job = launch { k.changes().collect { seen.add(it) } }
        runCurrent()

        k.set("a", "1")
        k.get("a")                              // read: no change
        k.execute(listOf("INCR", "a"))          // WRONGTYPE? no — "1" is integer, succeeds
        k.execute(listOf("LPUSH", "a", "x"))    // WRONGTYPE error: no change
        runCurrent()

        assertEquals(
            listOf(listOf("SET", "a", "1"), listOf("SET", "a", "2")),
            seen.map { it.command },
        )
        job.cancelAndJoin()
    }

    @Test
    fun changeIndexTracksVersionsAndTombstones() = runTest {
        val k = Kemus(trackChanges = true)
        k.set("a", "1")
        k.set("b", "2")

        // First pull from version 0 returns the whole keyspace, not deleted.
        val first = k.changesSince(0)
        assertFalse(first.resyncRequired)
        assertEquals(listOf("a", "b"), first.changes.map { it.key })
        assertTrue(first.changes.none { it.deleted })
        assertEquals(2L, first.cursor)

        // Incremental pull from the cursor returns only newer changes.
        assertTrue(k.changesSince(first.cursor, first.epoch).changes.isEmpty())

        // A delete surfaces as a tombstone with a higher version.
        k.del("a")
        val afterDelete = k.changesSince(first.cursor, first.epoch)
        assertEquals(listOf(ChangeEntry("a", 3, deleted = true)), afterDelete.changes)
    }

    @Test
    fun changeIndexFlushAndEpochMismatch() = runTest {
        val k = Kemus(trackChanges = true)
        k.set("a", "1")
        k.set("b", "2")
        k.execute(listOf("FLUSHALL"))

        // FLUSHALL tombstones every live key.
        val all = k.changesSince(0).changes.filter { it.deleted }.map { it.key }
        assertEquals(setOf("a", "b"), all.toSet())

        // A cursor from another index instance forces a full resync.
        val stale = k.changesSince(0, epoch = "not-the-epoch")
        assertTrue(stale.resyncRequired)
        assertEquals(k.changesSince(0).changes.size, stale.changes.size)
    }

    @Test
    fun changeIndexDisabledRequestsResync() = runTest {
        val k = Kemus() // tracking off
        k.set("a", "1")
        val page = k.changesSince(0)
        assertTrue(page.resyncRequired)
        assertTrue(page.changes.isEmpty())
    }

    @Test
    fun tombstoneCompactionForcesResyncOnlyForStaleCursors() = runTest {
        val k = Kemus(trackChanges = true)
        k.set("a", "1")
        k.set("b", "2")
        val behind = k.changesSince(0).cursor      // 2 — before the delete
        k.del("a")
        val caughtUp = k.changesSince(behind).cursor // 3 — saw the tombstone

        assertEquals(1, k.compactChanges())          // drops the "a" tombstone, floor -> 3

        // A client already past the deletion is served incrementally as usual.
        val ahead = k.changesSince(caughtUp)
        assertFalse(ahead.resyncRequired)
        assertTrue(ahead.changes.isEmpty())

        // A client still behind the dropped tombstone must resync; the page is the live keyspace.
        val stale = k.changesSince(behind)
        assertTrue(stale.resyncRequired)
        assertEquals(listOf("b"), stale.changes.map { it.key })
    }

    @Test
    fun tombstoneLimitCompactsAutomatically() = runTest {
        val k = Kemus(trackChanges = true, tombstoneLimit = 1)
        k.set("a", "1")
        k.set("b", "2")
        k.del("a")                  // 1 tombstone — at the limit
        k.del("b")                  // 2nd tombstone trips the limit -> auto-compaction

        val page = k.changesSince(0)
        assertTrue(page.changes.none { it.deleted }) // tombstones gone
        assertTrue(page.changes.isEmpty())           // and no live keys remain
    }

    @Test
    fun contentHashesDetectAgreementAndChange() = runTest {
        val k = Kemus(trackChanges = true, hashContents = true)
        k.set("a", "1")
        val h1 = k.changesSince(0).changes.single { it.key == "a" }.hash
        assertTrue(h1 != null)

        // Re-setting the same value yields the same hash; a different value changes it.
        k.set("a", "1")
        assertEquals(h1, k.changesSince(0).changes.single { it.key == "a" }.hash)
        k.set("a", "2")
        assertTrue(k.changesSince(0).changes.single { it.key == "a" }.hash != h1)

        // Tombstones carry no hash.
        k.del("a")
        assertNull(k.changesSince(0).changes.single { it.key == "a" }.hash)
    }

    @Test
    fun contentHashesAreStableAcrossStoresAndMemberOrder() = runTest {
        val k1 = Kemus(trackChanges = true, hashContents = true)
        val k2 = Kemus(trackChanges = true, hashContents = true)
        // Same members, different insertion order — the hash must still match (sets are unordered).
        k1.sadd("s", "x", "y", "z")
        k2.sadd("s", "z", "y", "x")
        val h1 = k1.changesSince(0).changes.single().hash
        val h2 = k2.changesSince(0).changes.single().hash
        assertEquals(h1, h2)
    }

    @Test
    fun contentHashesAbsentUnlessEnabled() = runTest {
        val k = Kemus(trackChanges = true) // hashing off
        k.set("a", "1")
        assertNull(k.changesSince(0).changes.single().hash)
    }

    @Test
    fun changeIndexSeedsFromReplayedState() = runTest {
        val persistence = InMemoryPersistence()
        Kemus.open(persistence).set("seeded", "v")
        // Reopen on the same log with tracking on; the existing key must appear at since = 0.
        val reopened = Kemus.open(persistence, trackChanges = true)
        assertEquals(listOf("seeded"), reopened.changesSince(0).changes.map { it.key })
    }

    @Test
    fun changeIndexSurvivesCompactionAndReopen() = runTest {
        val p = InMemoryPersistence()
        val k = Kemus.open(p, trackChanges = true, hashContents = true)
        k.set("a", "1")
        k.set("b", "2")
        val before = k.changesSince(0) // cursor covers a, b at this epoch
        k.save() // compaction writes the #EPOCH/#CHG/#END index snapshot into the log
        k.set("c", "3") // a tail change appended after the snapshot

        // Reopen on the same log: the epoch and per-key versions must survive, so a cursor taken
        // before the restart is still honoured incrementally — no forced full resync.
        val reopened = Kemus.open(p, trackChanges = true, hashContents = true)
        val after = reopened.changesSince(before.cursor, before.epoch)
        assertFalse(after.resyncRequired)
        assertEquals(listOf("c"), after.changes.map { it.key })
        // Same epoch instance survived (a fresh full pull reports it unchanged).
        assertEquals(before.epoch, reopened.changesSince(0).epoch)
        // Content hashes were restored/recomputed, so anti-entropy still works after restart.
        assertEquals(
            k.changesSince(0).changes.single { it.key == "a" }.hash,
            reopened.changesSince(0).changes.single { it.key == "a" }.hash,
        )
    }

    @Test
    fun changeIndexEpochPersistsWithoutCompaction() = runTest {
        // A tracking store with no compaction still persists its epoch on open, so a plain restart
        // (replaying the raw command log) keeps the same epoch instead of forcing every client to resync.
        val p = InMemoryPersistence()
        val first = Kemus.open(p, trackChanges = true)
        first.set("a", "1")
        val epoch = first.changesSince(0).epoch

        val reopened = Kemus.open(p, trackChanges = true)
        assertEquals(epoch, reopened.changesSince(0).epoch)
    }

    @Test
    fun changesPaginate() = runTest {
        val k = Kemus(trackChanges = true)
        repeat(10) { k.set("k$it", "v") } // versions 1..10

        // Page through in chunks of 4 (4, 4, 2): every key exactly once, in order, no resync.
        val collected = mutableListOf<String>()
        var cursor = 0L
        var epoch: String? = null
        var pages = 0
        while (true) {
            val page = k.changesSince(cursor, epoch, limit = 4)
            assertFalse(page.resyncRequired)
            epoch = page.epoch
            cursor = page.cursor
            collected += page.changes.map { it.key }
            pages++
            if (page.changes.size < 4) break
        }
        assertEquals((0 until 10).map { "k$it" }, collected)
        assertEquals(3, pages)
        // Caught up: the final cursor is the high-water mark, so a further pull is empty.
        assertTrue(k.changesSince(cursor, epoch, limit = 4).changes.isEmpty())
    }

    @Test
    fun mgetBatchFetch() = runTest {
        val k = Kemus()
        k.set("a", "1")
        k.set("b", "2")
        k.rpush("list", "x") // wrong type -> nil, like Redis
        // One call, one value per key in order: present, present, missing, wrong-type.
        assertEquals(listOf("1", "2", null, null), k.mget("a", "b", "missing", "list"))
    }

    @Test
    fun globMatching() {
        assertTrue(Kemus.glob("user:*", "user:42"))
        assertTrue(Kemus.glob("user:??", "user:42"))
        assertFalse(Kemus.glob("user:??", "user:4"))
        assertTrue(Kemus.glob("*", "anything"))
        assertFalse(Kemus.glob("a*c", "abx"))
    }
}
