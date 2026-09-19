package com.cloudstream.tr.dizikorea

import com.lagradost.cloudstream3.TvSeriesLoadResponse
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiziKoreaParserTest {
    private val api = DiziKorea()

    @Test
    fun parseSearchResults_extractsExpectedTitlesAndLinks() {
        val stream = javaClass.getResourceAsStream("/dizikorea_search.html")
        assertNotNull("Search fixture must exist", stream)
        val html = stream!!.bufferedReader().use { it.readText() }
        val document = Jsoup.parse(html, "https://dizikorea3.com")

        val results = api.parseSearchResults(document)
        assertTrue("Search results should not be empty", results.isNotEmpty())
        val first = results.first()
        assertTrue("Title should not be blank", first.name.isNotBlank())
        assertTrue("Url should be valid dizi link", first.url.contains("dizikorea3.com/dizi/"))
    }

    @Test
    fun parseLoadMetadata_extractsSeriesAndEpisodes() = runBlocking {
        val stream = javaClass.getResourceAsStream("/dizikorea_detail.html")
        assertNotNull("Detail fixture must exist", stream)
        val html = stream!!.bufferedReader().use { it.readText() }
        val document = Jsoup.parse(html, "https://dizikorea3.com/dizi/flex-x-cop-izle-dq")

        val load = api.parseLoadMetadata(document, "https://dizikorea3.com/dizi/flex-x-cop-izle-dq")
        assertNotNull("Load response must not be null", load)
        assertTrue("Load response should be TvSeriesLoadResponse", load is TvSeriesLoadResponse)
        val series = load as TvSeriesLoadResponse
        assertEquals("Flex X Cop", series.name)
        assertTrue("Episodes list should not be empty", series.episodes.isNotEmpty())
        val firstEp = series.episodes.first()
        assertEquals(1, firstEp.season)
        assertEquals(1, firstEp.episode)
    }
}
