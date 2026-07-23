package io.github.jqssun.airplay.lyrics

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Upper bound for a parsed LRC payload; shared by the parser and network guard. */
internal const val MAX_LRC_CHARS = 192 * 1024

data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

enum class LyricAlignment {
    LEADING,
    TRAILING,
}

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val exactWordTiming: Boolean = false,
    /** Optional source-provided end time for a line-synchronised lyric. */
    val endMs: Long? = null,
    /** Vocal turns from a second singer use the opposite edge in the TV UI. */
    val alignment: LyricAlignment = LyricAlignment.LEADING,
)

enum class WordSyncMode {
    NONE,
    ENHANCED,
    ESTIMATED,
}

enum class LyricsStatus {
    IDLE,
    LOADING,
    SYNCED,
    PLAIN,
    INSTRUMENTAL,
    NOT_FOUND,
    ERROR,
}

data class LyricsState(
    val status: LyricsStatus = LyricsStatus.IDLE,
    val lines: List<LyricLine> = emptyList(),
    val plainLyrics: String = "",
    val sourceId: Long? = null,
    val wordSyncMode: WordSyncMode = WordSyncMode.NONE,
)

data class LyricsRequest(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

/** Minimal LRCLIB client. The public API requires no account or API key. */
class LrclibClient {

    suspend fun find(request: LyricsRequest): LyricsState = withContext(Dispatchers.IO) {
        try {
            val exact = if (request.album.isNotBlank() && request.durationMs > 0) {
                requestJsonObject(
                    "$BASE_URL/api/get" + query(
                        "track_name" to request.title,
                        "artist_name" to request.artist,
                        "album_name" to request.album,
                        "duration" to ((request.durationMs + 500) / 1000).toString(),
                    )
                )
            } else {
                null
            }
            exact?.toLyricsState(request.durationMs)
                ?.takeUnless { it.status == LyricsStatus.NOT_FOUND }
                ?.let { return@withContext it }

            val results = requestJsonArray(
                "$BASE_URL/api/search" + query(
                    "track_name" to request.title,
                    "artist_name" to request.artist,
                    "album_name" to request.album,
                )
            ) ?: return@withContext LyricsState(LyricsStatus.NOT_FOUND)

            val best = selectBest(results, request)
                ?: return@withContext LyricsState(LyricsStatus.NOT_FOUND)
            best.toLyricsState(request.durationMs)
        } catch (_: Exception) {
            LyricsState(LyricsStatus.ERROR)
        }
    }

    private fun selectBest(results: JSONArray, request: LyricsRequest): JSONObject? {
        val wantedTitle = normalize(request.title)
        val wantedArtist = normalize(request.artist)
        val wantedAlbum = normalize(request.album)
        val wantedDuration = request.durationMs / 1000.0

        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val title = normalize(item.optString("trackName"))
            val artist = normalize(item.optString("artistName"))
            val album = normalize(item.optString("albumName"))

            val titleMatches = title == wantedTitle ||
                (title.isNotEmpty() && wantedTitle.isNotEmpty() &&
                    (title.contains(wantedTitle) || wantedTitle.contains(title)))
            val artistMatches = artist == wantedArtist ||
                (artist.isNotEmpty() && wantedArtist.isNotEmpty() &&
                    (artist.contains(wantedArtist) || wantedArtist.contains(artist)))
            if (!titleMatches || !artistMatches) continue

            var score = 100
            if (title == wantedTitle) score += 80
            if (artist == wantedArtist) score += 60
            if (wantedAlbum.isNotEmpty() && album == wantedAlbum) score += 30

            val candidateDuration = item.optDouble("duration", -1.0)
            if (wantedDuration > 0 && candidateDuration > 0) {
                val difference = kotlin.math.abs(candidateDuration - wantedDuration)
                if (difference > 12.0) continue
                score += when {
                    difference <= 2.0 -> 60
                    difference <= 5.0 -> 30
                    else -> 5
                }
            }
            if (item.optString("syncedLyrics").isNotBlank()) score += 25

            if (score > bestScore) {
                bestScore = score
                best = item
            }
        }
        return best
    }

    private fun JSONObject.toLyricsState(requestDurationMs: Long = 0L): LyricsState {
        if (optBoolean("instrumental", false)) {
            return LyricsState(
                status = LyricsStatus.INSTRUMENTAL,
                sourceId = optLong("id").takeIf { it > 0 },
            )
        }
        val synced = optString("syncedLyrics")
        val plain = trimLyricsPayload(optString("plainLyrics"))
        val sourceDurationMs = optDouble("duration", -1.0)
            .takeIf { it > 0.0 }
            ?.let { (it * 1000.0).toLong() }
            ?: 0L
        // The receiver's duration is the one the playback clock uses. Prefer
        // it to provider metadata (which can be an album edit or a rounded
        // value) when determining the last line's timing window.
        val trackDurationMs = requestDurationMs.takeIf { it > 0L } ?: sourceDurationMs
        val parsed = parseLrc(synced, trackDurationMs)
        return when {
            parsed.isNotEmpty() -> LyricsState(
                status = LyricsStatus.SYNCED,
                lines = parsed,
                sourceId = optLong("id").takeIf { it > 0 },
                wordSyncMode = if (parsed.any { it.exactWordTiming }) {
                    WordSyncMode.ENHANCED
                } else {
                    WordSyncMode.ESTIMATED
                },
            )
            plain.isNotBlank() -> LyricsState(
                status = LyricsStatus.PLAIN,
                plainLyrics = plain,
                sourceId = optLong("id").takeIf { it > 0 },
            )
            else -> LyricsState(LyricsStatus.NOT_FOUND)
        }
    }

    private fun requestJsonObject(url: String): JSONObject? {
        val response = request(url) ?: return null
        return runCatching { JSONObject(response) }.getOrNull()
    }

    private fun requestJsonArray(url: String): JSONArray? {
        val response = request(url) ?: return null
        return runCatching { JSONArray(response) }.getOrNull()
    }

    private fun request(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            if (connection.contentLengthLong > MAX_LYRICS_RESPONSE_BYTES) return null
            connection.inputStream.use { it.readLyricsResponseUtf8() }
        } finally {
            connection.disconnect()
        }
    }

    private fun query(vararg pairs: Pair<String, String>): String = pairs
        .filter { it.second.isNotBlank() }
        .joinToString(prefix = "?", separator = "&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(versionNoise, "")
        .replace(nonAlphaNumeric, "")

    companion object {
        private const val BASE_URL = "https://lrclib.net"
        private const val USER_AGENT =
            "AirplaySpeaker/0.14.13 (https://github.com/jqssun/android-airplay-server)"

        private const val MAX_LYRIC_LINES = 750
        private const val MAX_WORDS_PER_LINE = 120

        private val timeTag = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        private val wordTimeTag = Regex("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>")
        private val offsetTag = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
        private val whitespace = Regex("\\s+")
        private val tokenWithTrailingWhitespace = Regex("\\S+\\s*")
        private val versionNoise = Regex("\\([^)]*(?:feat|ft\\.|remaster)[^)]*\\)")
        private val nonAlphaNumeric = Regex("[^\\p{L}\\p{N}]+")

        private data class WordSeed(val text: String, val startMs: Long)

        private data class LineSeed(
            val timeMs: Long,
            val text: String,
            val enhancedWords: List<WordSeed>,
        )

        /**
         * Parses regular and Enhanced LRC. When only line timestamps exist,
         * deterministic word windows are generated between adjacent lines so
         * the TV can still render a word-following effect.
         */
        fun parseLrc(value: String, durationMs: Long = 0L): List<LyricLine> {
            if (value.isBlank()) return emptyList()
            val payload = trimLyricsPayload(value)
            val offsetMs = offsetTag.find(payload)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val seeds = mutableListOf<LineSeed>()
            payload.lineSequence().forEach { rawLine ->
                if (seeds.size >= MAX_LYRIC_LINES) return@forEach
                val matches = timeTag.findAll(rawLine).toList()
                if (matches.isEmpty()) return@forEach
                val content = rawLine.substring(matches.last().range.last + 1)
                val enhancedWords = parseEnhancedWords(content, offsetMs)
                val text = wordTimeTag.replace(content, "")
                    .replace(whitespace, " ")
                    .trim()
                if (text.isBlank()) return@forEach
                for (match in matches) {
                    if (seeds.size >= MAX_LYRIC_LINES) break
                    val lineTime = parseTime(match, offsetMs) ?: continue
                    seeds += LineSeed(
                        timeMs = lineTime,
                        text = text,
                        enhancedWords = enhancedWords,
                    )
                }
            }
            val sorted = seeds
                .distinctBy { it.timeMs to it.text }
                // In enhanced LRC the word marker is the most accurate onset.
                // Some sources put a rounded line marker before it, which made
                // the line visibly arrive ahead of the vocal on the TV.
                .map { seed ->
                    seed.enhancedWords.minOfOrNull { it.startMs }
                        ?.let { firstWordStart -> seed.copy(timeMs = firstWordStart) }
                        ?: seed
                }
                .sortedBy { it.timeMs }
            return sorted.mapIndexed { index, line ->
                val tokens = tokenize(line.text)
                val naturalEnd = line.timeMs + (tokens.size.coerceAtLeast(1) * 480L)
                    .coerceIn(1_200L, 8_000L)
                val nextLineStart = sorted.getOrNull(index + 1)?.timeMs
                val trackEnd = durationMs.takeIf { it > line.timeMs }
                val lineEnd = (nextLineStart ?: trackEnd ?: naturalEnd)
                    .coerceAtLeast(line.timeMs + 350L)

                val exact = line.enhancedWords.isNotEmpty()
                val words = if (exact) {
                    val normalized = ensureLatinSpacing(line.enhancedWords)
                        .take(MAX_WORDS_PER_LINE)
                    normalized.mapIndexed { wordIndex, word ->
                        LyricWord(
                            text = word.text,
                            startMs = word.startMs,
                            endMs = (normalized.getOrNull(wordIndex + 1)?.startMs ?: lineEnd)
                                .coerceAtLeast(word.startMs + 1L),
                        )
                    }
                } else {
                    distributeWords(tokens, line.timeMs, lineEnd)
                }
                LyricLine(
                    timeMs = line.timeMs,
                    text = line.text,
                    words = words,
                    exactWordTiming = exact,
                    endMs = lineEnd,
                )
            }
        }

        private fun parseEnhancedWords(content: String, offsetMs: Long): List<WordSeed> {
            val tags = wordTimeTag.findAll(content).toList()
            if (tags.isEmpty()) return emptyList()

            val result = mutableListOf<WordSeed>()
            tags.forEachIndexed { index, tag ->
                val startMs = parseTime(tag, offsetMs) ?: return@forEachIndexed
                val segmentEnd = tags.getOrNull(index + 1)?.range?.first ?: content.length
                val segment = content.substring(tag.range.last + 1, segmentEnd)
                val tokens = tokenize(segment)
                if (tokens.isEmpty()) return@forEachIndexed

                val nextTagMs = tags.getOrNull(index + 1)?.let { parseTime(it, offsetMs) }
                val segmentEndMs = nextTagMs
                    ?: (startMs + tokens.size.coerceAtLeast(1) * 420L)
                distributeWords(tokens, startMs, segmentEndMs).forEach { word ->
                    if (result.size >= MAX_WORDS_PER_LINE) return@forEach
                    result += WordSeed(word.text, word.startMs)
                }
            }
            return result.distinctBy { it.startMs to it.text }.sortedBy { it.startMs }
        }

        private fun parseTime(match: MatchResult, offsetMs: Long): Long? {
            val minutes = match.groupValues[1].toLongOrNull() ?: return null
            val seconds = match.groupValues[2].toLongOrNull() ?: return null
            val fraction = match.groupValues[3]
            val fractionMs = when (fraction.length) {
                1 -> fraction.toLongOrNull()?.times(100) ?: 0L
                2 -> fraction.toLongOrNull()?.times(10) ?: 0L
                3 -> fraction.toLongOrNull() ?: 0L
                else -> 0L
            }
            return ((minutes * 60 + seconds) * 1000 + fractionMs + offsetMs)
                .coerceAtLeast(0L)
        }

        private fun tokenize(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            if (text.any(Char::isWhitespace)) {
                return tokenWithTrailingWhitespace.findAll(text)
                    .map { it.value }
                    .take(MAX_WORDS_PER_LINE)
                    .toList()
            }

            // java.text.BreakIterator is backed by ICU on Android and gives a
            // much better approximation for Thai, CJK and other unspaced text.
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
                if (tokens.size >= MAX_WORDS_PER_LINE) break
                start = end
                end = iterator.next()
            }
            return tokens.ifEmpty { listOf(text) }
        }

        private fun distributeWords(
            tokens: List<String>,
            startMs: Long,
            requestedEndMs: Long,
        ): List<LyricWord> {
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

        private fun ensureLatinSpacing(words: List<WordSeed>): List<WordSeed> =
            words.mapIndexed { index, word ->
                val next = words.getOrNull(index + 1)
                val needsSpace = next != null &&
                    word.text.lastOrNull()?.let { it.code < 128 && it.isLetterOrDigit() } == true &&
                    next.text.firstOrNull()?.let { it.code < 128 && it.isLetterOrDigit() } == true &&
                    word.text.lastOrNull()?.isWhitespace() != true
                if (needsSpace) word.copy(text = word.text + " ") else word
        }
    }
}

/**
 * Both lyric providers are network services. Bounding their response keeps a
 * malformed or unexpected response from creating a large temporary String on
 * low-RAM Android TV devices. Normal synced lyrics are far below this limit.
 */
internal const val MAX_LYRICS_RESPONSE_BYTES = 512 * 1024L

internal fun InputStream.readLyricsResponseUtf8(): String? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > MAX_LYRICS_RESPONSE_BYTES) return null
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}

/** Retain only complete lines when a provider sends an unexpectedly huge LRC. */
internal fun trimLyricsPayload(value: String): String {
    if (value.length <= MAX_LRC_CHARS) return value
    val lastLineBreak = value.lastIndexOf('\n', MAX_LRC_CHARS)
    return value.substring(0, if (lastLineBreak > 0) lastLineBreak else MAX_LRC_CHARS)
}
