package io.github.jqssun.airplay.lyrics

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class ProgressiveTextSlice(
    val solidEnd: Int,
    val blendEnd: Int,
    val blendFraction: Float,
)

/*
 * A lyric frame is redrawn at display rate. Building a BreakIterator (and a
 * boxed List of every grapheme boundary) for every word on every frame creates
 * avoidable GC pressure on entry-level TV hardware. Lyrics normally reuse the
 * same short word for hundreds of frames, so keep a deliberately tiny LRU of
 * primitive boundary arrays instead.
 */
private const val GRAPHEME_CACHE_ENTRIES = 512
private const val CACHEABLE_GRAPHEME_TEXT_LENGTH = 160
private val graphemeBoundaryCache = object : LinkedHashMap<String, IntArray>(
    GRAPHEME_CACHE_ENTRIES,
    0.75f,
    true,
) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?): Boolean =
        size > GRAPHEME_CACHE_ENTRIES
}

/** Pure timeline helpers shared by the TV renderer and its unit tests. */
internal fun activeLyricLineIndex(lines: List<LyricLine>, positionMs: Long): Int =
    lines.indexOfLast { it.timeMs <= positionMs }

internal fun lyricWordProgress(word: LyricWord, positionMs: Long): Float {
    if (positionMs <= word.startMs) return 0f
    if (positionMs >= word.endMs) return 1f
    val duration = (word.endMs - word.startMs).coerceAtLeast(1L)
    return ((positionMs - word.startMs).toFloat() / duration).coerceIn(0f, 1f)
}

/**
 * Word-timed providers occasionally omit an ad-lib, parenthetical or a whole
 * fragment of the displayed line. Rendering those tokens directly would make
 * the missing text disappear from the TV. Only use a word timeline when it
 * represents the complete visible line; otherwise retain the source text.
 */
internal fun lyricWordsMatchLine(text: String, words: List<LyricWord>): Boolean {
    if (text.isBlank() || words.isEmpty()) return false
    return normalizeLyricText(text) == normalizeLyricText(words.joinToString(separator = "") { it.text })
}

private fun normalizeLyricText(value: String): String = value
    .filterNot(Char::isWhitespace)
    .lowercase(Locale.ROOT)

/**
 * Returns a grapheme-safe prefix boundary for progressive karaoke fill.
 * Character BreakIterator avoids cutting surrogate pairs and combining marks,
 * which matters for Thai, emoji and other non-Latin lyrics.
 */
internal fun progressivePrefixIndex(text: String, progress: Float): Int {
    if (text.isEmpty() || progress <= 0f) return 0
    if (progress >= 1f) return text.length

    val boundaries = graphemeBoundaries(text)
    val clusterCount = (boundaries.size - 1).coerceAtLeast(1)
    val completed = (clusterCount * progress).roundToInt().coerceIn(0, clusterCount)
    return boundaries.getOrElse(completed) { text.length }
}

/**
 * Splits text into fully highlighted, currently blending, and upcoming spans.
 * The blending fraction lets the UI animate at display-frame speed even while
 * keeping every string boundary safe for Thai marks and emoji.
 */
internal fun progressiveTextSlice(text: String, progress: Float): ProgressiveTextSlice {
    if (text.isEmpty() || progress <= 0f) return ProgressiveTextSlice(0, 0, 0f)
    if (progress >= 1f) return ProgressiveTextSlice(text.length, text.length, 0f)

    val boundaries = graphemeBoundaries(text)
    val clusterCount = (boundaries.size - 1).coerceAtLeast(1)
    val exact = clusterCount * progress.coerceIn(0f, 1f)
    val solidClusters = floor(exact).toInt().coerceIn(0, clusterCount)
    val blendClusters = (solidClusters + 1).coerceAtMost(clusterCount)
    return ProgressiveTextSlice(
        solidEnd = boundaries.getOrElse(solidClusters) { text.length },
        blendEnd = boundaries.getOrElse(blendClusters) { text.length },
        blendFraction = (exact - solidClusters).coerceIn(0f, 1f),
    )
}

private fun graphemeBoundaries(text: String): IntArray {
    if (text.length <= CACHEABLE_GRAPHEME_TEXT_LENGTH) {
        synchronized(graphemeBoundaryCache) {
            graphemeBoundaryCache[text]?.let { return it }
        }
    }

    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    // A word almost always has only a few graphemes. Starting small avoids the
    // boxed MutableList allocations that otherwise occur once per display frame.
    var boundaries = IntArray(minOf(text.length + 1, 16).coerceAtLeast(2))
    var size = 1
    boundaries[0] = 0
    var boundary = iterator.next()
    while (boundary != BreakIterator.DONE) {
        if (size == boundaries.size) boundaries = boundaries.copyOf(boundaries.size * 2)
        boundaries[size++] = boundary
        boundary = iterator.next()
    }
    val result = boundaries.copyOf(size)
    if (text.length <= CACHEABLE_GRAPHEME_TEXT_LENGTH) {
        synchronized(graphemeBoundaryCache) {
            // Preserve the first result if another compositor pass happened to
            // calculate the same boundaries while this one was being built.
            return graphemeBoundaryCache.getOrPut(text) { result }
        }
    }
    return result
}
