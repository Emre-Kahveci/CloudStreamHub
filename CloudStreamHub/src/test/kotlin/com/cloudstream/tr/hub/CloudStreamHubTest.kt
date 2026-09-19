package com.cloudstream.tr.hub

import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val cand1 = hub.newMovieSearchResponse("Inception", "https://site.com/1", com.lagradost.cloudstream3.TvType.Movie) {
            this.year = 2010
        }
        val cand2 = hub.newMovieSearchResponse("Inception: The Beginning", "https://site.com/2", com.lagradost.cloudstream3.TvType.Movie) {
            this.year = 2015
        }
        val cand3 = hub.newMovieSearchResponse("Interstellar", "https://site.com/3", com.lagradost.cloudstream3.TvType.Movie) {
            this.year = 2014
        }

        // Test exact match
        val match1 = HubMatchingEngine.findConfidentMatch(listOf(cand1, cand2, cand3), "Inception", 2010)
        assertNotNull(match1)
        assertEquals("Inception", match1?.name)

        // Test rejecting completely unrelated title (NEVER returns first result!)
        val match2 = HubMatchingEngine.findConfidentMatch(listOf(cand1, cand2, cand3), "Matrix", 1999)
        org.junit.Assert.assertNull("Unrelated title must NOT match first candidate!", match2)
    }

    @Test
    fun testHubMatchingEngineYearTolerance() {
        val cand = hub.newMovieSearchResponse("Dune Part Two", "https://site.com/dune", com.lagradost.cloudstream3.TvType.Movie) {
            this.year = 2024
        }
        // +/- 1 year tolerance
        val match = HubMatchingEngine.findConfidentMatch(listOf(cand), "Dune: Part Two", 2023)
        assertNotNull(match)
        assertEquals("Dune Part Two", match?.name)

        // Large year mismatch penalty
        val mismatch = HubMatchingEngine.findConfidentMatch(listOf(cand), "Dune: Part Two", 1984)
        org.junit.Assert.assertNull("Decade-mismatched year must be rejected", mismatch)
    }
}
