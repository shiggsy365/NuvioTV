package com.nuvio.tv.domain.model

data class Podcast(
    val id: Long,
    val title: String,
    val author: String,
    val description: String,
    val feedUrl: String,
    val imageUrl: String?,
    val episodeCount: Int
)

data class PodcastEpisode(
    val id: Long,
    val feedId: Long,
    val title: String,
    val description: String,
    val audioUrl: String,
    val imageUrl: String?,
    val publishedAt: Long,
    val durationSeconds: Int,
    val guid: String
)
