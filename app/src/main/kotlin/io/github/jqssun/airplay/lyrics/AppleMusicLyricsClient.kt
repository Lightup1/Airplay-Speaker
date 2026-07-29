package io.github.jqssun.airplay.lyrics

import io.github.jqssun.airplay.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

        val result = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
            runCatching { findUncached(request) }.getOrNull()
        }
        result?.also { resolved ->
            synchronized(cache) { cache[cacheKey] = resolved }
        }
    }

    private suspend fun findUncached(request: LyricsRequest): LyricsState? = coroutineScope {
        val searchResponses = searchRequests(request)
            .map { searchRequest ->
                async {
                    requestText(
                        buildString {
                            append(ITUNES_SEARCH_URL)
                            append(query(
                                "term" to searchRequest.term,
                                "media" to "music",
                                "entity" to "song",
                                "country" to searchRequest.country,
                                "limit" to SEARCH_RESULT_LIMIT.toString(),
                            ))
                        },
                        maxBytes = MAX_SEARCH_BYTES,
                    )
                }
            }
            .awaitAll()
            .filterNotNull()

        val candidateIds = searchResponses
            .flatMap { rankTrackIds(it, request) }
            .distinct()
            .take(MAX_LYRICS_CANDIDATES)

        // Try candidates in ranking order. Launching every duplicate at once
        // made one slow or broken regional ID cancel a valid Apple result.
        // The best match normally succeeds first, so avoid touching lower
        // ranked duplicates unless the preferred candidate has no lyrics.
        val directResult = resolveLyricsCandidates(candidateIds)
        // Generic titles can be omitted from iTunes search results even while
        // their Apple Music page still exists. Resolve the album catalogue and
        // inspect its full track list as a second, exact metadata path.
        val (trackId, resolved) = directResult
            ?: resolveLyricsCandidates(findAlbumCandidateIds(request))
            ?: return@coroutineScope null
        val writerCredit = withTimeoutOrNull(CREDITS_TIMEOUT_MS) {
            requestText(
                "$APPLE_MUSIC_SONG_URL/$trackId",
                maxBytes = MAX_APPLE_PAGE_BYTES,
                accept = "text/html,application/xhtml+xml",
                connectTimeoutMs = 2_500,
                readTimeoutMs = 3_200,
            )
        }
            ?.let(::parseWriterCredit)
            .orEmpty()
        resolved.copy(writerCredit = writerCredit)
    }

    private suspend fun resolveLyricsCandidates(
        candidateIds: List<Long>,
    ): Pair<Long, LyricsState>? = resolveFirstCandidate(
        candidateIds.distinct().take(MAX_LYRICS_CANDIDATES),
    ) { candidateId ->
        requestText(
            "$LYRICS_URL" + query("id" to candidateId.toString(), "v" to "2"),
            maxBytes = MAX_LYRICS_BYTES,
            readTimeoutMs = LYRICS_READ_TIMEOUT_MS,
        )?.let { parseResponse(it, candidateId) }
    }

    private suspend fun findAlbumCandidateIds(request: LyricsRequest): List<Long> {
        if (request.album.isBlank() || request.artist.isBlank()) return emptyList()
        return coroutineScope {
            val albumSearchResponses = albumSearchRequests(request)
                .map { searchRequest ->
                    async {
                        requestText(
                            buildString {
                                append(ITUNES_SEARCH_URL)
                                append(query(
                                    "term" to searchRequest.term,
                                    "media" to "music",
                                    "entity" to "song",
                                    "country" to searchRequest.country,
                                    "limit" to SEARCH_RESULT_LIMIT.toString(),
                                ))
                            },
                            maxBytes = MAX_SEARCH_BYTES,
                        )
                    }
                }
                .awaitAll()
                .filterNotNull()
            val collectionIds = albumSearchResponses
                .flatMap { rankCollectionIds(it, request) }
                .distinct()
                .take(MAX_ALBUM_CANDIDATES)
            if (collectionIds.isEmpty()) return@coroutineScope emptyList()

            collectionIds
                .flatMap { collectionId ->
                    SEARCH_COUNTRIES.map { country -> collectionId to country }
                }
                .map { (collectionId, country) ->
                    async {
                        requestText(
                            ITUNES_LOOKUP_URL + query(
                                "id" to collectionId.toString(),
                                "entity" to "song",
                                "country" to country,
                            ),
                            maxBytes = MAX_SEARCH_BYTES,
                        )
                    }
                }
                .awaitAll()
                .filterNotNull()
                .flatMap { rankTrackIds(it, request) }
                .distinct()
                .take(MAX_LYRICS_CANDIDATES)
        }
    }

    private data class SearchRequest(
        val term: String,
        val country: String,
    )

    private fun searchRequests(request: LyricsRequest): List<SearchRequest> {
        return buildList {
            SEARCH_COUNTRIES.forEach { country ->
                searchTerms(request).forEach { term ->
                    add(SearchRequest(term = term, country = country))
                }
            }
        }.distinct().take(MAX_SEARCH_REQUESTS)
    }

    private fun albumSearchRequests(request: LyricsRequest): List<SearchRequest> {
        return buildList {
            SEARCH_COUNTRIES.forEach { country ->
                albumSearchTerms(request).forEach { term ->
                    add(SearchRequest(term = term, country = country))
                }
            }
        }.distinct().take(MAX_ALBUM_SEARCH_REQUESTS)
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
        } catch (_: IOException) {
            null
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
        private const val ITUNES_LOOKUP_URL = "https://itunes.apple.com/lookup"
        private const val LYRICS_URL = "https://lyrics.paxsenix.org/apple-music/lyrics"
        private const val APPLE_MUSIC_SONG_URL = "https://music.apple.com/th/song"
        private val USER_AGENT =
            "AirplaySpeaker/${BuildConfig.VERSION_NAME} " +
                "(https://github.com/l3kza08/Airplay-Speaker)"
        private const val MAX_SEARCH_BYTES = 1024 * 1024
        private const val MAX_LYRICS_BYTES = 768 * 1024
        private const val MAX_APPLE_PAGE_BYTES = 768 * 1024
        private const val CREDITS_TIMEOUT_MS = 3_800L
        private const val LOOKUP_TIMEOUT_MS = 18_000L
        private const val LYRICS_READ_TIMEOUT_MS = 5_500
        private const val MAX_CREDIT_SECTION_CHARS = 256 * 1024
        private const val CACHE_ENTRIES = 12
        private const val SEARCH_RESULT_LIMIT = 50
        private const val MAX_SEARCH_REQUESTS = 6
        private const val MAX_ALBUM_SEARCH_REQUESTS = 6
        private const val MAX_ALBUM_CANDIDATES = 3
        private const val MAX_LYRICS_CANDIDATES = 5
        private const val MAX_LINES = 750
        private const val MAX_WORDS_PER_LINE = 160
        private val SEARCH_COUNTRIES = listOf("TH", "US")
        private val versionNoise = Regex(
            "[\\[(][^\\])]*(?:feat|ft\\.?|remaster|version|edit|mix|live|acoustic|instrumental|karaoke)[^\\])]*[\\])]",
            RegexOption.IGNORE_CASE,
        )
        private val trailingVersionNoise = Regex(
            "\\s*[-–—:]\\s*(?:\\d{4}\\s+)?(?:remaster(?:ed)?|version|edit|mix|live|acoustic|instrumental|karaoke|radio).*?$",
            RegexOption.IGNORE_CASE,
        )
        private val featuredArtistTail = Regex(
            "\\s+(?:feat(?:uring)?\\.?|ft\\.?)\\s+.+$",
            RegexOption.IGNORE_CASE,
        )
        private val combiningMarks = Regex("\\p{M}+")
        private val nonAlphaNumeric = Regex("[^\\p{L}\\p{N}]+")
        private val noSpaceBeforeCharacters = setOf(
            ',', '.', ';', ':', '!', '?', '%',
            ')', ']', '}', '…',
            '，', '。', '、', '；', '：', '！', '？',
            '）', '】', '》', '〉', '〕', '〗', '〙', '〛',
            '」', '』', '”', '’',
        )
        private val noSpaceAfterCharacters = setOf(
            '(', '[', '{',
            '（', '【', '《', '〈', '〔', '〖', '〘', '〚',
            '「', '『', '“', '‘',
        )
        private val inlineJoinCharacters = setOf(
            '\'', '’', 'ʼ', '‛',
            '-', '‐', '‑', '‒', '–', '—',
            '/', '\\',
        )

        internal fun selectTrackId(value: String, request: LyricsRequest): Long? {
            return rankTrackIds(value, request).firstOrNull()
        }

        internal suspend fun <T : Any> resolveFirstCandidate(
            candidateIds: List<Long>,
            resolver: suspend (Long) -> T?,
        ): Pair<Long, T>? {
            for (candidateId in candidateIds) {
                val resolved = try {
                    resolver(candidateId)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (resolved != null) return candidateId to resolved
            }
            return null
        }

        internal fun rankTrackIds(value: String, request: LyricsRequest): List<Long> {
            val results = runCatching { JSONObject(value).optJSONArray("results") }.getOrNull()
                ?: return emptyList()
            val wantedTitle = normalize(request.title)
            val wantedArtist = normalize(request.artist)
            val wantedPrimaryArtist = normalize(primarySearchArtist(request.artist))
            val wantedAlbum = normalize(request.album)
            val ranked = mutableListOf<Pair<Long, Int>>()
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val title = normalize(item.optString("trackName"))
                val artist = normalize(item.optString("artistName"))
                val primaryArtist = normalize(primarySearchArtist(item.optString("artistName")))
                val album = normalize(item.optString("collectionName"))
                val titleScore = matchScore(title, wantedTitle)
                val artistScore = maxOf(
                    matchScore(artist, wantedArtist),
                    matchScore(primaryArtist, wantedPrimaryArtist),
                )
                if (titleScore <= 0 || (wantedArtist.isNotBlank() && artistScore <= 0)) continue

                val candidateDuration = item.optLong("trackTimeMillis", 0L)
                val hasDuration = request.durationMs > 0L && candidateDuration > 0L
                val durationDifference = if (hasDuration) {
                    kotlin.math.abs(request.durationMs - candidateDuration)
                } else {
                    0L
                }
                val exactIdentity = titleScore == 180 && artistScore == 180
                val exactAlbum = wantedAlbum.isNotBlank() && album == wantedAlbum
                if (
                    hasDuration &&
                    durationDifference > MAX_DURATION_DIFFERENCE_MS &&
                    !exactIdentity &&
                    !exactAlbum
                ) {
                    continue
                }

                var score = titleScore + artistScore
                if (wantedAlbum.isNotBlank()) {
                    score += when {
                        album == wantedAlbum -> 110
                        closeMatch(album, wantedAlbum) -> 45
                        else -> 0
                    }
                }
                if (hasDuration) {
                    score += when {
                        durationDifference <= 2_000L -> 65
                        durationDifference <= 6_000L -> 35
                        durationDifference <= 15_000L -> 15
                        durationDifference <= 35_000L -> 4
                        else -> -(durationDifference / 1_000L).coerceAtMost(120L).toInt()
                    }
                }
                val id = item.optLong("trackId", -1L)
                if (id > 0L) {
                    ranked += id to score
                }
            }
            return ranked
                .sortedWith(
                    compareByDescending<Pair<Long, Int>> { it.second }
                        .thenBy { it.first },
                )
                .map { it.first }
                .distinct()
        }

        internal fun rankCollectionIds(value: String, request: LyricsRequest): List<Long> {
            val results = runCatching { JSONObject(value).optJSONArray("results") }.getOrNull()
                ?: return emptyList()
            val wantedAlbum = normalize(request.album)
            val wantedArtist = normalize(request.artist)
            val wantedPrimaryArtist = normalize(primarySearchArtist(request.artist))
            if (wantedAlbum.isBlank()) return emptyList()

            val ranked = mutableListOf<Pair<Long, Int>>()
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val album = normalize(item.optString("collectionName"))
                val artist = normalize(item.optString("artistName"))
                val primaryArtist = normalize(primarySearchArtist(item.optString("artistName")))
                val albumScore = matchScore(album, wantedAlbum)
                val artistScore = maxOf(
                    matchScore(artist, wantedArtist),
                    matchScore(primaryArtist, wantedPrimaryArtist),
                )
                if (albumScore <= 0 || artistScore <= 0) continue
                val collectionId = item.optLong("collectionId", -1L)
                if (collectionId > 0L) {
                    ranked += collectionId to (albumScore * 2 + artistScore)
                }
            }
            return ranked
                .sortedWith(
                    compareByDescending<Pair<Long, Int>> { it.second }
                        .thenBy { it.first },
                )
                .map { it.first }
                .distinct()
        }

        private fun matchScore(candidate: String, wanted: String): Int {
            if (wanted.isBlank()) return 1
            if (candidate == wanted) return 180
            if (candidate.isBlank()) return 0
            val shorter = minOf(candidate.length, wanted.length)
            val longer = maxOf(candidate.length, wanted.length)
            return when {
                (candidate.contains(wanted) || wanted.contains(candidate)) &&
                    shorter >= 3 &&
                    shorter.toDouble() / longer.toDouble() >= 0.45 -> 95
                else -> 0
            }
        }

        private fun cleanSearchTitle(value: String): String = value
            .replace(versionNoise, " ")
            .replace(trailingVersionNoise, " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        private fun primarySearchArtist(value: String): String = value
            .replace(featuredArtistTail, "")
            .trim()

        internal fun searchTerms(request: LyricsRequest): List<String> {
            val title = request.title.trim()
            val artist = request.artist.trim()
            val cleanTitle = cleanSearchTitle(title)
            val primaryArtist = primarySearchArtist(artist)
            return linkedSetOf(
                "$artist $title".trim(),
                "$primaryArtist $cleanTitle".trim(),
                "$cleanTitle $primaryArtist".trim(),
                cleanTitle,
            ).filter { it.isNotBlank() }
        }

        internal fun albumSearchTerms(request: LyricsRequest): List<String> {
            val album = request.album.trim()
            val artist = request.artist.trim()
            val primaryArtist = primarySearchArtist(artist)
            return linkedSetOf(
                "$artist $album".trim(),
                "$primaryArtist $album".trim(),
                "$album $primaryArtist".trim(),
            ).filter { it.isNotBlank() }
        }

        private fun normalize(value: String): String = Normalizer
            .normalize(cleanSearchTitle(value), Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT)
            .replace(combiningMarks, "")
            .replace(nonAlphaNumeric, "")

        private const val MAX_DURATION_DIFFERENCE_MS = 90_000L

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
            val declaredWordTiming = declaredWordTiming(
                root.optString("syncType", ""),
            )
            val allTexts = mutableListOf<String>()
            val parsedLines = buildList {
                for (index in 0 until minOf(rawLines.length(), MAX_LINES)) {
                    val rawLine = rawLines.optJSONObject(index) ?: continue
                    val declaredEnd = rawLine.optTimestampMs("endtime", "endTime") ?: -1L
                    val main = parseTextField(
                        value = rawLine.opt("text"),
                        fallbackEndMs = declaredEnd.takeIf { it > 0L },
                    )
                    val mainText = main.text
                    val background = parseTextField(
                        value = rawLine.opt("backgroundText"),
                        fallbackEndMs = declaredEnd.takeIf { it > 0L },
                    )
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
                    val timeMs = rawLine.optTimestampMs("timestamp", "time") ?: -1L
                    if (timeMs < 0L) continue
                    val mainHasWordTiming = hasTrustedProviderWordTiming(
                        declaredWordTiming = declaredWordTiming,
                        text = mainText,
                        words = main.words,
                    )
                    val backgroundHasWordTiming = background.text.isNotBlank() &&
                        hasTrustedProviderWordTiming(
                            declaredWordTiming = declaredWordTiming,
                            text = background.text,
                            words = background.words,
                        )
                    val mainWords = main.words.takeIf { mainHasWordTiming }.orEmpty()
                    val backgroundWords = background.words
                        .takeIf { backgroundHasWordTiming }
                        .orEmpty()
                    // Preserve a valid large or compact vocal independently.
                    // One untimed Apple part must not disable karaoke for the
                    // other part on the same visual row.
                    val hasAnyCompleteWordTiming =
                        mainHasWordTiming || backgroundHasWordTiming
                    val wordEnd = (mainWords + backgroundWords)
                        .maxOfOrNull { it.endMs }
                        ?: timeMs
                    val endMs = maxOf(declaredEnd, wordEnd).takeIf { it > timeMs }
                    val agentId = rawLine.optString("agent").trim()
                    add(
                        ParsedProviderLine(
                            line = LyricLine(
                                timeMs = timeMs,
                                text = mainText,
                                words = mainWords,
                                exactWordTiming = hasAnyCompleteWordTiming,
                                endMs = endMs,
                                backgroundText = background.text,
                                backgroundWords = backgroundWords,
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
                    WordSyncMode.LINE
                },
            )
        }

        /**
         * The v2 Apple mirror represents line-synced rows as a one-item text
         * array whose timestamp covers the entire row. That object is a line
         * token, not word timing. Trust the response's syncType first; only
         * infer enhanced timing for older payloads that omit syncType and
         * contain multiple independently timed tokens.
         */
        private fun hasTrustedProviderWordTiming(
            declaredWordTiming: Boolean?,
            text: String,
            words: List<LyricWord>,
        ): Boolean {
            if (declaredWordTiming == false || !lyricWordsMatchLine(text, words)) {
                return false
            }
            if (declaredWordTiming == true) return true
            return words.size >= 2 &&
                words.asSequence().map(LyricWord::startMs).distinct().take(2).count() >= 2
        }

        private fun declaredWordTiming(syncType: String): Boolean? {
            val normalized = syncType
                .lowercase(Locale.ROOT)
                .filter(Char::isLetterOrDigit)
            return when (normalized) {
                "syllable", "syllables", "word", "words",
                "wordbyword", "enhanced" -> true

                "line", "lines", "linebyline", "none",
                "unsynced", "unsynchronized" -> false

                else -> null
            }
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

        private fun JSONObject.optTimestampMs(vararg keys: String): Long? {
            keys.forEach { key ->
                if (has(key) && !isNull(key)) {
                    parseTimestampMs(opt(key))?.let { return it }
                }
            }
            return null
        }

        /**
         * Apple lyric mirrors expose time values as integer milliseconds in
         * most responses, but some line-synced catalogues retain TTML-style
         * clock strings. Accept both without inventing word timing.
         */
        internal fun parseTimestampMs(value: Any?): Long? = when (value) {
            is Number -> value.toDouble()
                .takeIf { it.isFinite() && it >= 0.0 }
                ?.roundToLong()

            is String -> {
                val raw = value.trim().lowercase(Locale.ROOT)
                when {
                    raw.isBlank() -> null
                    raw.startsWith("pt") && raw.endsWith("s") -> {
                        raw.removePrefix("pt")
                            .removeSuffix("s")
                            .toDoubleOrNull()
                            ?.takeIf { it >= 0.0 }
                            ?.times(1_000.0)
                            ?.roundToLong()
                    }

                    raw.endsWith("ms") -> raw.removeSuffix("ms")
                        .toDoubleOrNull()
                        ?.takeIf { it >= 0.0 }
                        ?.roundToLong()

                    raw.endsWith("s") -> raw.removeSuffix("s")
                        .toDoubleOrNull()
                        ?.takeIf { it >= 0.0 }
                        ?.times(1_000.0)
                        ?.roundToLong()

                    ':' in raw -> {
                        val parts = raw.split(':')
                        if (parts.size !in 2..3) {
                            null
                        } else {
                            parts.map(String::toDoubleOrNull)
                                .takeIf { values -> values.all { it != null } }
                                ?.map { it ?: 0.0 }
                                ?.fold(0.0) { totalSeconds, part ->
                                    totalSeconds * 60.0 + part
                                }
                                ?.takeIf { it >= 0.0 }
                                ?.times(1_000.0)
                                ?.roundToLong()
                        }
                    }

                    else -> raw.toDoubleOrNull()
                        ?.takeIf { it >= 0.0 }
                        ?.roundToLong()
                }
            }

            else -> null
        }

        private fun parseTextField(
            value: Any?,
            fallbackEndMs: Long?,
        ): ParsedWords = when (value) {
            is JSONArray -> parseWords(value, fallbackEndMs)
            is String -> ParsedWords(value.trim(), emptyList())
            else -> ParsedWords("", emptyList())
        }

        private fun parseWords(
            values: JSONArray?,
            fallbackEndMs: Long?,
        ): ParsedWords {
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

            // Apple payload variants do not always include an explicit endtime
            // for every syllable. The following syllable onset is the most
            // faithful boundary; the published line end closes the last token.
            // Keeping a 1 ms placeholder here made the repair stage discard
            // otherwise complete word-by-word rows.
            val nextTimedStart = LongArray(tokens.size) { -1L }
            var followingStart = -1L
            for (index in tokens.indices.reversed()) {
                nextTimedStart[index] = followingStart
                val start = tokens[index].first.optTimestampMs("timestamp", "time") ?: -1L
                if (start >= 0L) followingStart = start
            }
            val words = tokens.mapIndexedNotNull { index, (item, token) ->
                val start = item.optTimestampMs("timestamp", "time") ?: -1L
                val declaredEnd = item.optTimestampMs("endtime", "endTime") ?: -1L
                val duration = item.optTimestampMs("duration") ?: 0L
                if (start < 0L) return@mapIndexedNotNull null
                val nextToken = tokens.getOrNull(index + 1)?.second
                val separator = separatorAfter(token, nextToken)
                val end = when {
                    declaredEnd > start -> declaredEnd
                    duration > 0L -> start + duration
                    nextTimedStart[index] > start -> nextTimedStart[index]
                    fallbackEndMs != null && fallbackEndMs > start -> fallbackEndMs
                    else -> start + DEFAULT_LAST_TOKEN_DURATION_MS
                }
                LyricWord(token + separator, start, end)
            }
            val text = tokens.mapIndexed { index, (_, token) ->
                token + separatorAfter(token, tokens.getOrNull(index + 1)?.second)
            }.joinToString("").trimEnd()
            return ParsedWords(text, words)
        }

        /**
         * Reconstructs Apple token rows without forcing Latin spacing rules
         * onto scripts that normally flow without inter-word spaces.
         *
         * Explicit whitespace always wins. This also prevents the old
         * double-space bug when a provider token already included its own
         * trailing separator.
         */
        internal fun separatorAfter(current: String, next: String?): String {
            if (next == null || current.isEmpty() || next.isEmpty()) return ""
            if (current.last().isWhitespace() || next.first().isWhitespace()) return ""

            val currentCodePoint = current.codePointBefore(current.length)
            val nextCodePoint = next.codePointAt(0)
            val currentCharacter = currentCodePoint.toBmpCharOrNull()
            val nextCharacter = nextCodePoint.toBmpCharOrNull()

            if (
                nextCharacter != null && nextCharacter in noSpaceBeforeCharacters ||
                currentCharacter != null && currentCharacter in noSpaceAfterCharacters ||
                nextCharacter != null && nextCharacter in inlineJoinCharacters ||
                currentCharacter != null && currentCharacter in inlineJoinCharacters
            ) {
                return ""
            }
            if (
                nextCodePoint.isCombiningOrFormatCharacter() ||
                currentCodePoint == ZERO_WIDTH_JOINER ||
                nextCodePoint == ZERO_WIDTH_JOINER
            ) {
                return ""
            }

            val currentScript = Character.UnicodeScript.of(currentCodePoint)
            val nextScript = Character.UnicodeScript.of(nextCodePoint)
            if (
                nextScript.isContinuousLyricScript() &&
                currentCharacter != null &&
                currentCharacter in noSpaceBeforeCharacters ||
                currentScript.isContinuousLyricScript() &&
                nextCharacter != null &&
                nextCharacter in noSpaceAfterCharacters
            ) {
                return ""
            }
            if (
                currentScript.isContinuousLyricScript() &&
                nextScript.isContinuousLyricScript()
            ) {
                return ""
            }
            // ASCII numbers are commonly embedded directly inside Thai/CJK
            // titles and lyric words (for example "24時間"). Do not create a
            // visual gap that Apple did not publish.
            if (
                currentScript.isContinuousLyricScript() &&
                Character.isDigit(nextCodePoint) ||
                nextScript.isContinuousLyricScript() &&
                Character.isDigit(currentCodePoint)
            ) {
                return ""
            }
            val contraction = next.lowercase(Locale.ROOT)
            if (contraction.startsWith("n't") || contraction.startsWith("n’t")) return ""
            return " "
        }

        private fun Int.toBmpCharOrNull(): Char? =
            takeIf { it <= Char.MAX_VALUE.code }?.toChar()

        private fun Int.isCombiningOrFormatCharacter(): Boolean = when (Character.getType(this)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            Character.FORMAT.toInt() -> true
            else -> false
        }

        private fun Character.UnicodeScript.isContinuousLyricScript(): Boolean = when (this) {
            Character.UnicodeScript.THAI,
            Character.UnicodeScript.LAO,
            Character.UnicodeScript.KHMER,
            Character.UnicodeScript.MYANMAR,
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA -> true
            else -> false
        }

        private fun closeMatch(candidate: String, wanted: String): Boolean =
            candidate == wanted || (
                candidate.isNotEmpty() && wanted.isNotEmpty() &&
                    (candidate.contains(wanted) || wanted.contains(candidate))
                )

        private const val DEFAULT_LAST_TOKEN_DURATION_MS = 520L
        private const val ZERO_WIDTH_JOINER = 0x200D
    }
}
