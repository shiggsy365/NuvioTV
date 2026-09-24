package com.nuvio.tv.core.sync.androidtv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.nuvio.tv.MainActivity
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvGuide
import com.nuvio.tv.domain.model.WatchProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private const val TAG = "TvChannelSync"

@Singleton
class AndroidTvChannelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: TvChannelPreferences,
) {
    private val syncedProgramFingerprints = ConcurrentHashMap<String, ChannelProgramFingerprint>()

    private data class ChannelProgramFingerprint(
        val title: String?,
        val position: Int?,
        val duration: Int?,
        val imageUri: String?,
        val logoUri: String?,
        val sortOrder: Int,
        val season: Int?,
        val episode: Int?,
        val lastEngagementTime: Long
    )

    fun isSupported(): Boolean = try {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    } catch (_: Exception) {
        false
    }

    /**
     * Returns the channel id, creating or reusing the channel as needed.
     * Returns null on non-leanback devices or on failure.
     */
    suspend fun ensureChannel(): Long? = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext null
        runCatching {
            val stored = prefs.getChannelId()
            if (stored != null) {
                val cursor = context.contentResolver.query(
                    TvContractCompat.buildChannelUri(stored),
                    arrayOf(TvContractCompat.Channels._ID),
                    null, null, null
                )
                cursor?.use { if (it.moveToFirst()) return@runCatching stored }
                Log.d(TAG, "Stored channel $stored gone; recreating")
                prefs.clearChannelId()
            }

            val orphan = context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf(
                    TvContractCompat.Channels._ID,
                    TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID
                ),
                null, null, null
            )?.use { c ->
                val idIdx = c.getColumnIndex(TvContractCompat.Channels._ID)
                val providerIdx = c.getColumnIndex(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID)
                if (idIdx < 0 || providerIdx < 0) return@use null
                while (c.moveToNext()) {
                    val providerId = c.getString(providerIdx)
                    if (providerId != null && providerId.startsWith(context.packageName)) {
                        return@use c.getLong(idIdx)
                    }
                }
                null
            }
            if (orphan != null) {
                Log.d(TAG, "Reusing orphaned channel $orphan")
                prefs.setChannelId(orphan)
                writeChannelLogo(orphan)
                return@runCatching orphan
            }

            val appLinkUri = Uri.parse(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }.toUri(Intent.URI_INTENT_SCHEME)
            )
            val channel = Channel.Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName(context.getString(R.string.tv_channel_continue_watching))
                .setAppLinkIntentUri(appLinkUri)
                .setInternalProviderId(context.packageName)
                .build()

            val inserted = context.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI,
                channel.toContentValues()
            ) ?: return@runCatching null

            val id = ContentUris.parseId(inserted)
            prefs.setChannelId(id)
            writeChannelLogo(id)
            TvContractCompat.requestChannelBrowsable(context, id)
            Log.d(TAG, "Created channel id=$id")
            id
        }.onFailure { Log.w(TAG, "ensureChannel failed", it) }.getOrNull()
    }

    /**
     * Syncs [items] to the Continue Watching channel: upserts present items and
     * removes rows that are no longer in the list (completed or dismissed).
     */
    suspend fun reconcile(items: List<WatchProgress>) = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext
        runCatching {
            val channelId = ensureChannel() ?: return@runCatching
            val existing = queryExistingPrograms(channelId)
            val desiredKeys = items.map { progressKey(it) }.toSet()

            for ((key, rowIds) in existing) {
                if (key !in desiredKeys) {
                    rowIds.forEach { rowId ->
                        context.contentResolver.delete(
                            TvContractCompat.buildPreviewProgramUri(rowId), null, null
                        )
                        Log.d(TAG, "Removed program key=$key rowId=$rowId")
                    }
                    syncedProgramFingerprints.remove(key)
                }
            }

            items.forEachIndexed { index, progress ->
                val key = progressKey(progress)
                val rowIds = existing[key]

                val (imageUri, _) = when {
                    !progress.backdrop.isNullOrBlank() -> progress.backdrop to TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
                    !progress.poster.isNullOrBlank() -> progress.poster to TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
                    else -> null to null
                }
                val positionMs = if (progress.position > 0) {
                    progress.position.toInt()
                } else {
                    (progress.progressPercent?.let { it / 100f * progress.duration }?.toLong() ?: 0L).toInt()
                }

                val newFingerprint = ChannelProgramFingerprint(
                    title = progress.name,
                    position = positionMs,
                    duration = progress.duration.toInt(),
                    imageUri = imageUri,
                    logoUri = progress.logo,
                    sortOrder = index,
                    season = progress.season,
                    episode = progress.episode,
                    lastEngagementTime = progress.lastWatched
                )
                val oldFingerprint = syncedProgramFingerprints[key]

                if (!rowIds.isNullOrEmpty() && oldFingerprint != null &&
                    oldFingerprint.title == newFingerprint.title &&
                    oldFingerprint.imageUri == newFingerprint.imageUri &&
                    oldFingerprint.logoUri == newFingerprint.logoUri &&
                    oldFingerprint.sortOrder == newFingerprint.sortOrder &&
                    oldFingerprint.season == newFingerprint.season &&
                    oldFingerprint.episode == newFingerprint.episode &&
                    oldFingerprint.duration == newFingerprint.duration &&
                    abs((oldFingerprint.position ?: 0) - (newFingerprint.position ?: 0)) < 2_000
                ) {
                    // Item already in sync, skip database update to prevent waking up launcher
                    return@forEachIndexed
                }

                val values = buildProgramValues(progress, channelId, index, key)
                if (!rowIds.isNullOrEmpty()) {
                    val primaryRowId = rowIds.first()
                    // UPDATE the existing program row in place — keeping its row ID stable.
                    context.contentResolver.update(
                        TvContractCompat.buildPreviewProgramUri(primaryRowId), values, null, null
                    )
                    // If there are duplicate rows for the same key, delete them to clean up the database.
                    if (rowIds.size > 1) {
                        rowIds.drop(1).forEach { extraRowId ->
                            context.contentResolver.delete(
                                TvContractCompat.buildPreviewProgramUri(extraRowId), null, null
                            )
                            Log.d(TAG, "Removed duplicate program key=$key rowId=$extraRowId")
                        }
                    }
                } else {
                    context.contentResolver.insert(
                        TvContractCompat.PreviewPrograms.CONTENT_URI, values
                    )
                }
                syncedProgramFingerprints[key] = newFingerprint
                Log.d(TAG, "${if (!rowIds.isNullOrEmpty()) "Updated" else "Inserted"} program key=$key pos=${values.getAsInteger("last_playback_position_millis")} dur=${values.getAsInteger("duration_millis")} pct=${progress.progressPercent}")
            }
        }.onFailure { Log.w(TAG, "reconcile failed", it) }
    }

    suspend fun reconcileContinueWatching(
        profileId: Int,
        profileName: String,
        items: List<WatchProgress>
    ) = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext
        val key = "cw_profile_$profileId"
        val displayName = "$profileName: ${context.getString(R.string.tv_channel_continue_watching)}"
        val channelId = ensureNamedChannel(key, displayName, launchMode = "home") ?: return@withContext
        reconcileWatchProgress(channelId, key, profileId, items)
    }

    suspend fun reconcileLiveTv(
        profileId: Int,
        profileName: String,
        guide: LiveTvGuide,
        prefixProfileName: Boolean
    ) = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext
        val programmesByChannel = guide.programmes.groupBy { it.channelId }
        guide.channels.groupBy { it.group.ifBlank { "Other" } }.forEach { (group, channels) ->
            val key = "live_profile_${profileId}_${stableKey(group)}"
            val displayName = if (prefixProfileName) "$profileName: $group" else group
            val channelId = ensureNamedChannel(key, displayName, launchMode = "live_tv")
                ?: return@forEach
            reconcileLivePrograms(
                channelId = channelId,
                channelScope = key,
                profileId = profileId,
                channels = channels,
                programmesByChannel = programmesByChannel
            )
        }
    }

    suspend fun removeManagedChannelsExcept(keys: Set<String>) = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext
        prefs.getChannelId()?.let { legacyId ->
            context.contentResolver.delete(TvContractCompat.buildChannelUri(legacyId), null, null)
            prefs.clearChannelId()
        }
        val providerPrefix = "${context.packageName}:"
        context.contentResolver.query(
            TvContractCompat.Channels.CONTENT_URI,
            arrayOf(TvContractCompat.Channels._ID, TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID),
            null, null, null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(TvContractCompat.Channels._ID)
            val providerIndex = cursor.getColumnIndexOrThrow(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID)
            while (cursor.moveToNext()) {
                val providerId = cursor.getString(providerIndex) ?: continue
                if (!providerId.startsWith(providerPrefix)) continue
                val key = providerId.removePrefix(providerPrefix)
                if (key !in keys) {
                    context.contentResolver.delete(
                        TvContractCompat.buildChannelUri(cursor.getLong(idIndex)), null, null
                    )
                    prefs.clearChannelId(key)
                }
            }
        }
    }

    /** Removes all preview programs from our channel (used on sign-out / history clear). */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext
        runCatching {
            val channelId = prefs.getChannelId() ?: return@runCatching
            val rows = queryExistingPrograms(channelId)
            var deletedCount = 0
            rows.values.flatten().forEach { rowId ->
                context.contentResolver.delete(
                    TvContractCompat.buildPreviewProgramUri(rowId), null, null
                )
                deletedCount++
            }
            syncedProgramFingerprints.clear()
            Log.d(TAG, "Cleared $deletedCount programs for channel $channelId")
        }.onFailure { Log.w(TAG, "clearAll failed", it) }
    }

    // ---

    private fun queryExistingPrograms(channelId: Long): Map<String, List<Long>> {
        val projection = arrayOf(
            TvContractCompat.PreviewPrograms._ID,
            TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID,
            TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID
        )
        val result = mutableMapOf<String, MutableList<Long>>()
        // Fire OS rejects any selection clause on preview_program URIs with SecurityException;
        // the provider auto-scopes to the calling package, so we filter channelId in memory.
        context.contentResolver.query(
            TvContractCompat.PreviewPrograms.CONTENT_URI,
            projection,
            null, null, null
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms._ID)
            val channelIdx = c.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID)
            val keyIdx = c.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            while (c.moveToNext()) {
                if (c.getLong(channelIdx) != channelId) continue
                val key = c.getString(keyIdx) ?: continue
                result.getOrPut(key) { mutableListOf() }.add(c.getLong(idIdx))
            }
        }
        return result
    }

    private fun buildProgramValues(
        progress: WatchProgress,
        channelId: Long,
        sortOrder: Int,
        key: String,
        profileId: Int? = null
    ): ContentValues {
        val intentUri = Uri.parse(
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("contentId", progress.contentId)
                putExtra("contentType", progress.contentType)
                putExtra("videoId", progress.videoId)
                putExtra("name", progress.name)
                putExtra("poster", progress.poster)
                putExtra("backdrop", progress.backdrop)
                putExtra("logo", progress.logo)
                progress.season?.let { putExtra("season", it) }
                progress.episode?.let { putExtra("episode", it) }
                progress.episodeTitle?.let { putExtra("episodeTitle", it) }
                putExtra("launchMode", "stream")
                profileId?.let { putExtra("profileId", it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }.toUri(Intent.URI_INTENT_SCHEME)
        )

        val type = if (progress.contentType.equals("movie", ignoreCase = true))
            TvContractCompat.PreviewPrograms.TYPE_MOVIE
        else
            TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE

        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(type)
            .setTitle(progress.name)
            .setIntentUri(intentUri)
            .setInternalProviderId(key)
            .setWeight(Int.MAX_VALUE - sortOrder)

        // Backdrop/poster fills the tile via posterArt; logo goes to the dedicated logo column
        // so the launcher renders it as a small badge overlay on focus.
        val (imageUri, aspectRatio) = when {
            !progress.backdrop.isNullOrBlank() ->
                progress.backdrop to TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
            !progress.poster.isNullOrBlank() ->
                progress.poster to TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
            else -> null to null
        }
        imageUri?.let { builder.setPosterArtUri(Uri.parse(it)).setPosterArtAspectRatio(aspectRatio!!) }
        progress.logo?.let { builder.setLogoUri(Uri.parse(it)) }

        if (progress.duration > 0) {
            builder.setDurationMillis(progress.duration.toInt())
            val positionMs = if (progress.position > 0) {
                progress.position.toInt()
            } else {
                (progress.progressPercent?.let { it / 100f * progress.duration }?.toLong() ?: 0L).toInt()
            }
            builder.setLastPlaybackPositionMillis(positionMs)
        } else if (progress.progressPercent != null && progress.progressPercent > 0f) {
            // No real duration known (e.g. Simkl/Trakt sync), but we have a percent.
            // Use synthetic values so the launcher can render a progress bar.
            val syntheticDuration = 100_000
            val syntheticPosition = (progress.progressPercent / 100f * syntheticDuration).toInt()
            builder.setDurationMillis(syntheticDuration)
            builder.setLastPlaybackPositionMillis(syntheticPosition)
        }

        if (type == TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE) {
            progress.season?.let { builder.setSeasonNumber(it) }
            progress.episode?.let { builder.setEpisodeNumber(it) }
            progress.episodeTitle?.let { builder.setEpisodeTitle(it) }
        }

        return builder.build().toContentValues().also {
            // COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS drives launcher ordering;
            // the Builder method was added after tvprovider 1.0.0, so set directly.
            it.put("last_engagement_time_utc_millis", progress.lastWatched)
            // Explicitly clear poster art when no image is available, so UPDATE operations
            // don't leave stale artwork from previous reconcile cycles.
            if (imageUri == null) {
                it.putNull(TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_URI)
            }
            if (progress.logo.isNullOrBlank()) {
                it.putNull(TvContractCompat.PreviewPrograms.COLUMN_LOGO_URI)
            }
            // Clear duration/position when unknown so stale values (e.g. previous 1hr fallback)
            // don't persist across UPDATE cycles.
            if (progress.duration <= 0 && (progress.progressPercent == null || progress.progressPercent <= 0f)) {
                it.putNull("duration_millis")
                it.putNull("last_playback_position_millis")
            }
        }
    }

    private fun writeChannelLogo(channelId: Long) {
        runCatching {
            val bitmap = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
                ?: return
            context.contentResolver.openOutputStream(
                TvContractCompat.buildChannelLogoUri(channelId)
            )?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { Log.w(TAG, "writeChannelLogo failed", it) }
    }

    /**
     * Mirrors WatchProgressPreferences.createKey() so channel and storage agree on identity.
     */
    private fun progressKey(progress: WatchProgress): String =
        if (progress.season != null && progress.episode != null)
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        else
            progress.contentId

    private suspend fun ensureNamedChannel(
        key: String,
        displayName: String,
        launchMode: String
    ): Long? {
        val providerId = "${context.packageName}:$key"
        val stored = prefs.getChannelId(key)
        if (stored != null && channelExists(stored)) {
            updateNamedChannel(stored, providerId, displayName, launchMode)
            return stored
        }
        if (stored != null) prefs.clearChannelId(key)

        val orphan = findChannelByProviderId(providerId)
        if (orphan != null) {
            prefs.setChannelId(key, orphan)
            updateNamedChannel(orphan, providerId, displayName, launchMode)
            return orphan
        }

        val appLinkUri = Uri.parse(
            Intent(context, MainActivity::class.java).apply {
                putExtra("launchMode", launchMode)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }.toUri(Intent.URI_INTENT_SCHEME)
        )
        val channel = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(displayName)
            .setAppLinkIntentUri(appLinkUri)
            .setInternalProviderId(providerId)
            .build()
        val inserted = context.contentResolver.insert(
            TvContractCompat.Channels.CONTENT_URI,
            channel.toContentValues()
        ) ?: return null
        val id = ContentUris.parseId(inserted)
        prefs.setChannelId(key, id)
        writeChannelLogo(id)
        TvContractCompat.requestChannelBrowsable(context, id)
        return id
    }

    private fun updateNamedChannel(
        channelId: Long,
        providerId: String,
        displayName: String,
        launchMode: String
    ) {
        val appLinkUri = Uri.parse(
            Intent(context, MainActivity::class.java).apply {
                putExtra("launchMode", launchMode)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }.toUri(Intent.URI_INTENT_SCHEME)
        )
        val values = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(displayName)
            .setAppLinkIntentUri(appLinkUri)
            .setInternalProviderId(providerId)
            .build()
            .toContentValues()
        context.contentResolver.update(TvContractCompat.buildChannelUri(channelId), values, null, null)
    }

    private fun channelExists(id: Long): Boolean = context.contentResolver.query(
        TvContractCompat.buildChannelUri(id),
        arrayOf(TvContractCompat.Channels._ID),
        null, null, null
    )?.use { it.moveToFirst() } == true

    private fun findChannelByProviderId(providerId: String): Long? =
        context.contentResolver.query(
            TvContractCompat.Channels.CONTENT_URI,
            arrayOf(TvContractCompat.Channels._ID, TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID),
            null, null, null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(TvContractCompat.Channels._ID)
            val providerIndex = cursor.getColumnIndexOrThrow(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID)
            while (cursor.moveToNext()) {
                if (cursor.getString(providerIndex) == providerId) return@use cursor.getLong(idIndex)
            }
            null
        }

    private fun reconcileWatchProgress(
        channelId: Long,
        channelScope: String,
        profileId: Int,
        items: List<WatchProgress>
    ) {
        val existing = queryExistingPrograms(channelId)
        val desiredKeys = items.map(::progressKey).toSet()
        removeStalePrograms(existing, desiredKeys, channelScope)
        items.forEachIndexed { index, progress ->
            val key = progressKey(progress)
            val rowIds = existing[key]
            val values = buildProgramValues(progress, channelId, index, key, profileId)
            upsertProgram(rowIds, values, key)
        }
    }

    private fun reconcileLivePrograms(
        channelId: Long,
        channelScope: String,
        profileId: Int,
        channels: List<LiveTvChannel>,
        programmesByChannel: Map<String, List<com.nuvio.tv.domain.model.LiveTvProgramme>>
    ) {
        val existing = queryExistingPrograms(channelId)
        val desiredKeys = channels.map { "live:${it.id}:${it.streamUrl}" }.toSet()
        removeStalePrograms(existing, desiredKeys, channelScope)
        val now = System.currentTimeMillis()
        channels.forEachIndexed { index, channel ->
            val key = "live:${channel.id}:${channel.streamUrl}"
            val currentProgramme = programmesByChannel[channel.id]
                ?.firstOrNull { it.startMillis <= now && it.endMillis > now }
            val intentUri = Uri.parse(
                Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra("launchMode", "direct_stream")
                    putExtra("profileId", profileId)
                    putExtra("contentId", channel.id)
                    putExtra("contentType", "channel")
                    putExtra("name", channel.name)
                    putExtra("streamUrl", channel.streamUrl)
                    putExtra("poster", channel.logoUrl)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }.toUri(Intent.URI_INTENT_SCHEME)
            )
            val builder = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
                .setTitle(channel.name)
                .setDescription(currentProgramme?.title)
                .setIntentUri(intentUri)
                .setInternalProviderId(key)
                .setWeight(Int.MAX_VALUE - index)
            (currentProgramme?.iconUrl ?: channel.logoUrl)?.let {
                builder.setPosterArtUri(Uri.parse(it))
                    .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            }
            val values = builder.build().toContentValues().also {
                it.put("last_engagement_time_utc_millis", now)
            }
            upsertProgram(existing[key], values, key)
        }
    }

    private fun removeStalePrograms(
        existing: Map<String, List<Long>>,
        desiredKeys: Set<String>,
        channelScope: String
    ) {
        existing.filterKeys { it !in desiredKeys }.forEach { (key, rowIds) ->
            rowIds.forEach { context.contentResolver.delete(TvContractCompat.buildPreviewProgramUri(it), null, null) }
            syncedProgramFingerprints.remove("$channelScope:$key")
        }
    }

    private fun upsertProgram(rowIds: List<Long>?, values: ContentValues, key: String) {
        if (rowIds.isNullOrEmpty()) {
            context.contentResolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, values)
        } else {
            context.contentResolver.update(TvContractCompat.buildPreviewProgramUri(rowIds.first()), values, null, null)
            rowIds.drop(1).forEach {
                context.contentResolver.delete(TvContractCompat.buildPreviewProgramUri(it), null, null)
            }
        }
        Log.d(TAG, "Reconciled program key=$key")
    }

    private fun stableKey(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(56) +
            "_${Integer.toHexString(value.hashCode())}"
}
