package io.github.jqssun.airplay.lyrics

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
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
            val result = coroutineScope {
                val lyricsRequest = async {
                    requestText(
                        "$LYRICS_URL" + query("id" to trackId.toString(), "v" to "2"),
                        maxBytes = MAX_LYRICS_BYTES,
                    )
                }
                val creditRequest = async {
                    withTimeoutOrNull(CREDITS_TIMEOUT_MS) {
                        requestText(
                            "$APPLE_MUSIC_SONG_URL/$trackId",
                            maxBytes = MAX_APPLE_PAGE_BYTES,
                            accept = "text/html,application/xhtml+xml",
                            connectTimeoutMs = 2_500,
                            readTimeoutMs = 3_200,
                        )
                    }
                }
                val response = lyricsRequest.await() ?: return@coroutineScope null
                parseResponse(response, trackId)?.copy(
                    writerCredit = creditRequest.await()
                        ?.let(::parseWriterCredit)
                        .orEmpty(),
                )
            }
            result?.also { resolved ->
                synchronized(cache) { cache[cacheKey] = resolved }
            }
        }.getOrNull()
    }

    private fun requestText(
        url: String,
        maxBytes: Int,
        accept: String = "application/json",
        connectTimeoutMs: Int = 3_500,
        readTimeoutMs: Int = 6_500,
    ): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
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
        private const val APPLE_MUSIC_SONG_URL = "https://music.apple.com/th/song"
        private const val USER_AGENT =
            "AirplaySpeaker/2.0.0 (https://github.com/l3kza08/Sonic-Cast)"
        private const val MAX_SEARCH_BYTES = 1024 * 1024
        private const val MAX_LYRICS_BYTES = 768 * 1024
        private const val MAX_APPLE_PAGE_BYTES = 768 * 1024
        private const val CREDITS_TIMEOUT_MS = 3_800L
        private const val MAX_CREDIT_SECTION_CHARS = 256 * 1024
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
            val rawLines = root.optJSONArray("lyrics")
            if (rawLines == null) {
                val plainLyrics = root.optString("lyrics").trim()
                return plainLyrics.takeIf { it.isNotBlank() }?.let {
                    LyricsState(
                        status = LyricsStatus.PLAIN,
                        plainLyrics = it,
                        sourceId = trackId,
                        wordSyncMode = WordSyncMode.NONE,
                    )
                }
            }
            val allTexts = mutableListOf<String>()
            val parsedLines = buildList {
                for (index in 0 until minOf(rawLines.length(), MAX_LINES)) {
                    val rawLine = rawLines.optJSONObject(index) ?: continue
                    val main = parseTextField(rawLine.opt("text"))
                    val mainText = main.text
                    val background = parseTextField(rawLine.opt("backgroundText"))
                    if (mainText.isBlank() && background.text.isBlank()) continue
                    val combinedText = if (background.text.isNotBlank()) {
                        listOf(
                            mainText,
                            "(${background.text})",
                        ).filter(String::isNotBlank).joinToString(" ")
                    } else {
                        mainText
                    }
                    allTexts += combinedText
                    val timeMs = rawLine.optLong("timestamp", rawLine.optLong("time", -1L))
                    if (timeMs < 0L) continue
                    val mainHasWordTiming = lyricWordsMatchLine(mainText, main.words)
                    val backgroundHasWordTiming = background.text.isNotBlank() &&
                        lyricWordsMatchLine(background.text, background.words)
                    // Preserve a valid large or compact vocal independently.
                    // One untimed Apple part must not disable karaoke for the
                    // other part on the same visual row.
                    val hasAnyCompleteWordTiming =
                        mainHasWordTiming || backgroundHasWordTiming
                    val declaredEnd = rawLine.optLong("endtime", -1L)
                    val wordEnd = (main.words + background.words)
                        .maxOfOrNull { it.endMs }
                        ?: timeMs
                    val endMs = maxOf(declaredEnd, wordEnd).takeIf { it > timeMs }
                    val agentId = rawLine.optString("agent").trim()
                    add(
                        ParsedProviderLine(
                            line = LyricLine(
                                timeMs = timeMs,
                                text = mainText,
                                words = main.words,
                                exactWordTiming = hasAnyCompleteWordTiming,
                                endMs = endMs,
                                backgroundText = background.text,
                                backgroundWords = background.words,
                                agentId = agentId,
                            ),
                            vocalTurn = ProviderVocalTurn(
                                explicitOpposite = rawLine.optBooleanOrNull("oppositeTurn"),
                                agent = agentId,
                            ),
                        )
                    )
                }
            }.sortedBy { it.line.timeMs }
            if (parsedLines.isEmpty()) {
                val plainLyrics = allTexts.joinToString("\n").trim()
                return plainLyrics.takeIf { it.isNotBlank() }?.let {
                    LyricsState(
                        status = LyricsStatus.PLAIN,
                        plainLyrics = it,
                        sourceId = trackId,
                        wordSyncMode = WordSyncMode.NONE,
                    )
                }
            }
            val alignments = resolveProviderDuetAlignments(parsedLines.map { it.vocalTurn })
            val lines = parsedLines.mapIndexed { index, parsed ->
                parsed.line.copy(alignment = alignments[index])
            }
            return LyricsState(
                status = LyricsStatus.SYNCED,
                lines = lines,
                plainLyrics = allTexts.joinToString("\n").ifBlank {
                    lines.joinToString("\n", transform = LyricLine::combinedDisplayText)
                },
                sourceId = trackId,
                wordSyncMode = if (lines.any { it.exactWordTiming }) {
                    WordSyncMode.ENHANCED
                } else {
                    WordSyncMode.NONE
                },
            )
        }

        /**
         * Reads the public Composition & Lyrics section embedded in the
         * matching Apple Music song page. Only writing roles are retained;
         * producers, performers and sampled artists are deliberately ignored.
         */
        internal fun parseWriterCredit(page: String): String {
            val sectionStart = page.indexOf("\"id\":\"composer-and-lyrics\"")
            if (sectionStart < 0) return ""
            val itemsMarker = page.indexOf("\"items\":[", startIndex = sectionStart)
            if (itemsMarker < 0) return ""
            val arrayStart = page.indexOf('[', startIndex = itemsMarker)
            val itemsJson = extractJsonArray(page, arrayStart) ?: return ""
            val items = runCatching { JSONArray(itemsJson) }.getOrNull() ?: return ""
            return buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val roles = item.optJSONArray("roleNames")
                    val hasWritingRole = roles != null &&
                        (0 until roles.length()).any { roleIndex ->
                            val role = roles.optString(roleIndex).lowercase(Locale.ROOT)
                            role.contains("songwriter") ||
                                role.contains("composer") ||
                                role.contains("lyricist")
                        }
                    if (!hasWritingRole) continue
                    item.optString("name")
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }.distinct().joinToString(", ")
        }

        private fun extractJsonArray(value: String, startIndex: Int): String? {
            if (startIndex !in value.indices || value[startIndex] != '[') return null
            val scanEnd = minOf(value.length, startIndex + MAX_CREDIT_SECTION_CHARS)
            var depth = 0
            var inString = false
            var escaped = false
            for (index in startIndex until scanEnd) {
                val char = value[index]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == '"' -> inString = false
                    }
                    continue
                }
                when (char) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return value.substring(startIndex, index + 1)
                    }
                }
            }
            return null
        }

        private data class ParsedWords(val text: String, val words: List<LyricWord>)
        private data class ParsedProviderLine(
            val line: LyricLine,
            val vocalTurn: ProviderVocalTurn,
        )

        private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
            if (has(key) && !isNull(key)) optBoolean(key, false) else null

        private fun parseTextField(value: Any?): ParsedWords = when (value) {
            is JSONArray -> parseWords(value)
            is String -> ParsedWords(value.trim(), emptyList())
            else -> ParsedWords("", emptyList())
        }

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
