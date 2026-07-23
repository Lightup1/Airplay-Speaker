package io.github.jqssun.airplay.artwork

import android.graphics.Bitmap
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Downloads the normal album cover used behind Apple Music motion artwork. */
class RemoteArtworkLoader {

    // Motion-artwork lookups normally occur once per track. A tiny cache makes
    // toggling motion artwork or revisiting a recent track instant without
    // holding a gallery of full-size covers in a 2 GB TV's memory.
    private val memoryCache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    suspend fun load(
        url: String,
        targetEdgePx: Int = ArtworkDecoder.TARGET_EDGE_PX,
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (!isTrustedArtworkUrl(url)) return@withContext null
        val cacheKey = "$targetEdgePx\u0000$url"
        memoryCache.get(cacheKey)?.let { return@withContext it }
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "image/*")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                val length = connection.contentLengthLong
                if (length > MAX_ARTWORK_BYTES) return@runCatching null
                val bytes = connection.inputStream.use(::readBoundedArtwork)
                    ?: return@runCatching null
                ArtworkDecoder.decode(bytes, targetEdgePx)?.also { memoryCache.put(cacheKey, it) }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private fun isTrustedArtworkUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val trustedHost = uri.host?.lowercase(Locale.ROOT)?.let {
            it == "mzstatic.com" || it.endsWith(".mzstatic.com")
        } == true
        return uri.scheme == "https" && trustedHost
    }

    /** Reject chunked responses that grow past our memory budget. */
    private fun readBoundedArtwork(input: java.io.InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1_024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_ARTWORK_BYTES) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_ARTWORK_BYTES = 6 * 1024 * 1024
        const val CACHE_BYTES = 6 * 1024 * 1024
        const val USER_AGENT =
            "AirplaySpeaker/0.14.13 (https://github.com/jqssun/android-airplay-server)"
    }
}
