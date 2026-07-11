package io.github.kemus.ktor

import io.github.kemus.CommandBody
import io.github.kemus.Kemus
import io.github.kemus.SetBody
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KemusPluginTest {

    @Test
    fun kvRoundTrip() = testApplication {
        application { install(KemusPlugin) { store = Kemus() } }
        val client = createClient { install(ContentNegotiation) { json() } }

        assertEquals(HttpStatusCode.NotFound, client.get("/kv/missing").status)

        val put = client.put("/kv/greeting") {
            contentType(ContentType.Application.Json)
            setBody(SetBody(value = "hello"))
        }
        assertEquals(HttpStatusCode.NoContent, put.status)

        val got = client.get("/kv/greeting")
        assertEquals(HttpStatusCode.OK, got.status)
        assertTrue(got.bodyAsText().contains("hello"))
    }

    @Test
    fun genericCommand() = testApplication {
        application { install(KemusPlugin) { store = Kemus() } }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.post("/command") {
            contentType(ContentType.Application.Json)
            setBody(CommandBody(listOf("RPUSH", "l", "a", "b", "c")))
        }
        val range = client.post("/command") {
            contentType(ContentType.Application.Json)
            setBody(CommandBody(listOf("LRANGE", "l", "0", "-1")))
        }
        assertEquals(HttpStatusCode.OK, range.status)
        assertTrue(range.bodyAsText().contains("a"))
    }

    @Test
    fun mountsUnderCustomPath() = testApplication {
        application { install(KemusPlugin) { store = Kemus(); path = "/cache" } }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.put("/cache/kv/x") {
            contentType(ContentType.Application.Json)
            setBody(SetBody(value = "1"))
        }
        assertEquals(HttpStatusCode.OK, client.get("/cache/kv/x").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/kv/x").status)
    }
}
