package io.github.kemus.benchmarks

import io.github.kemus.Kemus
import io.github.kemus.resp.RespServer
import io.lettuce.core.LettuceFutures
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisFuture
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
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
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.util.concurrent.TimeUnit

/**
 * Layer 2b — the native RESP-over-TCP server ([RespServer]) running in-process, driven by the **same**
 * Lettuce client as [RedisBenchmark]. Same client, same protocol, same machine — the only difference
 * from the Redis baseline is the server implementation, so this is the most direct kemus-vs-Redis
 * comparison, and the head-to-head for the HTTP path ([KemusServerBenchmark]) it aims to replace.
 *
 * [pipelinedSet]/[pipelinedGet] (depth 64) measure throughput once the client stops waiting per
 * command — the regime the HTTP path can never reach.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class KemusRespBenchmark {

    @Param("16", "256", "4096")
    var valueSize: Int = 0

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var server: RespServer
    private lateinit var redisClient: RedisClient
    private lateinit var connection: StatefulRedisConnection<String, String>
    private lateinit var redis: RedisCommands<String, String>
    private lateinit var pipeConnection: StatefulRedisConnection<String, String>
    private lateinit var pipeAsync: RedisAsyncCommands<String, String>
    private lateinit var value: String

    @Setup
    fun setup() {
        server = RespServer(Kemus(), host = "127.0.0.1", port = 0, dispatcher = Dispatchers.IO)
        runBlocking { server.bind() }
        scope.launch { server.serve(scope) }

        val uri = "redis://127.0.0.1:${server.boundPort}"
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
        scope.cancel()
        server.close()
    }

    @Benchmark
    fun set(): String = redis.set(Workload.SET_KEY, value)

    @Benchmark
    fun get(): String? = redis.get(Workload.GET_KEY)

    @Benchmark
    fun incr(): Long = redis.incr(Workload.INCR_KEY)

    @Benchmark
    @OperationsPerInvocation(PIPELINE)
    fun pipelinedSet() = runPipeline { pipeAsync.set(Workload.SET_KEY, value) }

    @Benchmark
    @OperationsPerInvocation(PIPELINE)
    fun pipelinedGet() = runPipeline { pipeAsync.get(Workload.GET_KEY) }

    private inline fun runPipeline(command: () -> RedisFuture<*>) {
        val futures = Array(PIPELINE) { command() }
        pipeConnection.flushCommands()
        LettuceFutures.awaitAll(5, TimeUnit.SECONDS, *futures)
    }

    companion object {
        const val PIPELINE = 64
    }
}
