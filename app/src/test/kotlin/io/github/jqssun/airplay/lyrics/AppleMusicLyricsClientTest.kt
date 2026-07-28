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
        assertEquals("A face", state?.lines?.first()?.text)
        assertEquals("And watches", state?.lines?.first()?.backgroundText)
        assertEquals(2, state?.lines?.first()?.words?.size)
        assertEquals(2, state?.lines?.first()?.backgroundWords?.size)
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

    @Test
    fun aSingleAgentLabelDoesNotPushTheWholeSongToTheRight() {
        val response = """
            {
              "lyrics": [
                {
                  "timestamp": 1000,
                  "endtime": 2000,
                  "agent": "v2",
                  "text": [
                    {"text":"Second singer", "timestamp":1000, "endtime":2000}
                  ]
                }
              ]
            }
        """.trimIndent()

        val state = AppleMusicLyricsClient.parseResponse(response, 1L)

        assertEquals(LyricAlignment.LEADING, state?.lines?.single()?.alignment)
    }

    @Test
    fun distinctAgentsRestoreDuetSidesWhenBooleanFlagIsMissing() {
        val response = """
            {
              "lyrics": [
                {
                  "timestamp": 1000,
                  "agent": "v2",
                  "text": [{"text":"First singer", "timestamp":1000, "endtime":2000}]
                },
                {
                  "timestamp": 3000,
                  "agent": "v1",
                  "text": [{"text":"Second singer", "timestamp":3000, "endtime":4000}]
                }
              ]
            }
        """.trimIndent()

        val state = AppleMusicLyricsClient.parseResponse(response, 1L)

        assertEquals(LyricAlignment.LEADING, state?.lines?.first()?.alignment)
        assertEquals(LyricAlignment.TRAILING, state?.lines?.last()?.alignment)
    }

    @Test
    fun lineSyncedAppleLyricsStayLineSyncedWithoutInventedWords() {
        val response = """
            {
              "lyrics": [
                {"timestamp":1000,"endtime":2500,"text":"Whole line one"},
                {"timestamp":3000,"endtime":4500,"text":"Whole line two"}
              ]
            }
        """.trimIndent()

        val state = AppleMusicLyricsClient.parseResponse(response, 1L)

        assertEquals(LyricsStatus.SYNCED, state?.status)
        assertTrue(state?.lines?.all { it.words.isEmpty() } == true)
        assertTrue(state?.lines?.none { it.exactWordTiming } == true)
        assertEquals(WordSyncMode.NONE, state?.wordSyncMode)
    }

    @Test
    fun unsynchronisedAppleLyricsRemainReadablePlainText() {
        val response = """
            {
              "lyrics": [
                {"text":"First plain line"},
                {"text":"Second plain line"}
              ]
            }
        """.trimIndent()

        val state = AppleMusicLyricsClient.parseResponse(response, 1L)

        assertEquals(LyricsStatus.PLAIN, state?.status)
        assertEquals("First plain line\nSecond plain line", state?.plainLyrics)
        assertTrue(state?.lines?.isEmpty() == true)
    }

    @Test
    fun timedMainVocalSurvivesAnUntimedCompactBackingVocal() {
        val response = """
            {
              "lyrics": [
                {
                  "timestamp":1000,
                  "endtime":3000,
                  "text":[
                    {"text":"Main","timestamp":1000,"endtime":1600},
                    {"text":"line","timestamp":1600,"endtime":2200}
                  ],
                  "backgroundText":"soft echo"
                }
              ]
            }
        """.trimIndent()

        val line = AppleMusicLyricsClient.parseResponse(response, 1L)?.lines?.single()

        assertTrue(line?.exactWordTiming == true)
        assertEquals("Main line", line?.text)
        assertEquals("soft echo", line?.backgroundText)
        assertEquals("Main line", line?.words?.joinToString("") { it.text })
        assertTrue(line?.backgroundWords?.isEmpty() == true)
    }

    @Test
    fun timedCompactVocalSurvivesAnUntimedMainVocal() {
        val response = """
            {
              "lyrics": [
                {
                  "timestamp":1000,
                  "endtime":3000,
                  "text":"Big line",
                  "backgroundText":[
                    {"text":"small","timestamp":1500,"endtime":2000},
                    {"text":"voice","timestamp":2000,"endtime":2500}
                  ]
                }
              ]
            }
        """.trimIndent()

        val line = AppleMusicLyricsClient.parseResponse(response, 1L)?.lines?.single()

        assertTrue(line?.exactWordTiming == true)
        assertEquals("Big line", line?.text)
        assertTrue(line?.words?.isEmpty() == true)
        assertEquals("small voice", line?.backgroundText)
        assertEquals("small voice", line?.backgroundWords?.joinToString("") { it.text })
    }

    @Test
    fun parsesOnlyWritingCreditsFromAppleCompositionSection() {
        val applePage = """
            <script>
            {
              "id":"composer-and-lyrics",
              "items":[
                {"name":"Madonna","roleNames":["Songwriter"]},
                {"name":"Stephen Bray","roleNames":["Composer","Songwriter"]},
                {"name":"Madonna","roleNames":["Lyricist"]},
                {"name":"Example Producer","roleNames":["Producer"]}
              ]
            }
            </script>
        """.trimIndent()

        val credit = AppleMusicLyricsClient.parseWriterCredit(applePage)

        assertEquals("Madonna, Stephen Bray", credit)
    }

    @Test
    fun missingAppleCompositionSectionDoesNotInventAWriter() {
        val credit = AppleMusicLyricsClient.parseWriterCredit(
            """<script>{"id":"artist-biography","items":[]}</script>"""
        )

        assertEquals("", credit)
    }
}
