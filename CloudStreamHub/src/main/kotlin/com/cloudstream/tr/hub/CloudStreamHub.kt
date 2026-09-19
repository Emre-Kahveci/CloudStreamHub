package com.cloudstream.tr.hub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.cloudstream.tr.core.concurrency.BoundedParallelResolver
import com.cloudstream.tr.core.model.ProviderModels

class CloudStreamHub : MainAPI() {
    override var mainUrl = "https://api.themoviedb.org/3"
    override var name = "CloudStreamHub"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
        TvType.Documentary
    )

    private val tmdbApiKey = "b0f2a969335a4d434220b33215284eb1"
    private val imageBase = "https://image.tmdb.org/t/p/w500"

    override val mainPage = mainPageOf(
        "${mainUrl}/trending/all/day?language=tr-TR&api_key=${tmdbApiKey}" to "Günün Trendleri",
        "${mainUrl}/movie/popular?language=tr-TR&api_key=${tmdbApiKey}" to "Popüler Filmler",
        "${mainUrl}/tv/popular?language=tr-TR&api_key=${tmdbApiKey}" to "Popüler Diziler",
        "${mainUrl}/movie/top_rated?language=tr-TR&api_key=${tmdbApiKey}" to "En Çok Oy Alan Filmler",
        "${mainUrl}/movie/now_playing?language=tr-TR&api_key=${tmdbApiKey}" to "Vizyondaki Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = "${request.data}&page=${page}"
        val resp = app.get(targetUrl).parsedSafe<TmdbPageResponse>()
        val items = resp?.results?.mapNotNull { parseTmdbItem(it) } ?: emptyList()
        val totalPages = resp?.totalPages ?: 1

        return newHomePageResponse(request.name, items, hasNext = page < totalPages)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val targetUrl = "${mainUrl}/search/multi?query=${query}&language=tr-TR&page=${page}&api_key=${tmdbApiKey}"
        val resp = app.get(targetUrl).parsedSafe<TmdbPageResponse>()
        val items = resp?.results?.mapNotNull { parseTmdbItem(it) } ?: emptyList()
        val deduped = ProviderModels.dedupSearchResults(items)
        val totalPages = resp?.totalPages ?: 1

        return newSearchResponseList(deduped, hasNext = page < totalPages)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    fun parseTmdbItem(item: TmdbItem): SearchResponse? {
        val id = item.id ?: return null
        val isMovie = when (item.mediaType) {
            "movie" -> true
            "tv" -> false
            else -> item.title != null
        }

        val rawTitle = if (isMovie) {
            item.title ?: item.originalTitle
        } else {
            item.name ?: item.originalName
        } ?: return null

        val poster = item.posterPath?.let { "$imageBase$it" }
        val date = if (isMovie) item.releaseDate else item.firstAirDate
        val year = date?.take(4)?.toIntOrNull()
        val score = item.voteAverage?.toString()

        val dataUrl = if (isMovie) {
            "${mainUrl}/movie/${id}?api_key=${tmdbApiKey}&language=tr-TR&append_to_response=credits,videos"
        } else {
            "${mainUrl}/tv/${id}?api_key=${tmdbApiKey}&language=tr-TR&append_to_response=credits,videos"
        }

        return if (isMovie) {
            newMovieSearchResponse(rawTitle, dataUrl, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(score)
            }
        } else {
            newTvSeriesSearchResponse(rawTitle, dataUrl, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(score)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val resp = app.get(url).parsedSafe<TmdbDetailResponse>() ?: return null
        return parseTmdbDetail(resp, url)
    }

    suspend fun parseTmdbDetail(resp: TmdbDetailResponse, url: String): LoadResponse? {
        val id = resp.id ?: return null
        val isMovie = url.contains("/movie/")
        val title = (if (isMovie) resp.title ?: resp.originalTitle else resp.name ?: resp.originalName) ?: return null
        val poster = resp.posterPath?.let { "$imageBase$it" }
        val backdrop = resp.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
        val date = if (isMovie) resp.releaseDate else resp.firstAirDate
        val year = date?.take(4)?.toIntOrNull()
        val score = resp.voteAverage?.toString()
        val tags = resp.genres?.mapNotNull { it.name } ?: emptyList()
        val actors = resp.credits?.cast?.mapNotNull { cast ->
            cast.name?.let { name ->
                Actor(name, cast.profilePath?.let { "$imageBase$it" })
            }
        } ?: emptyList()
        val trailerKey = resp.videos?.results?.firstOrNull { it.site == "YouTube" && (it.type == "Trailer" || it.type == "Teaser") }?.key
        val trailerUrl = trailerKey?.let { "https://www.youtube.com/watch?v=$it" }

        if (isMovie) {
            val payload = AggregatorLinkPayload(
                title = title,
                year = year,
                isMovie = true,
                tmdbId = id
            ).toUrlData()

            return newMovieLoadResponse(title, url, TvType.Movie, payload) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = resp.overview
                this.year = year
                this.tags = tags
                this.score = Score.from10(score)
                addActors(actors)
                addTrailer(trailerUrl)
            }
        } else {
            val episodes = mutableListOf<Episode>()
            val seasons = resp.seasons?.filter { (it.seasonNumber ?: 0) > 0 } ?: emptyList()

            seasons.forEach { season ->
                val sNum = season.seasonNumber ?: 1
                val epCount = season.episodeCount ?: 0
                for (ep in 1..epCount) {
                    val epPayload = AggregatorLinkPayload(
                        title = title,
                        year = year,
                        isMovie = false,
                        season = sNum,
                        episode = ep,
                        tmdbId = id
                    ).toUrlData()

                    episodes.add(
                        newEpisode(epPayload) {
                            this.name = "${sNum}. Sezon ${ep}. Bölüm"
                            this.season = sNum
                            this.episode = ep
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = resp.overview
                this.year = year
                this.tags = tags
                this.score = Score.from10(score)
                addActors(actors)
                addTrailer(trailerUrl)
            }
        }
    }

    private fun getRegisteredTurkishProviders(): List<MainAPI> {
        return try {
            val field = Class.forName("com.lagradost.cloudstream3.APIHolder").getDeclaredField("allProviders")
            field.isAccessible = true
            val obj = field.get(null)
            val list = when (obj) {
                is Array<*> -> obj.filterIsInstance<MainAPI>()
                is Collection<*> -> obj.filterIsInstance<MainAPI>()
                else -> emptyList()
            }
            list.filter { it.lang == "tr" && it.name != this.name }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = AggregatorLinkPayload.fromUrlData(data) ?: return false
        var linksFound = false

        val providers = getRegisteredTurkishProviders()
        if (providers.isEmpty()) {
            return false
        }

        val resolved = BoundedParallelResolver.resolveProgressive(
            candidates = providers,
            maxConcurrency = 4,
            resolver = { provider, emitLink ->
                try {
                    val searchList = provider.search(payload.title) ?: emptyList()
                    if (searchList.isEmpty()) return@resolveProgressive

                    val matched = searchList.firstOrNull { res: SearchResponse ->
                        val normA = ProviderModels.normalizeTitle(res.name)
                        val normB = ProviderModels.normalizeTitle(payload.title)
                        normA.contains(normB) || normB.contains(normA)
                    } ?: searchList.first()

                    val loadRes = provider.load(matched.url) ?: return@resolveProgressive
                    val targetLinkData: String? = if (payload.isMovie) {
                        matched.url
                    } else {
                        val epList = (loadRes as? TvSeriesLoadResponse)?.episodes ?: emptyList()
                        val ep = epList.firstOrNull { it.season == payload.season && it.episode == payload.episode }
                            ?: epList.firstOrNull { it.episode == payload.episode }
                        ep?.data
                    }

                    if (targetLinkData != null) {
                        provider.loadLinks(
                            data = targetLinkData,
                            isCasting = isCasting,
                            subtitleCallback = subtitleCallback,
                            callback = { rawLink ->
                                val formattedName = ProviderModels.formatSourceTitle(
                                    sourceName = provider.name,
                                    resolution = rawLink.name
                                )
                                val taggedLink = ExtractorLink(
                                    source = provider.name,
                                    name = formattedName,
                                    url = rawLink.url,
                                    referer = rawLink.referer,
                                    quality = rawLink.quality,
                                    type = rawLink.type,
                                    headers = rawLink.headers
                                )
                                emitLink(taggedLink)
                            }
                        )
                    }
                } catch (_: Exception) {
                    // Failures in individual providers do not abort the aggregation
                }
            },
            onLinkFound = { link ->
                callback(link)
                linksFound = true
            }
        )

        return linksFound || resolved > 0
    }
}
