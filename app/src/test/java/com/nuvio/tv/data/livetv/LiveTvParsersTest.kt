package com.nuvio.tv.data.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvParsersTest {
    @Test fun `extended m3u preserves channel and group order`() {
        val input = """#EXTM3U
            #EXTINF:-1 tvg-id="one" tvg-logo="https://example.test/1.png" group-title="News",One
            https://example.test/one.m3u8
            #EXTINF:-1 tvg-id="two" group-title="Sport",Two
            https://example.test/two
        """.trimIndent()
        val channels = M3uParser.parse(input)
        assertEquals(listOf("one", "two"), channels.map { it.id })
        assertEquals(listOf("News", "Sport"), channels.map { it.group })
    }

    @Test fun `xmltv timestamps honour explicit offsets`() {
        val utc = XmlTvParser.parseTime("20260901120000 +0000")
        val bst = XmlTvParser.parseTime("20260901130000 +0100")
        assertTrue(utc > 0)
        assertEquals(utc, bst)
    }
}
