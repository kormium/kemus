package io.github.kemus.benchmarks

import io.github.kemus.Kemus
import io.github.kemus.Reply
import io.github.kemus.client.KemusClient
import io.github.kemus.client.kemusClient
import io.github.kemus.ktor.kemus
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java as ClientJava
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
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
import java.util.concurrent.TimeUnit

/**
 * Layer 2 — the full kemus-server path exactly as a remote app sees it: the [KemusClient] serialises
 * the command to JSON, POSTs it over HTTP to an in-process CIO ktor server, which runs it on the
 * engine and replies with lossless RESP. The server runs in this same JVM (loopback) so the numbers
 * isolate protocol + serialization + ktor overhead, not real network latency.
 *
 * Compare against [RedisBenchmark] (the RESP-over-TCP baseline) for the headline gap, and against
 * [EngineBenchmark] to see how much of that gap is HTTP rather than the engine itself.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class KemusServerBenchmark {

    @Param("16", "256", "4096")
    var valueSize: Int = 0

    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var http: HttpClient
    private lateinit var client: KemusClient
    private lateinit var setArgs: List<String>

    @Setup
    fun setup() {
        val port = freePort()
        val store = Kemus()
        server = embeddedServer(ServerCIO, port = port) { kemus(store) }
        server.start(wait = false)

        // NOTE: the ktor *CIO* client (used in kemus-client's docs) does NOT reuse keep-alive
        // connections in 3.5.0 — it opens a fresh TCP connection per request, which caps throughput
        // and exhausts ephemeral ports (java.net.BindException) under sustained load (see
        // ReuseProbe). The Java engine (java.net.http) pools connections, so we use it here to
        // measure the *achievable* HTTP path; fixing CIO reuse is a kemus-client follow-up.
        http = HttpClient(ClientJava) { kemusClient() }
        client = KemusClient(http, "http://localhost:$port")

        val value = Workload.value(valueSize)
        setArgs = listOf("SET", Workload.SET_KEY, value)

        // Poll until the server accepts requests, then seed the GET key.
        val deadline = System.currentTimeMillis() + 15_000
        while (true) {
            val ok = runCatching {
                runBlocking { client.execute(listOf("SET", Workload.GET_KEY, value)) }
            }.isSuccess
            if (ok) break
            check(System.currentTimeMillis() < deadline) { "kemus-server did not become ready" }
            Thread.sleep(50)
        }
    }

    @TearDown
    fun tearDown() {
        http.close()
        server.stop(100, 500)
    }

    @Benchmark
    fun set(): Reply = runBlocking { client.execute(setArgs) }

    @Benchmark
    fun get(): Reply = runBlocking { client.execute(listOf("GET", Workload.GET_KEY)) }

    @Benchmark
    fun incr(): Reply = runBlocking { client.execute(listOf("INCR", Workload.INCR_KEY)) }
}
