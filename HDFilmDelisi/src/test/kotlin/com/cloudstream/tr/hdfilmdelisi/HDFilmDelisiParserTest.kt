package com.cloudstream.tr.hdfilmdelisi

import com.lagradost.cloudstream3.MovieLoadResponse
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HDFilmDelisiParserTest {
    private val api = HDFilmDelisi()

    @Test
    fun parseSearchResults_extractsMovies() {
        val stream = javaClass.getResourceAsStream("/delisi_search.html")
        assertNotNull("Search fixture must exist", stream)
        val html = stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val document = Jsoup.parse(html, "https://hdfilmdelisi.one")

        val results = api.parseSearchResults(document)
        assertTrue("Search results should not be empty", results.isNotEmpty())
        val first = results.first()
        assertTrue("Title should not be blank", first.name.isNotBlank())
        assertTrue("Url should contain /film/", first.url.contains("/film/"))
    }

    @Test
    fun parseLoadMetadata_extractsMovieDetail() = runBlocking {
        val stream = javaClass.getResourceAsStream("/delisi_detail.html")
        assertNotNull("Detail fixture must exist", stream)
        val html = stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val document = Jsoup.parse(html, "https://hdfilmdelisi.one/film/saplanti-obsession")

        val load = api.parseLoadMetadata(document, "https://hdfilmdelisi.one/film/saplanti-obsession")
        assertNotNull("Load response must not be null", load)
        assertTrue("Load response should be MovieLoadResponse", load is MovieLoadResponse)
        val movie = load as MovieLoadResponse
        assertTrue("Title should not be blank", movie.name.isNotBlank())
        assertTrue("Title should contain Saplant", movie.name.contains("Saplant"))
    }
}
