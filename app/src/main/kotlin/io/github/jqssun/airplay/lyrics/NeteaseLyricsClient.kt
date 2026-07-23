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
 * Fast synchronized-lyrics fallback backed by the public NetEase Music API.
 *
 * The provider can return YRC (word timestamps) as well as regular LRC. The
 * normal LRC path remains important because the word-timed catalogue is not
 * complete for every language or older release.
 */
class NeteaseLyricsClient {

    suspend fun find(request: LyricsRequest): LyricsState? = withContext(Dispatchers.IO) {
        runCatching {
            val searchResponse = request(
                "$BASE_URL/api/search/get" + query(
                    "s" to "${request.artist} ${request.title}",
                    "type" to "1",
                    "limit" to "12",
                )
            ) ?: return@withContext null
            val songId = selectSongId(searchResponse, request) ?: return@withContext null
            val lyricsResponse = request(
                "$BASE_URL/api/song/lyric" + query(
                    "id" to songId.toString(),
                    "lv" to "1",
                    "kv" to "1",
                    "tv" to "-1",
                    "yv" to "1",
                )
            ) ?: return@withContext null
            parseLyricsResponse(lyricsResponse, request.durationMs, songId)
        }.getOrNull()
    }

    private fun request(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 9_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", BASE_URL)
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            if (connection.contentLengthLong > MAX_RESPONSE_BYTES) return null
            connection.inputStream.use(::readBoundedUtf8)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBoundedUtf8(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1_024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) return null
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
        private const val BASE_URL = "https://music.163.com"
        private const val USER_AGENT =
            "AirplaySpeaker/0.14.13 (https://github.com/jqssun/android-airplay-server)"
        private const val MAX_RESPONSE_BYTES = 512 * 1_024
        private const val MAX_LINES = 750
        private const val MAX_WORDS_PER_LINE = 120

        private val yrcLineTag = Regex("^\\[(\\d+),(\\d+)](.*)$")
        private val yrcWordTag = Regex("\\((\\d+),(\\d+),-?\\d+\\)")
        private val versionNoise = Regex(
            "[\\[(][^\\])]*(?:feat|ft\\.|remaster|version|edit|mix)[^\\])]*[\\])]",
            RegexOption.IGNORE_CASE,
        )
        private val nonAlphaNumeric = Regex("[^\\p{L}\\p{N}]+")

        internal fun selectSongId(value: String, request: LyricsRequest): Long? {
            val root = runCatching { JSONObject(value) }.getOrNull() ?: return null
            val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return null
            val wantedTitle = normalize(request.title)
            val wantedArtist = normalize(request.artist)
            val wantedDuration = request.durationMs
            var bestId: Long? = null
            var bestScore = Int.MIN_VALUE

            for (index in 0 until songs.length()) {
                val song = songs.optJSONObject(index) ?: continue
                val title = normalize(song.optString("name"))
                val artists = song.optJSONArray("artists") ?: song.optJSONArray("ar")
                val artist = normalize(artists.artistNames())
                val titleMatches = title == wantedTitle ||
                    (title.isNotBlank() && wantedTitle.isNotBlank() &&
                        (title.contains(wantedTitle) || wantedTitle.contains(title)))
                val artistMatches = artist == wantedArtist ||
                    (artist.isNotBlank() && wantedArtist.isNotBlank() &&
                        (artist.contains(wantedArtist) || wantedArtist.contains(artist)))
                if (!titleMatches || !artistMatches) continue

                val duration = song.optLong("duration", song.optLong("dt", 0L))
                val durationDifference = if (wantedDuration > 0L && duration > 0L) {
                    kotlin.math.abs(wantedDuration - duration)
                } else {
                    0L
                }
                if (durationDifference > 25_000L) continue

                var score = 100
                if (title == wantedTitle) score += 100
                if (artist == wantedArtist) score += 80
                score += when {
                    durationDifference <= 2_000L -> 60
                    durationDifference <= 6_000L -> 30
                    durationDifference <= 12_000L -> 10
                    else -> 0
                }
                if (score > bestScore) {
                    val id = song.optLong("id", -1L)
                    if (id > 0L) {
                        bestScore = score
                        bestId = id
                    }
                }
            }
            return bestId
        }

        internal fun parseLyricsResponse(
            value: String,
            durationMs: Long,
            songId: Long,
        ): LyricsState? {
            val root = runCatching { JSONObject(value) }.getOrNull() ?: return null
            val yrc = root.optJSONObject("yrc")?.optString("lyric").orEmpty()
            val wordTimed = parseYrc(yrc)
            if (wordTimed.isNotEmpty()) {
                return LyricsState(
                    status = LyricsStatus.SYNCED,
                    lines = wordTimed,
                    plainLyrics = wordTimed.joinToString("\n") { it.text },
                    sourceId = songId,
                    wordSyncMode = WordSyncMode.ENHANCED,
                )
            }

            val lrc = root.optJSONObject("lrc")?.optString("lyric").orEmpty()
            val lineTimed = LrclibClient.parseLrc(lrc, durationMs)
            if (lineTimed.isNotEmpty()) {
                return LyricsState(
                    status = LyricsStatus.SYNCED,
                    lines = lineTimed,
                    plainLyrics = lineTimed.joinToString("\n") { it.text },
                    sourceId = songId,
                    wordSyncMode = if (lineTimed.any { it.exactWordTiming }) {
                        WordSyncMode.ENHANCED
                    } else {
                        WordSyncMode.ESTIMATED
                    },
                )
            }
            return null
        }

        internal fun parseYrc(value: String): List<LyricLine> = value.lineSequence()
            .mapNotNull(::parseYrcLine)
            .take(MAX_LINES)
            .sortedBy { it.timeMs }
            .toList()

        private fun parseYrcLine(value: String): LyricLine? {
            val match = yrcLineTag.matchEntire(value.trim()) ?: return null
            val lineStart = match.groupValues[1].toLongOrNull() ?: return null
            val lineDuration = match.groupValues[2].toLongOrNull() ?: return null
            val body = match.groupValues[3]
            val markers = yrcWordTag.findAll(body).take(MAX_WORDS_PER_LINE).toList()
            if (markers.isEmpty()) return null

            val words = markers.mapIndexedNotNull { index, marker ->
                val rawStart = marker.groupValues[1].toLongOrNull() ?: return@mapIndexedNotNull null
                val duration = marker.groupValues[2].toLongOrNull() ?: return@mapIndexedNotNull null
                val textStart = marker.range.last + 1
                val textEnd = markers.getOrNull(index + 1)?.range?.first ?: body.length
                if (textEnd < textStart) return@mapIndexedNotNull null
                val text = body.substring(textStart, textEnd)
                if (text.isEmpty()) return@mapIndexedNotNull null
                val start = if (rawStart + 1_000L < lineStart) lineStart + rawStart else rawStart
                LyricWord(text, start, start + duration.coerceAtLeast(1L))
            }
            val text = words.joinToString("") { it.text }.trimEnd()
            if (text.isBlank()) return null
            return LyricLine(
                timeMs = lineStart,
                text = text,
                words = words,
                exactWordTiming = true,
                endMs = lineStart + lineDuration.coerceAtLeast(1L),
            )
        }

        private fun JSONArray?.artistNames(): String {
            if (this == null) return ""
            return buildList {
                for (index in 0 until length()) {
                    optJSONObject(index)?.optString("name")
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }.joinToString(" ")
        }

        private fun normalize(value: String): String = value
            .lowercase(Locale.ROOT)
            .replace(versionNoise, "")
            .replace(nonAlphaNumeric, "")
    }
}
