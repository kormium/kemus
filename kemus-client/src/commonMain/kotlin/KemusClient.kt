package io.github.kemus.client

import io.github.kemus.ChangeSource
import io.github.kemus.ChangesPage
import io.github.kemus.CommandBody
import io.github.kemus.KemusCommands
import io.github.kemus.PublishBody
import io.github.kemus.Reply
import io.github.kemus.Resp
import io.github.kemus.SubscribersResponse
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A remote [KemusCommands] backed by a `kemus-server` over REST/SSE. It implements the **same**
 * interface as the embedded [io.github.kemus.Kemus], so application code (including every typed
 * helper such as `set`/`get`/`rpush`) can switch between an in-process store and a remote one
 * without changing.
 *
 * The caller supplies a configured [HttpClient]; apply [kemusClient] to install the plugins the
 * protocol needs:
 *
 * ```
 * val http = HttpClient(CIO) { kemusClient() }       // engine is platform-specific
 * val store: KemusCommands = KemusClient(http, "http://localhost:6390")
 * store.set("a", "1")
 * ```
 *
 * Commands use the lossless `application/resp` reply encoding, so reply types survive the round
 * trip exactly as from the embedded engine.
 */
class KemusClient(
    private val http: HttpClient,
    private val baseUrl: String = "",
) : KemusCommands, ChangeSource {

    override suspend fun execute(args: List<String>): Reply {
        val response = http.post("$baseUrl/command") {
            contentType(ContentType.Application.Json)
            accept(ContentType.parse(Resp.CONTENT_TYPE))
            setBody(CommandBody(args))
        }
        return Resp.decodeReply(response.body<ByteArray>())
    }

    override suspend fun publish(channel: String, message: String): Int {
        val response = http.post("$baseUrl/publish/$channel") {
            contentType(ContentType.Application.Json)
            setBody(PublishBody(message))
        }
        return response.body<SubscribersResponse>().subscribers
    }

    override fun subscribe(channel: String): Flow<String> = flow {
        http.sse("$baseUrl/subscribe/$channel") {
            incoming.collect { event -> event.data?.let { emit(it) } }
        }
    }

    override fun psubscribe(pattern: String): Flow<String> = flow {
        http.sse("$baseUrl/psubscribe?pattern=$pattern") {
            incoming.collect { event -> event.data?.let { emit(it) } }
        }
    }

    override suspend fun changesSince(since: Long, epoch: String?, limit: Int): ChangesPage =
        http.get("$baseUrl/changes") {
            parameter("since", since)
            if (epoch != null) parameter("epoch", epoch)
            if (limit > 0) parameter("limit", limit)
        }.body()
}

/** Install the ktor-client plugins [KemusClient] relies on (JSON content negotiation + SSE). */
fun HttpClientConfig<*>.kemusClient() {
    install(ContentNegotiation) { json() }
    install(SSE)
}
