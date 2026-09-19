package com.cloudstream.tr.core.extractors

import com.cloudstream.tr.core.network.SafeHttpClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class CloseLoadExtractor : ExtractorApi() {
    override val name = "CloseLoad"
    override val mainUrl = "https://closeload.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val host = Regex("""https?://[^/]+""").find(url)?.value ?: mainUrl
            val doc = app.get(
                url,
                headers = SafeHttpClient.defaultHeaders(referer = referer ?: host)
            ).document

            val videoSrc = doc.selectFirst("video source")?.attr("src")
                ?: doc.selectFirst("source")?.attr("src")
                ?: Regex("""file:\s*["']([^"']+)["']""").find(doc.html())?.groupValues?.get(1)

            if (!videoSrc.isNullOrBlank()) {
                val fullStream = if (videoSrc.startsWith("http")) videoSrc else if (videoSrc.startsWith("//")) "https:$videoSrc" else "${host}/$videoSrc"
                val isM3u8 = fullStream.contains(".m3u8")
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name Stream",
                        url = fullStream,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "${host}/"
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (_: Exception) {}
    }
}
