package com.cloudstream.tr.hub

import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLDecoder
import java.net.URLEncoder

data class TmdbPageResponse(
    @JsonProperty("page") val page: Int? = null,
    @JsonProperty("results") val results: List<TmdbItem>? = null,
    @JsonProperty("total_pages") val totalPages: Int? = null,
    @JsonProperty("total_results") val totalResults: Int? = null
)

data class TmdbItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("genre_ids") val genreIds: List<Int>? = null
)

data class TmdbGenre(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null
)

data class TmdbCast(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null,
    @JsonProperty("character") val character: String? = null
)

data class TmdbCredits(
    @JsonProperty("cast") val cast: List<TmdbCast>? = null
)

data class TmdbVideo(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("key") val key: String? = null,
    @JsonProperty("site") val site: String? = null,
    @JsonProperty("type") val type: String? = null
)

data class TmdbVideos(
    @JsonProperty("results") val results: List<TmdbVideo>? = null
)

data class TmdbSeason(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("episode_count") val episodeCount: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null
)

data class TmdbDetailResponse(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
    @JsonProperty("credits") val credits: TmdbCredits? = null,
    @JsonProperty("videos") val videos: TmdbVideos? = null,
    @JsonProperty("seasons") val seasons: List<TmdbSeason>? = null
)

data class AggregatorLinkPayload(
    val title: String,
    val year: Int?,
    val isMovie: Boolean,
    val season: Int? = null,
    val episode: Int? = null,
    val tmdbId: Int? = null
) {
    fun toUrlData(): String {
        val encTitle = URLEncoder.encode(title, "UTF-8")
        val y = year?.toString() ?: ""
        val s = season?.toString() ?: ""
        val ep = episode?.toString() ?: ""
        val tId = tmdbId?.toString() ?: ""
        return "hub://item?title=$encTitle&year=$y&isMovie=$isMovie&season=$s&episode=$ep&tmdbId=$tId"
    }

    companion object {
        fun fromUrlData(data: String): AggregatorLinkPayload? {
            return try {
                val query = data.substringAfter("?", "")
                if (query.isBlank()) return null
                val params = query.split("&").associate {
                    val parts = it.split("=", limit = 2)
                    parts[0] to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                }
                AggregatorLinkPayload(
                    title = params["title"] ?: return null,
                    year = params["year"]?.toIntOrNull(),
                    isMovie = params["isMovie"]?.toBoolean() ?: true,
                    season = params["season"]?.toIntOrNull(),
                    episode = params["episode"]?.toIntOrNull(),
                    tmdbId = params["tmdbId"]?.toIntOrNull()
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
