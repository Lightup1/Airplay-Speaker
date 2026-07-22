package io.github.jqssun.airplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseLyricsClientTest {

    @Test
    fun parsesWordTimedYrcWithoutDroppingSpacesOrPunctuation() {
        val lines = NeteaseLyricsClient.parseYrc(
            "[21020,3600](21020,240,0)But (21260,540,0)Heaven " +
                "(21800,420,0)knows(22220,0,0), (22220,150,0)I'm " +
                "(22370,750,0)miserable (23120,1500,0)now"
        )

        assertEquals(1, lines.size)
        assertEquals("But Heaven knows, I'm miserable now", lines.single().text)
        assertTrue(lines.single().exactWordTiming)
        assertEquals(7, lines.single().words.size)
        assertEquals(21_020L, lines.single().words.first().startMs)
        assertEquals(24_620L, lines.single().endMs)
    }

    @Test
    fun choosesMatchingArtistAndClosestDuration() {
        val response = """
            {
              "result": {
                "songs": [
                  {"id": 1, "name": "Rapture", "duration": 250000,
                   "artists": [{"name": "Someone Else"}]},
                  {"id": 2, "name": "Rapture (2002 Remaster)", "duration": 299000,
                   "artists": [{"name": "Blondie"}]},
                  {"id": 3, "name": "Rapture", "duration": 300500,
                   "artists": [{"name": "Blondie"}]}
                ]
              }
            }
        """.trimIndent()

        val selected = NeteaseLyricsClient.selectSongId(
            response,
            LyricsRequest("Rapture (2002 Remaster)", "Blondie", "", 300_000L),
        )

        assertEquals(2L, selected)
    }

    @Test
    fun fallsBackToRegularLrcWhenYrcIsMissing() {
        val response = """
            {"code": 200, "lrc": {"lyric": "[00:01.00]Hello world\n[00:04.00]Again"}}
        """.trimIndent()

        val state = NeteaseLyricsClient.parseLyricsResponse(response, 8_000L, 42L)

        assertNotNull(state)
        assertEquals(LyricsStatus.SYNCED, state?.status)
        assertEquals(2, state?.lines?.size)
        assertEquals(42L, state?.sourceId)
    }
}
