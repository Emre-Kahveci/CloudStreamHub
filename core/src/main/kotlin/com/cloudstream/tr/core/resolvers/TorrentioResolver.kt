package com.cloudstream.tr.core.resolvers

import com.cloudstream.tr.core.diagnostics.DiagnosticCategory
import com.cloudstream.tr.core.diagnostics.DiagnosticLogger
import com.cloudstream.tr.core.diagnostics.DiagnosticStage
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

data class TorrentioResponse(
    @JsonProperty("streams") val streams: List<TorrentioStream>? = null
)

data class TorrentioStream(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("infoHash") val infoHash: String? = null,
    @JsonProperty("url") val url: String? = null
)

object TorrentioResolver {
    suspend fun resolve(imdbId: String, isMovie: Boolean, season: Int? = null, episode: Int? = null): List<ExtractorLink> {
        if (imdbId.isBlank()) return emptyList()
        val type = if (isMovie) "movie" else "series"
        val idPath = if (isMovie) imdbId else "$imdbId:$season:$episode"
        val url = "https://torrentio.strem.fun/stream/$type/$idPath.json"

        try {
            val response = app.get(url).parsedSafe<TorrentioResponse>()
            val streams = response?.streams ?: return emptyList()

            return streams.mapNotNull { stream ->
                val magnetUrl = stream.url ?: stream.infoHash?.let { "magnet:?xt=urn:btih:$it" } ?: return@mapNotNull null
                val rawTitle = stream.title ?: stream.name ?: "Unknown"

                val sizeMatch = Regex("""\b(\d+(?:\.\d+)?\s*[GgMm]B)\b""").find(rawTitle)
                val size = sizeMatch?.groupValues?.get(1) ?: ""

                val resMatch = Regex("""\b(4K|2160p|1080p|720p)\b""").find(rawTitle)
                val resolution = resMatch?.groupValues?.get(1) ?: "Unknown"

                val isHDR = rawTitle.contains("HDR", ignoreCase = true)
                val isDV = rawTitle.contains("DV", ignoreCase = true) || rawTitle.contains("Dolby Vision", ignoreCase = true)
                val isAtmos = rawTitle.contains("Atmos", ignoreCase = true)

                var tags = ""
                if (isHDR) tags += " HDR"
                if (isDV) tags += " DV"
                if (isAtmos) tags += " Atmos"

                val name = "Torrentio $resolution$tags ${if (size.isNotBlank()) "[$size]" else ""}".trim()

                ExtractorLink(
                    source = "Torrentio",
                    name = name,
                    url = magnetUrl,
                    referer = "",
                    quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
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
