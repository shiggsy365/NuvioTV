package com.nuvio.tv.core.sync.androidtv

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tvChannelDataStore by preferencesDataStore(
    name = "tv_channel_prefs",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() }
)

@Singleton
class TvChannelPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val channelIdKey = longPreferencesKey("continue_watching_channel_id")

    private fun channelKey(key: String) = longPreferencesKey("channel_id_$key")

    suspend fun getChannelId(): Long? =
        context.tvChannelDataStore.data.map { it[channelIdKey] }.first()

    suspend fun setChannelId(id: Long) {
        context.tvChannelDataStore.edit { it[channelIdKey] = id }
    }

    suspend fun clearChannelId() {
        context.tvChannelDataStore.edit { it.remove(channelIdKey) }
    }

    suspend fun getChannelId(key: String): Long? =
        context.tvChannelDataStore.data.map { it[channelKey(key)] }.first()

    suspend fun setChannelId(key: String, id: Long) {
        context.tvChannelDataStore.edit { it[channelKey(key)] = id }
    }

    suspend fun clearChannelId(key: String) {
        context.tvChannelDataStore.edit { it.remove(channelKey(key)) }
    }
}
