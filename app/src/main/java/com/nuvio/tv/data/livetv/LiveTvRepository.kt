package com.nuvio.tv.data.livetv

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LiveTvSettingsDataStore
import com.nuvio.tv.domain.model.LiveTvGuide
import com.nuvio.tv.domain.model.LiveTvSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveTvRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val settingsStore: LiveTvSettingsDataStore,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val PLAYLIST_REFRESH_MS = 24 * 60 * 60 * 1000L
        private const val EPG_REFRESH_MS = 6 * 60 * 60 * 1000L
        private const val PAST_WINDOW_MS = 12 * 60 * 60 * 1000L
        private const val FUTURE_WINDOW_MS = 48 * 60 * 60 * 1000L
    }

    private val _guide = MutableStateFlow(LiveTvGuide())
    val guide: StateFlow<LiveTvGuide> = _guide.asStateFlow()

    suspend fun load(force: Boolean = false): Result<LiveTvGuide> = withContext(Dispatchers.IO) {
        runCatching {
            var settings = settingsStore.settings.first()
            require(settings.playlistUrl.isNotBlank() && settings.epgUrl.isNotBlank()) { "Live TV sources are not configured" }
            val now = System.currentTimeMillis()
            val directory = cacheDirectory()
            val playlistFile = File(directory, "playlist.m3u")
            val epgFile = File(directory, "guide.xml")
            var playlistUpdated = settings.playlistUpdatedAt
            var epgUpdated = settings.epgUpdatedAt

            if (force || !playlistFile.exists() || now - playlistUpdated >= PLAYLIST_REFRESH_MS) {
                runCatching { download(settings.playlistUrl, settings.userAgent, playlistFile) }
                    .onSuccess { playlistUpdated = now }
                    .getOrElse { if (!playlistFile.exists()) throw it }
            }
            if (force || !epgFile.exists() || now - epgUpdated >= EPG_REFRESH_MS) {
                runCatching { download(settings.epgUrl, settings.userAgent, epgFile) }
                    .onSuccess { epgUpdated = now }
                    .getOrElse { if (!epgFile.exists()) throw it }
            }
            if (playlistUpdated != settings.playlistUpdatedAt || epgUpdated != settings.epgUpdatedAt) {
                settings = settings.copy(playlistUpdatedAt = playlistUpdated, epgUpdatedAt = epgUpdated)
                settingsStore.save(settings)
            }
            val channels = M3uParser.parse(playlistFile.readText())
            require(channels.isNotEmpty()) { "The playlist contains no channels" }
            val programmes = epgFile.inputStream().buffered().use {
                XmlTvParser.parse(it, now - PAST_WINDOW_MS, now + FUTURE_WINDOW_MS)
            }
            LiveTvGuide(channels, programmes, now).also { _guide.value = it }
        }
    }

    private fun download(url: String, userAgent: String, destination: File) {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} loading Live TV data" }
            val body = checkNotNull(response.body) { "Empty Live TV response" }
            val temporary = File(destination.parentFile, "${destination.name}.tmp")
            body.byteStream().use { input -> temporary.outputStream().use(input::copyTo) }
            check(temporary.length() > 0) { "Empty Live TV response" }
            if (!temporary.renameTo(destination)) temporary.copyTo(destination, overwrite = true).also { temporary.delete() }
        }
    }

    private fun cacheDirectory(): File = File(
        context.filesDir,
        "live_tv_p${profileManager.activeProfileId.value}"
    ).apply { mkdirs() }
}
