package io.github.kemus.benchmarks

import io.github.kemus.Kemus
import io.github.kemus.client.KemusClient
import io.github.kemus.client.kemusClient
import io.github.kemus.ktor.kemus
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.engine.java.Java as ClientJava
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking

/**
 * Diagnostic (not a benchmark): fire many sequential commands through one [KemusClient] and report
 * whether they all succeed. If the CIO client reuses keep-alive connections, 30k sequential requests
 * complete fine; if it opens a fresh connection per request, it exhausts ephemeral ports and throws
 * java.net.BindException long before that. Run: `java -cp <jmh jar> ...ReuseProbeKt [N]`.
 */
fun main(args: Array<String>) {
    val n = args.firstOrNull()?.toIntOrNull() ?: 30_000
    val engineName = args.getOrNull(1) ?: "cio"
    val port = freePort()
    val store = Kemus()
    val server = embeddedServer(ServerCIO, port = port) { kemus(store) }
    server.start(wait = false)

    val http = when (engineName) {
        "java" -> HttpClient(ClientJava) { kemusClient() }
        else -> HttpClient(ClientCIO) {
            kemusClient()
            engine {
                maxConnectionsCount = 256
                endpoint.maxConnectionsPerRoute = 256
                endpoint.keepAliveTime = 60_000
            }
        }
    }
    println("engine=$engineName n=$n")
    val client = KemusClient(http, "http://localhost:$port")

    runBlocking {
        // readiness
        while (runCatching { client.execute(listOf("PING")) }.isFailure) Thread.sleep(50)
        val start = System.nanoTime()
        var ok = 0
        try {
            repeat(n) {
                client.execute(listOf("SET", "k", "v"))
                ok++
            }
        } catch (e: Exception) {
            println("FAILED after $ok/$n requests: ${e::class.simpleName}: ${e.message}")
        }
        val ms = (System.nanoTime() - start) / 1_000_000.0
        println("completed=$ok/$n in ${"%.0f".format(ms)}ms (${"%.0f".format(ok / (ms / 1000))} req/s)")
    }
    http.close()
    server.stop(100, 500)
}
