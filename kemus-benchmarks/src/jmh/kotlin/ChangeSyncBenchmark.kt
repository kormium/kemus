package io.github.kemus.benchmarks

import io.github.kemus.Kemus
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.util.concurrent.TimeUnit

/**
 * The sync pull cost — engine-level (no network), the metric that actually matters now that the
 * server's job is synchronisation. An incremental `changesSince(cursor)` returns only the small delta
 * of keys changed since the cursor, but today it scans the **whole** change index every call
 * (`changeByKey.values.filter { … }.sortedBy { … }`), so its cost grows with the keyspace, not the
 * delta. This benchmark makes that visible across keyspace sizes; after a version-ordered index it
 * should be flat (O(delta), not O(keyspace)).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class ChangeSyncBenchmark {

    @Param("1000", "10000", "100000")
    var keyspace: Int = 0

    private lateinit var store: Kemus
    private var cursor: Long = 0

    @Setup
    fun setup() {
        store = Kemus(trackChanges = true)
        runBlocking {
            repeat(keyspace) { store.execute(listOf("SET", "k$it", "v")) }
            // Consume the whole keyspace, then create a small delta above the cursor.
            cursor = store.changesSince(0).cursor
            repeat(DELTA) { store.execute(listOf("SET", "k$it", "v2")) }
        }
    }

    /** Incremental pull: returns [DELTA] changes regardless of keyspace — but how fast? */
    @Benchmark
    fun pullDelta(): Int = runBlocking { store.changesSince(cursor).changes.size }

    companion object {
        const val DELTA = 10
    }
}
