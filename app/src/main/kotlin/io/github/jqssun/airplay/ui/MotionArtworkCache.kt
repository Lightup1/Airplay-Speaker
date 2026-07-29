@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.jqssun.airplay.ui

import android.content.Context
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.jqssun.airplay.BuildConfig

/**
 * Small shared disk cache for looping animated artwork.
 *
 * Motion covers are short VOD clips. Keeping their HLS segments locally after
 * the first pass prevents each loop from depending on network timing, which
 * is especially visible as a pause on entry-level Android TV hardware.
 */
internal object MotionArtworkCache {
    private const val MAX_CACHE_BYTES = 64L * 1024L * 1024L
    private const val HTTP_TIMEOUT_MS = 12_000

    @Volatile
    private var cache: SimpleCache? = null

    fun mediaSourceFactory(context: Context): DefaultMediaSourceFactory {
        val appContext = context.applicationContext
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent("Airplay-Speaker/${BuildConfig.VERSION_NAME}")
            .setConnectTimeoutMs(HTTP_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
        val cachedData = CacheDataSource.Factory()
            .setCache(cache(appContext))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(cachedData)
    }

    @Synchronized
    private fun cache(context: Context): SimpleCache {
        cache?.let { return it }
        return SimpleCache(
            context.cacheDir.resolve("motion-artwork"),
            LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
        ).also { cache = it }
    }
}
