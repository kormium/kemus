package io.github.kemus

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class BytesTest {

    private val sample = byteArrayOf(0, 1, 2, 42, -1, -128, 127, 99, 0, 0, 7)

    @Test
    fun setAndGetBytes() = runTest {
        val k = Kemus()
        k.setBytes("blob", sample)
        assertContentEquals(sample, k.getBytes("blob"))

        k.setBytes("empty", ByteArray(0))
        assertContentEquals(ByteArray(0), k.getBytes("empty"))

        assertNull(k.getBytes("missing"))
    }

    @Test
    fun getBytesOnWrongTypeThrows() = runTest {
        val k = Kemus()
        k.set("str", "hello")
        assertFailsWith<IllegalStateException> { k.getBytes("str") }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun setBytesAndGetbCommandInterop() = runTest {
        val k = Kemus()
        k.setBytes("x", sample)
        val b64 = k.execute(listOf("GETB", "x")).asString()
        assertContentEquals(sample, Base64.decode(b64!!))

        // The reverse: a raw SETB command is readable through the typed API.
        k.execute(listOf("SETB", "y", Base64.encode(sample)))
        assertContentEquals(sample, k.getBytes("y"))
    }

    @Test
    fun bytesSurviveAofReplay() = runTest {
        val p = InMemoryPersistence()
        val first = Kemus.open(p)
        first.setBytes("blob", sample)

        val second = Kemus.open(p)
        assertContentEquals(sample, second.getBytes("blob"))
    }

    @Test
    fun bytesSurviveSnapshot() = runTest {
        val p = InMemoryPersistence()
        val k = Kemus.open(p)
        k.setBytes("blob", sample)
        k.save()

        val restored = Kemus.open(p)
        assertContentEquals(sample, restored.getBytes("blob"))
    }
}
