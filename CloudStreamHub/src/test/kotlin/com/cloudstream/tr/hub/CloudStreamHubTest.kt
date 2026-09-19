package com.cloudstream.tr.hub

import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.launch

class CloudStreamHubTest {
    private val hub = CloudStreamHub()

    @Test
    fun testParseTmdbPage() {
        val stream = javaClass.classLoader?.getResourceAsStream("tmdb_trending.json")
        assertNotNull("tmdb_trending.json fixture missing", stream)

        val json = stream!!.bufferedReader().use { it.readText() }
        val response = AppUtils.parseJson<TmdbPageResponse>(json)
        assertNotNull(response)
        assertEquals(2, response.results?.size)

        val item1 = hub.parseTmdbItem(response.results!![0]) as? MovieSearchResponse
        assertNotNull(item1)
        assertEquals("Geleceğe Dönüş", item1?.name)
        assertEquals(1985, item1?.year)
        assertTrue(item1?.url?.contains("/movie/105") == true)

        val item2 = hub.parseTmdbItem(response.results!![1]) as? TvSeriesSearchResponse
        assertNotNull(item2)
        assertEquals("Game of Thrones", item2?.name)
        assertEquals(2011, item2?.year)
        assertTrue(item2?.url?.contains("/tv/1399") == true)
    }

    @Test
    fun testHubMatchingEngineExactAndConfident() {
        val cand1 = hub.newMovieSearchResponse("Inception", "https://site.com/1", TvType.Movie) {
            this.year = 2010
        }
        val cand2 = hub.newMovieSearchResponse("Inception: The Beginning", "https://site.com/2", TvType.Movie) {
            this.year = 2015
        }
        val cand3 = hub.newMovieSearchResponse("Interstellar", "https://site.com/3", TvType.Movie) {
            this.year = 2014
        }

        // Test exact match
        val match1 = HubMatchingEngine.findConfidentMatch(listOf(cand1, cand2, cand3), "Inception", 2010, isMovie = true)
        assertNotNull(match1)
        assertEquals("Inception", match1?.name)

        // Test rejecting completely unrelated title (NEVER returns first result!)
        val match2 = HubMatchingEngine.findConfidentMatch(listOf(cand1, cand2, cand3), "Matrix", 1999, isMovie = true)
        assertNull("Unrelated title must NOT match first candidate!", match2)
    }

    @Test
    fun testHubMatchingEngineYearTolerance() {
        val cand = hub.newMovieSearchResponse("Dune Part Two", "https://site.com/dune", TvType.Movie) {
            this.year = 2024
        }
        // +/- 1 year tolerance
        val match = HubMatchingEngine.findConfidentMatch(listOf(cand), "Dune: Part Two", 2023, isMovie = true)
        assertNotNull(match)
        assertEquals("Dune Part Two", match?.name)

        // Large year mismatch penalty
        val mismatch = HubMatchingEngine.findConfidentMatch(listOf(cand), "Dune: Part Two", 1984, isMovie = true)
        assertNull("Decade-mismatched year must be rejected", mismatch)
    }

    @Test
    fun testHubMatchingEngineMediaTypeRejection() {
        val movieCand = hub.newMovieSearchResponse("Breaking Bad", "https://site.com/bb-movie", TvType.Movie) {
            this.year = 2008
        }
        val seriesCand = hub.newTvSeriesSearchResponse("Breaking Bad", "https://site.com/bb-series", TvType.TvSeries) {
            this.year = 2008
        }

        // Seeking a TV series must match seriesCand, not movieCand
        val matchSeries = HubMatchingEngine.findConfidentMatch(listOf(movieCand, seriesCand), "Breaking Bad", 2008, isMovie = false)
        assertNotNull(matchSeries)
        assertTrue(matchSeries is TvSeriesSearchResponse)

        // Seeking a Movie must match movieCand, not seriesCand
        val matchMovie = HubMatchingEngine.findConfidentMatch(listOf(seriesCand, movieCand), "Breaking Bad", 2008, isMovie = true)
        assertNotNull(matchMovie)
        assertTrue(matchMovie is MovieSearchResponse)
    }

    @Test
    fun testBoundedChannelBurstBehavior() = kotlinx.coroutines.test.runTest {
        val channel = kotlinx.coroutines.channels.Channel<com.lagradost.cloudstream3.utils.ExtractorLink>(capacity = 16)
        var successCount = 0
        var droppedCount = 0

        // Rapidly push 50 links without an active consumer to simulate burst
        for (i in 1..50) {
            val dummyLink = com.lagradost.cloudstream3.utils.ExtractorLink(
                source = "TestProvider",
                name = "Link $i",
                url = "https://example.invalid/stream$i.m3u8",
                referer = "",
                quality = com.lagradost.cloudstream3.utils.Qualities.P1080.value,
                type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
            )
            val result = channel.trySend(dummyLink)
            if (result.isSuccess) {
                successCount++
            } else {
                droppedCount++
            }
        }

        channel.close()

        // With capacity 16, exactly 16 links should be buffered and 34 dropped
        assertEquals("Capacity 16 should accept exactly 16 burst links", 16, successCount)
        assertEquals("34 links should be dropped to prevent memory exhaustion", 34, droppedCount)

        // Verify that consumer reads exactly 16 items
        var consumedCount = 0
        for (link in channel) {
            consumedCount++
        }
        assertEquals("Consumer should receive exactly 16 items from channel", 16, consumedCount)
    }

    @Test
    fun testChannelBurstWithValidLinkAtEnd() = kotlinx.coroutines.test.runTest {
        val channel = kotlinx.coroutines.channels.Channel<com.lagradost.cloudstream3.utils.ExtractorLink>(capacity = 16)
        var rawReceived = 0
        var channelOverflowDropped = 0
        val consumedLinks = mutableListOf<String>()

        // Launch consumer concurrently using backgroundScope
        val consumerJob = backgroundScope.launch {
            for (link in channel) {
                consumedLinks.add(link.url)
            }
        }

        // Producer pushes 49 junk links + 50th valid link
        for (i in 1..50) {
            val url = if (i == 50) "https://cdn.example.com/valid-master.m3u8" else "https://cdn.example.com/junk$i.html"
            val link = com.lagradost.cloudstream3.utils.ExtractorLink(
                source = "TestProvider",
                name = "Source $i",
                url = url,
                referer = "",
                quality = com.lagradost.cloudstream3.utils.Qualities.P1080.value,
                type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
            )
            rawReceived++
            val res = channel.trySend(link)
            if (!res.isSuccess) {
                channelOverflowDropped++
            }
            // Small yield to simulate cooperative coroutine scheduling between producer and consumer
            kotlinx.coroutines.yield()
        }

        channel.close()
        consumerJob.join()

        assertEquals(50, rawReceived)
        // With cooperative progressive consumption, all 50 items are processed without drop
        assertEquals("No items should be dropped when consumer yields and consumes progressively", 0, channelOverflowDropped)
        assertEquals(50, consumedLinks.size)
        assertTrue("50th valid link must reach consumer", consumedLinks.contains("https://cdn.example.com/valid-master.m3u8"))
    }
}
