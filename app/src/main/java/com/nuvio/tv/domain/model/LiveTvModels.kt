package com.nuvio.tv.domain.model

data class LiveTvSettings(
    val enabled: Boolean = false,
    val playlistUrl: String = "",
    val epgUrl: String = "",
    val userAgent: String = "NuvioTV",
    val playlistUpdatedAt: Long = 0,
    val epgUpdatedAt: Long = 0
) {
    val isConfigured: Boolean get() = enabled && playlistUrl.isNotBlank() && epgUrl.isNotBlank()
}

data class LiveTvChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val group: String,
    val logoUrl: String? = null,
    val number: String? = null
)

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
