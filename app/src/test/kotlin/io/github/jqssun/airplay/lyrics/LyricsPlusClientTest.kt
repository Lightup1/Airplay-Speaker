package io.github.jqssun.airplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsPlusClientTest {

    @Test
    fun parsesRealWordWindowsFromV2Response() {
        val response = """
            {
              "type": "Word",
              "lyrics": [
                {
                  "time": 1000,
                  "duration": 1200,
                  "text": "Hello world",
                  "syllabus": [
                    {"time": 1000, "duration": 450, "text": "Hello "},
                    {"time": 1450, "duration": 750, "text": "world"}
                  ]
                }
              ]
            }
        """.trimIndent()

        val state = LyricsPlusClient.parseResponse(response)

        assertNotNull(state)
        assertEquals(WordSyncMode.ENHANCED, state?.wordSyncMode)
        assertEquals(listOf(1_000L, 1_450L), state?.lines?.single()?.words?.map { it.startMs })
        assertEquals(2_200L, state?.lines?.single()?.words?.last()?.endMs)
        assertTrue(state?.lines?.single()?.exactWordTiming == true)
    }

    @Test
    fun retainsLineOnlyResponseWithoutDroppingTheVerse() {
        val response = """
            {"type":"Line","lyrics":[{"time":1000,"duration":1000,"text":"Hello"}]}
        """.trimIndent()

        val state = LyricsPlusClient.parseResponse(response)

        assertNotNull(state)
        assertEquals(LyricsStatus.SYNCED, state?.status)
        assertEquals(WordSyncMode.ESTIMATED, state?.wordSyncMode)
        assertEquals("Hello", state?.lines?.single()?.text)
        assertEquals(2_000L, state?.lines?.single()?.endMs)
        assertTrue(state?.lines?.single()?.words?.isEmpty() == true)
    }

    @Test
    fun retainsUntimedWordsAlongsideWordTimedLines() {
        val response = """
            {
              "type": "Word",
              "lyrics": [
                {
                  "time": 1000,
                  "duration": 500,
                  "text": "Timed line",
                  "syllabus": [{"time": 1000, "duration": 500, "text": "Timed line"}]
                },
                {"time": 2000, "duration": 700, "text": "Line with no word timing"}
              ]
            }
        """.trimIndent()

        val state = LyricsPlusClient.parseResponse(response)

        assertNotNull(state)
        assertEquals(2, state?.lines?.size)
        assertTrue(state?.lines?.last()?.words?.isEmpty() == true)
        assertTrue(state?.plainLyrics?.contains("Line with no word timing") == true)
    }

    @Test
    fun aSingleAgentLabelDoesNotPushTheWholeSongToTheRight() {
        val response = """
            {
              "type": "Word",
              "lyrics": [
                {
                  "time": 1000,
                  "duration": 1000,
                  "text": "Right side",
                  "agent": "v2",
                  "syllables": [
                    {"time":1000,"duration":1000,"text":"Right side"}
                  ]
                }
              ]
            }
        """.trimIndent()

        val state = LyricsPlusClient.parseResponse(response)

        assertEquals(LyricAlignment.LEADING, state?.lines?.single()?.alignment)
    }

    @Test
    fun retainsDuetTurnsWhenTwoAgentsArePresent() {
        val response = """
            {
              "type": "Line",
              "lyrics": [
                {"time":1000,"duration":1000,"text":"First singer","agent":"v1"},
                {"time":3000,"duration":1000,"text":"Second singer","agent":"v2"}
              ]
            }
        """.trimIndent()

        val state = LyricsPlusClient.parseResponse(response)

        assertEquals(LyricAlignment.LEADING, state?.lines?.first()?.alignment)
        assertEquals(LyricAlignment.TRAILING, state?.lines?.last()?.alignment)
    }
}
