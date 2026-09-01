package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.LiveTvSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveTvSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private object Keys {
        val enabled = booleanPreferencesKey("enabled")
        val playlistUrl = stringPreferencesKey("playlist_url")
        val epgUrl = stringPreferencesKey("epg_url")
        val userAgent = stringPreferencesKey("user_agent")
        val playlistUpdatedAt = longPreferencesKey("playlist_updated_at")
        val epgUpdatedAt = longPreferencesKey("epg_updated_at")
    }

    val settings: Flow<LiveTvSettings> = profileManager.activeProfileId.flatMapLatest { id ->
        factory.get(id, "live_tv_settings").data.map { prefs ->
            LiveTvSettings(
                enabled = prefs[Keys.enabled] ?: false,
                playlistUrl = prefs[Keys.playlistUrl].orEmpty(),
                epgUrl = prefs[Keys.epgUrl].orEmpty(),
                userAgent = prefs[Keys.userAgent] ?: "NuvioTV",
                playlistUpdatedAt = prefs[Keys.playlistUpdatedAt] ?: 0,
                epgUpdatedAt = prefs[Keys.epgUpdatedAt] ?: 0
            )
        }
    }

    suspend fun save(value: LiveTvSettings) {
        factory.get(profileManager.activeProfileId.value, "live_tv_settings").edit { prefs ->
            prefs[Keys.enabled] = value.enabled
            prefs[Keys.playlistUrl] = value.playlistUrl.trim()
            prefs[Keys.epgUrl] = value.epgUrl.trim()
            prefs[Keys.userAgent] = value.userAgent.trim().ifEmpty { "NuvioTV" }
            prefs[Keys.playlistUpdatedAt] = value.playlistUpdatedAt
            prefs[Keys.epgUpdatedAt] = value.epgUpdatedAt
        }
    }
}
