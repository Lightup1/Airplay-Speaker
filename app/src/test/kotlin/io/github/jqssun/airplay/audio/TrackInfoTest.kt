package io.github.jqssun.airplay.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackInfoTest {

    @Test
    fun dmapComposerIsAvailableForEndOfLyricsCredit() {
        val track = TrackInfo.fromDmap(
            mapOf(
                "mlit" to mapOf(
                    "minm" to "Tiny Dancer",
                    "asar" to "Elton John",
                    "ascm" to "Elton John, Bernie Taupin",
                )
            )
        )

        assertEquals("Tiny Dancer", track.title)
        assertEquals("Elton John, Bernie Taupin", track.composer)
    }
}
