package io.github.kemus.benchmarks

import io.github.kemus.Kemus
import io.github.kemus.Reply
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import kotlin.random.Random
import java.util.concurrent.TimeUnit

/**
 * Sorted-set read/write cost as a function of set size — engine-level (no network), so the data
 * structure is what's measured. The current `KSortedSet.ordered()` sorts the whole set on every read,
 * so `ZRANGE` is O(n·log n) *per call*; this benchmark makes that pathology visible and is the
 * before/after for the skiplist index.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class ZSetBenchmark {

    @Param("100", "1000", "10000")
    var setSize: Int = 0

    private lateinit var store: Kemus
    private val rng = Random(42)

    @Setup
    fun setup() {
        store = Kemus()
        runBlocking {
            for (i in 0 until setSize) {
                store.execute(listOf("ZADD", "z", rng.nextInt(setSize * 10).toString(), "m$i"))
            }
        }
    }

    /** The pathological read: full range. Current code sorts all [setSize] members every call. */
    @Benchmark
    fun zrangeAll(): Reply = runBlocking { store.execute(listOf("ZRANGE", "z", "0", "-1")) }

    /** Top-10 by score — also pays the full sort today. */
    @Benchmark
    fun zrangeTop10(): Reply = runBlocking { store.execute(listOf("ZRANGE", "z", "0", "9")) }

    /** A single add/update (O(1) on the hash today; O(log n) with a skiplist). */
    @Benchmark
    fun zadd(): Reply = runBlocking {
        store.execute(listOf("ZADD", "z", rng.nextInt(setSize * 10).toString(), "m${rng.nextInt(setSize)}"))
    }
}
