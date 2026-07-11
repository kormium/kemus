package io.github.kemus.ktor

import io.github.kemus.ErrorResponse
import io.github.kemus.Kemus
import io.github.kemus.KemusException
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE

/** Configuration for [KemusPlugin]. */
class KemusPluginConfig {
    /** The store to expose. Required. */
    var store: Kemus? = null

    /** Path prefix the REST routes are mounted under (default `/`). */
    var path: String = "/"
}

/**
 * A ktor server plugin that exposes a [Kemus] store over REST. Install it in any ktor application:
 *
 * ```
 * install(KemusPlugin) {
 *     store = myStore
 *     path = "/cache"        // optional
 * }
 * ```
 *
 * The plugin installs the supporting plugins it needs (ContentNegotiation/JSON, SSE, StatusPages)
 * only if the application hasn't already installed them, then mounts [kemusRoutes]. To wire the
 * routes by hand instead — e.g. inside an existing `routing { }` block — call [kemusRoutes].
 */
val KemusPlugin = createApplicationPlugin("Kemus", ::KemusPluginConfig) {
    val store = requireNotNull(pluginConfig.store) { "KemusPlugin requires a `store`" }
    val prefix = pluginConfig.path

    with(application) {
        if (pluginOrNull(ContentNegotiation) == null) install(ContentNegotiation) { json() }
        if (pluginOrNull(SSE) == null) install(SSE)
        if (pluginOrNull(StatusPages) == null) {
            install(StatusPages) {
                exception<KemusException> { call, cause ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "error"))
                }
            }
        }
        routing { route(prefix) { kemusRoutes(store) } }
    }
}

/**
 * Convenience for a standalone setup: install [KemusPlugin] with the given [store]. Equivalent to
 * `install(KemusPlugin) { this.store = store }`.
 */
fun Application.kemus(store: Kemus) {
    install(KemusPlugin) { this.store = store }
}
