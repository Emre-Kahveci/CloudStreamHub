package com.cloudstream.tr.animecix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class TauVideo : ExtractorApi() {
    override val name = "TauVideo"
    override val mainUrl = "https://tau-video.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef = referer ?: ""
        val videoKey = url.split("/").last()
        val videoUrl = "${mainUrl}/api/video/${videoKey}"

        val api = app.get(videoUrl).parsedSafe<TauVideoUrls>() ?: return

        for (video in api.urls) {
            val preflight = com.cloudstream.tr.core.network.StreamValidator.validateStream(
                url = video.url,
                headers = mapOf("Referer" to extRef),
                provider = this.name
            )
            if (!preflight.isValid) continue

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = video.url,
                    type = preflight.streamType
                ) {
                    this.referer = extRef
                    this.quality = getQualityFromName(video.label)
                }
            )
        }
    }

    data class TauVideoUrls(
        @JsonProperty("urls") val urls: List<TauVideoData> = emptyList()
    )

    data class TauVideoData(
        @JsonProperty("url") val url: String,
        @JsonProperty("label") val label: String? = null,
    )
}
