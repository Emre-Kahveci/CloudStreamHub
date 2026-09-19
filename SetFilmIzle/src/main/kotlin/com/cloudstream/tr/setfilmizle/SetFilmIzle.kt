package com.cloudstream.tr.setfilmizle

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.cloudstream.tr.core.concurrency.BoundedParallelResolver
import com.cloudstream.tr.core.model.ProviderModels
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class SetFilmIzle : MainAPI() {
    override var mainUrl = "https://www.setfilmizle.ltd"
    override var name = "SetFilmIzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/film/" to "Son Eklenen Filmler",
        "${mainUrl}/dizi/" to "Son Eklenen Diziler",
        "${mainUrl}/tur/aksiyon/" to "Aksiyon Filmleri",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim Kurgu Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            "${request.data}page/${page}/"
        }

        val document = app.get(targetUrl).document
        val items = parseSearchResults(document)
        return newHomePageResponse(request.name, items)
    }

    fun toSearchResult(element: Element): SearchResponse? {
        val link = if (element.tagName() == "a") element else element.selectFirst("a[href*='/film/'], a[href*='/dizi/']") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        if (href.endsWith("/film/") || href.endsWith("/dizi/") || href.contains("/tur/")) return null

        val img = element.selectFirst("img")
        val rawTitle = img?.attr("alt")?.trim()
            ?.ifBlank { null }
            ?: element.selectFirst(".title, h3, h2, h4, span")?.text()?.trim()
            ?: link.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        val cleanTitle = rawTitle.replace("izle", "", ignoreCase = true)
            .replace("Türkçe Dublaj", "", ignoreCase = true)
            .replace("DUAL", "", ignoreCase = true)
            .trim()

        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.ifBlank { null }
                ?: img?.attr("src")?.ifBlank { null }
        )

        val isTvSeries = href.contains("/dizi/")
        return if (isTvSeries) {
            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val targetUrl = if (page <= 1) {
            "${mainUrl}/?s=${query}"
        } else {
            "${mainUrl}/page/${page}/?s=${query}"
        }

        val document = app.get(targetUrl).document
        val items = parseSearchResults(document)
        return newSearchResponseList(items, hasNext = false)
    }

    fun parseSearchResults(document: Document): List<SearchResponse> {
        val elements = document.select("a[href*='/film/'], a[href*='/dizi/'], div.movie-card, div.col-movie")
        val results = elements.mapNotNull { toSearchResult(it) }
        return ProviderModels.dedupSearchResults(results)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        return parseLoadMetadata(document, url)
    }

    suspend fun parseLoadMetadata(document: Document, url: String): LoadResponse? {
        val rawTitle = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore("|").trim()

        val title = rawTitle.replace("izle", "", ignoreCase = true)
            .replace("Türkçe Dublaj", "", ignoreCase = true)
            .trim()

        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("div.poster img, img.img-fluid, img[src*='uploads']")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }
        )

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("div.content-desc, div.story, p.desc")?.text()?.trim()

        val isTvSeries = url.contains("/dizi/")
        if (!isTvSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        val episodeElements = document.select("a[href*='-sezon-'], a[href*='-bolum-'], a[href*='/bolum/']")
        val episodes = mutableListOf<Episode>()

        val sRegex = Regex("""(?:(\d+)-sezon|sezon-(\d+))""")
        val eRegex = Regex("""(?:(\d+)-bolum|bolum-(\d+))""")
        val seenUrls = mutableSetOf<String>()

        for (el in episodeElements) {
            val href = fixUrlNull(el.attr("href")) ?: continue
            if (!seenUrls.add(href)) continue

            val sMatch = sRegex.find(href)
            val seasonNum = sMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: sMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1

            val eMatch = eRegex.find(href)
            val epNum = eMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: eMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1

            val epName = el.text().trim().takeIf { it.isNotBlank() } ?: "$seasonNum. Sezon $epNum. Bölüm"

            episodes.add(
                newEpisode(href) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private data class StreamData(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("provider") val provider: String? = null
    )

    private data class VideoData(
        @JsonProperty("stream") val stream: StreamData? = null
    )

    private data class VideoResponse(
        @JsonProperty("success") val success: Boolean = false,
        @JsonProperty("data") val data: VideoData? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val document = app.get(data).document

        val postId = document.selectFirst("[data-post-id]")?.attr("data-post-id")
            ?: document.selectFirst("[data-id]")?.attr("data-id")

        val html = document.html()
        val nonceRegex = Regex("""video\s*:\s*["']([^"']+)["']""")
        val nonce = nonceRegex.find(html)?.groupValues?.getOrNull(1)

        val playerNames = document.select("button[data-player-name]").map { it.attr("data-player-name") }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("SetPlay") }

        if (!postId.isNullOrBlank() && !nonce.isNullOrBlank()) {
            val count = BoundedParallelResolver.resolveProgressive(
                candidates = playerNames,
                resolver = { playerName, emitLink ->
                    try {
                        val resp = app.post(
                            "${mainUrl}/wp-admin/admin-ajax.php",
                            data = mapOf(
                                "action" to "get_video_url",
                                "nonce" to nonce,
                                "post_id" to postId,
                                "player_name" to playerName,
                                "part_key" to ""
                            ),
                            headers = mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Referer" to data
                            )
                        ).parsedSafe<VideoResponse>()

                        val streamUrl = resp?.data?.stream?.url
                        if (!streamUrl.isNullOrBlank()) {
                            loadExtractor(fixUrl(streamUrl), data, subtitleCallback, emitLink)
                        }
                    } catch (_: Exception) {}
                },
                onLinkFound = { link ->
                    callback(link)
                    found = true
                }
            )

            if (found || count > 0) return true
        }

        // Direct iframes fallback
        val iframes = document.select("iframe[src]").mapNotNull { fixUrlNull(it.attr("src")) }
        val fallbackCount = BoundedParallelResolver.resolveProgressive(
            candidates = iframes,
            resolver = { iframeUrl, emitLink ->
                loadExtractor(iframeUrl, data, subtitleCallback, emitLink)
            },
            onLinkFound = { link ->
                callback(link)
                found = true
            }
        )

        return found || fallbackCount > 0
    }
}
