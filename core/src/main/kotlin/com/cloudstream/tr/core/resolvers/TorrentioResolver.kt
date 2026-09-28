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

data class TorrentioResponse(
    @JsonProperty("streams") val streams: List<TorrentioStream>? = null
)

data class TorrentioBehaviorHints(
    @JsonProperty("filename") val filename: String? = null,
    @JsonProperty("bingeGroup") val bingeGroup: String? = null
)

data class TorrentioStream(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("infoHash") val infoHash: String? = null,
    @JsonProperty("fileIdx") val fileIdx: Int? = null,
    @JsonProperty("behaviorHints") val behaviorHints: TorrentioBehaviorHints? = null,
    @JsonProperty("url") val url: String? = null
)

object TorrentioResolver {
    suspend fun resolve(imdbId: String, isMovie: Boolean, season: Int? = null, episode: Int? = null): List<ExtractorLink> {
        if (imdbId.isBlank()) return emptyList()
        val type = if (isMovie) "movie" else "series"
        val idPath = if (isMovie) imdbId else "$imdbId:$season:$episode"
        val debridPrefix = DebridConfig.getActiveDebridPrefix()?.let { "$it/" } ?: ""
        val url = "https://torrentio.strem.fun/${debridPrefix}stream/$type/$idPath.json"

        try {
            val response = app.get(url, timeout = 10).parsedSafe<TorrentioResponse>()
            val streams = response?.streams ?: return emptyList()

            return streams.mapNotNull { stream ->
                val rawTitle = stream.title ?: stream.name ?: "Unknown"

                val sizeMatch = Regex("""\b(\d+(?:\.\d+)?\s*[GgMm]B)\b""").find(rawTitle)
                val size = sizeMatch?.groupValues?.get(1) ?: ""

                val resMatch = Regex("""\b(4K|2160p|1080p|720p)\b""", RegexOption.IGNORE_CASE).find(rawTitle)
                val resolution = resMatch?.groupValues?.get(1)?.uppercase() ?: "Unknown"

                val mappedQuality = when (resolution) {
                    "4K", "2160P" -> Qualities.P2160.value
                    "1080P" -> Qualities.P1080.value
                    "720P" -> Qualities.P720.value
                    else -> Qualities.Unknown.value
                }

                val isHDR = rawTitle.contains("HDR", ignoreCase = true)
                val isDV = rawTitle.contains("DV", ignoreCase = true) || rawTitle.contains("Dolby Vision", ignoreCase = true)
                val isAtmos = rawTitle.contains("Atmos", ignoreCase = true)

                val seedMatch = Regex("""[\U0001F464\U0001F465\uD83D\uDC64\uD83D\uDC65👤👥]\s*(\d+)""").find(rawTitle)
                val seedStr = seedMatch?.let { " (${it.groupValues[1]} seeds)" } ?: ""

                var tags = ""
                if (isHDR) tags += " HDR"
                if (isDV) tags += " DV"
                if (isAtmos) tags += " Atmos"

                val prefix = if (DebridConfig.isDebridEnabled) "🚀 Debrid " else ""
                val displayName = "$prefix$resolution$tags ${if (size.isNotBlank()) "[$size]" else ""}$seedStr".trim()

                // If direct video stream is provided, use it directly
                if (stream.url != null && !stream.url.startsWith("magnet:")) {
                    return@mapNotNull ExtractorLink(
                        source = "Torrentio",
                        name = displayName,
                        url = stream.url,
                        referer = "",
                        quality = mappedQuality,
                        type = ExtractorLinkType.VIDEO
                    )
                }

                val hash = stream.infoHash?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val rawFirstLine = rawTitle.lines().firstOrNull()?.trim() ?: "Torrent"
                val sanitizedTitle = rawFirstLine.replace(Regex("""[^\w\s\.\-\(\)\[\]]"""), "").trim().ifBlank { "Torrent" }
                val cleanTitle = stream.behaviorHints?.filename?.takeIf { it.isNotBlank() } ?: sanitizedTitle
                val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
                val fileIndex = stream.fileIdx ?: 0
                val magnetUrl = "magnet:?xt=urn:btih:$hash&dn=$encodedTitle&index=$fileIndex${TorrentTrackers.asMagnetParam}"

                ExtractorLink(
                    source = "Torrentio",
                    name = displayName,
                    url = magnetUrl,
                    referer = "",
                    quality = mappedQuality,
                    type = ExtractorLinkType.MAGNET
                )
            }
        } catch (e: Exception) {
            DiagnosticLogger.log(
                provider = "Torrentio",
                stage = DiagnosticStage.LOAD,
                category = DiagnosticCategory.NETWORK,
                message = "Failed to fetch Torrentio streams for $imdbId: ${e.message}",
                throwable = e
            )
            return emptyList()
        }
    }
}
