package io.github.kemus

import kotlin.random.Random

/**
 * A skiplist of [ChangeEntry] ordered by version — the index behind the durable-sync change log.
 * O(log n) insert/remove and O(log n + k) "the k entries after a cursor", which keeps **both** an
 * incremental pull (cursor near the newest) and a **paginated** pull (jump to an arbitrary cursor,
 * take a page) efficient. Versions are unique. Not thread-safe — guarded by the engine's write lock.
 */
internal class ChangeLog {
    private class Node(val version: Long, var entry: ChangeEntry?, level: Int) {
        val forward = arrayOfNulls<Node>(level)
    }

    // Sentinel head; its entry is never read.
    private val head = Node(Long.MIN_VALUE, null, MAX_LEVEL)
    private var levels = 1
    var size = 0
        private set

    private fun randomLevel(): Int {
        var lvl = 1
        while (lvl < MAX_LEVEL && Random.nextBoolean()) lvl++
        return lvl
    }

    fun put(version: Long, entry: ChangeEntry) {
        val update = arrayOfNulls<Node>(MAX_LEVEL)
        var x = head
        for (i in levels - 1 downTo 0) {
            var next = x.forward[i]
            while (next != null && next.version < version) { x = next; next = x.forward[i] }
            update[i] = x
        }
        val lvl = randomLevel()
        if (lvl > levels) {
            for (i in levels until lvl) update[i] = head
            levels = lvl
        }
        val node = Node(version, entry, lvl)
        for (i in 0 until lvl) {
            node.forward[i] = update[i]!!.forward[i]
            update[i]!!.forward[i] = node
        }
        size++
    }

    fun removeVersion(version: Long): Boolean {
        val update = arrayOfNulls<Node>(MAX_LEVEL)
        var x = head
        for (i in levels - 1 downTo 0) {
            var next = x.forward[i]
            while (next != null && next.version < version) { x = next; next = x.forward[i] }
            update[i] = x
        }
        val target = x.forward[0] ?: return false
        if (target.version != version) return false
        for (i in 0 until levels) {
            if (update[i]!!.forward[i] === target) update[i]!!.forward[i] = target.forward[i]
        }
        while (levels > 1 && head.forward[levels - 1] == null) levels--
        size--
        return true
    }

    /**
     * Entries with version > [since], in ascending version order, at most [limit] (<= 0 means all).
     * Descends the skiplist to the cursor in O(log n), then walks the bottom level for the page.
     */
    fun pageAfter(since: Long, limit: Int): List<ChangeEntry> {
        var x = head
        for (i in levels - 1 downTo 0) {
            var next = x.forward[i]
            while (next != null && next.version <= since) { x = next; next = x.forward[i] }
        }
        val out = ArrayList<ChangeEntry>()
        var node = x.forward[0]
        while (node != null && (limit <= 0 || out.size < limit)) {
            out.add(node.entry!!)
            node = node.forward[0]
        }
        return out
    }

    /** Replace the entry stored at [version] in place (same version), if present. */
    fun replaceEntry(version: Long, entry: ChangeEntry) {
        var x = head
        for (i in levels - 1 downTo 0) {
            var next = x.forward[i]
            while (next != null && next.version < version) { x = next; next = x.forward[i] }
        }
        val node = x.forward[0]
        if (node != null && node.version == version) node.entry = entry
    }

    /** Walk every entry in ascending version order (used to write the #CHG snapshot). O(n). */
    fun forEachAscending(action: (ChangeEntry) -> Unit) {
        var node = head.forward[0]
        while (node != null) {
            action(node.entry!!)
            node = node.forward[0]
        }
    }

    private companion object {
        const val MAX_LEVEL = 24 // supports ~2^24 entries before the search degrades
    }
}
