package io.github.jqssun.airplay.lyrics

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

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
 * Advances the display clock monotonically while gently following the latest
 * audible-output sample. AudioTrack queue measurements arrive in small steps;
 * feeding those steps directly to karaoke makes the highlight hesitate and
 * jump. Real seeks and long missed frames still snap immediately.
 */
internal fun smoothLyricDisplayPosition(
    previousMs: Long,
    sampledMs: Long,
    frameDeltaMs: Long,
): Long {
    val sample = sampledMs.coerceAtLeast(0L)
    if (previousMs < 0L || frameDeltaMs > 120L) return sample
    val delta = frameDeltaMs.coerceIn(1L, 80L)
    val predicted = previousMs + delta
    val error = sample - predicted
    if (abs(error) >= 850L) return sample

    val maxCorrection = (delta * 0.45f).roundToLong().coerceAtLeast(2L)
    val correction = (error * 0.12f).roundToLong()
        .coerceIn(-maxCorrection, maxCorrection)
    // During playback a changing output-queue estimate must never pull the
    // visible lyric backwards. Slowing briefly is far less distracting.
    return (predicted + correction).coerceAtLeast(previousMs + 1L)
}

/** A single grapheme grows and returns to rest as the fill passes through it. */
internal fun lyricHighlightGlyphPulse(blendFraction: Float): Float {
    val progress = blendFraction.coerceIn(0f, 1f)
    return sin(progress * PI.toFloat()).coerceIn(0f, 1f)
}

/**
 * Keeps the moving karaoke head luminous between adjacent graphemes.
 *
 * A raw sine pulse reaches zero every time the playhead crosses a character
 * boundary. At display rate that reads as a tiny flicker. A restrained floor,
 * softened by word-level attack and release envelopes, lets the aura glide
 * continuously while still disappearing cleanly outside the active word.
 */
internal fun lyricHighlightHeadAura(
    graphemeProgress: Float,
    wordProgress: Float,
    durationMs: Long,
): Float {
    val word = wordProgress.coerceIn(0f, 1f)
    if (word <= 0f || word >= 1f) return 0f
    fun smoothStep(value: Float): Float {
        val clamped = value.coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }

    val attack = smoothStep(word / 0.09f)
    val release = smoothStep((1f - word) / 0.11f)
    val wordEnvelope = minOf(attack, release)
    val movingHead = 0.58f + lyricHighlightGlyphPulse(graphemeProgress) * 0.42f
    val sustained = lyricSustainedWordAura(word, durationMs) * 0.82f
    return maxOf(movingHead * wordEnvelope, sustained * wordEnvelope)
        .coerceIn(0f, 1f)
}

/**
 * A paint-only scale for the grapheme under the playhead. It never changes
 * text measurement, so the live row can breathe without shaking or
 * re-wrapping. Every sung grapheme receives a subtle lift, while long held
 * words can still reach the wider 14% breath used by the television design.
 */
internal fun lyricHighlightGlyphScale(
    progress: Float,
    durationMs: Long,
): Float {
    if (progress <= 0f || progress >= 1f) return 1f
    val durationWeight = ((durationMs - 360L).toFloat() / 1_640f).coerceIn(0f, 1f)
    // The lift must be visible from a ten-foot viewing distance without
    // making adjacent glyphs appear to jostle. Apple-style karaoke relies
    // more on luminance than geometry, so cap sustained notes at eight per
    // cent and keep quick syllables below two per cent.
    val peakGrowth = 0.018f + durationWeight * 0.062f
    return 1f + lyricHighlightGlyphPulse(progress) * peakGrowth
}

internal data class LyricHighlightGraphemeRange(
    val start: Int,
    val endExclusive: Int,
)

/**
 * Locates the visible grapheme beneath the karaoke playhead. Separators added
 * between Apple word tokens are intentionally excluded so the highlight
 * cannot jump backwards or glow on an invisible trailing space.
 */
internal fun lyricHighlightGraphemeRange(
    wordText: String,
    wordStartOffset: Int,
    progress: Float,
): LyricHighlightGraphemeRange? {
    if (wordText.isEmpty() || wordStartOffset < 0) return null
    val boundaries = graphemeBoundaries(wordText)
    val visibleClusters = visibleGraphemeClusterCount(wordText, boundaries)
    if (visibleClusters <= 0) return null
    val clusterIndex = floor(visibleClusters * progress.coerceIn(0f, 0.999_999f))
        .toInt()
        .coerceIn(0, visibleClusters - 1)
    return LyricHighlightGraphemeRange(
        start = wordStartOffset + boundaries[clusterIndex],
        endExclusive = wordStartOffset + boundaries[clusterIndex + 1],
    )
}

/**
 * Sustained words receive a soft, broad aura. Short syllables intentionally
 * return zero so quick lyrics remain clean instead of flashing constantly.
 */
