package com.cloudstream.tr.hub

import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
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
}
