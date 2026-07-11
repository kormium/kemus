package io.github.kemus.ktor

import io.github.kemus.Kemus
import io.github.kemus.client.KemusClient
import io.github.kemus.client.kemusClient
import io.github.kemus.del
import io.github.kemus.get
import io.github.kemus.incr
import io.github.kemus.lrange
import io.github.kemus.rpush
import io.github.kemus.set
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The remote [KemusClient] driving the real [KemusPlugin] server, verifying that the unified
 * [io.github.kemus.KemusCommands] API behaves the same remotely — including that RESP reply types
 * (integers, bulk strings, arrays) survive the round trip.
 */
class KemusClientIntegrationTest {

    @Test
    fun typedApiOverRemote() = testApplication {
        application { install(KemusPlugin) { store = Kemus() } }
        val http = createClient { kemusClient() }
        val client = KemusClient(http) // baseUrl "" → routed to the test server

        assertNull(client.get("missing"))

        client.set("a", "1")
        assertEquals("1", client.get("a"))

        // Integer reply round-trips as Long.
        assertEquals(2L, client.incr("a"))

        // Array-of-bulk-string reply round-trips as List<String>.
        client.rpush("l", "x", "y", "z")
        assertEquals(listOf("x", "y", "z"), client.lrange("l", 0, -1))
    }

    @Test
    fun changeIndexOverRemote() = testApplication {
        application { install(KemusPlugin) { store = Kemus(trackChanges = true) } }
        val http = createClient { kemusClient() }
        val client = KemusClient(http)

        client.set("a", "1")
        client.set("b", "2")

        val first = client.changesSince(0)
        assertFalse(first.resyncRequired)
        assertEquals(listOf("a", "b"), first.changes.map { it.key })

        // Pull again from the cursor after a delete: only the tombstone comes back.
        client.del("a")
        val next = client.changesSince(first.cursor, first.epoch)
        assertEquals(1, next.changes.size)
        assertEquals("a", next.changes.single().key)
        assertTrue(next.changes.single().deleted)
    }
}