internal fun lyricSustainedWordAura(
    progress: Float,
    durationMs: Long,
): Float {
    if (durationMs < 760L || progress <= 0f || progress >= 1f) return 0f
    val envelope = sin(progress.coerceIn(0f, 1f) * PI.toFloat()).coerceAtLeast(0f)
    return sqrt(envelope)
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

/**
 * Uses one immutable string for both inactive and live word-timed rows.
 * Switching between provider tokens and source text at line onset can change
 * wrapping by a few pixels and make the whole lyric rail appear to shake.
 */
internal fun wordTimedLyricLayoutText(
    text: String,
    words: List<LyricWord>,
): String = words
    .takeIf { lyricWordsMatchLine(text, it) }
    ?.joinToString(separator = "") { it.text }
    ?: text

private fun normalizeLyricText(value: String): String {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
    val searchable = normalized.filter { character ->
        character.isLetterOrDigit() || when (Character.getType(character)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }
    }
    return searchable.ifBlank { normalized.filterNot(Char::isWhitespace) }
}

/**
 * Returns a grapheme-safe prefix boundary for progressive karaoke fill.
 * Character BreakIterator avoids cutting surrogate pairs and combining marks,
 * which matters for Thai, emoji and other non-Latin lyrics.
 */
internal fun progressivePrefixIndex(text: String, progress: Float): Int {
    if (text.isEmpty() || progress <= 0f) return 0
    if (progress >= 1f) return text.length

    val boundaries = graphemeBoundaries(text)
    val clusterCount = visibleGraphemeClusterCount(text, boundaries)
    if (clusterCount <= 0) return 0
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
    val clusterCount = visibleGraphemeClusterCount(text, boundaries)
    if (clusterCount <= 0) return ProgressiveTextSlice(0, 0, 0f)
    val exact = clusterCount * progress.coerceIn(0f, 1f)
    val solidClusters = floor(exact).toInt().coerceIn(0, clusterCount)
    val blendClusters = (solidClusters + 1).coerceAtMost(clusterCount)
    return ProgressiveTextSlice(
        solidEnd = boundaries.getOrElse(solidClusters) { text.length },
        blendEnd = boundaries.getOrElse(blendClusters) { text.length },
        blendFraction = (exact - solidClusters).coerceIn(0f, 1f),
    )
}

private fun visibleGraphemeClusterCount(
    text: String,
    boundaries: IntArray,
): Int {
    var visibleClusters = (boundaries.size - 1).coerceAtLeast(0)
    while (visibleClusters > 0) {
        val start = boundaries[visibleClusters - 1]
        val end = boundaries[visibleClusters]
        var hasVisibleCharacter = false
        for (index in start until end) {
            if (!text[index].isWhitespace()) {
                hasVisibleCharacter = true
                break
            }
        }
        if (hasVisibleCharacter) break
        visibleClusters--
    }
    return visibleClusters
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
        if (!isUnsafeGraphemeBoundary(text, boundary)) {
            if (size == boundaries.size) boundaries = boundaries.copyOf(boundaries.size * 2)
            boundaries[size++] = boundary
        }
        boundary = iterator.next()
    }
    if (boundaries[size - 1] != text.length) {
        if (size == boundaries.size) boundaries = boundaries.copyOf(boundaries.size * 2)
        boundaries[size++] = text.length
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

/**
 * java.text.BreakIterator implementations differ across Android releases.
 * Filter the boundaries that would split modern emoji/variation sequences so
 * the same lyric paints correctly on both API 24 and current televisions.
 */
private fun isUnsafeGraphemeBoundary(text: String, index: Int): Boolean {
    if (index <= 0 || index >= text.length) return false
    val before = text.codePointBefore(index)
    val after = text.codePointAt(index)
    if (before == ZERO_WIDTH_JOINER || after == ZERO_WIDTH_JOINER) return true
    if (after.isUnicodeGraphemeExtension()) return true
    if (before == '\r'.code && after == '\n'.code) return true
    if (before.isRegionalIndicator() && after.isRegionalIndicator()) {
        var regionalCount = 0
        var cursor = index
        while (cursor > 0) {
            val codePoint = text.codePointBefore(cursor)
            if (!codePoint.isRegionalIndicator()) break
            regionalCount++
            cursor -= Character.charCount(codePoint)
        }
        return regionalCount % 2 == 1
    }
    return false
}

private fun Int.isUnicodeGraphemeExtension(): Boolean {
    val category = Character.getType(this)
    return category == Character.NON_SPACING_MARK.toInt() ||
        category == Character.COMBINING_SPACING_MARK.toInt() ||
        category == Character.ENCLOSING_MARK.toInt() ||
        this in 0xFE00..0xFE0F ||
        this in 0xE0100..0xE01EF ||
        this in 0x1F3FB..0x1F3FF ||
        this in 0xE0020..0xE007F
}

private fun Int.isRegionalIndicator(): Boolean = this in 0x1F1E6..0x1F1FF

private const val ZERO_WIDTH_JOINER = 0x200D
