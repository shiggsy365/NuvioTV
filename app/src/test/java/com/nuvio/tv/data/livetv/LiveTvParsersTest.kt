package com.nuvio.tv.data.livetv

import com.nuvio.tv.domain.model.LiveTvProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvParsersTest {
    @Test fun `extended m3u preserves channel and group order`() {
        val input = """#EXTM3U
            #EXTINF:-1 tvg-id="one" tvg-logo="https://example.test/1.png" group-title="News" catchup="default" catchup-source="https://example.test/archive?start={utc}&duration={duration}" catchup-days="7",One
            https://example.test/one.m3u8
            #EXTINF:-1 tvg-id="two" group-title="Sport",Two
            https://example.test/two
        """.trimIndent()
        val channels = M3uParser.parse(input)
        assertEquals(listOf("one", "two"), channels.map { it.id })
        assertEquals(listOf("News", "Sport"), channels.map { it.group })
        assertEquals("default", channels.first().catchup)
        assertEquals("https://example.test/archive?start={utc}&duration={duration}", channels.first().catchupSource)
        assertEquals(7, channels.first().catchupDays)
    }

    @Test fun `catchup source builds playback url for past programme`() {
        val channel = M3uParser.parse(
            """#EXTM3U
                #EXTINF:-1 tvg-id="one" catchup-source="https://example.test/archive?start={utc}&end={utcend}&duration={duration}" catchup-days="2",One
                https://example.test/live/one.m3u8
            """.trimIndent()
        ).single()
        val programme = LiveTvProgramme(
            channelId = "one",
            title = "Earlier",
            startMillis = 1_800_000L,
            endMillis = 3_600_000L
        )
        assertEquals(
            "https://example.test/archive?start=1800&end=3600&duration=1800",
            channel.playbackUrl(programme, nowMillis = 7_200_000L)
        )
    }

    @Test fun `catchup source keeps live url for current programme`() {
        val channel = M3uParser.parse(
            """#EXTM3U
                #EXTINF:-1 tvg-id="one" catchup-source="https://example.test/archive?start={utc}",One
                https://example.test/live/one.m3u8
            """.trimIndent()
        ).single()
        val programme = LiveTvProgramme(
            channelId = "one",
            title = "Now",
            startMillis = 1_800_000L,
            endMillis = 3_600_000L
        )
        assertEquals("https://example.test/live/one.m3u8", channel.playbackUrl(programme, nowMillis = 2_400_000L))
    }

    @Test fun `xmltv timestamps honour explicit offsets`() {
        val utc = XmlTvParser.parseTime("20260901120000 +0000")
        val bst = XmlTvParser.parseTime("20260901130000 +0100")
        assertTrue(utc > 0)
        assertEquals(utc, bst)
    }
}
