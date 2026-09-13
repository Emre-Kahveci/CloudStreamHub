package com.cloudstream.tr.kultfilmler

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class KultFilmler : MainAPI() {
    override var mainUrl = "https://kultfilmler.net"
    override var name = "KultFilmler"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Son Eklenenler",
        "${mainUrl}/film-arsivi/" to "Film Arşivi",
        "${mainUrl}/dizi-kategori/mini-dizi-izle/" to "Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            val sep = if (request.data.contains("?")) "&" else "?"
            "${request.data}${sep}sayfa=${page}"
        }

        val doc = app.get(targetUrl).document
        val items = doc.select("a.mcard, a.dcard").mapNotNull { el ->
            parseSearchElement(el)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val targetUrl = if (page <= 1) {
            "${mainUrl}/?s=${query}"
        } else {
            "${mainUrl}/?s=${query}&sayfa=${page}"
        }

        val doc = app.get(targetUrl).document
        val items = doc.select("a.mcard, a.dcard, article, div.item").mapNotNull { el ->
            parseSearchElement(el)
        }.distinctBy { it.url }

        return newSearchResponseList(items, hasNext = items.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    fun parseSearchElement(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a") ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null

        val imgEl = element.selectFirst("img")
        val title = imgEl?.attr("alt")?.ifBlank { null }
            ?: element.selectFirst("h2, h3, .title, .entry-title")?.text()?.trim()
            ?: linkEl.attr("title").ifBlank { null }
            ?: return null

        val poster = fixUrlNull(
            imgEl?.attr("data-src")?.ifBlank { null }
                ?: imgEl?.attr("data-lazy-src")?.ifBlank { null }
                ?: imgEl?.attr("src")?.ifBlank { null }
        )

        val year = element.selectFirst(".year, .release-date, span.C a, span.date")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        val isTv = href.contains("/dizi/") || element.hasClass("dcard")
        val type = if (isTv) TvType.TvSeries else TvType.Movie

        return if (isTv) {
            newTvSeriesSearchResponse(title.trim(), href, type) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title.trim(), href, type) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        return parseLoadMetadata(doc, url)
    }

    suspend fun parseLoadMetadata(doc: Document, url: String): LoadResponse? {
        val title = doc.selectFirst("h1, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }?.replace(" - Kült Filmler", "")?.replace(" İzle", "")?.trim() ?: return null

        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("div.poster img, img.pimg")?.let {
                    it.attr("data-src").ifBlank { null } ?: it.attr("src").ifBlank { null }
                }
        )
        val description = doc.selectFirst("meta[property='og:description'], div.wp-content p, .overview p")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }
        val year = doc.selectFirst("a[href*='/yil/'], span.date, span.year")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val score = doc.selectFirst("span.rating, .score, span.imdb")?.text()?.trim()
        val tags = doc.select("a[href*='/tur/'], a[href*='/kategori/']").map { it.text().trim() }.filter { it.isNotBlank() }

        val episodeElements = doc.select("a[href*='/bolum/']").filter { el ->
            val text = el.text()
            text.contains("Sezon", ignoreCase = true) || text.contains("Bölüm", ignoreCase = true)
        }.distinctBy { it.attr("href") }

        val isTvSeries = url.contains("/dizi/") || episodeElements.isNotEmpty()

        return if (isTvSeries) {
            val episodes = episodeElements.mapIndexedNotNull { index, el ->
                val epHref = fixUrlNull(el.attr("href")) ?: return@mapIndexedNotNull null
                val epText = el.text().trim()

                val sMatch = Regex("""(\d+)\.\s*Sezon""").find(epText)
                val eMatch = Regex("""(\d+)\.\s*B[öo]l[üu]m""").find(epText)

                val seasonNum = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = eMatch?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
                val epTitle = "${seasonNum}. Sezon ${epNum}. Bölüm"

                newEpisode(epHref) {
                    this.name = epTitle
                    this.season = seasonNum
                    this.episode = epNum
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.score = Score.from10(score)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.score = Score.from10(score)
            }
        }
    }

    data class VidpapiResponse(
        @JsonProperty("videoSource") val videoSource: String? = null,
        @JsonProperty("securedLink") val securedLink: String? = null,
        @JsonProperty("hls") val hls: Boolean? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var linksFound = false

        // 1. Collect potential iframe embed sources
        val iframes = mutableListOf<String>()
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (src.isNotBlank()) {
                fixUrlNull(src)?.let { iframes.add(it) }
            }
        }

        // Also inspect JSON-embedded iframes in script blocks
        val scriptContent = doc.select("script").map { it.data() }.joinToString("\n")
        Regex("""https?:\\?/\\?/[^"'\s<>]+vidpapi\.xyz[^"'\s<>]*""").findAll(scriptContent).forEach { match ->
            val clean = match.value.replace("""\/""", "/")
            fixUrlNull(clean)?.let { iframes.add(it) }
        }

        for (iframeUrl in iframes.distinct()) {
            if (iframeUrl.contains("vidpapi.xyz")) {
                try {
                    val vidpapiDoc = app.get(iframeUrl, referer = mainUrl).text

                    // Subtitle discovery from playerjsSubtitle
                    Regex("""playerjsSubtitle\s*=\s*["']([^"']+)["']""").find(vidpapiDoc)?.let { match ->
                        val rawSub = match.groupValues[1]
                        val subLang = Regex("""\[(.*?)\]""").find(rawSub)?.groupValues?.get(1) ?: "Türkçe"
                        val subUrl = rawSub.replace(Regex("""\[.*?\]"""), "").trim()
                        if (subUrl.startsWith("http")) {
                            subtitleCallback(
                                SubtitleFile(
                                    lang = subLang,
                                    url = subUrl
                                )
                            )
                        }
                    }

                    // Request direct video stream via vidpapi getVideo API
                    val dataId = iframeUrl.substringAfter("/video/").substringBefore("/").substringBefore("?")
                    if (dataId.isNotBlank()) {
                        val apiUrl = "https://vidpapi.xyz/player/index.php?data=${dataId}&do=getVideo"
                        val apiResp = app.post(
                            apiUrl,
                            headers = mapOf(
                                "Referer" to iframeUrl,
                                "X-Requested-With" to "XMLHttpRequest"
                            )
                        ).parsedSafe<VidpapiResponse>()

                        val streamCandidate = apiResp?.videoSource?.ifBlank { null }
                            ?: apiResp?.securedLink?.ifBlank { null }

                        if (streamCandidate != null && (streamCandidate.contains(".m3u8") || streamCandidate.contains(".txt") || streamCandidate.contains("/hls/"))) {
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "$name HLS",
                                    url = streamCandidate,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "https://vidpapi.xyz/"
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            linksFound = true
                        }
                    }
                } catch (e: Exception) {
                    // continue to next candidate
                }
            } else {
                val success = loadExtractor(iframeUrl, referer = mainUrl, subtitleCallback) { link ->
                    callback(link)
                    linksFound = true
                }
                if (success) linksFound = true
            }
        }

        return linksFound
    }
}
