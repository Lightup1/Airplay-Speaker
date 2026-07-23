package io.github.jqssun.airplay.lyrics

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Small client for the open-source LyricsPlus v2 endpoint. Word timestamps are
 * preferred, while time-coded text without a word breakdown is retained so a
 * partially timed provider response never drops a verse from the TV surface.
 */
class LyricsPlusClient {

    suspend fun findPrecise(request: LyricsRequest): LyricsState? = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildString {
                append("$BASE_URL/v2/lyrics/get")
                append(query(
                    "title" to request.title,
                    "artist" to request.artist,
                    "album" to request.album,
                    "duration" to (request.durationMs / 1000.0).toString(),
                ))
            }
            val response = request(url) ?: return@withContext null
            parseResponse(response)
        }.getOrNull()
    }

    private fun request(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
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

    companion object {
        private const val BASE_URL = "https://lyricsplus.prjktla.my.id"
        private const val USER_AGENT =
            "AirplaySpeaker/0.14.13 (https://github.com/jqssun/android-airplay-server)"

        internal fun parseResponse(value: String): LyricsState? {
            val root = runCatching { JSONObject(value) }.getOrNull() ?: return null
            val rawLines = root.optJSONArray("lyrics") ?: return null
            val lines = buildList {
                for (lineIndex in 0 until rawLines.length()) {
                    val rawLine = rawLines.optJSONObject(lineIndex) ?: continue
                    val timeMs = rawLine.optLong("time", -1L)
                    val durationMs = rawLine.optLong("duration", 0L)
                    val text = rawLine.optString("text").trimEnd()
                    // LyricsPlus responses have used both spellings. Keep the
                    // alternate form so a provider update cannot silently turn
                    // word-synchronised lyrics into an incomplete line.
                    val syllables = rawLine.optJSONArray("syllabus")
                        ?: rawLine.optJSONArray("syllables")
                    if (timeMs < 0L || text.isBlank()) continue

                    val words = buildList {
                        for (wordIndex in 0 until (syllables?.length() ?: 0)) {
                            val rawWord = syllables?.optJSONObject(wordIndex) ?: continue
                            val startMs = rawWord.optLong("time", -1L)
                            val wordDuration = rawWord.optLong("duration", 0L)
                            val wordText = rawWord.optString("text")
                            if (startMs < 0L || wordText.isEmpty()) continue
                            val nextStart = syllables?.optJSONObject(wordIndex + 1)
                                ?.optLong("time", -1L)
                                ?.takeIf { it >= startMs }
                            val lineEnd = timeMs + durationMs.coerceAtLeast(1L)
                            val endMs = when {
                                wordDuration > 0L -> startMs + wordDuration
                                nextStart != null -> nextStart
                                else -> lineEnd
                            }.coerceAtLeast(startMs + 1L)
                            add(LyricWord(wordText, startMs, endMs))
                        }
                    }
                    val completeWordTimeline = words.takeIf {
                        lyricWordsMatchLine(text, it)
                    }.orEmpty()
                    add(
                        LyricLine(
                            timeMs = timeMs,
                            text = text,
                            words = completeWordTimeline,
                            exactWordTiming = completeWordTimeline.isNotEmpty(),
                            endMs = (timeMs + durationMs).takeIf { durationMs > 0L },
                        )
                    )
                }
            }.sortedBy { it.timeMs }

            if (lines.isEmpty()) return null
            return LyricsState(
                status = LyricsStatus.SYNCED,
                lines = lines,
                plainLyrics = lines.joinToString("\n") { it.text },
                wordSyncMode = if (lines.any { it.exactWordTiming }) {
                    WordSyncMode.ENHANCED
                } else {
                    WordSyncMode.ESTIMATED
                },
            )
        }
    }
}
