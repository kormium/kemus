package io.github.kemus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlin.random.Random

/**
 * An observed mutation emitted by [Kemus.changes]. [command] is the canonical argv that was applied
 * (the same form written to persistence), so a single logical write may surface as more than one
 * change (e.g. a `SET` with a TTL emits both `SET` and `PEXPIREAT`).
 */
data class KemusChange(val command: List<String>) {
    /**
     * The command's target key (`argv[1]`), or `null` for commands without one (e.g. `FLUSHALL`).
     * Note multi-key commands such as `DEL k1 k2` expose only the first key here; read [command]
     * for the full argument list.
     */
    val key: String? get() = command.getOrNull(1)
}

/**
 * An embedded, multiplatform in-memory data store with Redis-flavoured commands, TTL, Pub/Sub and
 * optional durability.
 *
 * Concurrency model: a single write lock ([Mutex]) serialises every command, so the engine behaves
 * like a single-writer event loop. This is correct on every Kotlin target — real threads on the
 * JVM/Native, the single JS/Wasm event loop — without per-platform code.
 *
 * Use [open] when a [Persistence] backend may hold existing data (it replays the log); the plain
 * constructor is fine for a volatile store.
 */
class Kemus(
    private val persistence: Persistence = NoPersistence,
    private val clock: Clock = Clock.System,
    trackChanges: Boolean = false,
    tombstoneLimit: Int = 0,
    hashContents: Boolean = false,
    maxMemory: Long = 0,
    maxMemoryPolicy: EvictionPolicy = EvictionPolicy.NOEVICTION,
    maxMemorySamples: Int = 5,
) : KemusCommands, ChangeSource {
    // Per-entry eviction bookkeeping: [memSize] is the last estimated byte size accounted into
    // [usedMemory]; [lastAccess] feeds LRU/LFU recency; [freq] is the LFU counter (0..255, saturating,
    // with the logarithmic increment Redis uses). All maintained under [mutex].
    private class Entry(
        var value: KemusValue,
        var expiresAt: Long? = null,
        var memSize: Long = 0,
        var lastAccess: Long = 0,
        var freq: Int = LFU_INIT_VAL,
    )

    private class ChannelMessage(val channel: String, val message: String)

    private val mutex = Mutex()
    private val map = HashMap<String, Entry>()

    // --- eviction / maxmemory -------------------------------------------------------------------
    // Mutable so CONFIG SET can retune them at runtime. `maxMemory == 0` means unlimited (the Redis
    // convention). `usedMemory` is an *estimate* — there is no real allocator to query on every KMP
    // target — kept as the running sum of each live entry's [Entry.memSize]. See [estimateSize].
    private var maxMemory: Long = maxMemory
    private var policy: EvictionPolicy = maxMemoryPolicy
    private var samples: Int = maxMemorySamples.coerceAtLeast(1)
    private var usedMemory: Long = 0

    // Pub/Sub is built on a single broadcast bus rather than a per-channel flow map: subscribers
    // filter the bus by channel/pattern, so there is nothing to allocate per channel and nothing
    // to leak when subscribers leave. Per-channel subscriber counts (for PUBLISH's reply) are
    // tracked explicitly as subscribers come and go.
    private val bus = MutableSharedFlow<ChannelMessage>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val subscriberCounts = HashMap<String, Int>()

    // Change-feed: every committed mutation is emitted here, carrying the same canonical command
    // that goes to persistence. Built on the same best-effort broadcast bus as pub/sub so emitting
    // never blocks the single-writer loop. See [changes].
    private val changeBus = MutableSharedFlow<KemusChange>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private class CommandError(message: String) : RuntimeException(message)

    // --- change index (opt-in, for offline→online sync) -----------------------------------------
    // A versioned index of which keys changed, maintained under the same write lock as the data so
    // it can never lose or reorder a mutation. `changeByKey` keeps the latest entry per key
    // (tombstones included); `changeVersion` is the monotonic cursor. `changeEpoch` identifies this
    // index instance: it changes whenever the index is rebuilt (e.g. process restart), which is how
    // a stale client cursor is detected. All guarded by [mutex].
    private val tracking = trackChanges
    private val tombstoneLimit = tombstoneLimit
    // When set, each live change entry carries a stable content hash of the key's value, enabling
    // "skip if both sides already agree" and cursor-independent anti-entropy. Costs an O(value-size)
    // hash per mutation, so it is opt-in on top of change-tracking.
    private val hashing = hashContents
    private var changeEpoch =
        clock.now().toEpochMilliseconds().toString(16) + "-" + Random.nextInt().toUInt().toString(16)
    private var changeVersion = 0L
    // `changeByKey` is the latest change per key (O(1) lookup for bumps); `changeLog` is a skiplist of
    // the same entries ordered by version, giving O(log n + k) "entries after a cursor" so both an
    // incremental pull (cursor near the newest) and a paginated pull (jump to a cursor, take a page)
    // stay efficient. Re-bumping a key removes its old version from the log and inserts the new one.
    private val changeByKey = HashMap<String, ChangeEntry>()
    private val changeLog = ChangeLog()
    // Compaction drops tombstones (forgetting *which* keys were deleted) but never live keys, so the
    // only history lost is deletions at or below `changeFloor`. A cursor at or after the floor has
    // already consumed them; an older cursor can no longer be served incrementally and is told to
    // resync. `tombstoneCount` is maintained incrementally to drive the optional auto-compaction.
    private var changeFloor = 0L
    private var tombstoneCount = 0

    private fun now(): Long = clock.now().toEpochMilliseconds()

    // --- lifecycle ------------------------------------------------------------------------------

    private suspend fun replay() {
        mutex.withLock {
            // Restore the change index from the log. A compacted snapshot leads with #EPOCH + its #CHG
            // records (the saved index) and ends with #END; data commands inside that region only
            // rebuild the map. Data commands outside it — a legacy log with no snapshot, or the tail
            // appended after the last snapshot — are re-counted via recordChange, reproducing the exact
            // live version sequence (and any auto-compaction) deterministically.
            var bumpRegion = true
            var restoredEpoch = false
            persistence.loadInto { cmd ->
                when (cmd[0]) {
                    CHG_EPOCH -> {
                        changeEpoch = cmd[1]
                        changeVersion = cmd[2].toLong()
                        changeFloor = cmd[3].toLong()
                        restoredEpoch = true
                        bumpRegion = false
                    }
                    CHG_ENTRY -> if (tracking) {
                        val e = ChangeEntry(cmd[1], cmd[2].toLong(), cmd[3] == "1", null)
                        changeByKey[e.key] = e
                        changeLog.put(e.version, e)
                        if (e.deleted) tombstoneCount++
                    }
                    CHG_END -> bumpRegion = true
                    else -> dispatch(cmd) { canonical -> if (tracking && bumpRegion) recordChange(canonical) }
                }
            }
            // Dispatch doesn't account memory; size the whole keyspace once so usedMemory starts right.
            recomputeMemory()
            if (tracking) {
                // Live entries restored from #CHG carry no hash; recompute now that values are loaded.
                if (hashing) for (key in changeByKey.keys.toList()) {
                    val e = changeByKey.getValue(key)
                    if (!e.deleted && e.hash == null) {
                        val withHash = e.copy(hash = hashOf(map[key]?.value))
                        changeByKey[key] = withHash
                        changeLog.replaceEntry(e.version, withHash)
                    }
                }
                // Persist the epoch if the log didn't carry one (legacy log / first run) so it — and
                // thus every client's cursor — survives the next restart. Close it with #END so the
                // header doesn't leave replay stuck in the (here empty) snapshot region.
                if (!restoredEpoch) {
                    persistence.append(listOf(CHG_EPOCH, changeEpoch, changeVersion.toString(), changeFloor.toString()))
                    persistence.append(listOf(CHG_END))
                }
            }
        }
    }

    /** Compact the persisted log to a snapshot of the current state. */
    suspend fun save(): Unit = mutex.withLock {
        persistence.rewrite(snapshotCommands())
    }

    /** Flush persistence and release resources. The store must not be used afterwards. */
    suspend fun close() {
        persistence.close()
    }

    /**
     * Launch a background job that periodically evicts expired keys (active expiration). Lazy
     * expiration on access happens regardless; this reclaims memory for keys never read again.
     */
    fun startExpiryCycle(scope: CoroutineScope, periodMs: Long = 1_000): Unit {
        scope.launch {
            while (isActive) {
                delay(periodMs)
                mutex.withLock { purgeExpired() }
            }
        }
    }

    // --- command entry points -------------------------------------------------------------------

    /** Execute a raw command (`argv`, Redis-style) and return its RESP [Reply]. */
    override suspend fun execute(args: List<String>): Reply = mutex.withLock {
        if (args.isEmpty()) return@withLock Reply.Error("ERR empty command")

        // Before a memory-growing command, evict keys to fit under maxmemory. Evictions are real
        // deletes and are committed unconditionally — even if we then reject this command for OOM
        // (noeviction, or a volatile-* policy with nothing left to evict).
        if (maxMemory > 0 && usedMemory > maxMemory && isDenyOom(args[0])) {
            val evicted = ArrayList<List<String>>()
            val fits = freeMemory(evicted)
            commit(evicted)
            if (!fits) return@withLock Reply.Error(
                "OOM command not allowed when used memory > 'maxmemory'.",
            )
        }

        // [pending] is reused across calls: execute() runs under [mutex], so only one command is in
        // flight at a time. The recorded inner lists are still freshly allocated (they may be retained
        // by persistence/the change feed) — only the outer collector is pooled.
        pending.clear()
        val reply = try {
            dispatch(args) { pending.add(it) }
        } catch (e: CommandError) {
            Reply.Error(e.message ?: "ERR")
        }
        if (reply !is Reply.Error && pending.isNotEmpty()) commit(pending)
        reply
    }

    // Reusable per-command record collector (safe: execute() is serialised by [mutex]).
    private val pending = ArrayList<List<String>>()

    /** Persist, index and broadcast each canonical command, keeping the memory estimate in step. */
    private suspend fun commit(commands: List<List<String>>) {
        // Only allocate/emit a KemusChange when something is actually collecting the change feed.
        val broadcast = changeBus.subscriptionCount.value > 0
        for (c in commands) {
            reconcileMemory(c)
            persistence.append(c)
            recordChange(c)
            if (broadcast) changeBus.tryEmit(KemusChange(c))
        }
    }

    // --- Pub/Sub --------------------------------------------------------------------------------

    private suspend fun incCount(channel: String) = mutex.withLock {
        subscriberCounts[channel] = (subscriberCounts[channel] ?: 0) + 1
    }

    private suspend fun decCount(channel: String) = mutex.withLock {
        val n = (subscriberCounts[channel] ?: 1) - 1
        if (n <= 0) subscriberCounts.remove(channel) else subscriberCounts[channel] = n
    }

    /**
     * Subscribe to a channel. The returned cold flow delivers messages published after collection
     * begins; collecting registers the subscriber (and increments the channel's count) and stopping
     * collection deregisters it. Messages are fire-and-forget — not persisted, dropped for slow
     * collectors.
     */
    override fun subscribe(channel: String): Flow<String> =
        bus.onSubscription { incCount(channel) }
            .filter { it.channel == channel }
            .map { it.message }
            // NonCancellable: the deregister must run even when collection ends via cancellation,
            // otherwise the subscriber count leaks.
            .onCompletion { withContext(NonCancellable) { decCount(channel) } }

    /**
     * Pattern subscription (Redis `PSUBSCRIBE`): receive messages from every channel whose name
     * matches [pattern] (glob `*`/`?`, the same matcher as `KEYS`), including channels created
     * later. Pattern subscribers are not reflected in PUBLISH's subscriber count.
     */
    override fun psubscribe(pattern: String): Flow<String> =
        bus.filter { glob(pattern, it.channel) }.map { it.message }

    /**
     * Publish a message to a channel. Returns the number of (exact-channel) subscribers. Not
     * persisted.
     */
    override suspend fun publish(channel: String, message: String): Int {
        bus.tryEmit(ChannelMessage(channel, message))
        return mutex.withLock { subscriberCounts[channel] ?: 0 }
    }

    // --- change-feed ----------------------------------------------------------------------------

    /**
     * A hot flow of committed mutations. Each [KemusChange] carries the canonical command that was
     * applied (the same one written to persistence): e.g. an `INCR`/`DECR` surfaces as the resulting
     * `SET`, and a `SET` with a TTL also emits a `PEXPIREAT`. Read-only commands do not emit.
     *
     * Intended as a building block for cache invalidation, secondary indexes, audit, triggers and —
     * notably — rolling your own offline→online sync: track which keys changed locally, then
     * reconcile against a remote [KemusCommands] however your application sees fit.
     *
     * Delivery is **best-effort**, exactly like pub/sub: emission is non-blocking (it never stalls
     * the single-writer loop) and the oldest events are dropped if a collector falls behind. For
     * guaranteed completeness, combine the feed with a periodic full reconciliation rather than
     * relying on it as a durable log.
     */
    fun changes(): Flow<KemusChange> = changeBus

    // --- change index ---------------------------------------------------------------------------

    /**
     * Update the versioned change index for a committed command. Called under [mutex] from [execute],
     * after the command has been applied, so existence can be read straight off [map] to set the
     * tombstone flag. No-op unless the store was constructed with change-tracking.
     */
    private fun recordChange(cmd: List<String>) {
        if (!tracking) return
        when (cmd[0]) {
            // FLUSHALL carries no keys; tombstone every key the index currently holds as live.
            "FLUSHALL" -> for (k in changeByKey.keys.toList()) {
                if (changeByKey[k]?.deleted == false) bumpChange(k)
            }
            // DEL is the only multi-key command we record.
            "DEL" -> for (i in 1 until cmd.size) bumpChange(cmd[i])
            else -> bumpChange(cmd[1])
        }
        if (tombstoneLimit > 0 && tombstoneCount > tombstoneLimit) compactTombstones()
    }

    private fun bumpChange(key: String) {
        val old = changeByKey[key]
        val wasTombstone = old?.deleted == true
        if (old != null) changeLog.removeVersion(old.version)
        val entry = map[key]
        val deleted = entry == null
        val ce = ChangeEntry(key, ++changeVersion, deleted, hashOf(entry?.value))
        changeByKey[key] = ce
        changeLog.put(ce.version, ce)
        if (deleted && !wasTombstone) tombstoneCount++ else if (!deleted && wasTombstone) tombstoneCount--
    }

    /** Stable content fingerprint of [value], or `null` for a missing key or when hashing is off. */
    private fun hashOf(value: KemusValue?): String? {
        if (!hashing || value == null) return null
        // A length-prefixed, type-tagged canonical encoding (order-normalised for the unordered
        // types) hashed with FNV-1a 64-bit. Deterministic across platforms and store instances, so
        // equal values always produce equal hashes. Excludes TTL — this fingerprints the value only.
        val sb = StringBuilder()
        fun field(s: String) { sb.append(s.length).append(':').append(s).append(';') }
        when (value) {
            is KemusValue.Str -> { sb.append("S;"); field(value.value) }
            is KemusValue.KList -> { sb.append("L;"); for (i in value.items) field(i) }
            is KemusValue.KSet -> { sb.append("T;"); for (m in value.members.sorted()) field(m) }
            is KemusValue.KHash -> {
                sb.append("H;")
                for ((k, v) in value.entries.entries.sortedBy { it.key }) { field(k); field(v) }
            }
            is KemusValue.KSortedSet -> {
                sb.append("Z;")
                for ((m, s) in value.ordered()) { field(m); field(formatScore(s)) }
            }
        }
        var h = -3750763034362895579L // FNV-1a 64-bit offset basis (0xcbf29ce484222325)
        for (b in sb.toString().encodeToByteArray()) {
            h = h xor (b.toLong() and 0xff)
            h *= 1099511628211L // FNV-1a 64-bit prime
        }
        return h.toULong().toString(16)
    }

    /** Drop every tombstone and raise the floor past them. Caller must hold [mutex]. */
    private fun compactTombstones(): Int {
        var removed = 0
        val it = changeByKey.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            if (e.deleted) {
                if (e.version > changeFloor) changeFloor = e.version
                changeLog.removeVersion(e.version)
                it.remove()
                removed++
            }
        }
        tombstoneCount = 0
        return removed
    }

    /**
     * Compact the change index: forget all tombstones (deleted-key markers) and advance the floor.
     * Returns the number dropped. Bounds the index's memory — tombstones otherwise accumulate for
     * every key ever deleted. Safe with respect to cursors: a client already synced past the dropped
     * deletions is unaffected; one still behind them gets [ChangesPage.resyncRequired] on its next
     * pull. No-op unless the store tracks changes. Run it periodically, or set `tombstoneLimit` to
     * compact automatically once that many tombstones accumulate.
     */
    suspend fun compactChanges(): Int = mutex.withLock { if (tracking) compactTombstones() else 0 }

    override suspend fun changesSince(since: Long, epoch: String?, limit: Int): ChangesPage = mutex.withLock {
        if (!tracking) return@withLock ChangesPage(changeEpoch, 0, resyncRequired = true, emptyList())
        // Resync when the cursor can't be honoured incrementally: a different index instance (epoch
        // mismatch), or a cursor older than the floor (deletions it needed have been compacted away).
        // Either way reset to 0 and hand back the whole keyspace (paginated) so the client can rebuild.
        val resync = (epoch != null && epoch != changeEpoch) || since < changeFloor
        val from = if (resync) 0L else since
        // O(log n) to the cursor + O(page). When the page was capped and more remain, its cursor is the
        // last version returned (pull again from there); otherwise the client is caught up to the
        // high-water mark and a further pull returns nothing.
        val page = changeLog.pageAfter(from, limit)
        val cursor = if (limit > 0 && page.isNotEmpty() && page.last().version < changeVersion) {
            page.last().version
        } else {
            changeVersion
        }
        ChangesPage(changeEpoch, cursor, resync, page)
    }

    // --- internals: storage helpers -------------------------------------------------------------

    private fun lookup(key: String): KemusValue? {
        val e = map[key] ?: return null
        val exp = e.expiresAt
        if (exp != null && exp <= now()) {
            discard(key)
            return null
        }
        touch(e) // record access recency for LRU/LFU
        return e.value
    }

    private fun purgeExpired() {
        val t = now()
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            val exp = e.expiresAt
            if (exp != null && exp <= t) { usedMemory -= e.memSize; it.remove() }
        }
    }

    private fun put(key: String, value: KemusValue, expiresAt: Long? = null) {
        // Overwrite reuses the existing Entry (no alloc): its memSize stays counted and the post-command
        // reconcile folds in the delta (see [reconcileKey]). A fresh key gets a new entry at the current
        // time with the LFU init counter.
        val existing = map[key]
        if (existing != null) {
            existing.value = value
            existing.expiresAt = expiresAt
            existing.lastAccess = now()
        } else {
            map[key] = Entry(value, expiresAt, lastAccess = now())
        }
    }

    private fun dropIfEmpty(key: String, value: KemusValue) {
        val empty = when (value) {
            is KemusValue.KList -> value.items.isEmpty()
            is KemusValue.KSet -> value.members.isEmpty()
            is KemusValue.KHash -> value.entries.isEmpty()
            is KemusValue.KSortedSet -> value.scores.isEmpty()
            is KemusValue.Str -> false
        }
        if (empty) discard(key)
    }

    // --- memory accounting & eviction -----------------------------------------------------------

    /** Remove a key, subtracting its accounted size. The single removal path for live keys. */
    private fun discard(key: String): Entry? {
        val e = map.remove(key) ?: return null
        usedMemory -= e.memSize
        return e
    }

    /** Re-estimate a key's size after a mutation and fold the delta into [usedMemory]. */
    private fun reconcileMemory(cmd: List<String>) {
        when (cmd[0]) {
            "DEL", "FLUSHALL" -> {} // removals already adjusted usedMemory at the point of removal
            else -> reconcileKey(cmd.getOrNull(1) ?: return)
        }
    }

    private fun reconcileKey(key: String) {
        val e = map[key] ?: return
        val newSize = estimateSize(key, e.value)
        usedMemory += newSize - e.memSize
        e.memSize = newSize
    }

    /** Recompute every entry's size and the running total from scratch (used after replay). */
    private fun recomputeMemory() {
        val t = now()
        var total = 0L
        for ((k, e) in map) {
            e.memSize = estimateSize(k, e.value)
            e.lastAccess = t
            total += e.memSize
        }
        usedMemory = total
    }

    private fun estimateSize(key: String, value: KemusValue): Long =
        ENTRY_OVERHEAD + strSize(key) + valueSize(value)

    private fun strSize(s: String): Long = strBytes(s)

    // O(1): each collection maintains its element byte-size incrementally (see KemusValue.byteSize),
    // so this no longer re-sums every element on each write.
    private fun valueSize(value: KemusValue): Long = when (value) {
        is KemusValue.Str -> strSize(value.value)
        is KemusValue.KList -> COLLECTION_OVERHEAD + value.byteSize
        is KemusValue.KSet -> COLLECTION_OVERHEAD + value.byteSize
        is KemusValue.KHash -> COLLECTION_OVERHEAD + value.byteSize
        is KemusValue.KSortedSet -> COLLECTION_OVERHEAD + value.byteSize
    }

    /** Bump access recency (LRU) and the LFU counter for an accessed entry. */
    private fun touch(e: Entry) {
        e.lastAccess = now()
        e.freq = lfuIncr(e.freq)
    }

    /** Redis-style logarithmic LFU increment: the higher the counter, the rarer a further bump. */
    private fun lfuIncr(freq: Int): Int {
        if (freq >= 255) return 255
        val baseval = (freq - LFU_INIT_VAL).coerceAtLeast(0)
        val p = 1.0 / (baseval * LFU_LOG_FACTOR + 1)
        return if (Random.nextDouble() < p) freq + 1 else freq
    }

    /** LFU counter after time decay: one point per [LFU_DECAY_MS] since the last access. */
    private fun lfuDecayed(e: Entry): Int {
        val periods = (now() - e.lastAccess) / LFU_DECAY_MS
        return (e.freq - periods).coerceAtLeast(0).toInt()
    }

    private fun isDenyOom(name: String): Boolean = name.uppercase() in DENY_OOM

    /**
     * Evict keys until [usedMemory] fits under [maxMemory], recording a `DEL` for each in [out] so
     * the caller can persist and broadcast them. Returns whether the limit was met — `false` for
     * [EvictionPolicy.NOEVICTION] or when no eligible key remains (a `volatile-*` policy with no
     * expiring keys), which the caller turns into an OOM error.
     */
    private fun freeMemory(out: MutableList<List<String>>): Boolean {
        if (policy == EvictionPolicy.NOEVICTION) return false
        while (usedMemory > maxMemory) {
            val key = pickCandidate() ?: break
            discard(key)
            out.add(listOf("DEL", key))
        }
        return usedMemory <= maxMemory
    }

    /**
     * Pick a key to evict per [policy]: reservoir-sample up to [samples] eligible keys (all keys, or
     * only those with a TTL for `volatile-*`) and return the best candidate — oldest access (LRU),
     * lowest decayed frequency (LFU), nearest expiry (volatile-ttl) or a random one. `null` when no
     * eligible key exists.
     */
    private fun pickCandidate(): String? {
        val volatileOnly = policy.isVolatile
        val pool = ArrayList<Map.Entry<String, Entry>>(samples)
        var seen = 0
        for (entry in map.entries) {
            if (volatileOnly && entry.value.expiresAt == null) continue
            seen++
            if (pool.size < samples) pool.add(entry)
            else {
                val j = Random.nextInt(seen)
                if (j < samples) pool[j] = entry
            }
        }
        if (pool.isEmpty()) return null
        val best = when (policy) {
            EvictionPolicy.ALLKEYS_RANDOM, EvictionPolicy.VOLATILE_RANDOM -> pool.random()
            EvictionPolicy.ALLKEYS_LRU, EvictionPolicy.VOLATILE_LRU -> pool.minByOrNull { it.value.lastAccess }
            EvictionPolicy.ALLKEYS_LFU, EvictionPolicy.VOLATILE_LFU -> pool.minByOrNull { lfuDecayed(it.value) }
            EvictionPolicy.VOLATILE_TTL -> pool.minByOrNull { it.value.expiresAt ?: Long.MAX_VALUE }
            EvictionPolicy.NOEVICTION -> null
        }
        return best?.key
    }

    // --- CONFIG / INFO --------------------------------------------------------------------------

    private fun configValues(): Map<String, String> = linkedMapOf(
        "maxmemory" to maxMemory.toString(),
        "maxmemory-policy" to policy.configName,
        "maxmemory-samples" to samples.toString(),
    )

    private fun configGet(param: String): Reply {
        val pattern = param.lowercase()
        val out = ArrayList<Reply>()
        for ((k, v) in configValues()) if (glob(pattern, k)) {
            out.add(Reply.BulkString(k)); out.add(Reply.BulkString(v))
        }
        return Reply.Array(out)
    }

    private fun configSet(param: String, value: String, record: (List<String>) -> Unit): Reply {
        when (param.lowercase()) {
            "maxmemory" -> maxMemory = parseMemory(value)
                ?: throw CommandError("ERR Invalid argument '$value' for CONFIG SET 'maxmemory'")
            "maxmemory-policy" -> policy = EvictionPolicy.fromConfig(value)
                ?: throw CommandError("ERR Invalid argument '$value' for CONFIG SET 'maxmemory-policy'")
            "maxmemory-samples" -> samples = value.toIntOrNull()?.takeIf { it > 0 }
                ?: throw CommandError("ERR Invalid argument '$value' for CONFIG SET 'maxmemory-samples'")
            else -> throw CommandError("ERR Unknown option or number of arguments for CONFIG SET - '$param'")
        }
        // Apply a tightened limit immediately: evict now and record the DELs through the command's
        // persistence/change path so the new bound takes effect without waiting for the next write.
        if (maxMemory > 0 && usedMemory > maxMemory && policy != EvictionPolicy.NOEVICTION) {
            val evicted = ArrayList<List<String>>()
            freeMemory(evicted)
            for (c in evicted) record(c)
        }
        return Reply.OK
    }

    private fun info(section: String?): Reply {
        val want = section?.lowercase()
        val sb = StringBuilder()
        if (want == null || want == "memory" || want == "default" || want == "all") {
            sb.append("# Memory\r\n")
            sb.append("used_memory:").append(usedMemory).append("\r\n")
            sb.append("maxmemory:").append(maxMemory).append("\r\n")
            sb.append("maxmemory_policy:").append(policy.configName).append("\r\n")
        }
        if (want == null || want == "keyspace" || want == "default" || want == "all") {
            sb.append("# Keyspace\r\n")
            sb.append("db0:keys=").append(map.size).append("\r\n")
        }
        return Reply.BulkString(sb.toString())
    }

    private fun wrongType(): Nothing =
        throw CommandError("WRONGTYPE Operation against a key holding the wrong kind of value")

    private fun arity(name: String): Nothing =
        throw CommandError("ERR wrong number of arguments for '${name.lowercase()}' command")

    private fun asStr(v: KemusValue) = v as? KemusValue.Str ?: wrongType()
    private fun asList(v: KemusValue) = v as? KemusValue.KList ?: wrongType()
    private fun asSet(v: KemusValue) = v as? KemusValue.KSet ?: wrongType()
    private fun asHash(v: KemusValue) = v as? KemusValue.KHash ?: wrongType()
    private fun asZset(v: KemusValue) = v as? KemusValue.KSortedSet ?: wrongType()

    // --- the dispatcher -------------------------------------------------------------------------

    private fun dispatch(args: List<String>, record: (List<String>) -> Unit): Reply {
        val name = args[0].uppercase()
        fun need(n: Int) { if (args.size != n) arity(name) }
        fun needAtLeast(n: Int) { if (args.size < n) arity(name) }

        return when (name) {
            // -- connection --
            "PING" -> if (args.size >= 2) Reply.BulkString(args[1]) else Reply.SimpleString("PONG")
            "ECHO" -> { need(2); Reply.BulkString(args[1]) }

            // -- generic keys --
            "DEL" -> {
                needAtLeast(2)
                var n = 0
                for (i in 1 until args.size) if (discard(args[i]) != null) n++
                if (n > 0) record(args)
                Reply.ofInt(n)
            }
            "EXISTS" -> {
                needAtLeast(2)
                var n = 0
                for (i in 1 until args.size) if (lookup(args[i]) != null) n++
                Reply.ofInt(n)
            }
            "TYPE" -> {
                need(2)
                Reply.SimpleString(lookup(args[1])?.typeName ?: "none")
            }
            "KEYS" -> {
                need(2)
                purgeExpired()
                val matched = map.keys.filter { glob(args[1], it) }.map { Reply.BulkString(it) }
                Reply.Array(matched)
            }
            "DBSIZE" -> { need(1); purgeExpired(); Reply.ofInt(map.size) }
            "FLUSHALL" -> { map.clear(); usedMemory = 0; record(listOf("FLUSHALL")); Reply.OK }

            // -- expiration --
            "EXPIRE", "PEXPIRE" -> {
                need(3)
                val e = map[args[1]] ?: return Reply.ofInt(0)
                if (lookup(args[1]) == null) return Reply.ofInt(0)
                val amount = args[2].toLongOrNull() ?: throw CommandError("ERR value is not an integer")
                val abs = now() + if (name == "EXPIRE") amount * 1000 else amount
                e.expiresAt = abs
                record(listOf("PEXPIREAT", args[1], abs.toString()))
                Reply.ofInt(1)
            }
            "PEXPIREAT" -> {
                need(3)
                val e = map[args[1]] ?: return Reply.ofInt(0)
                val abs = args[2].toLongOrNull() ?: throw CommandError("ERR value is not an integer")
                e.expiresAt = abs
                record(args)
                Reply.ofInt(1)
            }
            "PERSIST" -> {
                need(2)
                val e = map[args[1]]
                if (e == null || e.expiresAt == null || lookup(args[1]) == null) return Reply.ofInt(0)
                e.expiresAt = null
                record(args)
                Reply.ofInt(1)
            }
            "TTL", "PTTL" -> {
                need(2)
                val e = map[args[1]] ?: return Reply.ofInt(-2)
                if (lookup(args[1]) == null) return Reply.ofInt(-2)
                val exp = e.expiresAt ?: return Reply.ofInt(-1)
                val ms = exp - now()
                Reply.ofInt(if (name == "TTL") (ms + 999) / 1000 else ms)
            }

            // -- strings --
            "SET" -> set(args, record)
            "GET" -> {
                need(2)
                val v = lookup(args[1]) ?: return Reply.Nil
                Reply.BulkString(asStr(v).value)
            }
            // Batch fetch — one round trip for many keys. As in Redis, a missing or non-string key
            // yields nil rather than an error, so the array lines up 1:1 with the requested keys. Lets
            // a sync pull the values of just the keys it found changed (and, with hashes, only those
            // that actually differ) in a single call instead of N GETs.
            "MGET" -> {
                needAtLeast(2)
                Reply.Array((1 until args.size).map { i ->
                    when (val v = lookup(args[i])) {
                        is KemusValue.Str -> Reply.BulkString(v.value)
                        else -> Reply.Nil
                    }
                })
            }
            "APPEND" -> {
                need(3)
                val s = when (val existing = lookup(args[1])) {
                    null -> KemusValue.Str("").also { put(args[1], it) }
                    is KemusValue.Str -> existing
                    else -> wrongType()
                }
                s.value += args[2]
                record(args)
                Reply.ofInt(s.value.length)
            }
            "INCR" -> { need(2); incrBy(args[1], 1, record) }
            "DECR" -> { need(2); incrBy(args[1], -1, record) }
            "INCRBY" -> {
                need(3)
                val by = args[2].toLongOrNull() ?: throw CommandError("ERR value is not an integer")
                incrBy(args[1], by, record)
            }
            "DECRBY" -> {
                need(3)
                val by = args[2].toLongOrNull() ?: throw CommandError("ERR value is not an integer")
                incrBy(args[1], -by, record)
            }

            // -- hashes --
            "HSET" -> {
                needAtLeast(4)
                if ((args.size - 2) % 2 != 0) arity(name)
                val h = asHash(lookup(args[1]) ?: KemusValue.KHash().also { put(args[1], it) })
                var added = 0
                var i = 2
                while (i < args.size) {
                    if (h.put(args[i], args[i + 1]) == null) added++
                    i += 2
                }
                record(args)
                Reply.ofInt(added)
            }
            "HGET" -> {
                need(3)
                val v = lookup(args[1]) ?: return Reply.Nil
                Reply.of(asHash(v).entries[args[2]])
            }
            "HGETALL" -> {
                need(2)
                val v = lookup(args[1]) ?: return Reply.Array(emptyList())
                Reply.Array(asHash(v).entries.flatMap { listOf(Reply.BulkString(it.key), Reply.BulkString(it.value)) })
            }
            "HDEL" -> {
                needAtLeast(3)
                val v = lookup(args[1]) ?: return Reply.ofInt(0)
                val h = asHash(v)
                var n = 0
                for (i in 2 until args.size) if (h.remove(args[i]) != null) n++
                if (n > 0) { record(args); dropIfEmpty(args[1], h) }
                Reply.ofInt(n)
            }
            "HKEYS" -> { need(2); collection(args[1]) { asHash(it).entries.keys } }
            "HVALS" -> { need(2); collection(args[1]) { asHash(it).entries.values } }
            "HLEN" -> { need(2); count(args[1]) { asHash(it).entries.size } }
            "HEXISTS" -> {
                need(3)
                val v = lookup(args[1]) ?: return Reply.ofInt(0)
                Reply.bools(asHash(v).entries.containsKey(args[2]))
            }

            // -- lists --
            "LPUSH", "RPUSH" -> {
                needAtLeast(3)
                val l = asList(lookup(args[1]) ?: KemusValue.KList().also { put(args[1], it) })
                for (i in 2 until args.size) if (name == "LPUSH") l.addFirst(args[i]) else l.addLast(args[i])
                record(args)
                Reply.ofInt(l.items.size)
            }
            "LPOP", "RPOP" -> {
                need(2)
                val v = lookup(args[1]) ?: return Reply.Nil
                val l = asList(v)
                if (l.items.isEmpty()) return Reply.Nil
                val popped = if (name == "LPOP") l.removeFirst() else l.removeLast()
                record(args)
                dropIfEmpty(args[1], l)
                Reply.BulkString(popped)
            }
            "LLEN" -> { need(2); count(args[1]) { asList(it).items.size } }
            "LRANGE" -> {
                need(4)
                val v = lookup(args[1]) ?: return Reply.Array(emptyList())
                val l = asList(v).items
                val (s, e) = normalizeRange(args[2], args[3], l.size)
                if (s > e || l.isEmpty()) return Reply.Array(emptyList())
                Reply.Array((s..e).map { Reply.BulkString(l[it]) })
            }

            // -- sets --
            "SADD" -> {
                needAtLeast(3)
                val s = asSet(lookup(args[1]) ?: KemusValue.KSet().also { put(args[1], it) })
                var n = 0
                for (i in 2 until args.size) if (s.add(args[i])) n++
                if (n > 0) record(args)
                Reply.ofInt(n)
            }
            "SREM" -> {
                needAtLeast(3)
                val v = lookup(args[1]) ?: return Reply.ofInt(0)
                val s = asSet(v)
                var n = 0
                for (i in 2 until args.size) if (s.remove(args[i])) n++
                if (n > 0) { record(args); dropIfEmpty(args[1], s) }
                Reply.ofInt(n)
            }
            "SMEMBERS" -> { need(2); collection(args[1]) { asSet(it).members } }
            "SISMEMBER" -> {
                need(3)
                val v = lookup(args[1]) ?: return Reply.ofInt(0)
                Reply.bools(asSet(v).members.contains(args[2]))
            }
            "SCARD" -> { need(2); count(args[1]) { asSet(it).members.size } }

            // -- sorted sets --
            "ZADD" -> {
                needAtLeast(4)
                if ((args.size - 2) % 2 != 0) arity(name)
                val z = asZset(lookup(args[1]) ?: KemusValue.KSortedSet().also { put(args[1], it) })
                var added = 0
                var i = 2
                while (i < args.size) {
                    val score = args[i].toDoubleOrNull() ?: throw CommandError("ERR value is not a valid float")
                    if (z.add(args[i + 1], score)) added++
                    i += 2
                }
                record(args)
                Reply.ofInt(added)
            }
            "ZSCORE" -> {
                need(3)
                val v = lookup(args[1]) ?: return Reply.Nil
                val score = asZset(v).scores[args[2]] ?: return Reply.Nil
                Reply.BulkString(formatScore(score))
            }
            "ZREM" -> {
                needAtLeast(3)
                val v = lookup(args[1]) ?: return Reply.ofInt(0)
                val z = asZset(v)
                var n = 0
                for (i in 2 until args.size) if (z.remove(args[i])) n++
                if (n > 0) { record(args); dropIfEmpty(args[1], z) }
                Reply.ofInt(n)
            }
            "ZCARD" -> { need(2); count(args[1]) { asZset(it).scores.size } }
            "ZRANGE" -> {
                needAtLeast(4)
                val withScores = args.size >= 5 && args[4].equals("WITHSCORES", ignoreCase = true)
                val v = lookup(args[1]) ?: return Reply.Array(emptyList())
                val z = asZset(v)
                val (s, e) = normalizeRange(args[2], args[3], z.size)
                if (s > e) return Reply.Array(emptyList())
                // Walk only the requested slice instead of materialising the whole ordered set.
                val slice = z.range(s, e)
                val out = ArrayList<Reply>(if (withScores) slice.size * 2 else slice.size)
                for ((member, score) in slice) {
                    out.add(Reply.BulkString(member))
                    if (withScores) out.add(Reply.BulkString(formatScore(score)))
                }
                Reply.Array(out)
            }

            // -- pub/sub (publish via execute is allowed; not persisted) --
            "PUBLISH" -> {
                need(3)
                bus.tryEmit(ChannelMessage(args[1], args[2]))
                Reply.ofInt(subscriberCounts[args[1]] ?: 0)
            }

            // -- server / introspection (config is not part of the keyspace; never persisted) --
            "CONFIG" -> {
                needAtLeast(2)
                when (args[1].uppercase()) {
                    "GET" -> { need(3); configGet(args[2]) }
                    "SET" -> { need(4); configSet(args[2], args[3], record) }
                    "RESETSTAT" -> Reply.OK
                    else -> throw CommandError("ERR Unknown CONFIG subcommand '${args[1]}'")
                }
            }
            "INFO" -> {
                if (args.size > 2) arity(name)
                info(args.getOrNull(1))
            }

            else -> Reply.Error("ERR unknown command '${args[0]}'")
        }
    }

    private fun set(args: List<String>, record: (List<String>) -> Unit): Reply {
        if (args.size < 3) arity("SET")
        val key = args[1]
        val value = args[2]
        var ttlMs: Long? = null
        var nx = false
        var xx = false
        var keepTtl = false
        var i = 3
        while (i < args.size) {
            when (args[i].uppercase()) {
                "EX" -> { ttlMs = (args.getOrNull(++i)?.toLongOrNull() ?: throw CommandError("ERR syntax error")) * 1000 }
                "PX" -> { ttlMs = args.getOrNull(++i)?.toLongOrNull() ?: throw CommandError("ERR syntax error") }
                "NX" -> nx = true
                "XX" -> xx = true
                "KEEPTTL" -> keepTtl = true
                else -> throw CommandError("ERR syntax error")
            }
            i++
        }
        val exists = lookup(key) != null
        if (nx && exists) return Reply.Nil
        if (xx && !exists) return Reply.Nil

        val prevExpiry = if (keepTtl) map[key]?.expiresAt else null
        val abs = ttlMs?.let { now() + it }
        put(key, KemusValue.Str(value), abs ?: prevExpiry)

        record(listOf("SET", key, value))
        val effectiveExpiry = abs ?: prevExpiry
        if (effectiveExpiry != null) record(listOf("PEXPIREAT", key, effectiveExpiry.toString()))
        return Reply.OK
    }

    private fun incrBy(key: String, by: Long, record: (List<String>) -> Unit): Reply {
        val current = lookup(key)
        val expiry = map[key]?.expiresAt
        val n = if (current == null) 0L else asStr(current).value.toLongOrNull()
            ?: throw CommandError("ERR value is not an integer or out of range")
        val updated = n + by
        val updatedStr = updated.toString()
        put(key, KemusValue.Str(updatedStr), expiry)
        // Record the resulting value as a canonical SET so replay is order-independent; preserve TTL.
        record(listOf("SET", key, updatedStr))
        if (expiry != null) record(listOf("PEXPIREAT", key, expiry.toString()))
        return Reply.ofInt(updated)
    }

    private inline fun collection(key: String, extract: (KemusValue) -> Collection<String>): Reply {
        val v = lookup(key) ?: return Reply.Array(emptyList())
        return Reply.Array(extract(v).map { Reply.BulkString(it) })
    }

    private inline fun count(key: String, extract: (KemusValue) -> Int): Reply {
        val v = lookup(key) ?: return Reply.ofInt(0)
        return Reply.ofInt(extract(v))
    }

    private fun normalizeRange(startArg: String, stopArg: String, size: Int): Pair<Int, Int> {
        var start = startArg.toIntOrNull() ?: throw CommandError("ERR value is not an integer")
        var stop = stopArg.toIntOrNull() ?: throw CommandError("ERR value is not an integer")
        if (start < 0) start = maxOf(size + start, 0)
        if (stop < 0) stop = size + stop
        if (stop >= size) stop = size - 1
        return start to stop
    }

    private fun snapshotCommands(): List<List<String>> {
        purgeExpired()
        val out = ArrayList<List<String>>()
        // Lead with the change-index snapshot: the epoch + counter/floor, so replay restores them and
        // does not renumber from scratch (which would bump the epoch and force every client to resync).
        if (tracking) out.add(listOf(CHG_EPOCH, changeEpoch, changeVersion.toString(), changeFloor.toString()))
        for ((key, entry) in map) {
            when (val v = entry.value) {
                is KemusValue.Str -> out.add(listOf("SET", key, v.value))
                is KemusValue.KList -> out.add(listOf("RPUSH", key) + v.items)
                is KemusValue.KSet -> out.add(listOf("SADD", key) + v.members)
                is KemusValue.KHash -> {
                    val cmd = ArrayList<String>().apply { add("HSET"); add(key) }
                    v.entries.forEach { cmd.add(it.key); cmd.add(it.value) }
                    out.add(cmd)
                }
                is KemusValue.KSortedSet -> {
                    val cmd = ArrayList<String>().apply { add("ZADD"); add(key) }
                    v.scores.forEach { cmd.add(formatScore(it.value)); cmd.add(it.key) }
                    out.add(cmd)
                }
            }
            entry.expiresAt?.let { out.add(listOf("PEXPIREAT", key, it.toString())) }
        }
        // Then the per-key change index in version order, closed by #END. Replay restores these as the
        // index and treats anything after #END as new changes to re-count.
        if (tracking) {
            changeLog.forEachAscending { e ->
                out.add(listOf(CHG_ENTRY, e.key, e.version.toString(), if (e.deleted) "1" else "0"))
            }
            out.add(listOf(CHG_END))
        }
        return out
    }

    companion object {
        /** Create a store and replay any persisted log. Use this when [persistence] may hold data. */
        suspend fun open(
            persistence: Persistence = NoPersistence,
            clock: Clock = Clock.System,
            trackChanges: Boolean = false,
            tombstoneLimit: Int = 0,
            hashContents: Boolean = false,
            maxMemory: Long = 0,
            maxMemoryPolicy: EvictionPolicy = EvictionPolicy.NOEVICTION,
            maxMemorySamples: Int = 5,
        ): Kemus {
            val store = Kemus(
                persistence, clock, trackChanges, tombstoneLimit, hashContents,
                maxMemory, maxMemoryPolicy, maxMemorySamples,
            )
            store.replay()
            return store
        }

        // Eviction tunables. LFU counters use Redis's logarithmic scheme: start at [LFU_INIT_VAL],
        // saturate at 255, and decay one point per [LFU_DECAY_MS] of inactivity.
        private const val LFU_INIT_VAL = 5
        private const val LFU_LOG_FACTOR = 10.0
        private const val LFU_DECAY_MS = 60_000L

        // Rough per-entry/per-string/per-collection byte overheads for the memory estimate. These are
        // deliberately approximate — there is no portable allocator to query — but stable enough to
        // drive eviction proportionally to real footprint.
        private const val ENTRY_OVERHEAD = 64L
        // Per-string overhead lives in KemusValue (shared with each collection's incremental byteSize).
        private const val COLLECTION_OVERHEAD = 48L

        // Meta-records embedded in the persistence log to make the change index durable (see replay /
        // snapshotCommands). The '#' prefix can never collide with a real Redis command.
        private const val CHG_EPOCH = "#EPOCH" // #EPOCH <epoch> <versionCounter> <floor>
        private const val CHG_ENTRY = "#CHG"   // #CHG <key> <version> <deleted 0|1>
        private const val CHG_END = "#END"     // marks the end of a compacted snapshot's index

        // Commands that may grow memory and are therefore rejected (or trigger eviction) at the limit.
        private val DENY_OOM = setOf(
            "SET", "SETNX", "APPEND", "INCR", "DECR", "INCRBY", "DECRBY",
            "HSET", "LPUSH", "RPUSH", "SADD", "ZADD",
        )

        /** Glob matcher supporting `*` (any run) and `?` (one char), as used by `KEYS`. */
        internal fun glob(pattern: String, text: String): Boolean {
            // Iterative wildcard match with backtracking on '*'.
            var p = 0
            var t = 0
            var star = -1
            var mark = 0
            while (t < text.length) {
                when {
                    p < pattern.length && (pattern[p] == '?' || pattern[p] == text[t]) -> { p++; t++ }
                    p < pattern.length && pattern[p] == '*' -> { star = p; mark = t; p++ }
                    star != -1 -> { p = star + 1; mark++; t = mark }
                    else -> return false
                }
            }
            while (p < pattern.length && pattern[p] == '*') p++
            return p == pattern.length
        }

        private fun formatScore(score: Double): String =
            if (score == score.toLong().toDouble()) score.toLong().toString() else score.toString()
    }
}
