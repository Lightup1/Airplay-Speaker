package io.github.jqssun.airplay.lyrics

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Plain-text last resort so a synchronized provider outage never leaves an empty panel. */
class LyricsOvhClient {

    suspend fun find(request: LyricsRequest): LyricsState? = withContext(Dispatchers.IO) {
        val titles = listOf(request.title, cleanVersionSuffix(request.title)).distinct()
        titles.firstNotNullOfOrNull { title ->
            runCatching { fetch(request.artist, title) }.getOrNull()
        }
    }

    private fun fetch(artist: String, title: String): LyricsState? {
        if (artist.isBlank() || title.isBlank()) return null
        val url = "$BASE_URL/v1/${encodePath(artist)}/${encodePath(title)}"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 9_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            if (connection.contentLengthLong > MAX_RESPONSE_BYTES) return null
            val response = connection.inputStream.use(::readBoundedUtf8) ?: return null
            val lyrics = runCatching { JSONObject(response).optString("lyrics") }
                .getOrNull()
                ?.replace("\r\n", "\n")
                ?.trim()
                .orEmpty()
            lyrics.takeIf { it.isNotBlank() }?.let {
                LyricsState(status = LyricsStatus.PLAIN, plainLyrics = it)
            }
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

    private fun encodePath(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun cleanVersionSuffix(value: String): String = value
        .replace(versionSuffix, " ")
        .replace(whitespace, " ")
        .trim()

    private companion object {
        const val BASE_URL = "https://api.lyrics.ovh"
        const val USER_AGENT =
            "AirplaySpeaker/0.14.13 (https://github.com/jqssun/android-airplay-server)"
        const val MAX_RESPONSE_BYTES = 256 * 1_024
        val whitespace = Regex("\\s+")
        val versionSuffix = Regex(
            "[\\[(][^\\])]*(?:remaster|version|edit|mix)[^\\])]*[\\])]",
            RegexOption.IGNORE_CASE,
        )
    }
}
