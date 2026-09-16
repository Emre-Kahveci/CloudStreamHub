package com.cloudstream.tr.sinemacx

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class SinemaCX : MainAPI() {
    override var mainUrl = "https://www.sinema.gg"
    override var name = "SinemaCX"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Son Eklenen Filmler",
        "${mainUrl}/kategori/turkce-dublaj-filmler/" to "Türkçe Dublaj",
        "${mainUrl}/kategori/turkce-altyazili-filmler/" to "Türkçe Altyazılı",
        "${mainUrl}/en-cok-izlenen-filmler/" to "En Çok İzlenenler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            val base = request.data.removeSuffix("/")
            "${base}/page/${page}/"
        }

        val doc = app.get(targetUrl).document
        val items = doc.select("div.frag-k, div.film-k").mapNotNull { el ->
            parseFragCard(el)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    fun parseFragCard(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a") ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null

        val imgEl = element.selectFirst("img")
        val title = element.selectFirst("div.f-baslik, h2, h3, .baslik")?.text()?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: imgEl?.attr("alt")?.ifBlank { null }
            ?: return null

        val poster = fixUrlNull(
            imgEl?.attr("data-src")?.ifBlank { null }
                ?: imgEl?.attr("src")?.ifBlank { null }
        )

        val year = element.selectFirst("span.yil, span.f-yil, div.yil")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val cleanTitle = title.replace(Regex("""\s*\(\d{4}\)$"""), "").replace(" Türkçe Dublaj İzle", "").replace(" İzle", "").trim()

        return newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val targetUrl = if (page <= 1) {
            "${mainUrl}/?s=${query}"
        } else {
            "${mainUrl}/page/${page}/?s=${query}"
        }

        val doc = app.get(targetUrl).document
        val items = doc.select("div.frag-k, div.film-k, article.film").mapNotNull { el ->
            parseFragCard(el)
        }.distinctBy { it.url }

        return newSearchResponseList(items, hasNext = items.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        return parseLoadMetadata(doc, url)
    }

    suspend fun parseLoadMetadata(doc: Document, url: String): LoadResponse? {
        val rawTitle = doc.selectFirst("h1, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }?.replace(" - SinemaCX", "")?.replace(" - Sinema.gg", "")?.replace(" Full HD İzle", "")?.replace(" İzle", "")?.trim() ?: return null

        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("div.f-afis img, div.f-bilgi img, .afis img")?.let {
                    it.attr("data-src").ifBlank { null } ?: it.attr("src").ifBlank { null }
                }
        )

        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?: doc.selectFirst("div.f-ozet, div.konu, div.film-ozeti")?.text()?.trim()

        val year = doc.selectFirst("span.f-yil, span.yil, div.f-detay")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        val cleanTitle = rawTitle.replace(Regex("""\s*\(\d{4}\)$"""), "").trim()
        val score = doc.selectFirst("span.imdb, span.puan, div.f-puan")?.text()?.trim()
        val tags = doc.select("a[href*='/kategori/'], a[href*='/tur/']").map { it.text().trim() }.filter { it.isNotBlank() }

        return newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = Score.from10(score)
        }
    }

    data class FilmizleVideoResponse(
        @JsonProperty("securedLink") val securedLink: String? = null,
        @JsonProperty("hls") val hls: Boolean? = null,
        @JsonProperty("videoSource") val videoSource: String? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var linksFound = false
        val doc = app.get(data).document

        val pagesToCheck = mutableListOf(doc)
        val part2Url = doc.selectFirst("a[href*='/2/'], .part-sayfala a")?.attr("href")
            ?: if (!data.endsWith("/2/")) "${data.removeSuffix("/")}/2/" else null

        if (part2Url != null) {
            try {
                val p2Doc = app.get(fixUrl(part2Url)).document
                pagesToCheck.add(p2Doc)
            } catch (e: Exception) {
                // ignore
            }
        }

        val allIframes = mutableListOf<String>()
        for (pageDoc in pagesToCheck) {
            pageDoc.select("iframe").forEach { iframe ->
                val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
                if (src.isNotBlank() && !src.contains("youtube", ignoreCase = true)) {
                    fixUrlNull(src)?.let { allIframes.add(it) }
                }
            }
        }

        for (iframeUrl in allIframes.distinct()) {
            if (iframeUrl.contains("filmizle.in")) {
                try {
                    val videoId = Regex("""/(?:video|embed)/([a-zA-Z0-9_-]+)""").find(iframeUrl)?.groupValues?.get(1)
                    if (!videoId.isNullOrBlank()) {
                        val apiUrl = "https://player.filmizle.in/player/index.php?data=${videoId}&do=getVideo"
                        val resp = app.post(
                            apiUrl,
                            data = mapOf(
                                "hash" to videoId,
                                "r" to "https://www.sinema.gg/"
                            ),
                            headers = mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Referer" to iframeUrl,
                                "Origin" to "https://player.filmizle.in"
                            )
                        ).parsedSafe<FilmizleVideoResponse>()

                        val streamUrl = resp?.securedLink?.ifBlank { null } ?: resp?.videoSource?.ifBlank { null }
                        if (!streamUrl.isNullOrBlank() && streamUrl.contains(".m3u8")) {
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
                            linksFound = true
                        }
                    }
                } catch (e: Exception) {
                    // continue
                }
            } else {
                val success = loadExtractor(iframeUrl, referer = data, subtitleCallback) { link ->
                    callback(link)
                    linksFound = true
                }
                if (success) linksFound = true
            }
        }

        return linksFound
    }
}
