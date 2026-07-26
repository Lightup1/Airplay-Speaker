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
}
