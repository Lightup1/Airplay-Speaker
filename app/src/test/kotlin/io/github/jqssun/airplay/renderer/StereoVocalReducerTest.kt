package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class StereoVocalReducerTest {

    @Test
    fun stronglyReducesCentreVocalsWhenStereoBackingExists() {
        val pcm = mixedStereoFrames(
            centreFrequencyHz = 1_000.0,
            centreAmplitude = 9_000,
            sideFrequencyHz = 330.0,
            sideAmplitude = 5_000,
            frameCount = 8_000,
        )
        val before = averageAbsoluteMid(pcm, skipFrames = 2_000)

        StereoVocalReducer().processInPlace(
            pcm = pcm,
            channels = 2,
            sampleRate = 44_100,
            reduction = 1f,
        )

        assertTrue(averageAbsoluteMid(pcm, skipFrames = 2_000) < before * 0.48f)
    }

    @Test
    fun protectsMonoMusicFromBeingCompletelyHollowedOut() {
        val pcm = sineStereoFrames(1_000.0, 10_000, frameCount = 8_000)
        val before = averageAbsoluteMid(pcm, skipFrames = 2_000)

        StereoVocalReducer().processInPlace(pcm, 2, 44_100, 1f)

        val after = averageAbsoluteMid(pcm, skipFrames = 2_000)
        assertTrue(after > before * 0.35f)
        assertTrue(after < before * 0.8f)
    }

    @Test
    fun preservesMoreHighFrequencyAirThanTheVocalBand() {
        val pcm = sineStereoFrames(10_000.0, 8_000, frameCount = 8_000)
        val before = averageAbsoluteMid(pcm, skipFrames = 2_000)

        StereoVocalReducer().processInPlace(pcm, 2, 44_100, 1f)

        assertTrue(averageAbsoluteMid(pcm, skipFrames = 2_000) > before * 0.65f)
    }

    @Test
    fun preservesCentredBassWhileReducingTheVocalRange() {
        val bassPcm = mixedStereoFrames(
            centreFrequencyHz = 70.0,
            centreAmplitude = 9_000,
            sideFrequencyHz = 420.0,
            sideAmplitude = 4_000,
            frameCount = 12_000,
        )
        val vocalPcm = mixedStereoFrames(
            centreFrequencyHz = 1_400.0,
            centreAmplitude = 9_000,
            sideFrequencyHz = 420.0,
            sideAmplitude = 4_000,
            frameCount = 12_000,
        )
        val bassBefore = averageAbsoluteMid(bassPcm, skipFrames = 3_000)
        val vocalBefore = averageAbsoluteMid(vocalPcm, skipFrames = 3_000)

        StereoVocalReducer().processInPlace(bassPcm, 2, 44_100, 1f)
        StereoVocalReducer().processInPlace(vocalPcm, 2, 44_100, 1f)

        val bassRetention = averageAbsoluteMid(bassPcm, skipFrames = 3_000) / bassBefore
        val vocalRetention = averageAbsoluteMid(vocalPcm, skipFrames = 3_000) / vocalBefore
        assertTrue(bassRetention > 0.72f)
        assertTrue(vocalRetention < bassRetention * 0.65f)
    }

    @Test
    fun vocalLevelProducesAStableMonotonicBlend() {
        val stronger = mixedStereoFrames(
            centreFrequencyHz = 1_000.0,
            centreAmplitude = 9_000,
            sideFrequencyHz = 330.0,
            sideAmplitude = 5_000,
            frameCount = 8_000,
        )
        val gentler = stronger.copyOf()

        StereoVocalReducer().processInPlace(stronger, 2, 44_100, 1f)
        StereoVocalReducer().processInPlace(gentler, 2, 44_100, 0.5f)

        assertTrue(
            averageAbsoluteMid(stronger, skipFrames = 2_000) <
                averageAbsoluteMid(gentler, skipFrames = 2_000)
        )
    }

    @Test
    fun preservesSideOnlyStereoContent() {
        val pcm = stereoFrames(left = 9_000, right = -9_000, frameCount = 512)
        val original = pcm.copyOf()

        StereoVocalReducer().processInPlace(pcm, 2, 44_100, 1f)

        assertArrayEquals(original, pcm)
    }

    @Test
    fun bypassesMonoAndZeroReduction() {
        val mono = byteArrayOf(0x34, 0x12, 0x78, 0x56)
        val monoOriginal = mono.copyOf()
        StereoVocalReducer().processInPlace(mono, 1, 44_100, 1f)
        assertArrayEquals(monoOriginal, mono)

        val stereo = stereoFrames(left = 7_000, right = 2_000, frameCount = 64)
        val stereoOriginal = stereo.copyOf()
        StereoVocalReducer().processInPlace(stereo, 2, 44_100, 0f)
        assertArrayEquals(stereoOriginal, stereo)
    }

    private fun sineStereoFrames(
        frequencyHz: Double,
        amplitude: Int,
        frameCount: Int,
    ): ByteArray {
        val pcm = ByteArray(frameCount * 4)
        repeat(frameCount) { frame ->
            val value = (
                sin(2.0 * PI * frequencyHz * frame / 44_100.0) * amplitude
            ).toInt()
            writeSample(pcm, frame * 4, value)
            writeSample(pcm, frame * 4 + 2, value)
        }
        return pcm
    }

    private fun mixedStereoFrames(
        centreFrequencyHz: Double,
        centreAmplitude: Int,
        sideFrequencyHz: Double,
        sideAmplitude: Int,
        frameCount: Int,
    ): ByteArray {
        val pcm = ByteArray(frameCount * 4)
        repeat(frameCount) { frame ->
            val centre = (
                sin(2.0 * PI * centreFrequencyHz * frame / 44_100.0) * centreAmplitude
            ).toInt()
            val side = (
                sin(2.0 * PI * sideFrequencyHz * frame / 44_100.0) * sideAmplitude
            ).toInt()
            writeSample(pcm, frame * 4, centre + side)
            writeSample(pcm, frame * 4 + 2, centre - side)
        }
        return pcm
    }

    private fun stereoFrames(left: Int, right: Int, frameCount: Int): ByteArray {
        val pcm = ByteArray(frameCount * 4)
        repeat(frameCount) { frame ->
            writeSample(pcm, frame * 4, left)
            writeSample(pcm, frame * 4 + 2, right)
        }
        return pcm
    }

    private fun averageAbsoluteMid(data: ByteArray, skipFrames: Int): Float {
        var sum = 0L
        var samples = 0
        var offset = (skipFrames * 4).coerceAtMost(data.size)
        while (offset + 3 < data.size) {
            val mid = (sampleAt(data, offset) + sampleAt(data, offset + 2)) / 2
            sum += abs(mid).toLong()
            samples++
            offset += 4
        }
        return if (samples == 0) 0f else sum.toFloat() / samples
    }

    private fun writeSample(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xff).toByte()
        data[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun sampleAt(data: ByteArray, offset: Int): Int =
        (((data[offset + 1].toInt() and 0xff) shl 8) or
            (data[offset].toInt() and 0xff)).toShort().toInt()
}
