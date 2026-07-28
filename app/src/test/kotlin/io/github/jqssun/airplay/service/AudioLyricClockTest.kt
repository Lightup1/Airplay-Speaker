package io.github.jqssun.airplay.service

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioLyricClockTest {

    @Test
    fun subtractsOnlyPendingLocalAudio() {
        assertEquals(9_760L, compensateAudioOutputLatency(10_000L, 240L))
        assertEquals(0L, compensateAudioOutputLatency(100L, 240L))
        assertEquals(10_000L, compensateAudioOutputLatency(10_000L, -20L))
    }

    @Test
    fun gentlyCorrectsSmallProgressJitter() {
        assertEquals(10_020L, stabilizeReportedProgress(10_000L, 10_100L))
        assertEquals(10_200L, stabilizeReportedProgress(10_000L, 10_400L))
    }

    @Test
    fun snapsToARealSeekOrTrackChange() {
        assertEquals(24_000L, stabilizeReportedProgress(10_000L, 24_000L))
    }
}
