package com.nuvio.tv.data.remote.api

import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface PodcastApi {
    @GET("search")
    suspend fun search(
        @Query("term") query: String,
        @Query("media") media: String = "podcast",
        @Query("entity") entity: String = "podcast",
        @Query("country") country: String = "GB",
        @Query("limit") limit: Int = 40
    ): ApplePodcastResponse

    @GET("lookup")
    suspend fun lookup(
        @Query("id") ids: String,
        @Query("entity") entity: String = "podcast",
        @Query("country") country: String = "GB"
    ): ApplePodcastResponse
}

interface PodcastChartsApi {
    @GET("api/v2/{country}/podcasts/top/{limit}/podcasts.json")
    suspend fun topPodcasts(
        @Path("country") country: String = "gb",
        @Path("limit") limit: Int = 40
    ): ApplePodcastChartResponse
}

@JsonClass(generateAdapter = true)
data class ApplePodcastResponse(
    val resultCount: Int = 0,
    val results: List<ApplePodcastDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class ApplePodcastDto(
    val collectionId: Long,
    val collectionName: String = "",
    val artistName: String = "",
    val feedUrl: String? = null,
    val artworkUrl600: String? = null,
    val artworkUrl100: String? = null,
    val trackCount: Int? = null,
    val primaryGenreName: String? = null
)

@JsonClass(generateAdapter = true)
data class ApplePodcastChartResponse(val feed: ApplePodcastChartFeed = ApplePodcastChartFeed())

@JsonClass(generateAdapter = true)
data class ApplePodcastChartFeed(val results: List<ApplePodcastChartItem> = emptyList())

@JsonClass(generateAdapter = true)
data class ApplePodcastChartItem(
    val id: String,
    val name: String = "",
    val artistName: String = "",
    val artworkUrl100: String? = null
)
