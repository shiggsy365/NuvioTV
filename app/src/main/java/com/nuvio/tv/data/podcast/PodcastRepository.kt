package com.nuvio.tv.data.podcast

import com.nuvio.tv.data.local.PodcastLibraryDataStore
import com.nuvio.tv.data.remote.api.ApplePodcastDto
import com.nuvio.tv.data.remote.api.PodcastApi
import com.nuvio.tv.data.remote.api.PodcastChartsApi
import com.nuvio.tv.domain.model.Podcast
import com.nuvio.tv.domain.model.PodcastEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Log
import okio.Buffer
import okio.BufferedSource
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PodcastRepository @Inject constructor(
    private val api: PodcastApi,
    private val chartsApi: PodcastChartsApi,
    private val httpClient: OkHttpClient,
    private val library: PodcastLibraryDataStore
) {
    val subscribedFeedIds: Flow<Set<Long>> = library.subscribedFeedIds

    suspend fun trending(): Result<List<Podcast>> = runCatching {
        val chart = chartsApi.topPodcasts()
        val ids = chart.feed.results.mapNotNull { it.id.toLongOrNull() }
        if (ids.isEmpty()) return@runCatching emptyList()
        val byId = api.lookup(ids.joinToString(",")).results.associateBy { it.collectionId }
        ids.mapNotNull(byId::get).map(ApplePodcastDto::toModel)
    }

    suspend fun search(query: String): Result<List<Podcast>> = runCatching {
        api.search(query.trim()).results.map(ApplePodcastDto::toModel)
    }

    suspend fun podcast(feedId: Long): Result<Podcast> = runCatching {
        api.lookup(feedId.toString()).results.firstOrNull()?.toModel() ?: error("Podcast not found")
    }

    suspend fun episodes(feedId: Long): Result<List<PodcastEpisode>> = runCatching {
        val podcast = api.lookup(feedId.toString()).results.firstOrNull() ?: error("Podcast not found")
        val feedUrl = podcast.feedUrl?.takeIf(String::isNotBlank) ?: error("Podcast feed unavailable")
        fetchAndParseFeed(feedId, feedUrl, podcast.artworkUrl600 ?: podcast.artworkUrl100)
    }.onFailure { error ->
        Log.e(TAG, "Unable to load episodes for podcast $feedId", error)
    }

    suspend fun setSubscribed(feedId: Long, subscribed: Boolean) = library.setSubscribed(feedId, subscribed)

    private suspend fun fetchAndParseFeed(feedId: Long, feedUrl: String, fallbackImage: String?): List<PodcastEpisode> =
        withContext(Dispatchers.IO) {
            require(feedUrl.startsWith("https://") || feedUrl.startsWith("http://")) { "Unsupported feed URL" }
            val request = Request.Builder()
                .url(feedUrl)
                .header("User-Agent", "NuvioTV/0.9 (Android TV podcast client)")
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.5")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Podcast feed returned ${response.code}")
                val body = response.body ?: error("Podcast feed returned an empty response")
                val bytes = body.source().readUpTo(MAX_FEED_BYTES + 1L)
                require(bytes.size <= MAX_FEED_BYTES) { "Podcast feed is too large" }
                val episodes = parseRss(feedId, bytes, fallbackImage)
                check(episodes.isNotEmpty()) { "This podcast feed contains no playable audio episodes" }
                Log.i(TAG, "Loaded ${episodes.size} episodes for podcast $feedId")
                episodes
            }
        }
}

private fun BufferedSource.readUpTo(maxBytes: Long): ByteArray {
    val buffer = Buffer()
    while (buffer.size < maxBytes) {
        val read = read(buffer, minOf(8_192L, maxBytes - buffer.size))
        if (read == -1L) break
    }
    return buffer.readByteArray()
}

private fun ApplePodcastDto.toModel() = Podcast(
    id = collectionId,
    title = collectionName,
    author = artistName,
    description = primaryGenreName.orEmpty(),
    feedUrl = feedUrl.orEmpty(),
    imageUrl = artworkUrl600 ?: artworkUrl100,
    episodeCount = trackCount ?: 0
)

