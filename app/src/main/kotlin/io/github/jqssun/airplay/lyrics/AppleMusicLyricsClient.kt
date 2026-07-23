package io.github.jqssun.airplay.lyrics

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Resolves normalized Apple-style syllable lyrics, including `oppositeTurn`
 * duet placement and background vocals. No credentials are stored in the app.
 */
class AppleMusicLyricsClient {

    private val cache = object : LinkedHashMap<String, LyricsState>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, LyricsState>?,
        ): Boolean = size > CACHE_ENTRIES
    }

    suspend fun find(request: LyricsRequest): LyricsState? = withContext(Dispatchers.IO) {
        val cacheKey = listOf(request.title, request.artist, request.album, request.durationMs.toString())
            .joinToString("\u0000") { it.trim().lowercase(Locale.ROOT) }
        synchronized(cache) { cache[cacheKey] }?.let { return@withContext it }

        runCatching {
            val search = requestText(
                buildString {
                    append(ITUNES_SEARCH_URL)
                    append(query(
                        "term" to "${request.artist} ${request.title}",
                        "entity" to "song",
                        "country" to "TH",
                        "limit" to "25",
                    ))
                },
                maxBytes = MAX_SEARCH_BYTES,
            ) ?: return@withContext null
            val trackId = selectTrackId(search, request) ?: return@withContext null
            val response = requestText(
                "$LYRICS_URL" + query("id" to trackId.toString(), "v" to "2"),
                maxBytes = MAX_LYRICS_BYTES,
            ) ?: return@withContext null
            parseResponse(response, trackId)?.also { result ->
                synchronized(cache) { cache[cacheKey] = result }
            }
        }.getOrNull()
    }

    private fun requestText(url: String, maxBytes: Int): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 14_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", "th-TH,th;q=0.9,en;q=0.8")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            if (connection.contentLengthLong > maxBytes) return null
            connection.inputStream.use { it.readBoundedUtf8(maxBytes) }
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readBoundedUtf8(maxBytes: Int): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1_024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) return null
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun query(vararg pairs: Pair<String, String>): String = pairs
        .filter { it.second.isNotBlank() }
        .joinToString(prefix = "?", separator = "&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        private const val ITUNES_SEARCH_URL = "https://itunes.apple.com/search"
        private const val LYRICS_URL = "https://lyrics.paxsenix.org/apple-music/lyrics"
        private const val USER_AGENT =
            "AirplaySpeaker/0.14.13 (https://github.com/jqssun/android-airplay-server)"
        private const val MAX_SEARCH_BYTES = 1024 * 1024
        private const val MAX_LYRICS_BYTES = 768 * 1024
        private const val CACHE_ENTRIES = 12
        private const val MAX_LINES = 750
        private const val MAX_WORDS_PER_LINE = 160
        private val versionNoise = Regex(
            "[\\[(][^\\])]*(?:feat|ft\\.|remaster|version|edit|mix)[^\\])]*[\\])]",
            RegexOption.IGNORE_CASE,
        )
        private val nonAlphaNumeric = Regex("[^\\p{L}\\p{N}]+")
        private val closingPunctuation = Regex("^[,.;:!?%)}\\]…]+")

        internal fun selectTrackId(value: String, request: LyricsRequest): Long? {
            val results = runCatching { JSONObject(value).optJSONArray("results") }.getOrNull()
                ?: return null
            val wantedTitle = normalize(request.title)
            val wantedArtist = normalize(request.artist)
            val wantedAlbum = normalize(request.album)
            var bestId: Long? = null
            var bestScore = Int.MIN_VALUE
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val title = normalize(item.optString("trackName"))
                val artist = normalize(item.optString("artistName"))
                val album = normalize(item.optString("collectionName"))
                if (!closeMatch(title, wantedTitle) || !closeMatch(artist, wantedArtist)) continue

                val candidateDuration = item.optLong("trackTimeMillis", 0L)
                val durationDifference = if (request.durationMs > 0L && candidateDuration > 0L) {
                    kotlin.math.abs(request.durationMs - candidateDuration)
                } else {
                    0L
                }
                if (durationDifference > 25_000L) continue

                var score = 100
                if (title == wantedTitle) score += 100
                if (artist == wantedArtist) score += 70
                if (wantedAlbum.isNotBlank() && album == wantedAlbum) score += 90
                score += when {
                    durationDifference <= 2_000L -> 55
                    durationDifference <= 6_000L -> 25
                    durationDifference <= 12_000L -> 8
                    else -> 0
                }
                val id = item.optLong("trackId", -1L)
                if (id > 0L && score > bestScore) {
                    bestScore = score
                    bestId = id
                }
            }
            return bestId
        }

        internal fun parseResponse(value: String, trackId: Long): LyricsState? {
            val root = runCatching { JSONObject(value) }.getOrNull() ?: return null
            val rawLines = root.optJSONArray("lyrics") ?: return null
            val lines = buildList {
                for (index in 0 until minOf(rawLines.length(), MAX_LINES)) {
                    val rawLine = rawLines.optJSONObject(index) ?: continue
                    val timeMs = rawLine.optLong("timestamp", rawLine.optLong("time", -1L))
                    if (timeMs < 0L) continue
                    val main = parseWords(rawLine.optJSONArray("text"))
                    val fallbackText = rawLine.optString("text").trim()
                    val mainText = main.text.ifBlank { fallbackText }
                    if (mainText.isBlank()) continue
                    val background = parseWords(rawLine.optJSONArray("backgroundText"))
                    val combinedText = if (background.text.isNotBlank()) {
                        "$mainText (${background.text})"
                    } else {
                        mainText
                    }
                    val words = main.words + background.words
                    val completeWordTiming = lyricWordsMatchLine(mainText, main.words) &&
                        (background.text.isBlank() ||
                            lyricWordsMatchLine(background.text, background.words))
                    val declaredEnd = rawLine.optLong("endtime", -1L)
                    val wordEnd = words.maxOfOrNull { it.endMs } ?: timeMs
                    val endMs = maxOf(declaredEnd, wordEnd).takeIf { it > timeMs }
                    add(
                        LyricLine(
                            timeMs = timeMs,
                            text = combinedText,
                            words = words,
                            exactWordTiming = completeWordTiming,
                            endMs = endMs,
                            alignment = if (rawLine.optBoolean("oppositeTurn", false)) {
                                LyricAlignment.TRAILING
                            } else {
                                LyricAlignment.LEADING
                            },
                        )
                    )
                }
            }.sortedBy { it.timeMs }
            if (lines.isEmpty()) return null
            return LyricsState(
                status = LyricsStatus.SYNCED,
                lines = lines,
                plainLyrics = lines.joinToString("\n") { it.text },
                sourceId = trackId,
                wordSyncMode = if (lines.any { it.words.isNotEmpty() }) {
                    WordSyncMode.ENHANCED
                } else {
                    WordSyncMode.ESTIMATED
                },
            )
        }

        private data class ParsedWords(val text: String, val words: List<LyricWord>)

        private fun parseWords(values: JSONArray?): ParsedWords {
            if (values == null) return ParsedWords("", emptyList())
            val tokens = buildList {
                for (index in 0 until minOf(values.length(), MAX_WORDS_PER_LINE)) {
                    val item = values.optJSONObject(index) ?: continue
                    val text = item.optString("text")
                    if (text.isEmpty()) continue
                    add(item to text)
                }
            }
            if (tokens.isEmpty()) return ParsedWords("", emptyList())

            val words = tokens.mapIndexedNotNull { index, (item, token) ->
                val start = item.optLong("timestamp", item.optLong("time", -1L))
                val declaredEnd = item.optLong("endtime", -1L)
                val duration = item.optLong("duration", 0L)
                if (start < 0L) return@mapIndexedNotNull null
                val nextToken = tokens.getOrNull(index + 1)?.second
                val separator = separatorAfter(token, nextToken)
                val end = when {
                    declaredEnd > start -> declaredEnd
                    duration > 0L -> start + duration
                    else -> start + 1L
                }
                LyricWord(token + separator, start, end)
            }
            val text = tokens.mapIndexed { index, (_, token) ->
                token + separatorAfter(token, tokens.getOrNull(index + 1)?.second)
            }.joinToString("").trimEnd()
            return ParsedWords(text, words)
        }

        private fun separatorAfter(current: String, next: String?): String {
            if (next == null) return ""
            if (next.matches(closingPunctuation)) return ""
            if (current.endsWith("(") || current.endsWith("[") || current.endsWith("{") ||
                current.endsWith("-") || current.endsWith("’") || current.endsWith("'")) {
                return ""
            }
            return " "
        }

        private fun closeMatch(candidate: String, wanted: String): Boolean =
            candidate == wanted || (
                candidate.isNotEmpty() && wanted.isNotEmpty() &&
                    (candidate.contains(wanted) || wanted.contains(candidate))
                )

        private fun normalize(value: String): String = value
            .lowercase(Locale.ROOT)
            .replace(versionNoise, "")
            .replace(nonAlphaNumeric, "")
    }
}
