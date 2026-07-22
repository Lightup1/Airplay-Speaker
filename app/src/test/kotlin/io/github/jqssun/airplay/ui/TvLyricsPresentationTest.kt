package io.github.jqssun.airplay.ui

import io.github.jqssun.airplay.lyrics.LyricLine
import io.github.jqssun.airplay.lyrics.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvLyricsPresentationTest {

    @Test
    fun attachesFullWidthParentheticalRowBelowThePreviousLyric() {
        val main = LyricLine(
            timeMs = 39_980L,
            text = "A face that laughs every time I fall",
            words = listOf(LyricWord("A face that laughs every time I fall", 39_980L, 41_390L)),
            exactWordTiming = true,
            endMs = 41_390L,
        )
        val backingVocal = LyricLine(
            timeMs = 41_390L,
            text = "（And watches everything）",
            words = listOf(LyricWord("（And watches everything）", 41_390L, 42_710L)),
            exactWordTiming = true,
            endMs = 42_710L,
        )

        val merged = mergeStandaloneParentheticalLines(listOf(main, backingVocal))

        assertEquals(1, merged.size)
        assertEquals(
            "A face that laughs every time I fall （And watches everything）",
            merged.single().text,
        )
        assertEquals(42_710L, merged.single().endMs)
        assertTrue(merged.single().exactWordTiming)
    }

    @Test
    fun attachesAnIndependentLeadingAsideAboveTheNextLyric() {
        val aside = LyricLine(1_000L, "(Whispering)", endMs = 1_600L)
        val main = LyricLine(5_000L, "This is the main line", endMs = 7_000L)

        val merged = mergeStandaloneParentheticalLines(listOf(aside, main))

        assertEquals(1, merged.size)
        assertEquals("(Whispering) This is the main line", merged.single().text)
        assertEquals(1_000L, merged.single().timeMs)
        assertEquals(7_000L, merged.single().endMs)
    }
}
