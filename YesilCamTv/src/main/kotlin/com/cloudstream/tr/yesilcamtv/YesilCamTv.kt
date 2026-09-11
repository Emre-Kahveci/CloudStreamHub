package com.cloudstream.tr.yesilcamtv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class YesilCamTv : MainAPI() {
    override var mainUrl = "https://yesilcamtv.com.tr"
    override var name = "YesilCamTv"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Son Eklenenler",
        "${mainUrl}/kategori/komedi/" to "Komedi",
        "${mainUrl}/kategori/dram/" to "Dram",
        "${mainUrl}/kategori/macera/" to "Macera",
        "${mainUrl}/kategori/duygusal/" to "Romantik"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            val base = request.data.removeSuffix("/")
            "$base/page/$page/"
        }

        val doc = app.get(targetUrl).document
        val items = doc.select("article, div.item, div.post, div.video-item").mapNotNull { el ->
            parseSearchItem(el)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val targetUrl = if (page <= 1) {
            "${mainUrl}/?s=${query}"
        } else {
            "${mainUrl}/page/$page/?s=${query}"
        }

        val doc = app.get(targetUrl).document
        val items = doc.select("article, div.item, div.post, div.search-result").mapNotNull { el ->
            parseSearchItem(el)
        }.distinctBy { it.url }

        return newSearchResponseList(items, hasNext = items.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    fun parseSearchItem(element: Element): SearchResponse? {
        val titleEl = element.selectFirst("h2, h3, .title, .entry-title a, a[title]") ?: return null
        val title = titleEl.text().trim().ifBlank { titleEl.attr("title").trim() }
        if (title.isBlank()) return null

        val linkEl = element.selectFirst("a[href]") ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null

        val posterEl = element.selectFirst("img[data-src], img[src], img[data-lazy-src]")
        val poster = fixUrlNull(
            posterEl?.attr("data-src")?.ifBlank { null }
                ?: posterEl?.attr("data-lazy-src")?.ifBlank { null }
                ?: posterEl?.attr("src")?.ifBlank { null }
        )

        val year = element.selectFirst(".year, .date")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        return parseLoadMetadata(doc, url)
    }

    suspend fun parseLoadMetadata(doc: Document, url: String): LoadResponse? {
        val title = doc.selectFirst("h1.entry-title, h1, .video-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            doc.selectFirst("div.poster img, meta[property=og:image], .entry-content img")?.let {
                it.attr("data-src").ifBlank { null }
                    ?: it.attr("src").ifBlank { null }
                    ?: it.attr("content").ifBlank { null }
            }
        )
        val description = doc.selectFirst("div.entry-content p, meta[property=og:description], .video-desc")?.text()?.trim()
        val year = doc.selectFirst(".year, .entry-date, span.date")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val tags = doc.select(".tags a, .categories a, rel[category]").map { it.text().trim() }.filter { it.isNotBlank() }
        val actors = doc.select(".actors a, .cast a").map { Actor(it.text().trim()) }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var linksFound = false

        // 1. Check embedded iframe players
        val iframes = doc.select("iframe[src], .video-player iframe").mapNotNull {
            it.attr("src").ifBlank { null }
        }

        for (iframeUrl in iframes) {
            val fixed = fixUrl(iframeUrl)
            val success = loadExtractor(fixed, referer = mainUrl, subtitleCallback) { link ->
                callback(link)
                linksFound = true
            }
            if (success) linksFound = true
        }

        // 2. Direct HTML5 video / mp4 / m3u8
        doc.select("video source[src], video[src]").forEach { v ->
            val src = fixUrlNull(v.attr("src")) ?: return@forEach
            val isM3u8 = src.contains(".m3u8")
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name HD",
                    url = src,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.P1080.value
                }
            )
            linksFound = true
        }

        return linksFound
    }
}
