package com.cloudstream.tr.core

import com.cloudstream.tr.core.concurrency.BoundedParallelResolver
import com.cloudstream.tr.core.extractors.RapidVidExtractor
import com.cloudstream.tr.core.model.ProviderModels
import com.cloudstream.tr.core.network.SafeHttpClient
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CoreTest {

    private val api = object : MainAPI() {
        override var name = "TestAPI"
        override var mainUrl = "https://example.com"
        override val supportedTypes = setOf(TvType.Movie)
    }

    @Test
    fun testProviderModelsNormalizeTitle() {
        val raw = "Kurtlar Vadisi: Pusu - 1. Bölüm İzle!"
        val norm = ProviderModels.normalizeTitle(raw)
        assertEquals("kurtlar vadisi pusu 1 bölüm izle", norm)
    }

    @Test
    fun testProviderModelsFormatSourceTitle() {
        val title = ProviderModels.formatSourceTitle("Vidmoly", isDubbed = true, isSubbed = false, resolution = "1080p")
        assertTrue(title.contains("TR Dublaj"))
        assertTrue(title.contains("1080p"))
        assertFalse(title.contains("TR Altyazı"))
    }

    @Test
    fun testProviderModelsDedupSearchResults() {
        val item1 = api.newMovieSearchResponse("Yıldızlararası", "https://site.com/film/1", TvType.Movie)
        val item2 = api.newMovieSearchResponse("Yıldızlararası (FİLM)", "https://site.com/film/1/", TvType.Movie)
        val item3 = api.newMovieSearchResponse("Inception", "https://site.com/film/2", TvType.Movie)

        val deduped = ProviderModels.dedupSearchResults(listOf(item1, item2, item3))
        assertEquals(2, deduped.size)
        assertEquals("Yıldızlararası", deduped[0].name)
        assertEquals("Inception", deduped[1].name)
    }

    @Test
    fun testSafeHttpClientHeaders() {
        val headers = SafeHttpClient.defaultHeaders("https://example.com/", isAjax = true)
        assertEquals("XMLHttpRequest", headers["X-Requested-With"])
        assertEquals("https://example.com/", headers["Referer"])
        assertEquals(SafeHttpClient.DEFAULT_USER_AGENT, headers["User-Agent"])
    }

    @Test
    fun testBoundedParallelResolverProgressive() = runBlocking {
        val items = listOf("stream1", "stream2", "stream3", "stream4")
        val linksEmitted = mutableListOf<String>()

        val count = BoundedParallelResolver.resolveProgressive(
            candidates = items,
            maxConcurrency = 2,
            earlyExitOnFirstSuccess = false,
            resolver = { item, emitLink ->
                delay(10)
                emitLink(
                    newExtractorLink("Test", item, "https://$item.mp4", ExtractorLinkType.VIDEO)
                )
            },
            onLinkFound = { link ->
                synchronized(linksEmitted) {
                    linksEmitted.add(link.url)
                }
            }
        )

        assertEquals(4, count)
        assertEquals(4, linksEmitted.size)
    }

    @Test
    fun testBoundedParallelResolverEarlyExit() = runBlocking {
        val items = listOf("stream1", "stream2", "stream3", "stream4")
        val linksEmitted = mutableListOf<String>()

        val count = BoundedParallelResolver.resolveProgressive(
            candidates = items,
            maxConcurrency = 1,
            earlyExitOnFirstSuccess = true,
            resolver = { item, emitLink ->
                delay(10)
                emitLink(
                    newExtractorLink("Test", item, "https://$item.mp4", ExtractorLinkType.VIDEO)
                )
            },
            onLinkFound = { link ->
                synchronized(linksEmitted) {
                    linksEmitted.add(link.url)
                }
            }
        )

        assertTrue(count >= 1)
        assertTrue(linksEmitted.isNotEmpty())
    }

    @Test
    fun testRapidVidDecrypt() {
        val result = RapidVidExtractor.decryptRapidvidAv("")
        assertEquals("", result)
    }
}
