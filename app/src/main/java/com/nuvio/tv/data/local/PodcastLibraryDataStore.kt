package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PodcastLibraryDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val subscriptionsKey = stringSetPreferencesKey("subscribed_feed_ids")
    private val menuEnabledKey = booleanPreferencesKey("podcasts_menu_enabled")

    val menuEnabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, "podcast_library").data.map { preferences ->
            preferences[menuEnabledKey] ?: true
        }
    }

    val subscribedFeedIds: Flow<Set<Long>> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, "podcast_library").data.map { preferences ->
            preferences[subscriptionsKey].orEmpty().mapNotNull(String::toLongOrNull).toSet()
        }
    }

    suspend fun setSubscribed(feedId: Long, subscribed: Boolean) {
        factory.get(profileManager.activeProfileId.value, "podcast_library").edit { preferences ->
            val values = preferences[subscriptionsKey].orEmpty().toMutableSet()
            if (subscribed) values += feedId.toString() else values -= feedId.toString()
            preferences[subscriptionsKey] = values
        }
    }

    suspend fun setMenuEnabled(enabled: Boolean) {
        factory.get(profileManager.activeProfileId.value, "podcast_library").edit { preferences ->
            preferences[menuEnabledKey] = enabled
        }
    }
}
