package com.cloudstream.tr.diziyou

import com.cloudstream.tr.core.concurrency.BoundedParallelResolver
import com.cloudstream.tr.core.model.ProviderModels
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DiziYou : MainAPI() {
    override var mainUrl = "https://www.diziyou.one"
    override var name = "DiziYou"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Son Eklenen Bölümler",
        "${mainUrl}/dizi-arsivi/?filtrele=tarih&sirala=DESC" to "Son Eklenen Diziler",
        "${mainUrl}/dizi-arsivi/?filtrele=alfabetik&sirala=ASC" to "Dizi Arşivi",
        "${mainUrl}/dizi-arsivi/?filtrele=imdb&sirala=DESC&yil=&imdb=7" to "Popüler Diziler (IMDb 7+)"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            val base = request.data
            if (base.contains("?")) {
                val parts = base.split("?", limit = 2)
                "${parts[0].removeSuffix("/")}/page/${page}/?${parts[1]}"
            } else {
                "${base.removeSuffix("/")}/page/${page}/"
            }
        }

        val doc = app.get(targetUrl).document
        val items = mutableListOf<SearchResponse>()

        // 1. Parse episode list if present on homepage / latest episodes
        doc.select(".listepisodes a, div.bolumust a, .latest-episodes a").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            val title = a.attr("title").ifBlank { a.text() }.trim()
            if (title.isBlank()) return@forEach
            val img = a.selectFirst("img")
            val poster = fixUrlNull(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))
            items.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            })
        }

        // 2. Parse series archive / categories
        doc.select(".alphabetical-category-wrapper a, div.cat-item, div.series-item, div.post, div#categorytitle").forEach { el ->
            if (el.tagName() == "a") {
                val href = fixUrlNull(el.attr("href")) ?: return@forEach
                if (href.contains("#")) return@forEach
                val title = el.attr("title").ifBlank { el.text() }.trim()
                if (title.isNotBlank()) {
                    items.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries))
                }
            } else {
                parseCard(el)?.let { items.add(it) }
            }
        }

        val distinctItems = items.distinctBy { it.url }
        return newHomePageResponse(request.name, distinctItems, hasNext = distinctItems.isNotEmpty())
    }

    fun parseCard(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href*='/dizi/']") ?: element.selectFirst("a") ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null
        if (href.contains("#")) return null

        val imgEl = element.selectFirst("img")
        val title = element.selectFirst("div#categorytitle, h2, h3, .series-title, .title")?.text()?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: imgEl?.attr("alt")?.ifBlank { null }
            ?: linkEl.text().ifBlank { null }
            ?: return null

        val poster = fixUrlNull(
            imgEl?.attr("data-src")?.ifBlank { null }
                ?: imgEl?.attr("src")?.ifBlank { null }
        )

        val year = element.selectFirst("span.year, div.year")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val cleanTitle = title.replace(Regex("""\s*\(\d{4}\)$"""), "").replace(" İzle", "").trim()

        return newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
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
        val items = doc.select("div#list-series .cat-item, div.cat-item, div.post, div.search-result, div.incontent a[href*='/dizi/'], .alphabetical-category-wrapper a").mapNotNull { el ->
            if (el.tagName() == "a") {
                val href = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                if (href.contains("#")) return@mapNotNull null
                val title = el.attr("title").ifBlank { el.text() }.trim()
                if (title.isBlank()) return@mapNotNull null
                val img = el.selectFirst("img")
                val poster = fixUrlNull(img?.attr("data-src") ?: img?.attr("src"))
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                parseCard(el)
            }
        }.distinctBy { it.url }

        val deduped = ProviderModels.dedupSearchResults(items)
        return newSearchResponseList(deduped, hasNext = deduped.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        return parseLoadMetadata(doc, url)
    }

    suspend fun parseLoadMetadata(doc: Document, url: String): LoadResponse? {
        // If url is an episode page and has a parent series link, we can inspect both
        var targetDoc = doc
        var targetUrl = url

        if (url.contains("-sezon-") && url.contains("-bolum")) {
            val parentSeriesLink = doc.select("a").firstOrNull { a ->
                val href = a.attr("href")
                href.isNotBlank() && !href.contains("-bolum") && !href.contains("#") && href.startsWith(mainUrl) && href != "${mainUrl}/"
            }?.attr("href")

            if (!parentSeriesLink.isNullOrBlank()) {
                try {
                    val pDoc = app.get(parentSeriesLink).document
                    if (pDoc.select("a[href*='-sezon-']").isNotEmpty()) {
                        targetDoc = pDoc
                        targetUrl = parentSeriesLink
                    }
                } catch (_: Exception) {}
            }
        }

        val rawTitle = targetDoc.selectFirst("h1, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }?.replace(" - DiziYou", "")?.replace(" İzle", "")?.replace(" izle", "")?.trim() ?: return null

        val poster = fixUrlNull(
            targetDoc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: targetDoc.selectFirst("div.cat-img img, div.poster img, img.series-poster, div.afis img")?.let {
                    it.attr("data-src").ifBlank { null } ?: it.attr("src").ifBlank { null }
                }
        )

        val plot = targetDoc.selectFirst("meta[property='og:description']")?.attr("content")
            ?: targetDoc.selectFirst("div.cat-desc, div.description, div.summary, div.konu")?.text()?.trim()

        val year = targetDoc.selectFirst("span.year, div.info span")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        val score = targetDoc.selectFirst("span.imdb, span.rating, div.score")?.text()?.trim()
        val tags = targetDoc.select("div.cat-tax a, a[href*='/kategori/'], a[href*='/tur/']").map { it.text().trim() }.filter { it.isNotBlank() }

        val episodeElements = targetDoc.select("a[href*='-sezon-']").filter { el ->
            val href = el.attr("href")
            href.contains("-sezon-") && href.contains("-bolum")
        }.distinctBy { it.attr("href") }

        val episodes = if (episodeElements.isNotEmpty()) {
            episodeElements.mapNotNull { el ->
                val epHref = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                val epText = el.text().trim()

                val sMatch = Regex("""(\d+)\.\s*Sezon""").find(epText)
                    ?: Regex("""(\d+)-sezon""").find(epHref)

                val eMatch = Regex("""(\d+)\.\s*B[öo]l[üu]m""").find(epText)
                    ?: Regex("""(\d+)-bolum""").find(epHref)

                val seasonNum = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = eMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name = "${seasonNum}. Sezon ${epNum}. Bölüm"
                    this.season = seasonNum
                    this.episode = epNum
                }
            }
        } else if (url.contains("-sezon-") && url.contains("-bolum")) {
            val sMatch = Regex("""(\d+)-sezon""").find(url)
            val eMatch = Regex("""(\d+)-bolum""").find(url)
            val seasonNum = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epNum = eMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            listOf(
                newEpisode(url) {
                    this.name = "${seasonNum}. Sezon ${epNum}. Bölüm"
                    this.season = seasonNum
                    this.episode = epNum
                }
            )
        } else {
            emptyList()
        }

        val cleanTitle = rawTitle.replace(Regex("""\s*\(\d{4}\)$"""), "").trim()

        return newTvSeriesLoadResponse(cleanTitle, targetUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = Score.from10(score)
        }
    }

    internal fun extractPlayerId(doc: Document): String? {
        val playerIframe = doc.selectFirst("iframe#diziyouPlayer, iframe[src*='/player/']")?.attr("src")
        return if (!playerIframe.isNullOrBlank()) {
            Regex("""/player/([a-zA-Z0-9_-]+)\.html""").find(playerIframe)?.groupValues?.get(1)
        } else {
            Regex("""itemId\s*=\s*['"]([a-zA-Z0-9_-]+)['"]""").find(doc.html())?.groupValues?.get(1)
                ?: Regex("""data-id=['"]([a-zA-Z0-9_-]+)['"]""").find(doc.html())?.groupValues?.get(1)
        }
    }

    internal suspend fun emitNativeLinks(
        itemId: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (itemId.isNullOrBlank()) return false

        val streamUrl = "https://storage.diziyou.one/episodes/${itemId}/play.m3u8"
        callback(
            newExtractorLink(
                source = name,
                name = "$name HLS",
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://www.diziyou.one/"
                this.quality = Qualities.Unknown.value
            }
        )

        subtitleCallback(
            SubtitleFile(
                lang = "Türkçe",
                url = "https://storage.diziyou.one/episodes/${itemId}/tr.vtt"
            )
        )
        subtitleCallback(
            SubtitleFile(
                lang = "İngilizce",
                url = "https://storage.diziyou.one/episodes/${itemId}/en.vtt"
            )
        )

        val dubUrl = "https://storage.diziyou.one/episodes/${itemId}_tr/play.m3u8"
        callback(
            newExtractorLink(
                source = name,
                name = "$name Dublaj HLS",
                url = dubUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://www.diziyou.one/"
                this.quality = Qualities.Unknown.value
            }
        )

        return true
    }

    internal fun getFallbackIframeCandidates(doc: Document): List<String> {
        return doc.select("iframe").mapNotNull { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (src.isNotBlank() && !src.contains("diziyouPlayer")) src else null
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

        val itemId = extractPlayerId(doc)
        if (emitNativeLinks(itemId, subtitleCallback, callback)) {
            linksFound = true
        }

        val fallbacks = getFallbackIframeCandidates(doc).mapNotNull { fixUrlNull(it) }
        val fallbackCount = BoundedParallelResolver.resolveProgressive(
            candidates = fallbacks,
            resolver = { fixed, emitLink ->
                loadExtractor(fixed, referer = data, subtitleCallback, emitLink)
            },
            onLinkFound = { link ->
                callback(link)
                linksFound = true
            }
        )

        return linksFound || fallbackCount > 0
    }
}
