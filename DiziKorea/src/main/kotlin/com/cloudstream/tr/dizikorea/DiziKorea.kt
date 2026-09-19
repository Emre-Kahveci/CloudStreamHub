package com.cloudstream.tr.dizikorea

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.cloudstream.tr.core.concurrency.BoundedParallelResolver
import com.cloudstream.tr.core.model.ProviderModels
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DiziKorea : MainAPI() {
    override var mainUrl = "https://dizikorea3.com"
    override var name = "DiziKorea"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "${mainUrl}/son-eklenen-bolumler" to "Son Eklenen Bölümler",
        "${mainUrl}/populer-diziler" to "Popüler Diziler",
        "${mainUrl}/kore-dizileri" to "Kore Dizileri",
        "${mainUrl}/cin-dizileri" to "Çin Dizileri",
        "${mainUrl}/tayland-dizileri" to "Tayland Dizileri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            "${request.data}?page=${page}"
        }

        val document = app.get(targetUrl).document
        val items = document.select("a.poster-card, div.content-grid a[href*='/dizi/']").mapNotNull { toSearchResult(it) }
        val deduped = ProviderModels.dedupSearchResults(items)

        return newHomePageResponse(request.name, deduped)
    }

    fun toSearchResult(element: Element): SearchResponse? {
        val link = if (element.tagName() == "a") element else element.selectFirst("a[href*='/dizi/']") ?: return null
        var href = fixUrlNull(link.attr("href")) ?: return null
        
        if (href.contains("/sezon-")) {
            href = href.substringBefore("/sezon-")
        }

        val img = element.selectFirst("img")
        val title = img?.attr("alt")?.trim()
            ?.ifBlank { null }
            ?: element.selectFirst(".poster-card-title, h3, h2, .title")?.text()?.trim()
            ?: link.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.ifBlank { null }
                ?: img?.attr("src")?.ifBlank { null }
        )

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val targetUrl = if (page <= 1) {
            "${mainUrl}/arama?q=${query}"
        } else {
            "${mainUrl}/arama?q=${query}&page=${page}"
        }

        val document = app.get(targetUrl).document
        val items = parseSearchResults(document)
        return newSearchResponseList(items, hasNext = false)
    }

    fun parseSearchResults(document: Document): List<SearchResponse> {
        val elements = document.select("a.poster-card, div.content-grid a[href*='/dizi/']")
        val results = elements.mapNotNull { toSearchResult(it) }
        return ProviderModels.dedupSearchResults(results)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        return parseLoadMetadata(document, url)
    }

    suspend fun parseLoadMetadata(document: Document, url: String): LoadResponse? {
        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            document.selectFirst("div.series-profile-poster img, div.series-header img, img[src*='series/']")?.let {
                it.attr("data-src").ifBlank { null } ?: it.attr("src").ifBlank { null }
            }
        )
        val description = document.selectFirst("p.series-about-text, div.series-about-body p")?.text()?.trim()

        val episodeElements = document.select("a[href*='/sezon-']")
        val episodes = mutableListOf<Episode>()

        val epRegex = Regex("""/sezon-(\d+)/bolum-(\d+)""")
        val seenUrls = mutableSetOf<String>()

        for (el in episodeElements) {
            val href = fixUrlNull(el.attr("href")) ?: continue
            if (!seenUrls.add(href)) continue

            val match = epRegex.find(href)
            val seasonNum = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val epNum = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1

            val epName = el.text().trim().takeIf { it.isNotBlank() } ?: "$seasonNum. Sezon $epNum. Bölüm"

            episodes.add(
                newEpisode(href) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframes = mutableListOf<String>()

        document.select("iframe[data-src], iframe[src]").forEach {
            val src = fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") })
            if (!src.isNullOrBlank() && !src.contains("google") && !src.contains("recaptcha")) {
                iframes.add(src)
            }
        }

        var found = false
        val candidates = iframes.distinct()

        val count = BoundedParallelResolver.resolveProgressive(
            candidates = candidates,
            resolver = { iframeUrl, emitLink ->
                if (iframeUrl.contains("playerdkorea") || iframeUrl.contains("/video/")) {
                    try {
                        val playerDoc = app.get(iframeUrl, headers = mapOf("Referer" to data)).document
                        val innerSrc = playerDoc.selectFirst("iframe[src]")?.attr("src")
                        if (!innerSrc.isNullOrBlank()) {
                            loadExtractor(innerSrc, iframeUrl, subtitleCallback, emitLink)
                        } else {
                            val html = playerDoc.html()
                            val m3u8Regex = Regex("""["'](https?://[^\s"']+\.m3u8[^\s"']*)["']""")
                            m3u8Regex.findAll(html).forEach { m ->
                                val streamUrl = m.groupValues[1]
                                emitLink(
                                    newExtractorLink(
                                        source = name,
                                        name = "$name HLS",
                                        url = streamUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = iframeUrl
                                        this.quality = Qualities.P1080.value
                                    }
                                )
                            }
                        }
                    } catch (_: Exception) {}
                } else {
                    loadExtractor(iframeUrl, data, subtitleCallback, emitLink)
                }
            },
            onLinkFound = { link ->
                callback(link)
                found = true
            }
        )

        return found || count > 0
    }
}
