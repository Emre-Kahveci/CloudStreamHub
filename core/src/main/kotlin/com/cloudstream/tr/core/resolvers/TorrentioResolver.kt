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
    @param:JsonProperty("streams") val streams: List<TorrentioStream>? = null
)

data class TorrentioBehaviorHints(
    @param:JsonProperty("filename") val filename: String? = null,
    @param:JsonProperty("bingeGroup") val bingeGroup: String? = null
)

data class TorrentioStream(
    @param:JsonProperty("name") val name: String? = null,
    @param:JsonProperty("title") val title: String? = null,
    @param:JsonProperty("infoHash") val infoHash: String? = null,
    @param:JsonProperty("fileIdx") val fileIdx: Int? = null,
    @param:JsonProperty("behaviorHints") val behaviorHints: TorrentioBehaviorHints? = null,
    @param:JsonProperty("url") val url: String? = null
)

object TorrentioResolver {
    suspend fun resolve(imdbId: String, isMovie: Boolean, season: Int? = null, episode: Int? = null): List<ExtractorLink> {
        if (imdbId.isBlank()) return emptyList()
        val type = if (isMovie) "movie" else "series"
        val idPath = if (isMovie) imdbId else "$imdbId:$season:$episode"
        val options = "providers=yts,eztv,rarbg,1337x,thepiratebay,kickasstorrents,torrentgalaxy,magnetdl|qualityfilter=scr,cam|sort=qualitysize"
        val prefix = DebridConfig.getActiveDebridPrefix()?.let { "$it|" } ?: ""
        val fullOptions = "$prefix$options"
        val url = "https://torrentio.strem.fun/$fullOptions/stream/$type/$idPath.json"
        val hasDebrid = DebridConfig.isDebridEnabled

        try {
            val response = app.get(url, timeout = 10).parsedSafe<TorrentioResponse>()
            val streams = response?.streams ?: return emptyList()

            return streams.mapNotNull { stream ->
                val rawTitle = stream.title ?: stream.name ?: "Unknown"

                // Filter out CAM/TS/SCR if any slipped through
                if (rawTitle.contains("CAM", ignoreCase = true) || rawTitle.contains(" HDCAM", ignoreCase = true) || rawTitle.contains(" TELESYNC", ignoreCase = true) || rawTitle.contains(" TS", ignoreCase = true) || rawTitle.contains(" SCR", ignoreCase = true)) {
                    return@mapNotNull null
                }

                val seedMatch = Regex("""[\uD83D\uDC64\uD83D\uDC65👤👥]\s*(\d+)""").find(rawTitle)
                val seedCount = seedMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (!hasDebrid && seedCount < 3) return@mapNotNull null

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

                val isRemux = rawTitle.contains("REMUX", ignoreCase = true)
                val isDV = rawTitle.contains("DV", ignoreCase = true) || rawTitle.contains("Dolby Vision", ignoreCase = true)
                val isHDR10Plus = rawTitle.contains("HDR10+", ignoreCase = true)
                val isHDR = rawTitle.contains("HDR", ignoreCase = true) && !isHDR10Plus
                val isTrueHD = rawTitle.contains("TrueHD", ignoreCase = true)
                val isDtsHdMa = rawTitle.contains("DTS-HD MA", ignoreCase = true) || rawTitle.contains("DTS-HD", ignoreCase = true)
                val isAtmos = rawTitle.contains("Atmos", ignoreCase = true)
                val is71 = rawTitle.contains("7.1")
                val is51 = rawTitle.contains("5.1") || rawTitle.contains("DD")

                var tags = ""
                if (isRemux) tags += " [REMUX]"
                if (isDV) tags += " [DV]"
                if (isHDR10Plus) tags += " [HDR10+]"
                else if (isHDR) tags += " [HDR]"

                if (isTrueHD && isAtmos && is71) tags += " [TrueHD Atmos 7.1]"
                else if (isDtsHdMa && is71) tags += " [DTS-HD MA 7.1]"
                else if (isAtmos && is71) tags += " [Atmos 7.1]"
                else if (isAtmos) tags += " [Atmos]"
                else if (is71) tags += " [7.1]"
                else if (is51) tags += " [5.1]"

                val seedStr = " ($seedCount seeds)"
                val prefixStr = if (hasDebrid) "🚀 Debrid " else "Torrentio • "
                val displayName = "$prefixStr$resolution$tags ${if (size.isNotBlank()) "[$size] " else ""}$seedStr".trim()

                if (stream.url != null && !stream.url.startsWith("magnet:")) {
                    return@mapNotNull com.lagradost.cloudstream3.utils.newExtractorLink(
                        source = "Torrentio",
                        name = displayName,
                        url = stream.url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = mappedQuality
                    }
                }

                val hash = stream.infoHash?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val rawFirstLine = rawTitle.lines().firstOrNull()?.trim() ?: "Torrent"
                val sanitizedTitle = rawFirstLine.replace(Regex("""[^\w\s\.\-\(\)\[\]]"""), "").trim().ifBlank { "Torrent" }
                val cleanTitle = stream.behaviorHints?.filename?.takeIf { it.isNotBlank() } ?: sanitizedTitle
                val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")

                val fileIndex = stream.fileIdx ?: 0
                val magnetUrl = "magnet:?xt=urn:btih:$hash&dn=$encodedTitle&index=$fileIndex${TorrentTrackers.asMagnetParam}"

                com.lagradost.cloudstream3.utils.newExtractorLink(
                    source = "Torrentio",
                    name = displayName,
                    url = magnetUrl,
                    type = ExtractorLinkType.MAGNET
                ) {
                    this.quality = mappedQuality
                }
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
