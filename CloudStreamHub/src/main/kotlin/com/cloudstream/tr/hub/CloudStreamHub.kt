package com.cloudstream.tr.hub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.cloudstream.tr.core.concurrency.BoundedParallelResolver
import com.cloudstream.tr.core.diagnostics.DiagnosticCategory
import com.cloudstream.tr.core.diagnostics.DiagnosticLogger
import com.cloudstream.tr.core.diagnostics.DiagnosticStage
import com.cloudstream.tr.core.model.ProviderModels
import com.cloudstream.tr.core.network.StreamValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections

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

    // TMDB Credential Policy: Public read-only client credential model.
    // Client-side plugins (.cs3) cannot achieve zero-knowledge secret storage against decompilation.
    // Tokens are redacted from all diagnostic logs and network traces by DiagnosticLogger.
    private val tmdbApiKey = "90ad3ec891e5923150283b99719d890f"
    private val tmdbToken = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI5MGFkM2VjODkxZTU5MjMxNTAyODNiOTk3MTlkODkwZiIsIm5iZiI6MTc4OTgxODY1Mi40NzksInN1YiI6IjZhYWU3NzFjOTZlY2VmMDkzYmExZGU4NSIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.2iN-8AMjIO4zMGL60TwvBY0sVdDThmf66GRfkanrtvc"
    private val imageBase = "https://image.tmdb.org/t/p/w500"

    private val authHeaders = mapOf(
        "Authorization" to "Bearer $tmdbToken",
        "Accept" to "application/json"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/trending/all/day?language=tr-TR&api_key=${tmdbApiKey}" to "Günün Trendleri",
        "${mainUrl}/movie/popular?language=tr-TR&api_key=${tmdbApiKey}" to "Popüler Filmler",
        "${mainUrl}/tv/popular?language=tr-TR&api_key=${tmdbApiKey}" to "Popüler Diziler",
        "${mainUrl}/movie/top_rated?language=tr-TR&api_key=${tmdbApiKey}" to "En Çok Oy Alan Filmler",
        "${mainUrl}/movie/now_playing?language=tr-TR&api_key=${tmdbApiKey}" to "Vizyondaki Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = "${request.data}&page=${page}"
        val resp = app.get(targetUrl, headers = authHeaders).parsedSafe<TmdbPageResponse>()
        val items = resp?.results?.mapNotNull { parseTmdbItem(it) } ?: emptyList()
        val totalPages = resp?.totalPages ?: 1

        return newHomePageResponse(request.name, items, hasNext = page < totalPages)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val targetUrl = "${mainUrl}/search/multi?query=${query}&language=tr-TR&page=${page}&api_key=${tmdbApiKey}"
        val resp = app.get(targetUrl, headers = authHeaders).parsedSafe<TmdbPageResponse>()
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
            "${mainUrl}/movie/${id}?api_key=${tmdbApiKey}&language=tr-TR&append_to_response=credits,videos,external_ids"
        } else {
            "${mainUrl}/tv/${id}?api_key=${tmdbApiKey}&language=tr-TR&append_to_response=credits,videos,external_ids"
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
        val resp = app.get(url, headers = authHeaders).parsedSafe<TmdbDetailResponse>() ?: return null
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
                tmdbId = id,
                imdbId = resp.externalIds?.imdbId,
                originalTitle = resp.originalTitle ?: resp.originalName
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

            for (season in seasons) {
                val sNum = season.seasonNumber ?: 1
                val epCount = season.episodeCount ?: 0
                for (ep in 1..epCount) {
                    val epPayload = AggregatorLinkPayload(
                        title = title,
                        year = year,
                        isMovie = false,
                        season = sNum,
                        episode = ep,
                        tmdbId = id,
                        imdbId = resp.externalIds?.imdbId,
                        originalTitle = resp.originalTitle ?: resp.originalName
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = AggregatorLinkPayload.fromUrlData(data) ?: return false
        val torrentSourcesEnabled = com.cloudstream.tr.core.resolvers.DebridConfig.enableTorrentSources
        val debridEnabled = com.cloudstream.tr.core.resolvers.DebridConfig.isDebridEnabled
        val linksFound = java.util.concurrent.atomic.AtomicBoolean(false)
        val linkCallbackLock = Any()
        val collectedLinks = mutableListOf<ExtractorLink>()
        val seenLinkUrls = mutableSetOf<String>()
        val emittedSourcesBeforeFederationComplete = mutableSetOf<String>()
        val deferredLinksBySource = linkedMapOf<String, java.util.ArrayDeque<ExtractorLink>>()
        var federationComplete = false
        val interceptedCallback: (ExtractorLink) -> Unit = { link ->
            synchronized(linkCallbackLock) {
                val url = link.url.trim()
                val isTorrentLink = link.type == ExtractorLinkType.MAGNET ||
                    link.type == ExtractorLinkType.TORRENT ||
                    url.startsWith("magnet:", ignoreCase = true)
                val isTorrentio = link.source.equals("Torrentio", ignoreCase = true)
                val isYts = link.source.equals("YTS", ignoreCase = true)
                val sourceAllowed = when {
                    isYts -> torrentSourcesEnabled
                    isTorrentio -> torrentSourcesEnabled || (debridEnabled && !isTorrentLink)
                    isTorrentLink -> torrentSourcesEnabled
                    else -> true
                }
                if (sourceAllowed && url.isNotBlank() && seenLinkUrls.add(url)) {
                    collectedLinks.add(link)
                    linksFound.set(true)
                    val sourceKey = link.source.trim().lowercase()
                    if (federationComplete || sourceKey.isBlank() || emittedSourcesBeforeFederationComplete.add(sourceKey)) {
                        callback(link)
                    } else {
                        deferredLinksBySource.getOrPut(sourceKey) { java.util.ArrayDeque() }.addLast(link)
                    }
                }
            }
        }

        fun flushDeferredLinks() {
            synchronized(linkCallbackLock) {
                federationComplete = true
                while (deferredLinksBySource.isNotEmpty()) {
                    val iterator = deferredLinksBySource.entries.iterator()
                    while (iterator.hasNext()) {
                        val queue = iterator.next().value
                        callback(queue.removeFirst())
                        if (queue.isEmpty()) iterator.remove()
                    }
                }
            }
        }

        suspend fun fetchSubtitlesInBackground() {
            val imdbId = payload.imdbId ?: return
            CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) {
                com.cloudstream.tr.core.resolvers.SubtitlesResolver.resolveTurkishSubtitles(
                    imdbId = imdbId,
                    isMovie = payload.isMovie,
                    season = payload.season,
                    episode = payload.episode,
                    title = payload.title,
                    callback = subtitleCallback
                )
            }
        }

        // 0. Zero-Latency Stream Cache Check (instant links)
        val cacheKey = com.cloudstream.tr.core.streaming.StreamCacheManager.buildKey(
            title = payload.title,
            year = payload.year,
            isMovie = payload.isMovie,
            season = payload.season,
            episode = payload.episode
        ) + (payload.tmdbId?.let { "_tmdb_$it" } ?: "")

        fun flushCollectedLinks() {
            if (collectedLinks.isEmpty()) return

            val linksForCache = collectedLinks.toList().map { link ->
                val baseName = link.name.removePrefix("[⚡ Smart Play] ")
                if (baseName == link.name) {
                    link
                } else {
                    ExtractorLink(
                        source = link.source,
                        name = baseName,
                        url = link.url,
                        referer = link.referer,
                        quality = link.quality,
                        type = link.type,
                        headers = link.headers,
                        extractorData = link.extractorData
                    )
                }
            }
            val deterministicLinks = linksForCache.sortedWith(
                compareBy<ExtractorLink> { it.source.lowercase() }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.url }
            )
            val sortedLinks = com.cloudstream.tr.core.model.StreamPrioritySorter.sortByPriority(deterministicLinks)
            com.cloudstream.tr.core.streaming.StreamCacheManager.put(cacheKey, sortedLinks)
        }

        val cachedLinks = com.cloudstream.tr.core.streaming.StreamCacheManager.get(cacheKey)
        if (!cachedLinks.isNullOrEmpty()) {
            for (link in cachedLinks) {
                interceptedCallback(link)
            }
        }

        // 1. Automated Turkish Subtitles via OpenSubtitles
        fetchSubtitlesInBackground()

        // 2. High-speed Direct DDL CDN (4KHDHub Hub-Cloud, V-Cloud, FSL Server)
        try {
            val fourKLinks = com.cloudstream.tr.core.resolvers.FourKhubResolver.resolve(
                title = payload.title,
                year = payload.year,
                isMovie = payload.isMovie,
                season = payload.season,
                episode = payload.episode
            )
            for (link in fourKLinks) {
                interceptedCallback(link)
            }
        } catch (_: Exception) {}

        // 3. Debrid & Torrent P2P (Torrentio & YTS) - checked against DebridConfig preferences
        if (payload.imdbId != null && (com.cloudstream.tr.core.resolvers.DebridConfig.enableTorrentSources || com.cloudstream.tr.core.resolvers.DebridConfig.isDebridEnabled)) {
            val torrentioLinks = com.cloudstream.tr.core.resolvers.TorrentioResolver.resolve(
                imdbId = payload.imdbId,
                isMovie = payload.isMovie,
                season = payload.season,
                episode = payload.episode
            )
            for (link in torrentioLinks) {
                interceptedCallback(link)
            }

            if (payload.isMovie && com.cloudstream.tr.core.resolvers.DebridConfig.enableTorrentSources) {
                val ytsLinks = com.cloudstream.tr.core.resolvers.YtsResolver.resolve(
                    imdbId = payload.imdbId,
                    isMovie = true
                )
                for (link in ytsLinks) {
                    interceptedCallback(link)
                }
            }
        }

        val discoveredProviders = CloudStreamProviderRegistryAdapter.getRegisteredProviders(excludeName = this.name)
        val providers = if (payload.isMovie) {
            discoveredProviders.filter { TvType.Movie in it.supportedTypes }
        } else {
            discoveredProviders
        }
        if (providers.isEmpty()) {
            flushDeferredLinks()
            DiagnosticLogger.log(
                provider = name,
                stage = DiagnosticStage.LOAD,
                category = DiagnosticCategory.SOURCE_DISCOVERY,
                message = "No eligible providers available from the CloudStream registry or CloudStreamHub bundle."
            )
            flushCollectedLinks()
            return linksFound.get()
        }

        val resolved = BoundedParallelResolver.resolveProgressive(
            candidates = providers,
            maxConcurrency = 4,
            provider = name,
            resolver = { provider, emitLink ->
                try {
                    val targetTitles = listOfNotNull(payload.title, payload.originalTitle).distinct()
                    val searchQueries = targetTitles
                        .filter { it.isNotBlank() }
                        .distinctBy { it.trim().lowercase() }
                    val matched = HubMatchingEngine.findConfidentMatchFromSearches(
                        searchQueries = searchQueries,
                        targetTitles = targetTitles,
                        targetYear = payload.year,
                        isMovie = payload.isMovie,
                        search = { query -> HubMatchingEngine.searchProvider(provider, query) }
                    ) ?: run {
                        DiagnosticLogger.log(
                            provider = provider.name,
                            stage = DiagnosticStage.SEARCH,
                            category = DiagnosticCategory.SOURCE_DISCOVERY,
                            message = "Federation result: no confident match for '${payload.title}' or '${payload.originalTitle}' (${payload.year ?: "N/A"}); queries=${searchQueries.size}; arbitrary first results rejected."
                        )
                        return@resolveProgressive
                    }

                    DiagnosticLogger.log(
                        provider = provider.name,
                        stage = DiagnosticStage.SEARCH,
                        category = DiagnosticCategory.SOURCE_DISCOVERY,
                        message = "Federation result: confident ${matched.type} match '${matched.name}'; loading provider result."
                    )
                    val loadRes = provider.load(matched.url) ?: run {
                        DiagnosticLogger.log(
                            provider = provider.name,
                            stage = DiagnosticStage.LOAD,
                            category = DiagnosticCategory.SOURCE_DISCOVERY,
                            message = "Federation result: matched '${matched.name}', but provider.load returned no response."
                        )
                        return@resolveProgressive
                    }
                    val targetLinkData: String? = if (payload.isMovie) {
                        (loadRes as? MovieLoadResponse)?.dataUrl?.takeIf { it.isNotBlank() } ?: matched.url
                    } else {
                        val epList = (loadRes as? TvSeriesLoadResponse)?.episodes ?: emptyList()
                        val ep = epList.firstOrNull { it.season == payload.season && it.episode == payload.episode }
                        if (ep == null) {
                            DiagnosticLogger.log(
                                provider = provider.name,
                                stage = DiagnosticStage.EPISODE_DISCOVERY,
                                category = DiagnosticCategory.SOURCE_DISCOVERY,
                                message = "Episode S${payload.season}E${payload.episode} not found in provider. Rejected fallback to prevent wrong season playback."
                            )
                        }
                        ep?.data
                    }

                    if (targetLinkData != null) {
                        val channel = Channel<ExtractorLink>(capacity = Channel.UNLIMITED)
                        val seenUrls = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
                        var rawLinksReceived = 0
                        var duplicatesDropped = 0
                        var channelOverflowDropped = 0
                        var preflightValid = 0
                        var preflightInvalid = 0
                        var preflightIndeterminate = 0
                        var linksEmitted = 0

                        coroutineScope {
                            val consumerJob = launch {
                                for (rawLink in channel) {
                                    val rawUrl = rawLink.url
                                    if (rawUrl.isBlank() || rawUrl.contains("youtube.com") || rawUrl.contains("youtu.be")) {
                                        continue
                                    }
                                    if (!seenUrls.add(rawUrl)) {
                                        duplicatesDropped++
                                        continue
                                    }

                                    if (
                                        rawUrl.startsWith("magnet:") ||
                                        rawLink.type == com.lagradost.cloudstream3.utils.ExtractorLinkType.MAGNET ||
                                        rawLink.type == com.lagradost.cloudstream3.utils.ExtractorLinkType.TORRENT
                                    ) {
                                        emitLink(rawLink)
                                        linksEmitted++
                                        continue
                                    }

                                    val reqHeaders = rawLink.headers.toMutableMap()
                                    if (rawLink.referer.isNotBlank() && !reqHeaders.containsKey("Referer") && !reqHeaders.containsKey("referer")) {
                                        reqHeaders["Referer"] = rawLink.referer
                                    }

                                    val preflight = StreamValidator.validateStream(
                                        url = rawUrl,
                                        headers = reqHeaders,
                                        provider = provider.name
                                    )
                                    when (preflight.status) {
                                        com.cloudstream.tr.core.network.ValidationStatus.VALID -> preflightValid++
                                        com.cloudstream.tr.core.network.ValidationStatus.INVALID -> preflightInvalid++
                                        com.cloudstream.tr.core.network.ValidationStatus.INDETERMINATE -> preflightIndeterminate++
                                    }

                                    if (preflight.isValid || preflight.status == com.cloudstream.tr.core.network.ValidationStatus.INDETERMINATE) {
                                        val formattedName = ProviderModels.formatSourceTitle(
                                            sourceName = provider.name,
                                            resolution = rawLink.name
                                        )
                                        val taggedLink = newExtractorLink(
                                            source = provider.name,
                                            name = formattedName,
                                            url = rawLink.url,
                                            type = preflight.streamType
                                        ) {
                                            this.referer = rawLink.referer
                                            this.quality = rawLink.quality
                                            this.headers = reqHeaders
                                        }
                                        emitLink(taggedLink)
                                        linksEmitted++
                                    }
                                }
                            }

                            try {
                                provider.loadLinks(
                                    data = targetLinkData,
                                    isCasting = isCasting,
                                    subtitleCallback = subtitleCallback,
                                    callback = { rawLink ->
                                        rawLinksReceived++
                                        val result = channel.trySend(rawLink)
                                        if (!result.isSuccess) {
                                            channelOverflowDropped++
                                            DiagnosticLogger.log(
                                                provider = provider.name,
                                                stage = DiagnosticStage.STREAM_PREFLIGHT,
                                                category = DiagnosticCategory.NETWORK,
                                                message = "Channel buffer overflow for link: ${DiagnosticLogger.redactUrl(rawLink.url)}"
                                            )
                                        }
                                    }
                                )
                            } finally {
                                channel.close()
                            }

                            consumerJob.join()

                            DiagnosticLogger.log(
                                provider = provider.name,
                                stage = DiagnosticStage.STREAM_PREFLIGHT,
                                category = if (linksEmitted > 0) DiagnosticCategory.SOURCE_DISCOVERY else DiagnosticCategory.NETWORK,
                                message = "Federation result: matched='${matched.name}', raw=$rawLinksReceived, providerEmitted=$linksEmitted (before hub dedupe and source-policy filtering), valid=$preflightValid, indeterminate=$preflightIndeterminate, invalid=$preflightInvalid, duplicates=$duplicatesDropped, dropped=$channelOverflowDropped. Preflight does not confirm sustained playback."
                            )
                        }
                    } else {
                        DiagnosticLogger.log(
                            provider = provider.name,
                            stage = if (payload.isMovie) DiagnosticStage.LOAD else DiagnosticStage.EPISODE_DISCOVERY,
                            category = DiagnosticCategory.SOURCE_DISCOVERY,
                            message = "Federation result: matched '${matched.name}', but no movie or requested episode link data was available."
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagnosticLogger.log(
                        provider = provider.name,
                        stage = DiagnosticStage.LOAD,
                        category = DiagnosticCategory.EXTRACTOR,
                        message = "Provider resolution failed during aggregation: ${e.message}",
                        throwable = e
                    )
                }
            },
            onLinkFound = { link ->
                interceptedCallback(link)
            }
        )

        flushDeferredLinks()
        flushCollectedLinks()

        return linksFound.get() || resolved > 0
    }
}
