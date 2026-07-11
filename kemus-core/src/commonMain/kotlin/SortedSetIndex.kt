package io.github.kemus

import kotlin.random.Random

/**
 * A skiplist ordering of `(score, member)` entries, ordered by score then member. Gives O(log n)
 * insert/remove and O(n) in-order iteration, so a sorted set no longer re-sorts its whole contents on
 * every read (the previous `sortedWith`-on-read was O(n·log n) per `ZRANGE`).
 *
 * Members are assumed unique here; [KemusValue.KSortedSet] keeps the member→score map and removes an
 * entry's old `(score, member)` before re-inserting on a score change, so this list never holds two
 * entries for the same member. Not thread-safe — guarded by the engine's write lock like every value.
 */
internal class SortedSetIndex {
    private class Node(val score: Double, val member: String, level: Int) {
        val forward = arrayOfNulls<Node>(level)
    }

    private val head = Node(0.0, "", MAX_LEVEL)
    private var levels = 1
    var size = 0
        private set

    /** Strict order on `(score, member)`: lower score first, then lexicographic member. */
    private fun less(s1: Double, m1: String, s2: Double, m2: String): Boolean {
        val c = s1.compareTo(s2)
        return if (c != 0) c < 0 else m1 < m2
    }

    private fun randomLevel(): Int {
        var lvl = 1
        while (lvl < MAX_LEVEL && Random.nextBoolean()) lvl++
        return lvl
    }

    fun insert(score: Double, member: String) {
        val update = arrayOfNulls<Node>(MAX_LEVEL)
        var x = head
        for (i in levels - 1 downTo 0) {
            var next = x.forward[i]
            while (next != null && less(next.score, next.member, score, member)) {
                x = next
                next = x.forward[i]
            }
            update[i] = x
        }
        val lvl = randomLevel()
        if (lvl > levels) {
            for (i in levels until lvl) update[i] = head
            levels = lvl
        }
        val node = Node(score, member, lvl)
        for (i in 0 until lvl) {
            node.forward[i] = update[i]!!.forward[i]
            update[i]!!.forward[i] = node
        }
        size++
    }

    fun remove(score: Double, member: String): Boolean {
        val update = arrayOfNulls<Node>(MAX_LEVEL)
        var x = head
        for (i in levels - 1 downTo 0) {
            var next = x.forward[i]
            while (next != null && less(next.score, next.member, score, member)) {
                x = next
                next = x.forward[i]
            }
            update[i] = x
        }
        val target = x.forward[0] ?: return false
        if (target.score != score || target.member != member) return false
        for (i in 0 until levels) {
            if (update[i]!!.forward[i] === target) update[i]!!.forward[i] = target.forward[i]
        }
        while (levels > 1 && head.forward[levels - 1] == null) levels--
        size--
        return true
    }

    /** Entries in `(score, member)` order. O(n). */
    fun toList(): List<Pair<String, Double>> = range(0, size - 1)

    /**
     * Entries at indices [start]..[endInclusive] (inclusive) in `(score, member)` order. Walks the
     * bottom level from the head, so it is O(start + k) for k returned — in particular O(k) for a
     * top-k query (`start == 0`). (A span-indexed skiplist would make the skip O(log n); deferred.)
     */
    fun range(start: Int, endInclusive: Int): List<Pair<String, Double>> {
        val from = maxOf(start, 0)
        val to = minOf(endInclusive, size - 1)
        if (from > to) return emptyList()
        val out = ArrayList<Pair<String, Double>>(to - from + 1)
        var x = head.forward[0]
        var i = 0
        while (x != null && i < from) { x = x.forward[0]; i++ }
        while (x != null && i <= to) {
            out.add(x.member to x.score)
            x = x.forward[0]
            i++
        }
        return out
    }

    private companion object {
        const val MAX_LEVEL = 24 // supports ~2^24 entries before the search degrades
    }
}
