package io.github.jqssun.airplay.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleMusicArtworkClientTest {

    @Test
    fun parsesSquareMotionVideoFromAlbumDictionary() {
        val html = """
            {"videoArtwork":{"dictionary":{"motionDetailSquare":{
              "previewFrame":{"url":"https://is1-ssl.mzstatic.com/frame.webp"},
              "video":{"url":"https://mvod.itunes.apple.com/path/cover_default.m3u8"}
            }}}}
        """.trimIndent()

        assertEquals(
            "https://mvod.itunes.apple.com/path/cover_default.m3u8",
            AppleMusicArtworkClient.parseSquareMotionUrl(html),
        )
    }

    @Test
    fun fallsBackToAmbientArtworkElement() {
        val html = """
            <div data-testid="video-artwork">
              <amp-ambient-video src="https://mvod.itunes.apple.com/path/fallback.m3u8"></amp-ambient-video>
            </div>
        """.trimIndent()

        assertEquals(
            "https://mvod.itunes.apple.com/path/fallback.m3u8",
            AppleMusicArtworkClient.parseSquareMotionUrl(html),
        )
    }

    @Test
    fun rejectsUntrustedMotionHost() {
        val html = """
            {"motionDetailSquare":{"video":{"url":"https://example.com/cover.m3u8"}}}
        """.trimIndent()

        assertNull(AppleMusicArtworkClient.parseSquareMotionUrl(html))
    }

    @Test
    fun upgradesTrustedStaticArtworkForTvBackground() {
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/cover.jpg/1200x1200bb.jpg",
            AppleMusicArtworkClient.highResolutionArtworkUrl(
                "https://is1-ssl.mzstatic.com/image/thumb/Music/cover.jpg/100x100bb.jpg"
            ),
        )
    }

    @Test
    fun rejectsStaticArtworkOutsideAppleCdn() {
        assertNull(
            AppleMusicArtworkClient.highResolutionArtworkUrl(
                "https://example.com/100x100bb.jpg"
            )
        )
    }
}
