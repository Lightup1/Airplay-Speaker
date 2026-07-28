package io.github.jqssun.airplay.renderer

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Lightweight stereo-width processing for entry-level Android TV hardware.
 *
 * The mid channel stays at unity while the side channel is widened. This
 * leaves mono material unchanged, avoids artificial echo, and uses no extra
 * per-packet allocations on the audio thread.
 */
internal class StereoSpatialEnhancer(
    private val width: Float = 1.28f,
) {
    fun processInPlace(
        pcm: ByteArray,
        channels: Int,
    ) {
        if (channels != 2 || pcm.size < STEREO_FRAME_BYTES) return
        val end = pcm.size - (pcm.size % STEREO_FRAME_BYTES)
        var offset = 0
        while (offset < end) {
            val left = readLittleEndianShort(pcm, offset)
            val right = readLittleEndianShort(pcm, offset + Short.SIZE_BYTES)
            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f * width
            var widenedLeft = mid + side
            var widenedRight = mid - side

            // Preserve the balance between channels when a rare peak exceeds
            // the signed 16-bit range rather than clipping each side alone.
            val peak = maxOf(abs(widenedLeft), abs(widenedRight))
            if (peak > Short.MAX_VALUE.toFloat()) {
                val limiter = Short.MAX_VALUE / peak
                widenedLeft *= limiter
                widenedRight *= limiter
            }

            writeLittleEndianShort(pcm, offset, widenedLeft.roundToInt())
            writeLittleEndianShort(
                pcm,
                offset + Short.SIZE_BYTES,
                widenedRight.roundToInt(),
            )
            offset += STEREO_FRAME_BYTES
        }
    }

    private fun readLittleEndianShort(data: ByteArray, offset: Int): Int =
        (((data[offset + 1].toInt() and 0xff) shl 8) or
            (data[offset].toInt() and 0xff)).toShort().toInt()

    private fun writeLittleEndianShort(data: ByteArray, offset: Int, value: Int) {
        val sample = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        data[offset] = (sample and 0xff).toByte()
        data[offset + 1] = ((sample ushr 8) and 0xff).toByte()
    }

    private companion object {
        const val STEREO_FRAME_BYTES = 2 * Short.SIZE_BYTES
    }
}
