package com.cloudstream.tr.core.extractors

import com.cloudstream.tr.core.network.SafeHttpClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.util.Base64
import java.util.regex.Pattern

open class RapidVidExtractor : ExtractorApi() {
    override val name = "RapidVid"
    override val mainUrl = "https://rapidvid.org"
    override val requiresReferer = true

    companion object {
        fun decryptRapidvidAv(token: String): String {
            return try {
                val rev = token.reversed()
                val padLen = (4 - rev.length % 4) % 4
                val padded = rev + "=".repeat(padLen)
                val decodedBytes = Base64.getDecoder().decode(padded)
                val decodedStr = String(decodedBytes, Charsets.ISO_8859_1)
                val key = "K9L"
                val sb = StringBuilder()
                for (i in decodedStr.indices) {
                    val r = key[i % 3]
                    val n = decodedStr[i].code - (r.code % 5 + 1)
                    sb.append(n.toChar())
                }
                val inner = sb.toString()
                val innerPad = (4 - inner.length % 4) % 4
                val innerPadded = inner + "=".repeat(innerPad)
                String(Base64.getDecoder().decode(innerPadded), Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
        }

        suspend fun parseHtmlResponse(
            html: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            var foundStream = false
            try {
                val avPattern = Pattern.compile("""file:\s*av\(['"]([^'"]+)['"]\)""")
                val avMatcher = avPattern.matcher(html)
                if (avMatcher.find()) {
                    val token = avMatcher.group(1)
                    if (!token.isNullOrBlank()) {
                        val streamUrl = decryptRapidvidAv(token)
                        if (streamUrl.isNotBlank()) {
                            val preflight = com.cloudstream.tr.core.network.StreamValidator.validateStream(
                                url = streamUrl,
                                headers = mapOf("Referer" to "https://rapidvid.org/"),
                                provider = "RapidVid"
                            )
                            if (preflight.isValid) {
                                callback(
                                    newExtractorLink(
                                        source = "RapidVid",
                                        name = "RapidVid",
                                        url = streamUrl,
                                        type = preflight.streamType
                                    ) {
                                        this.referer = "https://rapidvid.org/"
                                    }
                                )
                                foundStream = true
                            }
                        }
                    }
                }

                val tracksPattern = Pattern.compile("""jwSetup\.tracks\s*=\s*(\[.+?\]);""", Pattern.DOTALL)
                val tracksMatcher = tracksPattern.matcher(html)
                if (tracksMatcher.find()) {
                    val tracksJson = tracksMatcher.group(1)
                    val trackPattern = Pattern.compile(""""file"\s*:\s*"([^"]+)"[^}]+?"label"\s*:\s*"([^"]+)"""")
                    val trackMatcher = trackPattern.matcher(tracksJson ?: "")
                    while (trackMatcher.find()) {
                        val file = trackMatcher.group(1)?.replace("\\/", "/") ?: continue
                        val label = trackMatcher.group(2) ?: "Türkçe"
                        subtitleCallback(SubtitleFile(lang = label.trim(), url = file))
                    }
                }
            } catch (e: Exception) {
                com.cloudstream.tr.core.diagnostics.DiagnosticLogger.log(
                    provider = "RapidVid",
                    stage = com.cloudstream.tr.core.diagnostics.DiagnosticStage.EXTRACTOR,
                    category = com.cloudstream.tr.core.diagnostics.DiagnosticCategory.EXTRACTOR,
                    message = "RapidVid HTML parsing failed: ${e.message}",
                    throwable = e
                )
            }
            return foundStream
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val resp = app.get(url, headers = SafeHttpClient.defaultHeaders(referer = referer ?: mainUrl)).text
            parseHtmlResponse(resp, subtitleCallback, callback)
        } catch (e: Exception) {
            com.cloudstream.tr.core.diagnostics.DiagnosticLogger.log(
                provider = name,
                stage = com.cloudstream.tr.core.diagnostics.DiagnosticStage.EXTRACTOR,
                category = com.cloudstream.tr.core.diagnostics.DiagnosticCategory.EXTRACTOR,
                message = "RapidVid getUrl failed: ${e.message}",
                url = url,
                throwable = e
            )
        }
    }
}
