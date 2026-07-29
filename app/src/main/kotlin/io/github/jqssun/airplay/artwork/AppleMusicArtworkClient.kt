package io.github.jqssun.airplay.artwork

import io.github.jqssun.airplay.BuildConfig
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AnimatedArtworkRequest(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

data class AnimatedArtworkResult(
    val motionUrl: String,
    val staticArtworkUrl: String?,
)

/**
 * Resolves Apple Music's public square motion artwork for the current album.
 * The video remains hosted by Apple and is streamed by Media3; it is never
 * copied into the APK or persisted by Airplay Speaker.
 */
class AppleMusicArtworkClient {

    /** Resolves a normal sleeve without fetching or parsing the album page. */
    suspend fun findStaticArtwork(request: AnimatedArtworkRequest): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                findCollection(request)?.artworkUrl?.let(::highResolutionArtworkUrl)
            }.getOrNull()
        }

    suspend fun findSquareMotionArtwork(request: AnimatedArtworkRequest): AnimatedArtworkResult? =
        withContext(Dispatchers.IO) {
            runCatching {
                val collection = findCollection(request) ?: return@withContext null
                val albumPage = requestText("$APPLE_MUSIC_ALBUM_URL/${collection.id}")
                    ?: return@withContext null
                val motionUrl = parseSquareMotionUrl(albumPage) ?: return@withContext null
                AnimatedArtworkResult(
                    motionUrl = motionUrl,
                    staticArtworkUrl = highResolutionArtworkUrl(collection.artworkUrl),
                )
            }.getOrNull()
        }

    private data class CollectionMatch(
        val id: Long,
        val artworkUrl: String,
    )

    private fun findCollection(request: AnimatedArtworkRequest): CollectionMatch? {
        val queryText = listOf(request.title, request.artist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (queryText.isBlank()) return null

        val response = requestText(
            buildString {
                append(ITUNES_SEARCH_URL)
                append("?term=")
                append(encode(queryText))
                append("&entity=song&country=TH&limit=25")
            }
        ) ?: return null
        val results = runCatching { JSONObject(response).optJSONArray("results") }.getOrNull()
            ?: return null

        val wantedTitle = normalize(request.title)
        val wantedArtist = normalize(request.artist)
        val wantedAlbum = normalize(request.album)
        val wantedDuration = request.durationMs.takeIf { it > 0L }
        var bestMatch: CollectionMatch? = null
        var bestScore = Int.MIN_VALUE

        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            val id = item.optLong("collectionId", -1L).takeIf { it > 0L } ?: continue
            val title = normalize(item.optString("trackName"))
            val artist = normalize(item.optString("artistName"))
            val album = normalize(item.optString("collectionName"))
            if (!closeMatch(title, wantedTitle) || !closeMatch(artist, wantedArtist)) continue

            var score = 100
            if (title == wantedTitle) score += 100
            if (artist == wantedArtist) score += 70
            if (wantedAlbum.isNotEmpty()) {
                score += when {
                    album == wantedAlbum -> 140
                    closeMatch(album, wantedAlbum) -> 45
                    else -> -35
                }
            }
            val candidateDuration = item.optLong("trackTimeMillis", -1L)
            if (wantedDuration != null && candidateDuration > 0L) {
                val difference = kotlin.math.abs(candidateDuration - wantedDuration)
                score += when {
                    difference <= 2_000L -> 45
                    difference <= 6_000L -> 20
                    difference > 15_000L -> -50
                    else -> 0
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestMatch = CollectionMatch(
                    id = id,
                    artworkUrl = item.optString("artworkUrl100"),
                )
            }
        }
        return bestMatch
    }

    private fun requestText(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "th-TH,th;q=0.9,en;q=0.8")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun closeMatch(candidate: String, wanted: String): Boolean =
        candidate == wanted || (
            candidate.isNotEmpty() && wanted.isNotEmpty() &&
                (candidate.contains(wanted) || wanted.contains(candidate))
            )

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("\\([^)]*(?:feat|ft\\.|remaster)[^)]*\\)"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        private const val ITUNES_SEARCH_URL = "https://itunes.apple.com/search"
        private const val APPLE_MUSIC_ALBUM_URL = "https://music.apple.com/th/album"
        private val USER_AGENT =
            "AirplaySpeaker/${BuildConfig.VERSION_NAME} " +
                "(https://github.com/l3kza08/Airplay-Speaker)"

        private val motionDictionaryPattern = Regex(
            """motionDetailSquare.{0,16000}?\"video\"\s*:\s*\{.{0,6000}?\"url\"\s*:\s*\"([^\"]+?\.m3u8[^\"]*)\"""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val ambientVideoPattern = Regex(
            """data-testid=\"video-artwork\".{0,3000}?<amp-ambient-video[^>]+src=\"([^\"]+?\.m3u8[^\"]*)\"""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )

        internal fun parseSquareMotionUrl(html: String): String? {
            val raw = motionDictionaryPattern.find(html)?.groupValues?.getOrNull(1)
                ?: ambientVideoPattern.find(html)?.groupValues?.getOrNull(1)
                ?: return null
            val decoded = raw
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("&amp;", "&")
            val uri = runCatching { URI(decoded) }.getOrNull() ?: return null
            val trustedHost = uri.host?.lowercase(Locale.ROOT)?.let {
                it == "itunes.apple.com" || it.endsWith(".itunes.apple.com")
            } == true
            return decoded.takeIf { uri.scheme == "https" && trustedHost }
        }

        internal fun highResolutionArtworkUrl(value: String): String? {
            if (value.isBlank()) return null
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            val trustedHost = uri.host?.lowercase(Locale.ROOT)?.let {
                it == "mzstatic.com" || it.endsWith(".mzstatic.com")
            } == true
            if (uri.scheme != "https" || !trustedHost) return null
            return value.replace(
                Regex("/\\d+x\\d+bb(?=\\.)", RegexOption.IGNORE_CASE),
                "/1200x1200bb",
            )
        }
    }
}
