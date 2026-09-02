package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ContinueWatchingCategory(val title: String) {
    SOAP_REALITY("Soap / Reality"),
    COMEDY("Comedy"),
    DRAMA("Drama"),
    MOVIES_IN_PROGRESS("Movies in Progress")
}

/** Profile-scoped manual filing for Continue Watching. The key is the parent content id,
 * so a series remains in its chosen row as tracking advances to the next episode. */
@Singleton
class ContinueWatchingCategoryDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val keys = ContinueWatchingCategory.entries.associateWith {
        stringSetPreferencesKey("category_${it.name.lowercase()}")
    }

    val assignments: Flow<Map<String, ContinueWatchingCategory>> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            factory.get(profileId, FEATURE).data.map { prefs ->
                buildMap<String, ContinueWatchingCategory> {
                    ContinueWatchingCategory.entries.forEach { category ->
                        val key = requireNotNull(this@ContinueWatchingCategoryDataStore.keys[category])
                        prefs[key].orEmpty().forEach { contentId -> put(contentId, category) }
                    }
                }
            }
        }

    suspend fun move(contentId: String, destination: ContinueWatchingCategory?) {
        val store = factory.get(profileManager.activeProfileId.value, FEATURE)
        store.edit { prefs ->
            this@ContinueWatchingCategoryDataStore.keys.values.forEach { key -> prefs[key] = prefs[key].orEmpty() - contentId }
            if (destination != null) {
                val key = this@ContinueWatchingCategoryDataStore.keys.getValue(destination)
                prefs[key] = prefs[key].orEmpty() + contentId
            }
        }
    }

    private companion object { const val FEATURE = "continue_watching_categories" }
}
