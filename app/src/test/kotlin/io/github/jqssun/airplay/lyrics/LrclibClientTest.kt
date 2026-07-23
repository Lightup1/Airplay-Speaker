package io.github.jqssun.airplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrclibClientTest {

    @Test
    fun regularLrcGetsEstimatedWordWindows() {
        val lines = LrclibClient.parseLrc(
            "[00:01.00]Hello brave world\n[00:04.00]Next line",
            durationMs = 6_000,
        )

        assertEquals(2, lines.size)
        assertFalse(lines.first().exactWordTiming)
        assertEquals("Hello brave world", lines.first().words.joinToString("") { it.text }.trim())
        assertEquals(1_000L, lines.first().words.first().startMs)
        assertEquals(4_000L, lines.first().words.last().endMs)
    }

    @Test
    fun enhancedLrcKeepsExactWordTimestamps() {
        val lines = LrclibClient.parseLrc(
            "[00:01.00]<00:01.00>Hello <00:01.70>world\n" +
                "[00:03.00]<00:03.00>Next <00:03.60>line",
            durationMs = 5_000,
        )

        val first = lines.first()
        assertTrue(first.exactWordTiming)
        assertEquals(listOf(1_000L, 1_700L), first.words.map { it.startMs })
        assertEquals("Hello world", first.words.joinToString("") { it.text }.trim())
        assertEquals(3_000L, first.words.last().endMs)
    }

    @Test
    fun offsetAppliesToLineAndWordTimestamps() {
        val line = LrclibClient.parseLrc(
            "[offset:250]\n[00:02.00]<00:02.00>One <00:02.50>two",
            durationMs = 4_000,
        ).single()

        assertEquals(2_250L, line.timeMs)
        assertEquals(2_250L, line.words.first().startMs)
        assertEquals(2_750L, line.words.last().startMs)
    }
}
