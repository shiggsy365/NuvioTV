package com.nuvio.tv.core.image

import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Emits URLs of images that were refreshed by background revalidation.
 * Composables observe this to reload visible posters in-place.
 */
object ImageInvalidationBus {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun notifyInvalidated(url: String) {
        _events.tryEmit(url)
    }

    /**
     * Force-evicts [url] from both memory and disk cache, then notifies listeners.
     * Use when the caller knows the underlying image content may have changed at
     * the same URL (e.g. a manual EPG/playlist refresh) and can't rely on HTTP
     * cache-control headers to trigger revalidation.
     */
    @OptIn(ExperimentalCoilApi::class)
    fun evictAndNotify(imageLoader: ImageLoader, url: String) {
        try {
            imageLoader.memoryCache?.let { memoryCache ->
                memoryCache.keys.filter { it.key.contains(url) }.forEach(memoryCache::remove)
            }
        } catch (_: Exception) { }
        try {
            imageLoader.diskCache?.let { diskCache ->
                diskCache.openSnapshot(url)?.use { diskCache.remove(url) }
            }
        } catch (_: Exception) { }
        notifyInvalidated(url)
    }
}
