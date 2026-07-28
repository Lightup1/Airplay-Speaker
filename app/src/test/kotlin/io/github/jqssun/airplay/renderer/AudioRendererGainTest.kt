package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRendererGainTest {

    @Test
    fun fullAppVolumeKeepsLosslessPcmAtUnity() {
        assertEquals(1f, resolveTrackGain(1f, 1f))
    }

    @Test
    fun airPlayControllerAttenuatesInsideTheApp() {
        assertEquals(0.2f, resolveTrackGain(0.2f, 1f))
    }

    @Test
    fun appVolumeAttenuatesWithoutChangingSystemVolume() {
        assertEquals(0.35f, resolveTrackGain(1f, 0.35f))
    }

    @Test
    fun senderMuteStillWins() {
        assertEquals(0f, resolveTrackGain(0f, 0.8f))
    }

    @Test
    fun audioQueueSmoothingCannotMoveLyricsBackDuringNormalPlayback() {
        val previousQueueMs = 180L
        val elapsedMs = 40L
        val smoothed = smoothAudioQueueEstimate(
            previousMs = previousQueueMs,
            measuredMs = 280L,
            elapsedMs = elapsedMs,
            initialized = true,
        )

        // The media clock advances 40 ms while the compensated queue can grow
        // by at most 34 ms, leaving a forward-moving visible lyric position.
        assertEquals(214L, smoothed)
    }

    @Test
    fun firstAudioQueueSampleUsesTheRealMeasurement() {
        assertEquals(
            240L,
            smoothAudioQueueEstimate(
                previousMs = 0L,
                measuredMs = 240L,
                elapsedMs = 40L,
                initialized = false,
            ),
        )
    }
}
