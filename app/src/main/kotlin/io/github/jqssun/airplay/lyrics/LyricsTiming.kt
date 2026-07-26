package io.github.jqssun.airplay.lyrics

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

private const val MAX_ESTIMATED_WORDS_PER_LINE = 120
private const val MIN_ESTIMATED_LINE_MS = 1_200L
private const val MAX_ESTIMATED_LINE_MS = 8_000L
private val tokenWithTrailingWhitespace = Regex("\\S+\\s*")

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
 * Adds a complete word timeline to every line-synchronised lyric row.
 *
 * Precise provider timing is retained when it covers the complete visible
 * line. A missing or partial provider row receives a bounded estimate instead,
 * so one malformed verse cannot disable karaoke for the whole line.
 */
internal fun LyricsState.withRepairedWordTimings(trackDurationMs: Long): LyricsState {
    if (status != LyricsStatus.SYNCED || lines.isEmpty()) return this
    val sorted = lines.sortedBy { it.timeMs }
    val repaired = sorted.mapIndexed { index, line ->
        val completeExactTimeline = line.exactWordTiming &&
            hasUsableWordTimeline(line.text, line.words)
        if (completeExactTimeline) {
            val wordEnd = line.words.maxOfOrNull { it.endMs } ?: line.timeMs
            return@mapIndexed line.copy(
                endMs = maxOf(line.endMs ?: line.timeMs, wordEnd)
                    .takeIf { it > line.timeMs },
            )
        }

        val tokens = tokenizeLyricText(line.text)
        if (tokens.isEmpty()) return@mapIndexed line.copy(words = emptyList(), exactWordTiming = false)

        val nextStart = sorted.getOrNull(index + 1)?.timeMs?.takeIf { it > line.timeMs }
        val trackEnd = trackDurationMs.takeIf { it > line.timeMs }
        val hardBoundary = listOfNotNull(nextStart, trackEnd).minOrNull()
        val naturalDuration = estimatedVocalDurationMs(tokens)
        val partialWordEnd = line.words.maxOfOrNull { it.endMs }
            ?.takeIf { it > line.timeMs }
        // A provider duration is most useful when no partial word list was
        // supplied. Existing estimated LRC words often stretch to the next
        // verse, so cap those to a natural vocal window instead.
        val providerEnd = line.endMs
            ?.takeIf { line.words.isEmpty() && it > line.timeMs }
            ?.coerceAtMost(line.timeMs + MAX_ESTIMATED_LINE_MS)
        val desiredEnd = maxOf(
            line.timeMs + naturalDuration,
            partialWordEnd ?: line.timeMs,
            providerEnd ?: line.timeMs,
        )
        val boundedEnd = hardBoundary?.let { minOf(desiredEnd, it) } ?: desiredEnd
        val lineEnd = boundedEnd.coerceAtLeast(line.timeMs + 350L)

        line.copy(
            words = estimateLyricWords(line.text, line.timeMs, lineEnd),
            exactWordTiming = false,
            endMs = lineEnd,
        )
    }
    return copy(
        lines = repaired,
        plainLyrics = plainLyrics.ifBlank { repaired.joinToString("\n") { it.text } },
        wordSyncMode = if (repaired.any { it.exactWordTiming }) {
            WordSyncMode.ENHANCED
        } else {
            WordSyncMode.ESTIMATED
        },
    )
}

/**
 * Keeps the best available word timing while borrowing Apple-style vocalist
 * turns from another matched provider result. Providers often describe the
 * same line a few hundred milliseconds apart, so time proximity is used when
 * their normalized text is not byte-for-byte identical.
 */
internal fun LyricsState.withDuetAlignmentFrom(reference: LyricsState?): LyricsState {
    if (status != LyricsStatus.SYNCED || lines.isEmpty()) return this
    val stableSource = withStableDuetAlignment()
    if (stableSource.lines.any { it.alignment == LyricAlignment.TRAILING }) return stableSource
    val stableReference = reference
        ?.takeIf { it.status == LyricsStatus.SYNCED }
        ?.withStableDuetAlignment()
    val referenceLines = stableReference?.lines
        ?.takeIf { rows ->
            rows.any { it.alignment == LyricAlignment.LEADING } &&
                rows.any { it.alignment == LyricAlignment.TRAILING }
        }
        ?: return stableSource

    val aligned = stableSource.lines.map { line ->
        val normalizedText = normalizeDuetText(line.text)
        val best = referenceLines.asSequence()
            .map { candidate ->
                val timeDistance = abs(candidate.timeMs - line.timeMs)
                val candidateText = normalizeDuetText(candidate.text)
                val textScore = when {
                    normalizedText.isNotBlank() && normalizedText == candidateText -> 10_000
                    normalizedText.isNotBlank() && candidateText.isNotBlank() &&
                        (normalizedText.contains(candidateText) ||
                            candidateText.contains(normalizedText)) -> 5_000
                    else -> 0
                }
                candidate to (textScore - timeDistance.coerceAtMost(5_000L).toInt())
            }
            .filter { (candidate, score) ->
                val distance = abs(candidate.timeMs - line.timeMs)
                (distance <= DUET_TEXT_MATCH_WINDOW_MS && score > 0) ||
                    (distance <= DUET_TIME_MATCH_WINDOW_MS && score >= -DUET_TIME_MATCH_WINDOW_MS)
            }
            .maxByOrNull { it.second }
            ?.first

        best?.let { line.copy(alignment = it.alignment) } ?: line
    }
    return stableSource.copy(lines = aligned).withStableDuetAlignment()
}

