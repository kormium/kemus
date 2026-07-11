package io.github.kemus.benchmarks

import io.lettuce.core.LettuceFutures
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisFuture
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.api.sync.RedisCommands
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.TimeUnit

/**
 * Layer 3 — the Redis baseline, driven by Lettuce over RESP/TCP. By default this is the *fair*
 * target: a native `redis-server` reached via `KEMUS_REDIS_URI` (e.g. `redis://localhost:6379`).
 * If that is unset it falls back to a Testcontainers `redis:7` container — convenient, but its Docker
 * network proxy adds latency and **understates** Redis, so a warning is printed and the number must
 * not be taken as the real bar.
 *
 * [set]/[get]/[incr] mirror [KemusServerBenchmark]'s one-command-per-round-trip shape. [pipelinedSet]
 * and [pipelinedGet] show Redis's pipelined ceiling — the regime kemus must reach (via a RESP server
 * + pipelining) to be competitive on throughput, since the HTTP path cannot pipeline at all.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class RedisBenchmark {

    @Param("16", "256", "4096")
    var valueSize: Int = 0

    private var container: GenericContainer<Nothing>? = null
    private lateinit var redisClient: RedisClient
    private lateinit var connection: StatefulRedisConnection<String, String>
    private lateinit var redis: RedisCommands<String, String>

    // A second connection with auto-flush disabled, dedicated to the pipelined benchmarks so it does
    // not stall the synchronous single-op methods sharing [connection].
    private lateinit var pipeConnection: StatefulRedisConnection<String, String>
    private lateinit var pipeAsync: RedisAsyncCommands<String, String>

    private lateinit var value: String

    @Setup
    fun setup() {
        val uri = externalRedisUri() ?: run {
            val c = GenericContainer<Nothing>(DockerImageName.parse("redis:7-alpine"))
                .apply { withExposedPorts(REDIS_PORT) }
            c.start()
            container = c
            System.err.println(
                "WARNING: benchmarking Redis via Testcontainers (Docker network proxy) — this " +
                    "UNDERSTATES Redis. Set KEMUS_REDIS_URI=redis://localhost:6379 to benchmark a " +
                    "native redis-server for a fair baseline.",
            )
            "redis://${c.host}:${c.getMappedPort(REDIS_PORT)}"
        }
        redisClient = RedisClient.create(uri)
        connection = redisClient.connect()
        redis = connection.sync()

        pipeConnection = redisClient.connect()
        pipeConnection.setAutoFlushCommands(false)
        pipeAsync = pipeConnection.async()

        value = Workload.value(valueSize)
        redis.set(Workload.GET_KEY, value)
    }

    @TearDown
    fun tearDown() {
        connection.close()
        pipeConnection.close()
        redisClient.shutdown()
        container?.stop()
    }

    @Benchmark
    fun set(): String = redis.set(Workload.SET_KEY, value)

    @Benchmark
    fun get(): String? = redis.get(Workload.GET_KEY)

    @Benchmark
    fun incr(): Long = redis.incr(Workload.INCR_KEY)

    /** [PIPELINE] SETs sent back-to-back on one connection, then awaited — Redis's throughput regime. */
    @Benchmark
    @OperationsPerInvocation(PIPELINE)
    fun pipelinedSet() = runPipeline { pipeAsync.set(Workload.SET_KEY, value) }

    /** [PIPELINE] GETs sent back-to-back on one connection, then awaited. */
    @Benchmark
    @OperationsPerInvocation(PIPELINE)
    fun pipelinedGet() = runPipeline { pipeAsync.get(Workload.GET_KEY) }

    private inline fun runPipeline(command: () -> RedisFuture<*>) {
        val futures = Array(PIPELINE) { command() }
        pipeConnection.flushCommands()
        LettuceFutures.awaitAll(5, TimeUnit.SECONDS, *futures)
    }

    companion object {
        const val REDIS_PORT = 6379
        const val PIPELINE = 64
    }
}
