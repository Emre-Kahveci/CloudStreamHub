package com.cloudstream.tr.filmmakinesi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class FilmMakinesi : MainAPI() {
    override var mainUrl = "https://filmmakinesi.to"
    override var name = "FilmMakinesi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler-1/" to "Son Eklenen Filmler",
        "${mainUrl}/tur/aksiyon-fmy54y/film/" to "Aksiyon Filmleri",
        "${mainUrl}/yabanci-dizi-izle-1/" to "Diziler",
        "${mainUrl}/yil/2026-fmfbkb/film/" to "2026 Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}sayfa/$page/"
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val home = parseHomePage(doc)
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    fun parseHomePage(doc: Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        doc.select("a.item, a.slide, div.item-relative a.item").forEach { el ->
            parseSearchElement(el)?.let { items.add(it) }
        }
        return items.distinctBy { it.url }
    }

    fun parseSearchElement(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a") ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null
        val title = element.attr("data-title").ifEmpty { null }
            ?: element.selectFirst(".title, .item-title, h4")?.text()?.trim()
            ?: linkEl.attr("title").ifEmpty { null }
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val posterUrl = fixUrlNull(
            element.selectFirst("img")?.let {
                it.attr("src").ifEmpty { null }
                    ?: it.attr("srcset").split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
            }
        )

        val score = element.attr("data-score").ifEmpty { null }
            ?: element.selectFirst(".rating, .imdb-score span")?.text()?.trim()

        val isTv = href.contains("/dizi/")
        val type = if (isTv) TvType.TvSeries else TvType.Movie

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.score = Score.from10(score)
            }
        } else {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.score = Score.from10(score)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val doc = app.get(
                "${mainUrl}/arama/?s=${query}",
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "${mainUrl}/"
                )
            ).document

            val searchResponses = mutableListOf<SearchResponse>()
            doc.select("a.item, div.item-relative a.item").forEach { a ->
                parseSearchElement(a)?.let { searchResponses.add(it) }
            }
            searchResponses.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent, "Referer" to "${mainUrl}/")).document
        return parseLoadMetadata(doc, url)
    }

    suspend fun parseLoadMetadata(doc: Document, url: String): LoadResponse? {
        val title = doc.selectFirst("h1, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }?.replace(" - FilmMakinesi", "")?.replace(" izle", "")?.trim() ?: return null

        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = doc.selectFirst("meta[property='og:description'], div.description, div.info-content .description")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }
        val year = doc.selectFirst("div.info span:first-child, span.year")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val score = doc.selectFirst("div.imdb-score span, .rating")?.text()?.trim()

        val isTv = url.contains("/dizi/")

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            doc.select("a[href*='bolum'], div.episodes a").forEach { a ->
                val epHref = fixUrlNull(a.attr("href")) ?: return@forEach
                val epTitle = a.text().trim()
                
                val sMatch = Regex("""(\d+)\.\s*Sezon""").find(epTitle)
                val eMatch = Regex("""(\d+)\.\s*B[öo]l[üu]m""").find(epTitle)

                val seasonNum = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = eMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epTitle
                        this.season = seasonNum
                        this.episode = epNum
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.score = Score.from10(score)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.score = Score.from10(score)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = mapOf("User-Agent" to userAgent, "Referer" to "${mainUrl}/")).document

        val iframes = mutableListOf<String>()
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (src.isNotEmpty()) {
                fixUrlNull(src)?.let { iframes.add(it) }
            }
        }

        val closeloadExtractor = CloseLoadExtractor()

        for (iframeUrl in iframes.distinct()) {
            if (iframeUrl.contains("closeload.filmmakinesi.to") || iframeUrl.contains("closeload")) {
                closeloadExtractor.getUrl(iframeUrl, data, subtitleCallback, callback)
            } else {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
