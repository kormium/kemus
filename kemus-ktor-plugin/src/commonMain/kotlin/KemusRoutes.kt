package io.github.kemus.ktor

import io.github.kemus.CommandBody
import io.github.kemus.DeletedResponse
import io.github.kemus.Kemus
import io.github.kemus.PublishBody
import io.github.kemus.Resp
import io.github.kemus.SetBody
import io.github.kemus.SubscribersResponse
import io.github.kemus.ValueResponse
import io.github.kemus.del
import io.github.kemus.get
import io.github.kemus.keys
import io.github.kemus.set
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.accept
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlin.time.Duration.Companion.seconds

/**
 * REST surface over a [Kemus] store. Mount under any [Route]. Requires ContentNegotiation/JSON and
 * (for `/subscribe`) the SSE plugin to be installed — [KemusPlugin] does this for you.
 */
fun Route.kemusRoutes(store: Kemus) {
    // Friendly string KV endpoints.
    get("/kv/{key}") {
        val key = call.parameters["key"]!!
        val value = store.get(key)
        if (value == null) call.respond(HttpStatusCode.NotFound) else call.respond(ValueResponse(value))
    }
    put("/kv/{key}") {
        val key = call.parameters["key"]!!
        val body = call.receive<SetBody>()
        val ttl = body.ttlSeconds
        if (ttl != null) store.set(key, body.value, ttl.seconds) else store.set(key, body.value)
        call.respond(HttpStatusCode.NoContent)
    }
    delete("/kv/{key}") {
        val key = call.parameters["key"]!!
        call.respond(DeletedResponse(store.del(key)))
    }

    get("/keys") {
        val pattern = call.request.queryParameters["pattern"] ?: "*"
        call.respond(store.keys(pattern))
    }

    // Change index for offline→online sync: a reconnecting device pulls the keys changed since its
    // cursor. First call: since=0 and no epoch. Later: pass the previous cursor + epoch. See
    // ChangesPage for the resync contract. Requires the store to be constructed with trackChanges.
    get("/changes") {
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        val epoch = call.request.queryParameters["epoch"]
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 0
        call.respond(store.changesSince(since, epoch, limit))
    }

    // Generic command endpoint — full access to the Redis-flavoured command set. Clients asking for
    // `application/resp` (the kemus-client) get a lossless RESP reply; everyone else (curl, …) gets
    // friendly JSON.
    post("/command") {
        val body = call.receive<CommandBody>()
        val reply = store.execute(body.args)
        if (call.request.accept()?.contains(Resp.CONTENT_TYPE) == true) {
            val (type, subtype) = Resp.CONTENT_TYPE.split("/")
            call.respondBytes(Resp.encodeReply(reply), ContentType(type, subtype))
        } else {
            call.respond(reply.toJson())
        }
    }

    // Pub/Sub.
    post("/publish/{channel}") {
        val channel = call.parameters["channel"]!!
        val body = call.receive<PublishBody>()
        call.respond(SubscribersResponse(store.publish(channel, body.message)))
    }
    sse("/subscribe/{channel}") {
        val channel = call.parameters["channel"]!!
        store.subscribe(channel).collect { message ->
            send(ServerSentEvent(data = message))
        }
    }
    // Pattern subscription, e.g. GET /psubscribe?pattern=user:*
    sse("/psubscribe") {
        val pattern = call.request.queryParameters["pattern"] ?: "*"
        store.psubscribe(pattern).collect { message ->
            send(ServerSentEvent(data = message))
        }
    }
}
