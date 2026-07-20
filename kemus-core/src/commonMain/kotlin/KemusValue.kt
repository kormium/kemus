package io.github.kemus

/** Per-element memory model, shared by the engine's size accounting and each collection's running
 *  [byte size][KemusValue.KList.byteSize] so the latter is maintained incrementally (O(1) per
 *  mutation) instead of re-summed on every write. */
internal const val STRING_OVERHEAD = 40L

internal fun strBytes(s: String): Long = STRING_OVERHEAD + s.length * 2L

/**
 * The in-memory value held under a key. Mirrors the core Redis data types so the engine, the
 * RESP/AOF wire format, and the REST API can all share one semantic model.
 *
 * Instances are mutable and are only ever touched while holding the store's write lock
 * (see [Kemus]); they are not safe to mutate outside the engine. Each collection keeps a running
 * [byteSize] (the summed size of its elements) so the engine's `maxmemory` accounting is O(1) per
 * write rather than O(n).
 */
sealed interface KemusValue {
    /** The Redis-style type name reported by `TYPE`. */
    val typeName: String

    class Str(var value: String) : KemusValue {
        override val typeName: String get() = "string"
    }

    /**
     * A binary-safe blob. Held as a raw [ByteArray] in memory (no UTF-16 tax); base64 is applied only
     * at the text boundaries — the canonical command written to the AOF/change-feed and the RESP wire.
     * Set/read it with the typed [Kemus.setBytes]/[Kemus.getBytes], or the `SETB`/`GETB` commands.
     */
    class Bytes(var value: ByteArray) : KemusValue {
        override val typeName: String get() = "bytes"
    }

    class KList : KemusValue {
        override val typeName: String get() = "list"

        private val deque = ArrayDeque<String>()

        /** Read-only view for `LRANGE`/`LLEN` (indexing, size, iteration). Mutate via the methods. */
        val items: List<String> get() = deque
        var byteSize: Long = 0L
            private set

        fun addFirst(value: String) { deque.addFirst(value); byteSize += strBytes(value) }
        fun addLast(value: String) { deque.addLast(value); byteSize += strBytes(value) }
        fun removeFirst(): String = deque.removeFirst().also { byteSize -= strBytes(it) }
        fun removeLast(): String = deque.removeLast().also { byteSize -= strBytes(it) }
    }

    class KSet : KemusValue {
        override val typeName: String get() = "set"

        private val backing = LinkedHashSet<String>()

        /** Read-only view for `SMEMBERS`/`SISMEMBER`/`SCARD`. Mutate via [add]/[remove]. */
        val members: Set<String> get() = backing
        var byteSize: Long = 0L
            private set

        fun add(member: String): Boolean = backing.add(member).also { if (it) byteSize += strBytes(member) }
        fun remove(member: String): Boolean = backing.remove(member).also { if (it) byteSize -= strBytes(member) }
    }

    class KHash : KemusValue {
        override val typeName: String get() = "hash"

        private val map = LinkedHashMap<String, String>()

        /** Read-only view for `HGET`/`HGETALL`/`HKEYS`/… Mutate via [put]/[remove]. */
        val entries: Map<String, String> get() = map
        var byteSize: Long = 0L
            private set

        /** Returns the previous value, or null if the field is new (mirrors `Map.put`). */
        fun put(field: String, value: String): String? {
            val previous = map.put(field, value)
            byteSize += if (previous == null) strBytes(field) + strBytes(value)
            else strBytes(value) - strBytes(previous)
            return previous
        }

        fun remove(field: String): String? =
            map.remove(field)?.also { byteSize -= strBytes(field) + strBytes(it) }
    }

    /**
     * Sorted set: a member→score map for O(1) score/membership lookup, plus a [SortedSetIndex]
     * skiplist that keeps `(score, member)` order incrementally — so [ordered]/[range] walk the
     * skiplist rather than re-sorting on every read. Mutate via [add]/[remove]; [scores] is read-only.
     */
    class KSortedSet : KemusValue {
        override val typeName: String get() = "zset"

        private val byMember = HashMap<String, Double>()
        private val index = SortedSetIndex()

        /** Read-only member→score view. Mutate with [add]/[remove]. */
        val scores: Map<String, Double> get() = byMember
        val size: Int get() = byMember.size
        var byteSize: Long = 0L
            private set

        /** Set/update [member]'s [score]; returns true if the member was newly added. */
        fun add(member: String, score: Double): Boolean {
            val previous = byMember.put(member, score)
            if (previous != null) {
                if (previous == score) return false
                index.remove(previous, member)
            } else {
                byteSize += strBytes(member) + SCORE_BYTES
            }
            index.insert(score, member)
            return previous == null
        }

        /** Remove [member]; returns true if it was present. */
        fun remove(member: String): Boolean {
            val previous = byMember.remove(member) ?: return false
            index.remove(previous, member)
            byteSize -= strBytes(member) + SCORE_BYTES
            return true
        }

        /** All members ordered by (score, member). O(n) walk of the skiplist — no per-call sort. */
        fun ordered(): List<Pair<String, Double>> = index.toList()

        /** Members at indices [start]..[endInclusive] in order, walking only that slice. */
        fun range(start: Int, endInclusive: Int): List<Pair<String, Double>> =
            index.range(start, endInclusive)

        private companion object {
            const val SCORE_BYTES = 8L
        }
    }
}
