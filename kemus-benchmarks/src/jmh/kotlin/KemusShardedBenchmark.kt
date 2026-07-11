package io.github.kemus.benchmarks

import io.github.kemus.ShardedKemus
import io.github.kemus.resp.RespServer
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import kotlin.random.Random
import java.util.concurrent.TimeUnit

/**
 * Does sharding break the single-`Mutex` plateau? This benchmark gives **each client thread its own
 * connection** (so each maps to its own server-side coroutine) and spreads keys across a keyspace, so
 * with a sharded store the per-connection coroutines execute on different shards in parallel.
 *
 * Run the thread sweep at both shard counts and compare the curves:
 * `... 'KemusShardedBenchmark' -p shardCount=1,16 -t 1 ; ... -t 8 ; ... -t 16`
 *  - `shardCount=1` (one global Mutex): throughput plateaus as threads rise — the Stage-1 ceiling.
 *  - `shardCount=16`: independent keys run concurrently, so throughput should keep climbing.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class KemusShardedBenchmark {

    @State(Scope.Benchmark)
    open class ServerState {
        @Param("1", "16")
        var shardCount: Int = 0

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private lateinit var server: RespServer
        var port: Int = 0
        lateinit var value: String

        @Setup
        fun setup() {
            val store = ShardedKemus(shardCount)
            server = RespServer(store, host = "127.0.0.1", port = 0, dispatcher = Dispatchers.IO)
            runBlocking { server.bind() }
            scope.launch { server.serve(scope) }
            port = server.boundPort

            value = Workload.value(VALUE_SIZE)
            // Pre-seed a bounded keyspace so GET hits and SET stays bounded; keys spread over shards.
            runBlocking { repeat(KEYSPACE) { store.execute(listOf("SET", "k$it", value)) } }
        }

        @TearDown
        fun tearDown() {
            scope.cancel()
            server.close()
        }
    }

    @State(Scope.Thread)
    open class ConnState {
        private lateinit var client: RedisClient
        private lateinit var connection: StatefulRedisConnection<String, String>
        lateinit var redis: RedisCommands<String, String>
        val rng = Random(Random.nextInt())

        @Setup
        fun setup(server: ServerState) {
            client = RedisClient.create("redis://127.0.0.1:${server.port}")
            connection = client.connect()
            redis = connection.sync()
        }

        @TearDown
        fun tearDown() {
            connection.close()
            client.shutdown()
        }
    }

    @Benchmark
    fun set(server: ServerState, conn: ConnState): String =
        conn.redis.set("k${conn.rng.nextInt(KEYSPACE)}", server.value)

    @Benchmark
    fun get(server: ServerState, conn: ConnState): String? =
        conn.redis.get("k${conn.rng.nextInt(KEYSPACE)}")

    companion object {
        const val KEYSPACE = 10_000
        const val VALUE_SIZE = 256
    }
}
