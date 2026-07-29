package io.github.jqssun.airplay.lyrics

import java.util.Locale
import kotlin.math.abs

private const val MAX_LINE_DISPLAY_MS = 8_000L
private const val MIN_MEANINGFUL_WORD_MS = 24L
private val duetTextSeparatorPattern = Regex("[^\\p{L}\\p{N}]+")

/**
 * Raw vocalist metadata supplied alongside a lyric row. Some providers label
 * every row as `v2`, so an agent name is only meaningful when at least two
 * different agents occur in the same response.
 */
internal data class ProviderVocalTurn(
    val explicitOpposite: Boolean?,
    val agent: String,
)

internal fun resolveProviderDuetAlignments(
    turns: List<ProviderVocalTurn>,
): List<LyricAlignment> {
    if (turns.isEmpty()) return emptyList()
    val normalizedAgents = turns.map { turn ->
        turn.agent.trim().lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
    }
    val distinctAgents = normalizedAgents.filterNotNull().distinct()
    val primaryAgent = distinctAgents.firstOrNull()
    val proposed = turns.mapIndexed { index, turn ->
        when {
            turn.explicitOpposite != null -> {
                if (turn.explicitOpposite) LyricAlignment.TRAILING else LyricAlignment.LEADING
            }
            distinctAgents.size >= 2 && normalizedAgents[index] != null -> {
                if (normalizedAgents[index] == primaryAgent) {
                    LyricAlignment.LEADING
                } else {
                    LyricAlignment.TRAILING
                }
            }
            else -> LyricAlignment.LEADING
        }
    }
    return proposed.withStableDuetSides()
}

/**
 * Repairs precise Apple word timing while preserving line-only lyrics.
 *
 * Precise timing is retained when it covers the complete visible line. Missing
 * or malformed word data is cleared so the UI highlights the complete line
 * instead of fabricating karaoke timing.
 */
internal fun LyricsState.withRepairedWordTimings(trackDurationMs: Long): LyricsState {
    if (status != LyricsStatus.SYNCED || lines.isEmpty()) return this
    val sorted = stableTimedLyricRows(lines, trackDurationMs)
    if (sorted.isEmpty()) {
        return copy(
            status = plainLyrics.takeIf { it.isNotBlank() }
                ?.let { LyricsStatus.PLAIN }
                ?: LyricsStatus.NOT_FOUND,
            lines = emptyList(),
            wordSyncMode = WordSyncMode.NONE,
        )
    }
    val repaired = sorted.mapIndexed { index, line ->
        val nextStart = sorted.getOrNull(index + 1)?.timeMs?.takeIf { it > line.timeMs }
        val trackEnd = trackDurationMs.takeIf { it > line.timeMs }
        val hardBoundary = listOfNotNull(nextStart, trackEnd).minOrNull()
        val minimumEnd = hardBoundary?.let {
            minOf(line.timeMs + 350L, it)
        } ?: (line.timeMs + 350L)
        val publishedLineEnd = line.endMs?.takeIf { it > line.timeMs }
        val inferredLineEnd = listOfNotNull(publishedLineEnd, nextStart, trackEnd)
            .minOrNull()
            ?: (line.timeMs + MAX_LINE_DISPLAY_MS)
        val lineEnd = minOf(inferredLineEnd, line.timeMs + MAX_LINE_DISPLAY_MS)
            .let { end -> hardBoundary?.let { minOf(end, it) } ?: end }
            .coerceAtLeast(minimumEnd)
        val meaningfulProviderEnd = (line.words + line.backgroundWords)
            .filter { it.endMs - it.startMs >= MIN_MEANINGFUL_WORD_MS }
            .maxOfOrNull { it.endMs }
        val unboundedProviderLineEnd = listOfNotNull(
            line.endMs?.takeIf { it > line.timeMs },
            meaningfulProviderEnd,
        ).maxOrNull() ?: lineEnd
        val providerLineEnd = minOf(
            unboundedProviderLineEnd,
            line.timeMs + MAX_LINE_DISPLAY_MS,
        ).let { end ->
            hardBoundary?.let { minOf(end, it) } ?: end
        }

        // Main and backing vocals are deliberately repaired independently.
        // Their timestamps may overlap, so concatenating both streams and
        // requiring one monotonically ordered list drops otherwise valid Apple
        // timing from both visible parts.
        val repairedMain = repairProviderWordStream(
            text = line.text,
            words = line.words.takeIf { line.exactWordTiming }.orEmpty(),
            providerLineEnd = providerLineEnd,
            hardBoundary = hardBoundary,
        )
        val repairedBackground = repairProviderWordStream(
            text = line.backgroundText,
            words = line.backgroundWords.takeIf { line.exactWordTiming }.orEmpty(),
            providerLineEnd = providerLineEnd,
            hardBoundary = hardBoundary,
        )
        val hasMainTiming = repairedMain.isNotEmpty()
        val hasBackgroundTiming = repairedBackground.isNotEmpty()
        val alignedLineStart = if (hasMainTiming) {
            repairedMain.first().startMs.takeIf {
                abs(it - line.timeMs) <= MAX_PROVIDER_LINE_ONSET_SHIFT_MS
            } ?: line.timeMs
        } else {
            // A backing vocal can start later than an untimed main vocal. It
            // must never pull the large lyric forward in time.
            line.timeMs
        }
        val repairedEnd = if (hasMainTiming || hasBackgroundTiming) {
            listOfNotNull(
                providerLineEnd,
                repairedMain.lastOrNull()?.endMs,
                repairedBackground.lastOrNull()?.endMs,
            ).maxOrNull() ?: lineEnd
        } else {
            lineEnd
        }.let { end ->
            hardBoundary?.let { minOf(end, it) } ?: end
        }
        line.copy(
            timeMs = alignedLineStart,
            words = repairedMain,
            backgroundWords = repairedBackground,
            exactWordTiming = hasMainTiming || hasBackgroundTiming,
            asideWordRange = null,
            endMs = repairedEnd.coerceAtLeast(minimumEnd),
        )
    }
    return copy(
        lines = repaired.sortedBy { it.timeMs },
        plainLyrics = plainLyrics.ifBlank {
            repaired.joinToString("\n", transform = LyricLine::combinedDisplayText)
        },
        wordSyncMode = if (repaired.any { it.exactWordTiming }) {
            WordSyncMode.ENHANCED
        } else {
            WordSyncMode.LINE
        },
    )
}

