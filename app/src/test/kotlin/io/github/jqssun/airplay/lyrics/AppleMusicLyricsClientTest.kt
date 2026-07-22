package io.github.jqssun.airplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicLyricsClientTest {

    @Test
    fun parsesOppositeSingerAndBackgroundVocalTiming() {
        val response = """
            {
              "provider": "apple_music",
              "syncType": "Syllable",
              "lyrics": [
                {
                  "timestamp": 39495,
                  "endtime": 40912,
                  "oppositeTurn": false,
                  "text": [
                    {"text":"A", "timestamp":39495, "endtime":39600},
                    {"text":"face", "timestamp":39600, "endtime":40000}
                  ],
                  "backgroundText": [
                    {"text":"And", "timestamp":40912, "endtime":41196},
                    {"text":"watches", "timestamp":41196, "endtime":41549}
                  ]
                },
                {
                  "timestamp": 124238,
                  "endtime": 131197,
                  "oppositeTurn": true,
                  "agent": "v2",
                  "text": [
                    {"text":"The", "timestamp":124238, "endtime":124800},
                    {"text":"sun", "timestamp":124800, "endtime":131197}
                  ],
                  "backgroundText": []
                }
              ]
            }
        """.trimIndent()

        val state = AppleMusicLyricsClient.parseResponse(response, 590431777L)

        assertNotNull(state)
        assertEquals(LyricsStatus.SYNCED, state?.status)
        assertEquals("A face (And watches)", state?.lines?.first()?.text)
        assertEquals(41_549L, state?.lines?.first()?.endMs)
        assertEquals(LyricAlignment.LEADING, state?.lines?.first()?.alignment)
        assertEquals(LyricAlignment.TRAILING, state?.lines?.last()?.alignment)
        assertEquals("The sun", state?.lines?.last()?.text)
        assertTrue(state?.lines?.all { it.exactWordTiming } == true)
    }

    @Test
    fun selectsTheStudioTrackWithMatchingDuration() {
        val response = """
            {
              "results": [
                {"trackId":1,"trackName":"Papercut (Live)","artistName":"LINKIN PARK",
                 "collectionName":"Live","trackTimeMillis":230556},
                {"trackId":2,"trackName":"Papercut","artistName":"LINKIN PARK",
                 "collectionName":"Hybrid Theory","trackTimeMillis":184469}
              ]
            }
        """.trimIndent()

        val id = AppleMusicLyricsClient.selectTrackId(
            response,
            LyricsRequest("Papercut", "LINKIN PARK", "Hybrid Theory", 184_866L),
        )

        assertEquals(2L, id)
    }
}
