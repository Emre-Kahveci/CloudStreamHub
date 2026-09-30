package com.cloudstream.tr.core.resolvers

import com.cloudstream.tr.core.diagnostics.DiagnosticCategory
import com.cloudstream.tr.core.diagnostics.DiagnosticLogger
import com.cloudstream.tr.core.diagnostics.DiagnosticStage
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URLEncoder

data class YtsResponse(
    @param:JsonProperty("status") val status: String? = null,
    @param:JsonProperty("data") val data: YtsData? = null
)

data class YtsData(
    @param:JsonProperty("movies") val movies: List<YtsMovie>? = null
)

data class YtsMovie(
    @param:JsonProperty("id") val id: Int? = null,
    @param:JsonProperty("imdb_code") val imdbCode: String? = null,
    @param:JsonProperty("title") val title: String? = null,
    @param:JsonProperty("year") val year: Int? = null,
    @param:JsonProperty("rating") val rating: Double? = null,
    @param:JsonProperty("genres") val genres: List<String>? = null,
    @param:JsonProperty("description_full") val descriptionFull: String? = null,
    @param:JsonProperty("yt_trailer_code") val ytTrailerCode: String? = null,
    @param:JsonProperty("medium_cover_image") val mediumCoverImage: String? = null,
    @param:JsonProperty("large_cover_image") val largeCoverImage: String? = null,
    @param:JsonProperty("background_image_original") val backgroundImage: String? = null,
    @param:JsonProperty("torrents") val torrents: List<YtsTorrent>? = null
)

data class YtsTorrent(
    @param:JsonProperty("url") val url: String? = null,
    @param:JsonProperty("hash") val hash: String? = null,
    @param:JsonProperty("quality") val quality: String? = null,
    @param:JsonProperty("type") val type: String? = null,
    @param:JsonProperty("video_codec") val videoCodec: String? = null,
    @param:JsonProperty("size") val size: String? = null,
    @param:JsonProperty("seeds") val seeds: Int? = null,
    @param:JsonProperty("peers") val peers: Int? = null
)

object YtsResolver {
    private val mirrors = listOf(
        "https://en.yts.lu",
        "https://yts.bz",
        "https://yts.lt",
        "https://yts.mx",
        "https://web.yts.gg",
        "https://yts.do"
    )

    suspend fun resolve(imdbId: String, isMovie: Boolean): List<ExtractorLink> {
        if (!isMovie || imdbId.isBlank()) return emptyList()

        for (mirror in mirrors) {
            try {
                val targetUrl = "$mirror/api/v2/list_movies.json?query_term=$imdbId"
                val response = app.get(targetUrl, timeout = 4).parsedSafe<YtsResponse>()
                val movies = response?.data?.movies
                if (movies.isNullOrEmpty()) continue

                val movie = movies.firstOrNull() ?: continue
                val torrents = movie.torrents ?: continue
                val movieTitle = movie.title ?: "Movie"

                val links = torrents.mapNotNull { t ->
                    val hash = t.hash?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val encodedTitle = URLEncoder.encode(movieTitle, "UTF-8")
                    val magnet = "magnet:?xt=urn:btih:$hash&dn=$encodedTitle&index=0${TorrentTrackers.asMagnetParam}"

                    val qStr = t.quality ?: "Unknown"
                    val codecStr = t.videoCodec?.let { " $it" } ?: ""
                    val sizeStr = t.size?.let { " [$it]" } ?: ""
                    val seedCount = t.seeds ?: 0

                    val hasDebrid = DebridConfig.isDebridEnabled
                    if (!hasDebrid && seedCount < 3) return@mapNotNull null

                    val seedStr = " ($seedCount seeds)"
                    val displayName = "YTS $qStr$codecStr$sizeStr$seedStr".trim()

                    val mappedQuality = when (qStr.lowercase()) {
                        "2160p", "4k" -> Qualities.P2160.value
                        "1080p" -> Qualities.P1080.value
                        "720p" -> Qualities.P720.value
                        else -> Qualities.Unknown.value
                    }

                    com.lagradost.cloudstream3.utils.newExtractorLink(
                        source = "YTS",
                        name = displayName,
                        url = magnet,
                        type = ExtractorLinkType.MAGNET
                    ) {
                        this.quality = mappedQuality
                    }
                }
                if (links.isNotEmpty()) return links
            } catch (e: Exception) {
                DiagnosticLogger.log(
                    provider = "YTS",
                    stage = DiagnosticStage.LOAD,
                    category = DiagnosticCategory.NETWORK,
                    message = "Failed to query YTS mirror $mirror for $imdbId: ${e.message}"
                )
            }
        }
        return emptyList()
    }
}
