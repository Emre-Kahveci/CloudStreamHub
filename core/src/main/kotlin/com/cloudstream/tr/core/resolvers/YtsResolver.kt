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
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("data") val data: YtsData? = null
)

data class YtsData(
    @JsonProperty("movies") val movies: List<YtsMovie>? = null
)

data class YtsMovie(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("imdb_code") val imdbCode: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("rating") val rating: Double? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("description_full") val descriptionFull: String? = null,
    @JsonProperty("yt_trailer_code") val ytTrailerCode: String? = null,
    @JsonProperty("medium_cover_image") val mediumCoverImage: String? = null,
    @JsonProperty("large_cover_image") val largeCoverImage: String? = null,
    @JsonProperty("background_image_original") val backgroundImage: String? = null,
    @JsonProperty("torrents") val torrents: List<YtsTorrent>? = null
)

data class YtsTorrent(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("hash") val hash: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("video_codec") val videoCodec: String? = null,
    @JsonProperty("size") val size: String? = null,
    @JsonProperty("seeds") val seeds: Int? = null,
    @JsonProperty("peers") val peers: Int? = null
)

object YtsResolver {
    private val mirrors = listOf(
        "https://yts.lt",
        "https://yts.bz",
        "https://yts.do",
        "https://yts.mx"
    )

    suspend fun resolve(imdbId: String, isMovie: Boolean): List<ExtractorLink> {
        if (!isMovie || imdbId.isBlank()) return emptyList()

        for (mirror in mirrors) {
            try {
                val targetUrl = "$mirror/api/v2/list_movies.json?query_term=$imdbId"
                val response = app.get(targetUrl, timeout = 5).parsedSafe<YtsResponse>()
                val movies = response?.data?.movies
                if (movies.isNullOrEmpty()) continue

                val movie = movies.firstOrNull() ?: continue
                val torrents = movie.torrents ?: continue
                val movieTitle = movie.title ?: "Movie"

                return torrents.mapNotNull { t ->
                    val hash = t.hash?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val encodedTitle = URLEncoder.encode(movieTitle, "UTF-8")
                    val magnet = "magnet:?xt=urn:btih:$hash&dn=$encodedTitle&index=0${TorrentTrackers.asMagnetParam}"

                    val qStr = t.quality ?: "Unknown"
                    val codecStr = t.videoCodec?.let { " $it" } ?: ""
                    val sizeStr = t.size?.let { " [$it]" } ?: ""
                    val seedStr = t.seeds?.let { " ($it seeds)" } ?: ""
                    val displayName = "YTS $qStr$codecStr$sizeStr$seedStr".trim()

                    val mappedQuality = when (qStr.lowercase()) {
                        "2160p", "4k" -> Qualities.P2160.value
                        "1080p" -> Qualities.P1080.value
                        "720p" -> Qualities.P720.value
                        else -> Qualities.Unknown.value
                    }

                    val directTorrentUrl = t.url?.takeIf { it.startsWith("http") }
                    val finalUrl = directTorrentUrl ?: magnet
                    val linkType = if (directTorrentUrl != null) ExtractorLinkType.TORRENT else ExtractorLinkType.MAGNET

                    ExtractorLink(
                        source = "YTS",
                        name = displayName,
                        url = finalUrl,
                        referer = "",
                        quality = mappedQuality,
                        type = linkType
                    )
                }
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
