package io.github.jqssun.airplay.ui

import io.github.jqssun.airplay.lyrics.LyricLine
import io.github.jqssun.airplay.lyrics.LyricWord
import io.github.jqssun.airplay.lyrics.lyricWordsMatchLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvLyricsPresentationTest {

    @Test
    fun queueBrowsingTemporarilyOverridesButDoesNotDisableLyrics() {
        assertTrue(lyricsPageVisible(lyricsEnabled = true, queueBrowsing = false))
        assertTrue(!lyricsPageVisible(lyricsEnabled = true, queueBrowsing = true))
        assertTrue(!lyricsPageVisible(lyricsEnabled = false, queueBrowsing = false))
    }

    @Test
    fun instrumentalDotsAllReachFullBrightnessAtTheLyricHandOff() {
        val durationMs = 7_000L
        val handOffMs = durationMs - 140L - 90L

        repeat(3) { index ->
            assertEquals(
                1f,
                stagedDotProgress(handOffMs, durationMs, index),
                0.001f,
            )
        }
    }

    @Test
    fun instrumentalDotsStillIlluminateFromLeftToRight() {
        val first = stagedDotProgress(3_400L, 7_000L, 0)
        val second = stagedDotProgress(3_400L, 7_000L, 1)
        val third = stagedDotProgress(3_400L, 7_000L, 2)

        assertTrue(first >= second)
        assertTrue(second >= third)
        assertTrue(first > third)
    }

    @Test
    fun instrumentalDotsDoNotIlluminateTogether() {
        val durationMs = 12_000L

        assertTrue(stagedDotProgress(2_000L, durationMs, 0) > 0f)
        assertEquals(0f, stagedDotProgress(2_000L, durationMs, 1), 0.001f)
        assertEquals(0f, stagedDotProgress(2_000L, durationMs, 2), 0.001f)
    }

    @Test
    fun lyricAuraShrinksBeforeItCanClipIntoAnEdgeBar() {
        assertEquals(
            4.6f,
            boundedLyricAuraRadius(
                desiredRadius = 30f,
                centerX = 95f,
                centerY = 40f,
                width = 100f,
                height = 80f,
            ),
            0.001f,
        )
        assertEquals(
            20f,
            boundedLyricAuraRadius(
                desiredRadius = 20f,
                centerX = 50f,
                centerY = 40f,
                width = 100f,
                height = 80f,
            ),
            0.001f,
        )
    }

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
        assertEquals("A face that laughs every time I fall", merged.single().text)
        assertEquals("And watches everything", merged.single().backgroundText)
        assertEquals(backingVocal.words, merged.single().backgroundWords)
        assertEquals(42_710L, merged.single().endMs)
        assertTrue(merged.single().exactWordTiming)
    }

    @Test
    fun attachesAnIndependentLeadingAsideAboveTheNextLyric() {
        val aside = LyricLine(1_000L, "(Whispering)", endMs = 1_600L)
        val main = LyricLine(5_000L, "This is the main line", endMs = 7_000L)

        val merged = mergeStandaloneParentheticalLines(listOf(aside, main))

        assertEquals(1, merged.size)
        assertEquals("This is the main line", merged.single().text)
        assertEquals("Whispering", merged.single().backgroundText)
        assertEquals(1_000L, merged.single().timeMs)
        assertEquals(7_000L, merged.single().endMs)
    }

    @Test
    fun trailingParentheticalBecomesASmallBelowLineAndKeepsRepeatMarker() {
        val parts = splitLyricDisplayParts(
            LyricLine(
                timeMs = 10_000L,
                text = "Don't let me go (stay with me) x 2",
                endMs = 13_000L,
            )
        )

        assertEquals("Don't let me go", parts.mainText)
        assertEquals("stay with me ×2", parts.asideText)
        assertEquals(LyricAsidePlacement.BELOW, parts.asidePlacement)
    }

    @Test
    fun leadingParentheticalBecomesASmallAboveLineEvenWithWhitespace() {
        val parts = splitLyricDisplayParts(
            LyricLine(
                timeMs = 10_000L,
                text = "  (softly) The night is young",
                endMs = 13_000L,
            )
        )

        assertEquals("The night is young", parts.mainText)
        assertEquals("softly", parts.asideText)
        assertEquals(LyricAsidePlacement.ABOVE, parts.asidePlacement)
    }

    @Test
    fun standaloneParentheticalStaysACompactBackingVocal() {
        val parts = splitLyricDisplayParts(
            LyricLine(
                timeMs = 10_000L,
                text = "(Ooh, ooh)",
                endMs = 12_000L,
            )
        )

        assertEquals("", parts.mainText)
        assertEquals("Ooh, ooh", parts.asideText)
        assertEquals(LyricAsidePlacement.ABOVE, parts.asidePlacement)
    }

    @Test
    fun largeTimedVocalStillHighlightsWhenCompactVocalIsUntimed() {
        val parts = splitLyricDisplayParts(
            LyricLine(
                timeMs = 1_000L,
                text = "Main line (soft echo)",
                words = listOf(
                    LyricWord("Main ", 1_000L, 1_500L),
                    LyricWord("line", 1_500L, 2_000L),
                ),
                exactWordTiming = true,
            )
        )

        assertTrue(lyricWordsMatchLine(parts.mainText, parts.mainWords))
        assertTrue(parts.asideWords.isEmpty())
    }

    @Test
    fun compactTimedVocalStillHighlightsWhenLargeVocalIsUntimed() {
        val parts = splitLyricDisplayParts(
            LyricLine(
                timeMs = 1_000L,
                text = "Big line (small voice)",
                words = listOf(
                    LyricWord("small ", 1_500L, 2_000L),
                    LyricWord("voice", 2_000L, 2_500L),
                ),
                exactWordTiming = true,
                asideWordRange = 0..1,
            )
        )

        assertTrue(parts.mainWords.isEmpty())
        assertTrue(lyricWordsMatchLine(parts.asideText.orEmpty(), parts.asideWords))
    }

    @Test
    fun nativeAppleBackingVocalDoesNotDependOnParenthesisParsing() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "Main line",
            words = listOf(
                LyricWord("Main ", 1_000L, 1_500L),
                LyricWord("line", 1_500L, 2_000L),
            ),
            exactWordTiming = true,
            backgroundText = "soft echo",
            backgroundWords = listOf(
                LyricWord("soft ", 1_250L, 1_700L),
                LyricWord("echo", 1_700L, 2_100L),
            ),
        )

        val parts = splitLyricDisplayParts(line)

        assertEquals("Main line", parts.mainText)
        assertEquals("soft echo", parts.asideText)
        assertEquals(line.words, parts.mainWords)
        assertEquals(line.backgroundWords, parts.asideWords)
    }
}
