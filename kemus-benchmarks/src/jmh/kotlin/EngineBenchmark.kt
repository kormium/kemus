package io.github.kemus.benchmarks

import io.github.kemus.Kemus
import io.github.kemus.Reply
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
import java.util.concurrent.TimeUnit

/**
 * Layer 1 — the embedded [Kemus] engine, called directly with no network and no protocol. This is
 * the floor: the cost of the data structures plus the single-writer [kotlinx.coroutines.sync.Mutex].
 * The gap between this and [KemusServerBenchmark] is the HTTP/serialization tax we pay versus Redis.
 *
 * Note: a single command is sub-microsecond, so a per-op `runBlocking` would mostly measure
 * coroutine launch overhead rather than the engine. [setBatch] amortises that across [BATCH] ops
 * (reported per-op via `@OperationsPerInvocation`) to expose the engine's true ceiling; the
 * single-op benchmarks below keep the same harness shape as the networked layers for comparison.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class EngineBenchmark {

    // Keep these literals in sync with Workload.VALUE_SIZES (annotations need compile-time constants).
    @Param("16", "256", "4096")
    var valueSize: Int = 0

    private lateinit var store: Kemus
    private lateinit var setArgs: List<String>

    @Setup
    fun setup() {
        store = Kemus()
        val value = Workload.value(valueSize)
        setArgs = listOf("SET", Workload.SET_KEY, value)
        runBlocking { store.execute(listOf("SET", Workload.GET_KEY, value)) }
    }

    @Benchmark
    fun set(): Reply = runBlocking { store.execute(setArgs) }

    @Benchmark
    fun get(): Reply = runBlocking { store.execute(listOf("GET", Workload.GET_KEY)) }

    @Benchmark
    fun incr(): Reply = runBlocking { store.execute(listOf("INCR", Workload.INCR_KEY)) }

    /** Engine ceiling with `runBlocking`/dispatch cost amortised across [BATCH] commands. */
    @Benchmark
    @OperationsPerInvocation(BATCH)
    fun setBatch(): Reply = runBlocking {
        var reply: Reply = Reply.OK
        repeat(BATCH) { reply = store.execute(setArgs) }
        reply
    }

    companion object {
        const val BATCH = 1_000
    }
}
