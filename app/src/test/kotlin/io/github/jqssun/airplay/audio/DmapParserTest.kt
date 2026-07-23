package io.github.jqssun.airplay.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class DmapParserTest {

    @Test
    fun preservesRepeatedPlayQueueSongs() {
        val first = container(
            "mlit",
            string("ceQn", "First Song") +
                string("ceQr", "First Artist") +
                string("ceQa", "First Album") +
                integer("astm", 182_000),
        )
        val second = container(
            "mlit",
            string("ceQn", "Second Song") +
                string("ceQr", "Second Artist") +
                string("ceQa", "Second Album") +
                integer("astm", 204_000),
        )
        val response = container("ceQR", container("mlcl", first + second))

        val items = DmapParser.parseQueueItems(response)

        assertEquals(2, items.size)
        assertEquals("First Song", items[0].title)
        assertEquals("First Artist", items[0].artist)
        assertEquals(182_000L, items[0].durationMs)
        assertEquals("Second Song", items[1].title)
        assertEquals("Second Album", items[1].album)
    }

    private fun string(tag: String, value: String): ByteArray = tagged(tag, value.toByteArray())

    private fun integer(tag: String, value: Int): ByteArray = tagged(
        tag,
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array(),
    )

    private fun container(tag: String, value: ByteArray): ByteArray = tagged(tag, value)

    private fun tagged(tag: String, value: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(tag.toByteArray(Charsets.US_ASCII))
        write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.size).array())
        write(value)
    }.toByteArray()
}