internal fun parseRss(
    feedId: Long,
    xml: ByteArray,
    fallbackImage: String? = null,
    parser: XmlPullParser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
): List<PodcastEpisode> {
    runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
    parser.setInput(ByteArrayInputStream(xml), null)
    val episodes = mutableListOf<PodcastEpisode>()
    var feedImage = fallbackImage
    var insideItem = false
    var title = ""
    var description = ""
    var audioUrl = ""
    var imageUrl: String? = null
    var guid = ""
    var publishedAt = 0L
    var durationSeconds = 0

    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.START_TAG) {
            val name = parser.name.lowercase(Locale.ROOT)
            when {
                name == "item" || name == "entry" -> {
                    insideItem = true
                    title = ""; description = ""; audioUrl = ""; imageUrl = null
                    guid = ""; publishedAt = 0L; durationSeconds = 0
                }
                insideItem && name == "title" -> title = parser.elementText()
                insideItem && name in setOf("description", "summary", "encoded") -> description = parser.elementText()
                insideItem && name in setOf("guid", "id") -> guid = parser.elementText()
                insideItem && name in setOf("pubdate", "published", "updated") -> publishedAt = parsePublishedDate(parser.elementText())
                insideItem && name == "duration" -> durationSeconds = parseDuration(parser.elementText())
                insideItem && name == "enclosure" -> audioUrl = parser.getAttributeValue(null, "url").orEmpty()
                insideItem && name == "link" && audioUrl.isBlank() -> {
                    val relation = parser.getAttributeValue(null, "rel")
                    val type = parser.getAttributeValue(null, "type").orEmpty()
                    if (relation == "enclosure" || type.startsWith("audio/")) {
                        audioUrl = parser.getAttributeValue(null, "href").orEmpty()
                    }
                }
                name == "image" -> {
                    val href = parser.getAttributeValue(null, "href")
                    if (!href.isNullOrBlank()) {
                        if (insideItem) imageUrl = href else feedImage = href
                    }
                }
            }
        } else if (parser.eventType == XmlPullParser.END_TAG && parser.name.lowercase(Locale.ROOT) in setOf("item", "entry")) {
            if (audioUrl.isNotBlank()) {
                val stableGuid = guid.ifBlank { audioUrl }
                episodes += PodcastEpisode(
                    id = stableLongId("$feedId:$stableGuid"),
                    feedId = feedId,
                    title = cleanText(title).ifBlank { "Untitled episode" },
                    description = cleanText(description),
                    audioUrl = audioUrl,
                    imageUrl = imageUrl ?: feedImage,
                    publishedAt = publishedAt,
                    durationSeconds = durationSeconds,
                    guid = stableGuid
                )
            }
            insideItem = false
        }
        parser.next()
    }
    return episodes.sortedByDescending(PodcastEpisode::publishedAt)
}

private fun XmlPullParser.elementText(): String {
    val startDepth = depth
    val value = StringBuilder()
    while (next() != XmlPullParser.END_DOCUMENT) {
        if (eventType == XmlPullParser.TEXT || eventType == XmlPullParser.CDSECT) value.append(text)
        if (eventType == XmlPullParser.END_TAG && depth == startDepth) break
    }
    return value.toString().trim()
}

private fun parseDuration(value: String): Int {
    val parts = value.trim().split(':').mapNotNull(String::toIntOrNull)
    return when (parts.size) {
        1 -> parts[0]
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        else -> 0
    }
}

private fun parsePublishedDate(value: String): Long {
    val formats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
    )
    return formats.firstNotNullOfOrNull { pattern ->
        runCatching { SimpleDateFormat(pattern, Locale.US).parse(value.trim())?.time }.getOrNull()
    } ?: 0L
}

private fun stableLongId(value: String): Long =
    ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(value.toByteArray())).long

private fun cleanText(value: String): String =
    value
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&#160;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private const val MAX_FEED_BYTES = 12 * 1024 * 1024
private const val TAG = "PodcastRepository"
