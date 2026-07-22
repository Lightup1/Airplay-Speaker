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
    fun incompleteWordTimelineFallsBackToTheCompleteSourceLine() {
        val complete = listOf(
            LyricWord("Don't ", 1_000, 1_300),
            LyricWord("stop", 1_300, 1_700),
        )
        val incomplete = listOf(LyricWord("Don't ", 1_000, 1_300))

        assertTrue(lyricWordsMatchLine("Don't stop", complete))
        assertTrue(!lyricWordsMatchLine("Don't stop", incomplete))
    }
}