/**
 * Repairs one vocalist stream without observing any concurrent stream.
 * Apple timing commonly uses one-millisecond placeholder ends; trustworthy
 * onsets are retained and only the missing end is reconstructed.
 */
private fun repairProviderWordStream(
    text: String,
    words: List<LyricWord>,
    providerLineEnd: Long,
    hardBoundary: Long?,
): List<LyricWord> {
    if (
        text.isBlank() ||
        words.isEmpty() ||
        !lyricWordsMatchLine(text, words) ||
        !hasOrderedWordStarts(words) ||
        words.any { hardBoundary != null && it.startMs >= hardBoundary }
    ) {
        return emptyList()
    }
    var valid = true
    val repaired = words.mapIndexed { index, word ->
        val followingStart = words.getOrNull(index + 1)?.startMs
        val suppliedDuration = word.endMs - word.startMs
        val requestedEnd = when {
            suppliedDuration >= MIN_MEANINGFUL_WORD_MS &&
                (followingStart == null || word.endMs <= followingStart + 80L) -> word.endMs
            followingStart != null -> followingStart
            else -> providerLineEnd
        }
        val wordBoundary = listOfNotNull(followingStart, hardBoundary).minOrNull()
        val boundedEnd = wordBoundary?.let { minOf(requestedEnd, it) } ?: requestedEnd
        if (boundedEnd <= word.startMs) valid = false
        word.copy(endMs = boundedEnd.coerceAtLeast(word.startMs + 1L))
    }
    return repaired.takeIf { valid && hasUsablePartialWordTimeline(it) }.orEmpty()
}

internal fun LyricLine.combinedDisplayText(): String = when {
    text.isNotBlank() && backgroundText.isNotBlank() -> "$text ($backgroundText)"
    text.isNotBlank() -> text
    backgroundText.isNotBlank() -> "($backgroundText)"
    else -> ""
}

/**
 * Removes malformed and near-identical provider rows before the live rail is
 * built. Duplicate timestamps otherwise cause one row to become active for
 * only a single frame, which looks like a missing or stuck lyric section.
 */
private fun stableTimedLyricRows(
    source: List<LyricLine>,
    trackDurationMs: Long,
): List<LyricLine> {
    val result = mutableListOf<LyricLine>()
    source.asSequence()
        .filter { line ->
            line.combinedDisplayText().isNotBlank() &&
                line.timeMs >= 0L &&
                (trackDurationMs <= 0L || line.timeMs <= trackDurationMs + 2_000L)
        }
        .sortedBy { it.timeMs }
        .forEach { rawLine ->
            val line = rawLine.copy(
                endMs = rawLine.endMs?.takeIf { it > rawLine.timeMs },
            )
            val normalized = normalizeDuetText(line.combinedDisplayText())
            val duplicateIndex = result.indices.reversed().firstOrNull { index ->
                val existing = result[index]
                line.timeMs - existing.timeMs <= DUPLICATE_LYRIC_WINDOW_MS &&
                    normalizeDuetText(existing.combinedDisplayText()) == normalized
            }
            if (duplicateIndex == null) {
                result += line
            } else if (lyricRowQuality(line) > lyricRowQuality(result[duplicateIndex])) {
                result[duplicateIndex] = line
            }
        }
    return result
}

