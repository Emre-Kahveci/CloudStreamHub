package com.cloudstream.tr.kultfilmler

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
        "${mainUrl}/tur/aksiyon/" to "Aksiyon",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim Kurgu",
        "${mainUrl}/tur/dram/" to "Dram",
        "${mainUrl}/tur/gerilim/" to "Gerilim",
        "${mainUrl}/tur/korku/" to "Korku",
        "${mainUrl}/tur/komedi/" to "Komedi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            val base = request.data.removeSuffix("/")
            "$base/page/$page/"
        }

        val doc = app.get(targetUrl).document
        val items = doc.select("article, div.item, div.post, div.movies-list article").mapNotNull { el ->
            parseSearchElement(el)
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
        val items = doc.select("article, div.item, div.result-item, div.search-page article").mapNotNull { el ->
            parseSearchElement(el)
        }.distinctBy { it.url }

        return newSearchResponseList(items, hasNext = items.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    fun parseSearchElement(element: Element): SearchResponse? {
        val titleEl = element.selectFirst("h2, h3, .title, .entry-title, div.flbaslik, .data h3 a") ?: element.selectFirst("a[title]")
        val title = titleEl?.text()?.trim() ?: element.attr("title").trim()
        if (title.isBlank()) return null

        val linkEl = element.selectFirst("a[href]") ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null

        val posterEl = element.selectFirst("img[data-src], img[src], img[data-lazy-src]")
        val poster = fixUrlNull(
            posterEl?.attr("data-src")
                ?.ifBlank { null }
                ?: posterEl?.attr("data-lazy-src")?.ifBlank { null }
                ?: posterEl?.attr("src")?.ifBlank { null }
        )

        val year = element.selectFirst(".year, .release-date, span.C a, span.date")?.text()?.trim()?.toIntOrNull()

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
        val title = doc.selectFirst("h1.entry-title, h1, div.data h1, .sheader .data h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            doc.selectFirst("div.poster img, div.sheader .poster img, meta[property=og:image]")?.let {
                it.attr("data-src").ifBlank { null }
                    ?: it.attr("src").ifBlank { null }
                    ?: it.attr("content").ifBlank { null }
            }
        )
        val description = doc.selectFirst("div.wp-content p, div#info .wp-content, meta[property=og:description], .overview p")?.text()?.trim()
        val year = doc.selectFirst("span.date, span.year, span.release-date, div.extra span.C a")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val score = doc.selectFirst(".dt_rating_vgs, span.rating, div.rating, .dt_rating_data")?.text()?.trim()
        val duration = doc.selectFirst("span.runtime, .runtime")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val tags = doc.select("div.sgeneros a, div.genres a, .genre-item a").map { it.text().trim() }.filter { it.isNotBlank() }
        val actors = doc.select(".cast-item a, span.valor a, .actors a").map { Actor(it.text().trim()) }
        val trailer = doc.selectFirst("iframe[src*='youtube.com'], iframe[src*='youtu.be']")?.attr("src")

        val episodeElements = doc.select("ul.episodios li, div.season-list a, .episodios a")
        val isTvSeries = episodeElements.isNotEmpty()

        return if (isTvSeries) {
            val episodes = episodeElements.mapIndexedNotNull { index, el ->
                val epHref = fixUrlNull(el.selectFirst("a")?.attr("href") ?: el.attr("href")) ?: return@mapIndexedNotNull null
                val epTitle = el.selectFirst(".episodiotitle a, .ep-title")?.text()?.trim() ?: "Bölüm ${index + 1}"
                val seasonNum = el.selectFirst(".season, .se-t")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 1
                val epNum = el.selectFirst(".episode, .num-ep")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: (index + 1)

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
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.score = Score.from10(score)
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
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

        // 1. Search for video iframes / embed players
        val iframes = doc.select("iframe[src], div.playex iframe, .movieplay iframe").mapNotNull {
            it.attr("src").ifBlank { null }
        }

        for (iframeUrl in iframes) {
            val fixedIframe = fixUrl(iframeUrl)
            val success = loadExtractor(fixedIframe, referer = mainUrl, subtitleCallback) { link ->
                callback(link)
                linksFound = true
            }
            if (success) linksFound = true
        }

        // 2. Direct HTML5 video sources if present
        doc.select("video source[src]").forEach { srcEl ->
            val src = fixUrlNull(srcEl.attr("src")) ?: return@forEach
            val isM3u8 = src.contains(".m3u8")
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name Direct",
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
