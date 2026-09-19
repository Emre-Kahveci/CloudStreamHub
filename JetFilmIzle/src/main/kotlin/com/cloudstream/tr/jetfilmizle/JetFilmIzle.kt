package com.cloudstream.tr.jetfilmizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.cloudstream.tr.core.concurrency.BoundedParallelResolver
import com.cloudstream.tr.core.model.ProviderModels
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class JetFilmIzle : MainAPI() {
    override var mainUrl = "https://jetfilmizle.vip"
    override var name = "JetFilmIzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Son Eklenenler",
        "${mainUrl}/kategori/aksiyon-filmleri-izle/" to "Aksiyon",
        "${mainUrl}/kategori/bilim-kurgu-filmleri-izle/" to "Bilim Kurgu",
        "${mainUrl}/kategori/komedi-filmleri-izle/" to "Komedi",
        "${mainUrl}/kategori/korku-filmleri-izle/" to "Korku",
        "${mainUrl}/kategori/macera-filmleri-izle/" to "Macera",
        "${mainUrl}/kategori/dram-filmleri-izle/" to "Dram",
        "${mainUrl}/kategori/animasyon-filmleri-izle/" to "Animasyon"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            val base = request.data.removeSuffix("/")
            "$base/page/$page/"
        }

        val doc = app.get(targetUrl).document
        val items = doc.select("article.movie, article.post, div.movie-item, div.film-kutusu").mapNotNull { el ->
            parseSearchItem(el)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val targetUrl = if (page <= 1) {
            "${mainUrl}/?s=${query}"
        } else {
            "${mainUrl}/page/$page/?s=${query}"
        }

        val doc = app.get(targetUrl).document
        val items = doc.select("article.movie, article.post, div.movie-item, div.film-kutusu").mapNotNull { el ->
            parseSearchItem(el)
        }
        val deduped = ProviderModels.dedupSearchResults(items)

        return newSearchResponseList(deduped, hasNext = deduped.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    fun parseSearchItem(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href*='/film/'], a[href]") ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null
        if (href == mainUrl || href == "${mainUrl}/") return null

        val imgEl = element.selectFirst("img")
        val title = element.selectFirst(".title, h2, h3, .entry-title")?.text()?.trim()
            ?: imgEl?.attr("alt")?.trim()
            ?: linkEl.attr("title").trim()
        if (title.isBlank()) return null

        val poster = fixUrlNull(
            imgEl?.attr("data-src")?.ifBlank { null }
                ?: imgEl?.attr("data-lazy-src")?.ifBlank { null }
                ?: imgEl?.attr("src")?.ifBlank { null }
        )

        val year = element.selectFirst(".year, .film-yil, .date")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val score = element.selectFirst(".imdb, .score, .rating, .puan")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
            this.score = Score.from10(score)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        return parseLoadMetadata(doc, url)
    }

    suspend fun parseLoadMetadata(doc: Document, url: String): LoadResponse? {
        val title = doc.selectFirst("h1.entry-title, h1, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }?.replace(" - JetFilmizle", "")?.replace(" Jet Film izle", "")?.replace(" izle", "")?.trim() ?: return null

        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".poster img, .movie-poster img, img.wp-post-image")?.let {
                    it.attr("data-src").ifBlank { null } ?: it.attr("src").ifBlank { null }
                }
        )

        val description = doc.selectFirst("meta[property='og:description'], .entry-content p, .movie-desc, div.ozet")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }

        val year = doc.selectFirst("a[href*='/yil/'], .year, .film-yil")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val tags = doc.select("a[href*='/kategori/'], .categories a, .tags a").map { it.text().trim() }.filter { it.isNotBlank() }
        val score = doc.selectFirst(".imdb, .puan, .score")?.text()?.trim()
        val actors = doc.select("a[href*='/oyuncu/'], .actors a").map { Actor(it.text().trim()) }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.score = Score.from10(score)
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var linksFound = false
        val doc = app.get(data).document

        val iframes = mutableListOf<String>()
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (src.isNotBlank() && !src.contains("wp-embedded-content")) {
                fixUrlNull(src)?.let { iframes.add(it) }
            }
        }

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

        val resolved = BoundedParallelResolver.resolveProgressive(
            candidates = iframes.distinct(),
            maxConcurrency = 4,
            resolver = { iframeUrl, emitLink ->
                val fixed = fixUrl(iframeUrl)
                loadExtractor(fixed, referer = mainUrl, subtitleCallback, emitLink)
            },
            onLinkFound = { link ->
                callback(link)
                linksFound = true
            }
        )

        return linksFound || resolved > 0
    }
}