/**
 * A valid duet must contain both sides. This final guard prevents malformed
 * provider responses from pushing an entire song to the right edge.
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

/** Higher scores indicate more complete provider-supplied word timing. */
internal fun LyricsState.wordTimingQualityScore(): Int {
    if (status != LyricsStatus.SYNCED || lines.isEmpty()) {
        return when (status) {
            LyricsStatus.PLAIN -> 100
            LyricsStatus.INSTRUMENTAL -> 90
            else -> 0
        }
    }
    val complete = lines.count { hasUsableWordTimeline(it.text, it.words) }
    val exact = lines.count {
        it.exactWordTiming && hasUsableWordTimeline(it.text, it.words)
    }
    val lineCount = lines.size.coerceAtLeast(1)
    return 1_000 +
        (complete * 500 / lineCount) +
        (exact * 1_000 / lineCount) +
        lineCount.coerceAtMost(200)
}

internal fun hasUsableWordTimeline(text: String, words: List<LyricWord>): Boolean {
    if (!lyricWordsMatchLine(text, words)) return false
    var previousStart = Long.MIN_VALUE
    return words.all { word ->
        val valid = word.startMs >= previousStart && word.endMs > word.startMs
        previousStart = word.startMs
        valid
    }
}

internal fun estimateLyricWords(
    text: String,
    startMs: Long,
    requestedEndMs: Long,
): List<LyricWord> {
    val tokens = tokenizeLyricText(text)
    if (tokens.isEmpty()) return emptyList()
    val endMs = requestedEndMs.coerceAtLeast(startMs + tokens.size * 80L)
    val weights = tokens.map { token ->
        val characterCount = token.codePointCount(0, token.length).coerceAtLeast(1)
        sqrt(characterCount.toDouble()) + if (token.any { it in ",.;:!?…" }) 0.35 else 0.0
    }
    val totalWeight = weights.sum().coerceAtLeast(1.0)
    val duration = endMs - startMs
    var cumulative = 0.0
    return tokens.mapIndexed { index, token ->
        val wordStart = startMs + (duration * (cumulative / totalWeight)).toLong()
        cumulative += weights[index]
        val wordEnd = if (index == tokens.lastIndex) {
            endMs
        } else {
            startMs + (duration * (cumulative / totalWeight)).toLong()
        }
        LyricWord(token, wordStart, wordEnd.coerceAtLeast(wordStart + 1L))
    }
}

internal fun tokenizeLyricText(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    if (text.any(Char::isWhitespace)) {
        return tokenWithTrailingWhitespace.findAll(text)
            .map { it.value }
            .take(MAX_ESTIMATED_WORDS_PER_LINE)
            .toList()
    }

    // ICU word boundaries provide a usable fallback for Thai, CJK, and other
    // scripts that do not separate every sung word with ASCII spaces.
    val iterator = BreakIterator.getWordInstance(Locale.getDefault())
    iterator.setText(text)
    val tokens = mutableListOf<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        val part = text.substring(start, end)
        if (part.any { it.isLetterOrDigit() }) {
            tokens += part
        } else if (tokens.isNotEmpty()) {
            tokens[tokens.lastIndex] += part
        } else if (part.isNotEmpty()) {
            tokens += part
        }
        if (tokens.size >= MAX_ESTIMATED_WORDS_PER_LINE) break
        start = end
        end = iterator.next()
    }
    return tokens.ifEmpty { listOf(text) }
}

private fun estimatedVocalDurationMs(tokens: List<String>): Long {
    val characters = tokens.sumOf { it.codePointCount(0, it.length) }
    return (tokens.size * 360L + characters * 24L)
        .coerceIn(MIN_ESTIMATED_LINE_MS, MAX_ESTIMATED_LINE_MS)
}

private fun normalizeDuetText(value: String): String = value
    .lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), "")

private fun List<LyricAlignment>.withStableDuetSides(): List<LyricAlignment> {
    if (none { it == LyricAlignment.TRAILING }) return this
    if (none { it == LyricAlignment.LEADING }) {
        return map { LyricAlignment.LEADING }
    }
    return this
}

private const val DUET_TIME_MATCH_WINDOW_MS = 700L
private const val DUET_TEXT_MATCH_WINDOW_MS = 4_000L
