package com.nuvio.tv.data.livetv

import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvSettings
import com.nuvio.tv.domain.model.LiveTvStreamOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DispatcharrClient @Inject constructor(
    private val client: OkHttpClient
) {
    suspend fun streamOptions(
        settings: LiveTvSettings,
        channel: LiveTvChannel
    ): Result<List<LiveTvStreamOption>> = withContext(Dispatchers.IO) {
        runCatching {
            require(settings.dispatcharrEnabled) { "Dispatcharr stream selection is disabled" }
            val baseUrl = settings.dispatcharrBaseUrl.trim().trimEnd('/')
            require(baseUrl.isNotBlank()) { "Dispatcharr URL is not configured" }

            val channels = fetchChannels(baseUrl, settings.dispatcharrApiToken)
            val match = channels.firstOrNull { it.matches(channel) }
                ?: throw IllegalStateException("Channel was not found in Dispatcharr")
            val streams = match.streamObjects()
            require(streams.isNotEmpty()) { "Dispatcharr did not return stream details for this channel" }
            streams.mapIndexedNotNull { index, stream -> stream.toOption(index) }
        }
    }

    private fun fetchChannels(baseUrl: String, token: String): List<JSONObject> {
        val primary = "$baseUrl/api/channels/channels/"
        val firstPage = requestJson(primary, token, mapOf("include_streams" to "true"))
        val results = firstPage.optJSONArray("results")
        return if (results != null) {
            buildList {
                addAll(results.objects())
                var next = firstPage.optString("next").takeIf { it.isNotBlank() && it != "null" }
                while (next != null) {
                    val page = requestJson(resolveUrl(baseUrl, next), token)
                    addAll(page.optJSONArray("results")?.objects().orEmpty())
                    next = page.optString("next").takeIf { it.isNotBlank() && it != "null" }
                }
            }
        } else {
            firstPage.asArrayOrObjectList()
        }
    }

    private fun requestJson(
        url: String,
        token: String,
        query: Map<String, String> = emptyMap()
    ): JSONObject {
        val parsed = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid Dispatcharr URL")
        val finalUrl = parsed.newBuilder().apply {
            query.forEach { (key, value) -> setQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(finalUrl).apply {
            val trimmed = token.trim()
            if (trimmed.isNotBlank()) {
                header("Authorization", trimmed.authorizationHeader())
                header("X-API-Key", trimmed.apiKeyHeaderValue())
            }
        }.build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Dispatcharr HTTP ${response.code}" }
            val body = response.body?.string().orEmpty()
            return body.toJsonObject()
        }
    }

    private fun resolveUrl(baseUrl: String, value: String): String {
        if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) {
            return value
        }
        return "$baseUrl/${value.trimStart('/')}"
    }

    private fun String.apiKeyHeaderValue(): String =
        removePrefixIgnoreCase("ApiKey ")

    private fun String.authorizationHeader(): String =
        if (startsWith("Bearer ", ignoreCase = true) ||
            startsWith("Token ", ignoreCase = true) ||
            startsWith("ApiKey ", ignoreCase = true) ||
            startsWith("Basic ", ignoreCase = true)
        ) {
            this
        } else {
            "ApiKey $this"
        }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) drop(prefix.length).trim() else this

    private fun String.toJsonObject(): JSONObject {
        val trimmed = trim()
        if (trimmed.startsWith("[")) {
            return JSONObject().put("results", JSONArray(trimmed))
        }
        return JSONObject(trimmed)
    }

    private fun JSONObject.matches(channel: LiveTvChannel): Boolean {
        val candidates = listOf(
            optLongOrNull("id")?.toString(),
            optStringOrNull("uuid"),
            optStringOrNull("tvg_id"),
            optStringOrNull("name")
        ).filterNotNull()
        return candidates.any { it.equals(channel.id, ignoreCase = true) || it.equals(channel.name, ignoreCase = true) }
    }

    private fun JSONObject.streamObjects(): List<JSONObject> {
        val streams = optJSONArray("streams") ?: return emptyList()
        return streams.objects()
    }

    private fun JSONObject.toOption(index: Int): LiveTvStreamOption? {
        val url = firstString(
            "url",
            "stream_url",
            "channel_url",
            "direct_url",
            "proxied_url",
            "preview_url"
        )
        val id = firstString("id", "uuid", "stream_id") ?: "stream-$index"
        val name = firstString("name", "stream_name", "title") ?: "Stream ${index + 1}"
        return LiveTvStreamOption(
            id = id,
            name = name,
            url = url,
            source = firstString("m3u_name", "source", "provider_name", "m3u"),
            status = firstString("status", "state"),
            resolution = firstString("resolution", "video_resolution"),
            bitrate = firstString("bitrate", "stream_bitrate")
        )
    }

    private fun JSONObject.firstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            val value = opt(key) ?: return@firstNotNullOfOrNull null
            when (value) {
                is Number -> value.toString()
                is String -> value.takeIf(String::isNotBlank)
                is JSONObject -> value.firstString("name", "url")
                else -> null
            }
        }

    private fun JSONObject.optStringOrNull(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key)) optLong(key) else null

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull { index -> optJSONObject(index) }

    private fun JSONObject.asArrayOrObjectList(): List<JSONObject> =
        optJSONArray("results")?.objects() ?: listOf(this)
}
