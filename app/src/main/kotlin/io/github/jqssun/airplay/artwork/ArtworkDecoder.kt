package io.github.jqssun.airplay.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Decodes receiver artwork at the size the TV interface can actually display.
 *
 * AirPlay senders are free to send a several-thousand-pixel JPEG/PNG. Keeping
 * that bitmap at full size can briefly consume tens of megabytes on low-memory
 * TVs, even though the Now Playing cover is never rendered anywhere near that
 * large. Bounds are read first and the actual decode is sampled down.
 */
internal object ArtworkDecoder {
    const val TARGET_EDGE_PX = 960
    private const val MAX_SOURCE_PIXELS = 64L * 1_024L * 1_024L

    fun decode(bytes: ByteArray, targetEdgePx: Int = TARGET_EDGE_PX): Bitmap? {
        if (bytes.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (!isSafeSource(bounds.outWidth, bounds.outHeight)) return null

        return runCatching {
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetEdgePx)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        }.getOrNull()
    }

    /** Pure so timing/size decisions can be unit-tested without an Android runtime. */
    internal fun sampleSizeFor(width: Int, height: Int, targetEdgePx: Int = TARGET_EDGE_PX): Int {
        if (width <= 0 || height <= 0 || targetEdgePx <= 0) return 1
        var sample = 1
        while (width / sample > targetEdgePx || height / sample > targetEdgePx) {
            sample *= 2
        }
        return sample
    }

    private fun isSafeSource(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height.toLong() <= MAX_SOURCE_PIXELS
}
