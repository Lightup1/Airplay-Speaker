package io.github.jqssun.airplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTimingTest {

    @Test
    fun incompleteAppleWordsFallBackToLineSync() {
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

        assertTrue(first.words.isEmpty())
        assertFalse(first.exactWordTiming)
        assertEquals(4_000L, first.endMs)
        assertEquals(WordSyncMode.NONE, repaired.wordSyncMode)
    }

    @Test
    fun appleOnlyPolicyKeepsMissingWordTimingAtLineLevel() {
        val source = LyricsState(
            status = LyricsStatus.SYNCED,
            lines = listOf(
                LyricLine(timeMs = 1_000L, text = "Whole active line", endMs = 3_000L),
                LyricLine(timeMs = 4_000L, text = "Next line", endMs = 6_000L),
            ),
        )

        val repaired = source.withRepairedWordTimings(trackDurationMs = 8_000L)

        assertTrue(repaired.lines.all { it.words.isEmpty() })
        assertTrue(repaired.lines.none { it.exactWordTiming })
        assertEquals(WordSyncMode.NONE, repaired.wordSyncMode)
    }

    @Test
    fun preciseAppleBackingVocalIsKeptWhenTheLargeLineIsUntimed() {
        val source = LyricsState(
            status = LyricsStatus.SYNCED,
            lines = listOf(
                LyricLine(
                    timeMs = 1_000L,
                    text = "Large line",
                    backgroundText = "small voice",
                    backgroundWords = listOf(
                        LyricWord("small ", 1_500L, 2_000L),
                        LyricWord("voice", 2_000L, 2_500L),
                    ),
                    exactWordTiming = true,
                    endMs = 3_000L,
                ),
            ),
        )

        val repaired = source.withRepairedWordTimings(trackDurationMs = 5_000L)
        val line = repaired.lines.single()

        assertTrue(line.exactWordTiming)
        assertTrue(line.words.isEmpty())
        assertEquals(2, line.backgroundWords.size)
        assertEquals(1_000L, line.timeMs)
    }

    @Test
    fun overlappingMainAndBackingVocalsKeepIndependentWordTimelines() {
        val source = LyricsState(
            status = LyricsStatus.SYNCED,
            lines = listOf(
                LyricLine(
                    timeMs = 1_000L,
                    text = "Hold on",
                    words = listOf(
                        LyricWord("Hold ", 1_000L, 1_600L),
                        LyricWord("on", 1_600L, 2_400L),
                    ),
                    exactWordTiming = true,
                    backgroundText = "stay close",
                    backgroundWords = listOf(
                        // This stream overlaps the main stream and therefore
                        // cannot be appended to one globally ordered word list.
                        LyricWord("stay ", 1_300L, 1_900L),
                        LyricWord("close", 1_900L, 2_500L),
                    ),
                    endMs = 2_500L,
                ),
            ),
        )

        val line = source.withRepairedWordTimings(trackDurationMs = 5_000L).lines.single()

        assertTrue(line.exactWordTiming)
        assertEquals(2, line.words.size)
        assertEquals(2, line.backgroundWords.size)
        assertEquals(1_000L, line.words.first().startMs)
        assertEquals(1_300L, line.backgroundWords.first().startMs)
    }

    @Test
    fun longInstrumentalGapDoesNotStretchLineHighlight() {
        val source = LyricsState(
            status = LyricsStatus.SYNCED,
            lines = listOf(
                LyricLine(timeMs = 2_000, text = "Short sung line", endMs = 32_000),
                LyricLine(timeMs = 32_000, text = "After the break"),
            ),
        )

        val first = source.withRepairedWordTimings(trackDurationMs = 40_000).lines.first()

        assertTrue(first.words.isEmpty())
        assertTrue(first.endMs!! <= 10_000L)
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
    fun placeholderWordEndsAreRepairedWithoutLosingProviderOnsets() {
        val source = LyricsState(
            status = LyricsStatus.SYNCED,
            lines = listOf(
                LyricLine(
                    timeMs = 900L,
                    text = "Stay here",
                    words = listOf(
                        LyricWord("Stay ", 1_000L, 1_001L),
                        LyricWord("here", 1_500L, 1_501L),
                    ),
                    exactWordTiming = true,
                    endMs = 2_200L,
                )
            ),
        )

        val line = source.withRepairedWordTimings(trackDurationMs = 5_000L).lines.single()

        assertEquals(1_000L, line.timeMs)
        assertEquals(1_000L, line.words.first().startMs)
        assertEquals(1_500L, line.words.first().endMs)
        assertEquals(1_500L, line.words.last().startMs)
        assertTrue(line.words.last().endMs >= 2_100L)
        assertTrue(line.exactWordTiming)
    }

    @Test
    fun exactWordsNeverRunIntoTheFollowingLyric() {
        val source = LyricsState(
            status = LyricsStatus.SYNCED,
            lines = listOf(
                LyricLine(
                    timeMs = 1_000L,
                    text = "Hold on",
                    words = listOf(
                        LyricWord("Hold ", 1_000L, 2_400L),
                        LyricWord("on", 2_000L, 8_000L),
                    ),
                    exactWordTiming = true,
                    endMs = 8_000L,
                ),
                LyricLine(timeMs = 5_000L, text = "Next line"),
            ),
        )

        val first = source.withRepairedWordTimings(trackDurationMs = 10_000L).lines.first()

        assertTrue(first.exactWordTiming)
        assertEquals(2_000L, first.words.first().endMs)
        assertEquals(5_000L, first.words.last().endMs)
        assertEquals(5_000L, first.endMs)
    }

    @Test
    fun impossibleProviderOnsetFallsBackWithoutDroppingTheText() {
        val source = LyricsState(
            status = LyricsStatus.SYNCED,
            lines = listOf(
                LyricLine(
                    timeMs = 1_000L,
                    text = "Still visible",
                    words = listOf(
                        LyricWord("Still ", 1_000L, 1_500L),
                        LyricWord("visible", 5_100L, 5_600L),
                    ),
                    exactWordTiming = true,
                ),
                LyricLine(timeMs = 5_000L, text = "Next line"),
            ),
        )

        val first = source.withRepairedWordTimings(trackDurationMs = 10_000L).lines.first()

        assertFalse(first.exactWordTiming)
        assertTrue(first.words.isEmpty())
        assertTrue(first.endMs!! <= 5_000L)
    }

    @Test
    fun duplicateProviderRowsCannotFlashAndHideALyricSection() {
        val source = LyricsState(
            status = LyricsStatus.SYNCED,
            lines = listOf(
                LyricLine(1_000L, "Keep this line"),
                LyricLine(
                    1_080L,
                    "Keep this line",
                    words = listOf(LyricWord("Keep this line", 1_080L, 2_200L)),
                    exactWordTiming = true,
                    endMs = 2_200L,
                ),
                LyricLine(3_000L, "Then this line"),
            ),
        )

        val repaired = source.withRepairedWordTimings(6_000L)

        assertEquals(2, repaired.lines.size)
        assertTrue(repaired.lines.first().exactWordTiming)
        assertEquals("Then this line", repaired.lines.last().text)
    }

    @Test
    fun invalidProviderEndDoesNotEraseTheVisibleText() {
        val repaired = LyricsState(
            status = LyricsStatus.SYNCED,
            lines = listOf(
                LyricLine(
                    timeMs = 1_000L,
                    text = "Still here",
                    endMs = 500L,
                ),
            ),
        ).withRepairedWordTimings(5_000L)

        assertEquals("Still here", repaired.lines.single().text)
        assertTrue(repaired.lines.single().endMs!! > 1_000L)
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

}
