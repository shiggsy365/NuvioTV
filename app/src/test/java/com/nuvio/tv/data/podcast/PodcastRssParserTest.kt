package com.nuvio.tv.data.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.mxp1.MXParser

class PodcastRssParserTest {
    @Test
    fun `parses RSS enclosure metadata without proxying audio`() {
        val xml = """
            <rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" version="2.0">
              <channel>
                <itunes:image href="https://cdn.example/show.jpg" />
                <item>
                  <title>Episode One</title>
                  <description><![CDATA[<p>A useful episode.</p>]]></description>
                  <guid>episode-1</guid>
                  <pubDate>Tue, 01 Sep 2026 12:30:00 +0000</pubDate>
                  <itunes:duration>01:02:03</itunes:duration>
                  <enclosure url="https://audio.example/one.mp3" type="audio/mpeg" />
                </item>
              </channel>
            </rss>
        """.trimIndent().toByteArray()

        val episodes = parseRss(feedId = 42, xml = xml, parser = MXParser())

        assertEquals(1, episodes.size)
        assertEquals("Episode One", episodes.single().title)
        assertEquals("A useful episode.", episodes.single().description)
        assertEquals("https://audio.example/one.mp3", episodes.single().audioUrl)
        assertEquals("https://cdn.example/show.jpg", episodes.single().imageUrl)
        assertEquals(3723, episodes.single().durationSeconds)
        assertTrue(episodes.single().publishedAt > 0)
    }

    @Test
    fun `parses Atom audio enclosure`() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Atom Episode</title>
                <id>atom-1</id>
                <updated>2026-09-01T12:30:00Z</updated>
                <link rel="enclosure" type="audio/mp4" href="https://audio.example/one.m4a" />
              </entry>
            </feed>
        """.trimIndent().toByteArray()

        val episode = parseRss(feedId = 7, xml = xml, parser = MXParser()).single()

        assertEquals("Atom Episode", episode.title)
        assertEquals("https://audio.example/one.m4a", episode.audioUrl)
    }
}
