package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoSpatialEnhancerTest {

    @Test
    fun leavesMonoFramesUnchanged() {
        val pcm = stereoFrame(left = 12_000, right = 12_000)

        StereoSpatialEnhancer().processInPlace(pcm, channels = 2)

        assertEquals(12_000, sampleAt(pcm, 0))
        assertEquals(12_000, sampleAt(pcm, 2))
    }

    @Test
    fun increasesStereoDifferenceWithoutChangingTheMidpoint() {
        val pcm = stereoFrame(left = 14_000, right = 2_000)
        val originalDifference = 12_000
        val originalMidpoint = 8_000

        StereoSpatialEnhancer().processInPlace(pcm, channels = 2)

        val left = sampleAt(pcm, 0)
        val right = sampleAt(pcm, 2)
        assertTrue(left - right > originalDifference)
        assertEquals(originalMidpoint, (left + right) / 2)
    }

    @Test
    fun bypassesNonStereoPcm() {
        val pcm = byteArrayOf(0x34, 0x12, 0x78, 0x56)
        val original = pcm.copyOf()

        StereoSpatialEnhancer().processInPlace(pcm, channels = 1)

        assertArrayEquals(original, pcm)
    }

    @Test
    fun keepsPeakSamplesInsideSixteenBitRange() {
        val pcm = stereoFrame(left = Short.MAX_VALUE.toInt(), right = Short.MIN_VALUE.toInt())

        StereoSpatialEnhancer().processInPlace(pcm, channels = 2)

        assertTrue(sampleAt(pcm, 0) in Short.MIN_VALUE..Short.MAX_VALUE)
        assertTrue(sampleAt(pcm, 2) in Short.MIN_VALUE..Short.MAX_VALUE)
    }

    private fun stereoFrame(left: Int, right: Int): ByteArray = byteArrayOf(
        (left and 0xff).toByte(),
        ((left ushr 8) and 0xff).toByte(),
        (right and 0xff).toByte(),
        ((right ushr 8) and 0xff).toByte(),
    )

    private fun sampleAt(data: ByteArray, offset: Int): Int =
        (((data[offset + 1].toInt() and 0xff) shl 8) or
            (data[offset].toInt() and 0xff)).toShort().toInt()
}
