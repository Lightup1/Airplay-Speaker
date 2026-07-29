package io.github.jqssun.airplay.renderer

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmAudioLevelAnalyzerTest {

    @Test
    fun `bass and treble bars follow their actual pcm bands`() {
        val bassAnalyzer = PcmAudioLevelAnalyzer()
        bassAnalyzer.analyzePcm16(
            pcm = stereoSine(frequencyHz = 105.0),
            channels = 2,
            sampleRate = SAMPLE_RATE,
            gain = 1f,
            capturedAtMs = 1_000L,
        )
        val bass = bassAnalyzer.snapshot(1_010L)

        val trebleAnalyzer = PcmAudioLevelAnalyzer()
        trebleAnalyzer.analyzePcm16(
            pcm = stereoSine(frequencyHz = 5_200.0),
            channels = 2,
            sampleRate = SAMPLE_RATE,
            gain = 1f,
            capturedAtMs = 1_000L,
        )
        val treble = trebleAnalyzer.snapshot(1_010L)

        assertTrue("bass should lead its high band: $bass", bass.low > bass.high)
        assertTrue("treble should lead its low band: $treble", treble.high > treble.low)
    }

    @Test
    fun `silence mute reset and stale pcm all stop the meter`() {
        val analyzer = PcmAudioLevelAnalyzer()
        analyzer.analyzePcm16(
            pcm = stereoSine(frequencyHz = 440.0),
            channels = 2,
            sampleRate = SAMPLE_RATE,
            gain = 1f,
            capturedAtMs = 2_000L,
        )
        assertTrue(analyzer.snapshot(2_010L).mid > 0f)

        analyzer.analyzePcm16(
            pcm = stereoSine(frequencyHz = 440.0),
            channels = 2,
            sampleRate = SAMPLE_RATE,
            gain = 0f,
            capturedAtMs = 2_020L,
        )
        assertEquals(AudioMeterLevels.Silent, analyzer.snapshot(2_030L))

        analyzer.analyzePcm16(
            pcm = stereoSine(frequencyHz = 440.0),
            channels = 2,
            sampleRate = SAMPLE_RATE,
            gain = 1f,
            capturedAtMs = 3_000L,
        )
        assertEquals(AudioMeterLevels.Silent, analyzer.snapshot(3_400L))

        analyzer.reset()
        assertEquals(AudioMeterLevels.Silent, analyzer.snapshot(4_000L))
    }

    private fun stereoSine(
        frequencyHz: Double,
        frames: Int = 8_192,
        amplitude: Double = 0.72,
    ): ByteArray {
        val pcm = ByteArray(frames * 2 * Short.SIZE_BYTES)
        repeat(frames) { frame ->
            val sample = (
                sin(2.0 * PI * frequencyHz * frame / SAMPLE_RATE) *
                    Short.MAX_VALUE *
                    amplitude
                ).roundToInt()
            val offset = frame * 2 * Short.SIZE_BYTES
            writeLittleEndianShort(pcm, offset, sample)
            writeLittleEndianShort(pcm, offset + Short.SIZE_BYTES, sample)
        }
        return pcm
    }

    private fun writeLittleEndianShort(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xff).toByte()
        data[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
