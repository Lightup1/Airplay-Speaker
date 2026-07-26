package io.github.jqssun.airplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTimingTest {

    @Test
    fun missingWordsReceiveACompleteEstimatedTimeline() {
        val source = LyricsState(
            status = LyricsStatus.SYNCED,
            lines = listOf(
                LyricLine(
                    timeMs = 1_000,
                    text = "A complete lyric line",
                    words = listOf(LyricWord("A ", 1_000, 1_300)),
                    endMs = 4_000,
                ),
                LyricLine(timeMs = 5_000, text = "Next line"),
            ),
        )

        val repaired = source.withRepairedWordTimings(trackDurationMs = 8_000)
        val first = repaired.lines.first()

        assertTrue(lyricWordsMatchLine(first.text, first.words))
        assertFalse(first.exactWordTiming)
        assertEquals(1_000L, first.words.first().startMs)
        assertTrue(first.words.last().endMs <= 5_000L)
    }

    @Test
    fun longInstrumentalGapDoesNotStretchEstimatedKaraoke() {
        val source = LyricsState(
            status = LyricsStatus.SYNCED,
            lines = listOf(
                LyricLine(timeMs = 2_000, text = "Short sung line", endMs = 32_000),
                LyricLine(timeMs = 32_000, text = "After the break"),
            ),
        )

        val first = source.withRepairedWordTimings(trackDurationMs = 40_000).lines.first()

        assertTrue(lyricWordsMatchLine(first.text, first.words))
        assertTrue(first.words.last().endMs <= 10_000L)
    }

    @Test
    fun completeExactProviderTimingIsPreserved() {
        val exactWords = listOf(
            LyricWord("Stay ", 1_000, 1_500),
            LyricWord("here", 1_500, 2_100),
        )
        val source = LyricsState(
            status = LyricsStatus.SYNCED,
            lines = listOf(
                LyricLine(
                    timeMs = 1_000,
                    text = "Stay here",
                    words = exactWords,
                    exactWordTiming = true,
                )
            ),
        )

        val line = source.withRepairedWordTimings(trackDurationMs = 5_000).lines.single()

        assertEquals(exactWords, line.words)
        assertTrue(line.exactWordTiming)
        assertEquals(2_100L, line.endMs)
    }

    @Test
    fun exactWordTimingOutranksPartialTiming() {
        val exact = LyricsState(
            status = LyricsStatus.SYNCED,
            lines = listOf(
                LyricLine(
                    timeMs = 1_000,
                    text = "One two",
                    words = listOf(
                        LyricWord("One ", 1_000, 1_400),
                        LyricWord("two", 1_400, 2_000),
                    ),
                    exactWordTiming = true,
                )
            ),
        )
        val partial = exact.copy(
            lines = listOf(
                exact.lines.single().copy(
                    words = listOf(LyricWord("One ", 1_000, 1_400)),
                    exactWordTiming = false,
                )
            )
        )

        assertTrue(exact.wordTimingQualityScore() > partial.wordTimingQualityScore())
    }

    @Test
    fun transfersDuetTurnsWithoutReplacingBetterWordTiming() {
        val selectedWords = listOf(LyricWord("Second singer", 10_300L, 11_500L))
        val selected = LyricsState(
            status = LyricsStatus.SYNCED,
            lines = listOf(
                LyricLine(timeMs = 5_250L, text = "First singer"),
                LyricLine(
                    timeMs = 10_300L,
                    text = "Second singer",
                    words = selectedWords,
                    exactWordTiming = true,
                ),
            ),
        )
        val appleReference = LyricsState(
            status = LyricsStatus.SYNCED,
            lines = listOf(
                LyricLine(timeMs = 5_000L, text = "First singer"),
                LyricLine(
                    timeMs = 10_000L,
                    text = "Second singer",
                    alignment = LyricAlignment.TRAILING,
                ),
            ),
        )

        val result = selected
            .withDuetAlignmentFrom(appleReference)
            .withRepairedWordTimings(trackDurationMs = 20_000L)

        assertEquals(LyricAlignment.LEADING, result.lines.first().alignment)
        assertEquals(LyricAlignment.TRAILING, result.lines.last().alignment)
        assertEquals(selectedWords, result.lines.last().words)
    }

    @Test
    fun malformedAllRightLyricsAreNormalizedToTheLeft() {
        val malformed = LyricsState(
            status = LyricsStatus.SYNCED,
            lines = listOf(
                LyricLine(
                    timeMs = 1_000L,
                    text = "First line",
                    alignment = LyricAlignment.TRAILING,
                ),
                LyricLine(
                    timeMs = 3_000L,
                    text = "Second line",
                    alignment = LyricAlignment.TRAILING,
                ),
            ),
        )

        val stable = malformed.withStableDuetAlignment()

        assertTrue(stable.lines.all { it.alignment == LyricAlignment.LEADING })
    }

    @Test
    fun anInvalidAllRightReferenceCannotCorruptAStableProvider() {
        val selected = LyricsState(
            status = LyricsStatus.SYNCED,
            lines = listOf(
                LyricLine(timeMs = 1_000L, text = "First line"),
                LyricLine(timeMs = 3_000L, text = "Second line"),
            ),
        )
        val malformedReference = selected.copy(
            lines = selected.lines.map { it.copy(alignment = LyricAlignment.TRAILING) },
        )

        val result = selected.withDuetAlignmentFrom(malformedReference)

        assertTrue(result.lines.all { it.alignment == LyricAlignment.LEADING })
    }
}
