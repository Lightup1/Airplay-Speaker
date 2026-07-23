package io.github.jqssun.airplay.artwork

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkDecoderTest {

    @Test
    fun samplesLargeArtworkToTheTvCoverBudget() {
        assertEquals(1, ArtworkDecoder.sampleSizeFor(960, 960))
        assertEquals(2, ArtworkDecoder.sampleSizeFor(1_200, 1_200))
        assertEquals(4, ArtworkDecoder.sampleSizeFor(3_000, 2_000))
        assertEquals(8, ArtworkDecoder.sampleSizeFor(7_680, 4_320))
    }
}
