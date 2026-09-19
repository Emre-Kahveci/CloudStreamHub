package com.cloudstream.tr.core.extractors

import com.cloudstream.tr.core.network.SafeHttpClient
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

open class PichiveExtractor : ExtractorApi() {
    override val name = "Pichive"
    override val mainUrl = "https://four.pichive.online"
    override val requiresReferer = true

    data class PichiveSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("title") val title: String? = null
    )

    data class PichivePlaylist(
        @JsonProperty("sources") val sources: List<PichiveSource>? = null
    )

    data class PichiveResponse(
        @JsonProperty("state") val state: Boolean? = null,
        @JsonProperty("playlist") val playlist: List<PichivePlaylist>? = null
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val playerResp = app.get(
                url,
                headers = SafeHttpClient.defaultHeaders(referer = referer ?: mainUrl)
            ).text

            val openPlayerMatch = Regex("""openPlayer\s*\(\s*['"]([^'"]+)['"]""").find(playerResp)
            val playlistToken = openPlayerMatch?.groupValues?.get(1)

            Regex("""\{\s*["']file["']\s*:\s*["']([^"']+)["'].*?["']lang["']\s*:\s*["']([^"']+)["']""").findAll(playerResp).forEach { sm ->
                val subFile = sm.groupValues[1].replace("""\/""", "/")
                val subLang = sm.groupValues[2]
                subtitleCallback(
                    SubtitleFile(
                        lang = if (subLang == "tr") "Türkçe" else "İngilizce",
                        url = subFile
                    )
                )
            }

            if (!playlistToken.isNullOrBlank()) {
                val host = Regex("""https?://[^/]+""").find(url)?.value ?: mainUrl
                val sourceUrl = "${host}/source2.php?v=${URLEncoder.encode(playlistToken, "UTF-8")}"
                val pichiveJson = app.get(
                    sourceUrl,
                    headers = mapOf(
                        "Referer" to url,
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                ).parsedSafe<PichiveResponse>()

                pichiveJson?.playlist?.forEach { pl ->
                    pl.sources?.forEach { s ->
                        val fileUrl = s.file ?: return@forEach
                        val masterUrl = fileUrl.replace("m.php", "master.m3u8")
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name ${s.title ?: "HLS"}",
                                url = masterUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "${host}/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
