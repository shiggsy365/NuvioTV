package com.nuvio.tv.domain.model

data class LiveTvSettings(
    val enabled: Boolean = false,
    val playlistUrl: String = "",
    val epgUrl: String = "",
    val userAgent: String = "NuvioTV",
    val playlistUpdatedAt: Long = 0,
    val epgUpdatedAt: Long = 0,
    val dispatcharrEnabled: Boolean = false,
    val dispatcharrBaseUrl: String = "",
    val dispatcharrApiToken: String = ""
) {
    val isConfigured: Boolean get() = enabled && playlistUrl.isNotBlank() && epgUrl.isNotBlank()
}

data class LiveTvChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val group: String,
    val logoUrl: String? = null,
    val number: String? = null,
    val catchup: String? = null,
    val catchupSource: String? = null,
    val catchupDays: Int? = null
) {
    fun playbackUrl(programme: LiveTvProgramme?, nowMillis: Long = System.currentTimeMillis()): String {
        val source = catchupSource?.takeIf(String::isNotBlank) ?: return streamUrl
        val item = programme ?: return streamUrl
        if (item.endMillis > nowMillis) return streamUrl
        val days = catchupDays
        if (days != null && days > 0) {
            val oldestStartMillis = nowMillis - days * 24L * 60L * 60L * 1000L
            if (item.startMillis < oldestStartMillis) return streamUrl
        }
        val startSeconds = item.startMillis / 1000L
        val endSeconds = item.endMillis / 1000L
        val durationSeconds = ((item.endMillis - item.startMillis) / 1000L).coerceAtLeast(0L)
        return source
            .replace("{utc}", startSeconds.toString())
            .replace("{utcend}", endSeconds.toString())
            .replace("{start}", startSeconds.toString())
            .replace("{end}", endSeconds.toString())
            .replace("{timestamp}", startSeconds.toString())
            .replace("{duration}", durationSeconds.toString())
    }
}

data class LiveTvProgramme(
    val channelId: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val description: String? = null,
    val category: String? = null,
    val iconUrl: String? = null
)

data class LiveTvGuide(
    val channels: List<LiveTvChannel> = emptyList(),
    val programmes: List<LiveTvProgramme> = emptyList(),
    val loadedAt: Long = 0
) {
    val groups: List<String> get() = channels.map { it.group }.distinct()
}

data class LiveTvStreamOption(
    val id: String,
    val name: String,
    val url: String?,
    val source: String? = null,
    val status: String? = null,
    val resolution: String? = null,
    val bitrate: String? = null
)
