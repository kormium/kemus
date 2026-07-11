package io.github.kemus.benchmarks

import io.github.kemus.ShardedKemus
import kotlinx.coroutines.Dispatchers
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
import kotlin.random.Random
import java.util.concurrent.TimeUnit

/**
 * The sharding hypothesis, with **no network** to add noise: drive the store directly with
 * [concurrency] coroutines on the CPU dispatcher and measure aggregate throughput. Each invocation
 * does a fixed [TOTAL_OPS] split across the coroutines, so throughput = TOTAL_OPS / wall-time, and
 * `@OperationsPerInvocation` stays a compile-time constant.
 *
 *  - `shardCount=1` (one global `Mutex`): every coroutine serialises on one lock → throughput is flat
 *    as concurrency rises (the plateau).
 *  - `shardCount=16`: keys hash to independent shards/locks → throughput scales with concurrency up to
 *    the core count, demonstrating the plateau being broken.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class EngineConcurrencyBenchmark {

    @Param("1", "16")
    var shardCount: Int = 0

    @Param("1", "4", "8", "16")
    var concurrency: Int = 0

    private lateinit var store: ShardedKemus
    private lateinit var value: String

    @Setup
    fun setup() {
        store = ShardedKemus(shardCount)
        value = Workload.value(256)
        runBlocking { repeat(KEYSPACE) { store.execute(listOf("SET", "k$it", value)) } }
    }

    @Benchmark
    @OperationsPerInvocation(TOTAL_OPS)
    fun set() = runBlocking {
        val perCoroutine = TOTAL_OPS / concurrency
        val jobs = (0 until concurrency).map {
            launch(Dispatchers.Default) {
                val rng = Random(it)
                repeat(perCoroutine) {
                    store.execute(listOf("SET", "k${rng.nextInt(KEYSPACE)}", value))
                }
            }
        }
        jobs.forEach { it.join() }
    }

    companion object {
        const val KEYSPACE = 10_000
        const val TOTAL_OPS = 200_000
    }
}
