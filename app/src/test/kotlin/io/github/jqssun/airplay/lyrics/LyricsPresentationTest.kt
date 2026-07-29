package io.github.jqssun.airplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsPresentationTest {

    @Test
    fun activeLineTracksTimelineIncludingIntro() {
        val lines = listOf(
            LyricLine(1_000, "First"),
            LyricLine(3_000, "Second"),
        )

        assertEquals(-1, activeLyricLineIndex(lines, 500))
        assertEquals(0, activeLyricLineIndex(lines, 2_999))
        assertEquals(1, activeLyricLineIndex(lines, 3_000))
    }

    @Test
    fun wordProgressIsContinuousAndClamped() {
        val word = LyricWord("music", 1_000, 2_000)

        assertEquals(0f, lyricWordProgress(word, 500))
        assertEquals(0.5f, lyricWordProgress(word, 1_500))
        assertEquals(1f, lyricWordProgress(word, 2_500))
    }

    @Test
    fun displayClockAbsorbsSmallAudioQueueJumpsWithoutMovingBackwards() {
        val next = smoothLyricDisplayPosition(
            previousMs = 10_000L,
            sampledMs = 9_920L,
            frameDeltaMs = 16L,
        )

        assertTrue(next > 10_000L)
        assertTrue(next < 10_016L)
    }

    @Test
    fun displayClockSnapsToARealSeek() {
        assertEquals(
            25_000L,
            smoothLyricDisplayPosition(
                previousMs = 10_000L,
                sampledMs = 25_000L,
                frameDeltaMs = 16L,
            ),
        )
    }

    @Test
    fun highlightedGraphemeGrowsThenReturnsToItsOriginalSize() {
        assertEquals(0f, lyricHighlightGlyphPulse(0f), 0.001f)
        assertEquals(1f, lyricHighlightGlyphPulse(0.5f), 0.001f)
        assertEquals(0f, lyricHighlightGlyphPulse(1f), 0.001f)
    }

    @Test
    fun liveLineBreathesWithoutChangingItsRestingLayout() {
        assertEquals(1f, lyricHighlightGlyphScale(0f, 1_200L), 0.001f)
        assertTrue(lyricHighlightGlyphScale(0.5f, 1_200L) > 1.04f)
        assertEquals(1f, lyricHighlightGlyphScale(1f, 1_200L), 0.001f)
        assertTrue(
            lyricHighlightGlyphScale(0.5f, 1_200L) >
                lyricHighlightGlyphScale(0.5f, 300L)
        )
        assertEquals(1.018f, lyricHighlightGlyphScale(0.5f, 300L), 0.001f)
        assertEquals(1.08f, lyricHighlightGlyphScale(0.5f, 2_000L), 0.001f)
    }

    @Test
    fun movingAuraDoesNotBlinkAtGraphemeBoundaries() {
        val boundary = lyricHighlightHeadAura(
            graphemeProgress = 0f,
            wordProgress = 0.5f,
            durationMs = 520L,
        )
        val crest = lyricHighlightHeadAura(
            graphemeProgress = 0.5f,
            wordProgress = 0.5f,
            durationMs = 520L,
        )

        assertTrue(boundary >= 0.57f)
        assertTrue(crest > boundary)
        assertEquals(0f, lyricHighlightHeadAura(0.5f, 0f, 520L), 0.001f)
        assertEquals(0f, lyricHighlightHeadAura(0.5f, 1f, 520L), 0.001f)
    }

    @Test
    fun highlightHeadNeverTargetsTheInvisibleWordSeparator() {
        val range = lyricHighlightGraphemeRange(
            wordText = "music ",
            wordStartOffset = 10,
            progress = 0.999f,
        )

        assertEquals(LyricHighlightGraphemeRange(14, 15), range)
    }

    @Test
    fun highlightHeadKeepsEmojiAndCombiningTextIntact() {
        val emoji = lyricHighlightGraphemeRange("A🎵 ", 0, 0.8f)
        val thai = lyricHighlightGraphemeRange("ก้ ", 5, 0.5f)

        assertEquals(LyricHighlightGraphemeRange(1, 3), emoji)
        assertEquals(LyricHighlightGraphemeRange(5, 7), thai)
    }

    @Test
    fun onlySustainedWordsReceiveTheWideAura() {
        assertEquals(0f, lyricSustainedWordAura(0.5f, 420L), 0.001f)
        assertTrue(lyricSustainedWordAura(0.5f, 1_200L) > 0.95f)
        assertEquals(0f, lyricSustainedWordAura(1f, 1_200L), 0.001f)
    }

    @Test
    fun progressivePrefixDoesNotSplitCombiningTextOrEmoji() {
        val thai = "ก้B"
        val emoji = "A🎵B"
        val thaiIndex = progressivePrefixIndex(thai, 0.45f)
        val emojiIndex = progressivePrefixIndex(emoji, 0.55f)

        assertEquals(thai, thai.substring(0, thaiIndex) + thai.substring(thaiIndex))
        assertEquals(2, thaiIndex)
        assertEquals(emoji, emoji.substring(0, emojiIndex) + emoji.substring(emojiIndex))
        assertTrue(emojiIndex !in 2..2)
    }

    @Test
    fun progressiveSliceProvidesSmoothBlendWithoutBreakingEmoji() {
        val text = "A🎵B"
        val slice = progressiveTextSlice(text, 0.5f)

        assertEquals(1, slice.solidEnd)
        assertEquals(3, slice.blendEnd)
        assertEquals(0.5f, slice.blendFraction)
        assertEquals(text, text.substring(0, slice.solidEnd) +
            text.substring(slice.solidEnd, slice.blendEnd) + text.substring(slice.blendEnd))
    }

    @Test
    fun progressiveSliceDoesNotSpendTimingOnTheInvisibleTrailingSpace() {
        val slice = progressiveTextSlice("go ", 0.999f)

        assertEquals(1, slice.solidEnd)
        assertEquals(2, slice.blendEnd)
        assertTrue(slice.blendEnd < "go ".length)
    }

    @Test
    fun graphemeRangesKeepEmojiFamiliesAndFlagsTogether() {
        val family = "👨‍👩‍👧‍👦"
        val flag = "🇹🇭"

        assertEquals(
            LyricHighlightGraphemeRange(0, family.length),
            lyricHighlightGraphemeRange("$family!", 0, 0.1f),
        )
        assertEquals(
            LyricHighlightGraphemeRange(0, flag.length),
            lyricHighlightGraphemeRange("${flag}X", 0, 0.1f),
        )
    }

    @Test
    fun incompleteWordTimelineFallsBackToTheCompleteSourceLine() {
        val complete = listOf(
            LyricWord("Don't ", 1_000, 1_300),
            LyricWord("stop", 1_300, 1_700),
        )
        val incomplete = listOf(LyricWord("Don't ", 1_000, 1_300))

        assertTrue(lyricWordsMatchLine("Don't stop", complete))
        assertTrue(!lyricWordsMatchLine("Don't stop", incomplete))
    }

    @Test
    fun wordMatchingToleratesTypographyOnlyPunctuationDifferences() {
        val words = listOf(
            LyricWord("Don't ", 1_000L, 1_400L),
            LyricWord("stop", 1_400L, 1_900L),
        )

        assertTrue(lyricWordsMatchLine("Don’t stop!", words))
    }

    @Test
    fun wordMatchingPreservesMeaningfulThaiAndIndicMarks() {
        assertTrue(
            lyricWordsMatchLine(
                "ฉันรักเธอ",
                listOf(LyricWord("ฉันรักเธอ", 1_000L, 2_000L)),
            )
        )
        assertTrue(
            !lyricWordsMatchLine(
                "กา",
                listOf(LyricWord("ก่า", 1_000L, 2_000L)),
            )
        )
    }

    @Test
    fun inactiveAndLiveRowsUseTheSameWordTimedLayoutString() {
        val words = listOf(
            LyricWord("Don’t ", 1_000L, 1_400L),
            LyricWord("stop!", 1_400L, 1_900L),
        )

        assertEquals(
            "Don’t stop!",
            wordTimedLyricLayoutText("Don't stop", words),
        )
        assertEquals(
            "Complete source text",
            wordTimedLyricLayoutText(
                text = "Complete source text",
                words = listOf(LyricWord("Incomplete", 1_000L, 1_500L)),
            ),
        )
    }

}
