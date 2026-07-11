package io.github.kemus

import kotlinx.coroutines.CoroutineScope

/**
 * A keyspace-sharded [KemusCommands]: [shardCount] independent [Kemus] shards, each with its **own**
 * single-writer `Mutex`. Commands on keys that hash to different shards execute in parallel across
 * cores, lifting the single-`Mutex` ceiling of one [Kemus]. The trade-off is cross-shard atomicity —
 * a multi-key command spanning shards is not atomic (same model as Redis Cluster).
 *
 * Routing:
 *  - single-key commands → `shard(hash(key))` (the hot path: one shard call, fully parallel);
 *  - `DBSIZE` → sum over shards, `KEYS` → union over shards, `FLUSHALL`/`CONFIG` → broadcast;
 *  - `DEL`/`EXISTS` → keys grouped per shard, integer replies summed;
 *  - pub/sub (`SUBSCRIBE`/`PUBLISH`/…) → shard 0, since channels are not part of the keyspace, so a
 *    single shared bus keeps publishers and subscribers together.
 *
 * Persistence and the change-feed are per shard; this first cut targets a **volatile** store. Durable
 * and synced sharding (ordered AOF, a merged change index) is follow-up.
 */
class ShardedKemus(
    val shardCount: Int = DEFAULT_SHARDS,
    shardFactory: (Int) -> Kemus = { Kemus() },
) : KemusCommands {
    init { require(shardCount >= 1) { "shardCount must be >= 1" } }

    private val shards: List<Kemus> = (0 until shardCount).map(shardFactory)

    private fun indexOf(key: String): Int = (key.hashCode() and 0x7fffffff) % shardCount
    private fun shardOf(key: String): Kemus = shards[indexOf(key)]

    override suspend fun execute(args: List<String>): Reply {
        if (args.isEmpty()) return shards[0].execute(args)
        return when (args[0].uppercase()) {
            "DBSIZE" -> Reply.Integer(sumIntReplies(args))
            "KEYS" -> Reply.Array(shards.flatMap { (it.execute(args) as? Reply.Array)?.items ?: emptyList() })
            "FLUSHALL", "CONFIG" -> broadcast(args)
            "DEL", "EXISTS" -> multiKeyCount(args)
            // Channels live outside the keyspace — keep all pub/sub on one shard so it shares a bus.
            "PUBLISH", "PING", "ECHO" -> shards[0].execute(args)
            else -> {
                val key = args.getOrNull(1)
                if (key == null) shards[0].execute(args) else shardOf(key).execute(args)
            }
        }
    }

    private suspend fun sumIntReplies(args: List<String>): Long {
        var total = 0L
        for (s in shards) total += (s.execute(args) as? Reply.Integer)?.value ?: 0L
        return total
    }

    private suspend fun broadcast(args: List<String>): Reply {
        var first: Reply = Reply.OK
        for ((i, s) in shards.withIndex()) {
            val r = s.execute(args)
            if (i == 0) first = r
        }
        return first
    }

    /** DEL/EXISTS: route each key to its shard, one call per shard, sum the integer counts. */
    private suspend fun multiKeyCount(args: List<String>): Reply {
        val name = args[0]
        val byShard = HashMap<Int, MutableList<String>>()
        for (i in 1 until args.size) byShard.getOrPut(indexOf(args[i])) { ArrayList() }.add(args[i])
        var total = 0L
        for ((idx, keys) in byShard) {
            val reply = shards[idx].execute(ArrayList<String>(keys.size + 1).apply { add(name); addAll(keys) })
            if (reply is Reply.Error) return reply
            total += (reply as? Reply.Integer)?.value ?: 0L
        }
        return Reply.Integer(total)
    }

    override fun subscribe(channel: String) = shards[0].subscribe(channel)
    override fun psubscribe(pattern: String) = shards[0].psubscribe(pattern)
    override suspend fun publish(channel: String, message: String) = shards[0].publish(channel, message)

    /** Start active expiry on every shard (see [Kemus.startExpiryCycle]). */
    fun startExpiryCycle(scope: CoroutineScope, periodMs: Long = 1_000) {
        shards.forEach { it.startExpiryCycle(scope, periodMs) }
    }

    /** Flush and release every shard. The store must not be used afterwards. */
    suspend fun close() {
        shards.forEach { it.close() }
    }

    companion object {
        const val DEFAULT_SHARDS = 16
    }
}
