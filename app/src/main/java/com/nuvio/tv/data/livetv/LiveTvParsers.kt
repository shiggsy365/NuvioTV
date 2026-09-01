package com.nuvio.tv.data.livetv

import android.util.Xml
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvProgramme
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object M3uParser {
    private val attribute = Regex("([\\w-]+)=\\\"([^\\\"]*)\\\"")

    fun parse(text: String): List<LiveTvChannel> {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val result = mutableListOf<LiveTvChannel>()
        var info: String? = null
        lines.forEach { line ->
            when {
                line.startsWith("#EXTINF", true) -> info = line
                !line.startsWith("#") && info != null -> {
                    val metadata = attribute.findAll(info!!).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val name = info!!.substringAfterLast(',', metadata["tvg-name"].orEmpty()).trim()
                    val id = metadata["tvg-id"].orEmpty().ifBlank { name }
                    result += LiveTvChannel(
                        id = id,
                        name = name.ifBlank { id },
                        streamUrl = line,
                        group = metadata["group-title"].orEmpty().ifBlank { "Other" },
                        logoUrl = metadata["tvg-logo"]?.takeIf(String::isNotBlank),
                        number = metadata["tvg-chno"]?.takeIf(String::isNotBlank)
                    )
                    info = null
                }
            }
        }
        return result
    }
}

object XmlTvParser {
    private val formatters = listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmm Z", "yyyyMMddHHmmss", "yyyyMMddHHmm")
        .map { DateTimeFormatter.ofPattern(it, Locale.ROOT) }

    fun parse(input: InputStream, fromMillis: Long, toMillis: Long): List<LiveTvProgramme> {
        val parser = Xml.newPullParser().apply { setInput(input, null) }
        val result = mutableListOf<LiveTvProgramme>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                val channel = parser.getAttributeValue(null, "channel").orEmpty()
                val start = parseTime(parser.getAttributeValue(null, "start"))
                val end = parseTime(parser.getAttributeValue(null, "stop"))
                var title = ""
                var description: String? = null
                var category: String? = null
                var icon: String? = null
                val depth = parser.depth
                while (!(event == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "programme")) {
                    event = parser.next()
                    if (event == XmlPullParser.START_TAG) when (parser.name) {
                        "title" -> title = parser.nextText()
                        "desc" -> description = parser.nextText()
                        "category" -> category = parser.nextText()
                        "icon" -> icon = parser.getAttributeValue(null, "src")
                    }
                }
                if (channel.isNotBlank() && title.isNotBlank() && end > fromMillis && start < toMillis) {
                    result += LiveTvProgramme(channel, title, start, end, description, category, icon)
                }
            }
            event = parser.next()
        }
        return result
    }

    internal fun parseTime(value: String?): Long {
        val raw = value?.trim().orEmpty()
        for (formatter in formatters) {
            try {
                return if (raw.contains(' ')) OffsetDateTime.parse(raw, formatter).toInstant().toEpochMilli()
                else OffsetDateTime.of(java.time.LocalDateTime.parse(raw, formatter), ZoneOffset.UTC).toInstant().toEpochMilli()
            } catch (_: Exception) { }
        }
        return 0
    }
}
