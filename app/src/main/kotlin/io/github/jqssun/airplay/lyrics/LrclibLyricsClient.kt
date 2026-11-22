package io.github.jqssun.airplay.lyrics

import io.github.jqssun.airplay.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fallback lyric source backed by LRCLIB (https://lrclib.net), a free and open
 * lyrics database. Used when the Apple Music lyric proxy is unavailable or has
 * no lyrics for a track. LRCLIB publishes line timing (LRC), so results are
 * presented as line-synced lyrics; no word timing is invented.
 */
class LrclibLyricsClient {

    suspend fun find(request: LyricsRequest): LyricsState? = withContext(Dispatchers.IO) {
        runCatching { findUncached(request) }.getOrNull()
    }

    private fun findUncached(request: LyricsRequest): LyricsState? {
        val durationSec = (request.durationMs + 500) / 1000
        val title = request.title.trim()
        val artist = request.artist.trim()
        if (title.isEmpty() || artist.isEmpty()) return null

        // 1. Exact signature lookup (title + artist + album + duration).
        val exact = if (request.album.isNotBlank() && durationSec > 0) {
            requestText(
                "$BASE_URL/api/get" + query(
                    "track_name" to title,
                    "artist_name" to artist,
                    "album_name" to request.album.trim(),
                    "duration" to durationSec.toString(),
                ),
            )?.let { runCatching { JSONObject(it) }.getOrNull() }
        } else {
            null
        }
        exact?.let(::toState)?.takeIf { it.status != LyricsStatus.NOT_FOUND }?.let { return it }

        // 2. Search by title + artist and pick the closest duration match,
        //    preferring entries that carry synced lyrics.
        val results = requestText(
            "$BASE_URL/api/search" + query(
                "track_name" to title,
                "artist_name" to artist,
            ),
        )?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return null

        val candidates = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
        val best = candidates
            .filter { candidate ->
                durationSec <= 0 ||
                    abs(candidate.optDouble("duration", 0.0) - durationSec) <= MAX_DURATION_DIFF_SEC
            }
            .sortedWith(
                compareByDescending<JSONObject> { it.optStringOrEmpty("syncedLyrics").isNotBlank() }
                    .thenBy { abs(it.optDouble("duration", 0.0) - durationSec) }
            )
            .firstOrNull() ?: return null
        return toState(best)?.takeIf { it.status != LyricsStatus.NOT_FOUND }
    }

    private fun toState(json: JSONObject): LyricsState? {
        val id = json.optLong("id").takeIf { it > 0 }
        if (json.optBoolean("instrumental", false)) {
            return LyricsState(status = LyricsStatus.INSTRUMENTAL, sourceId = id)
        }
        val synced = json.optStringOrEmpty("syncedLyrics")
        val plain = json.optStringOrEmpty("plainLyrics")
        val lines = parseLrc(synced)
        return when {
            lines.isNotEmpty() -> LyricsState(
                status = LyricsStatus.SYNCED,
                lines = lines,
                plainLyrics = plain,
                sourceId = id,
                wordSyncMode = WordSyncMode.LINE,
            )
            plain.isNotBlank() -> LyricsState(
                status = LyricsStatus.PLAIN,
                plainLyrics = plain,
                sourceId = id,
            )
            else -> LyricsState(status = LyricsStatus.NOT_FOUND)
        }
    }

    private fun JSONObject.optStringOrEmpty(key: String): String =
        if (isNull(key)) "" else optString(key, "")

    private fun requestText(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) return null
                    out.write(buffer, 0, read)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } catch (_: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun query(vararg pairs: Pair<String, String>): String = pairs
        .joinToString(separator = "&", prefix = "?") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        private const val BASE_URL = "https://lrclib.net"
        private val USER_AGENT =
            "AirplaySpeaker/${BuildConfig.VERSION_NAME} (LRCLIB fallback)"
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 8_000
        private const val MAX_BYTES = 1024 * 1024
        private const val MAX_DURATION_DIFF_SEC = 3.0
        private const val MAX_LINES = 750

        private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

        /**
         * Parses LRC text into timed rows. Blank timed rows mark the end of the
         * previous line (instrumental gaps) and are not shown as lyrics.
         */
        internal fun parseLrc(lrc: String): List<LyricLine> {
            if (lrc.isBlank()) return emptyList()
            data class Row(val timeMs: Long, val text: String)
            val rows = mutableListOf<Row>()
            lrc.lineSequence().forEach { raw ->
                val tags = TIME_TAG.findAll(raw).toList()
                if (tags.isEmpty()) return@forEach
                // Text follows the last leading time tag.
                val text = raw.substring(tags.last().range.last + 1).trim()
                tags.forEach { tag ->
                    val minutes = tag.groupValues[1].toLong()
                    val seconds = tag.groupValues[2].toLong()
                    val fraction = tag.groupValues[3]
                    val fractionMs = when (fraction.length) {
                        0 -> 0L
                        1 -> fraction.toLong() * 100
                        2 -> fraction.toLong() * 10
                        else -> fraction.take(3).toLong()
                    }
                    rows += Row(minutes * 60_000 + seconds * 1_000 + fractionMs, text)
                }
            }
            val sorted = rows.sortedBy { it.timeMs }
            val lines = mutableListOf<LyricLine>()
            sorted.forEachIndexed { index, row ->
                if (row.text.isEmpty()) return@forEachIndexed
                val nextTime = sorted.getOrNull(index + 1)?.timeMs?.takeIf { it > row.timeMs }
                lines += LyricLine(
                    timeMs = row.timeMs,
                    text = row.text,
                    endMs = nextTime,
                )
            }
            return lines.take(MAX_LINES)
        }
    }
}