private fun lyricRowQuality(line: LyricLine): Int =
    (if (line.exactWordTiming) 1_000 else 0) +
        (line.words.size + line.backgroundWords.size) * 4 +
        (if (line.endMs != null) 20 else 0)

private fun hasOrderedWordStarts(words: List<LyricWord>): Boolean {
    if (words.isEmpty()) return false
    var previous = Long.MIN_VALUE
    return words.all { word ->
        val ordered = word.startMs >= 0L && word.startMs >= previous
        previous = word.startMs
        ordered
    }
}

/**
 * A valid duet must contain both sides. This final guard prevents malformed
 * Apple metadata from pushing an entire song to the right edge.
 */
internal fun LyricsState.withStableDuetAlignment(): LyricsState {
    if (status != LyricsStatus.SYNCED || lines.isEmpty()) return this
    val alignments = lines.map { it.alignment }.withStableDuetSides()
    if (lines.indices.all { lines[it].alignment == alignments[it] }) return this
    return copy(
        lines = lines.mapIndexed { index, line ->
            line.copy(alignment = alignments[index])
        }
    )
}

/** Higher scores indicate more complete Apple-supplied word timing. */
internal fun LyricsState.wordTimingQualityScore(trackDurationMs: Long = 0L): Int {
    if (status != LyricsStatus.SYNCED || lines.isEmpty()) {
        return when (status) {
            LyricsStatus.PLAIN -> 100
            LyricsStatus.INSTRUMENTAL -> 90
            else -> 0
        }
    }
    val usableTimelines = lines.map { line ->
        hasUsableWordTimeline(line.text, line.words) ||
            hasUsableWordTimeline(line.backgroundText, line.backgroundWords)
    }
    val complete = usableTimelines.count { it }
    val exact = lines.indices.count { index ->
        lines[index].exactWordTiming && usableTimelines[index]
    }
    val recoverableOnsets = lines.count {
        it.exactWordTiming &&
            (
                lyricWordsMatchLine(it.text, it.words) &&
                    hasOrderedWordStarts(it.words) ||
                    lyricWordsMatchLine(it.backgroundText, it.backgroundWords) &&
                    hasOrderedWordStarts(it.backgroundWords)
                )
    }
    val invalidRows = lines.count { line ->
        line.timeMs < 0L ||
            (trackDurationMs > 0L && line.timeMs > trackDurationMs + 2_000L)
    }
    val duplicateRows = lines.groupingBy {
        it.timeMs to normalizeDuetText(it.combinedDisplayText())
    }.eachCount().values.sumOf { (it - 1).coerceAtLeast(0) }
    val lineCount = lines.size.coerceAtLeast(1)
    return 1_000 +
        (complete * 500 / lineCount) +
        (exact * 1_000 / lineCount) +
        (recoverableOnsets * 300 / lineCount) +
        lineCount.coerceAtMost(200) -
        invalidRows * 180 -
        duplicateRows * 40
}

internal fun hasUsableWordTimeline(text: String, words: List<LyricWord>): Boolean {
    if (!lyricWordsMatchLine(text, words)) return false
    return hasUsablePartialWordTimeline(words)
}

private fun hasUsablePartialWordTimeline(words: List<LyricWord>): Boolean {
    if (words.isEmpty()) return false
    var previousStart = Long.MIN_VALUE
    val ordered = words.all { word ->
        val valid = word.startMs >= previousStart && word.endMs > word.startMs
        previousStart = word.startMs
        valid
    }
    if (!ordered) return false
    val meaningful = words.count { it.endMs - it.startMs >= MIN_MEANINGFUL_WORD_MS }
    return meaningful >= (words.size * 2 + 2) / 3
}

private fun normalizeDuetText(value: String): String = value
    .lowercase(Locale.ROOT)
    .replace(duetTextSeparatorPattern, "")

private fun List<LyricAlignment>.withStableDuetSides(): List<LyricAlignment> {
    if (none { it == LyricAlignment.TRAILING }) return this
    if (none { it == LyricAlignment.LEADING }) {
        return map { LyricAlignment.LEADING }
    }
    return this
}

private const val MAX_PROVIDER_LINE_ONSET_SHIFT_MS = 1_500L
private const val DUPLICATE_LYRIC_WINDOW_MS = 220L
