package com.cloudstream.tr.core.extractors

import com.cloudstream.tr.core.network.SafeHttpClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class FilmizleInExtractor : ExtractorApi() {
    override val name = "FilmizleIn"
    override val mainUrl = "https://player.filmizle.in"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val videoId = Regex("""/video/([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: return
            val playerDoc = app.get(
                url,
                headers = SafeHttpClient.defaultHeaders(referer = referer ?: "https://sinemacc.com/")
            ).text

            val hash = Regex("""hash\s*[:=]\s*["']([^"']+)["']""").find(playerDoc)?.groupValues?.get(1) ?: videoId
            val apiUrl = "https://player.filmizle.in/player/index.php?data=${videoId}&do=getVideo"

            val apiResp = app.post(
                apiUrl,
                data = mapOf(
                    "hash" to hash,
                    "r" to (referer ?: "https://sinemacc.com/"),
                    "s" to ""
                ),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to url,
                    "User-Agent" to SafeHttpClient.DEFAULT_USER_AGENT
                )
            ).text

            val streamMatch = Regex(""""securedLink"\s*:\s*"([^"]+)"""").find(apiResp)
                ?: Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""").find(apiResp)
            val streamUrl = streamMatch?.groupValues?.getOrNull(1)?.replace("\\/", "/")
                ?: streamMatch?.value?.replace("\\/", "/")

            if (!streamUrl.isNullOrBlank()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name HLS",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://player.filmizle.in/"
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (_: Exception) {}
    }
}
