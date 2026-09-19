package com.cloudstream.tr.jetfilmizle

import com.lagradost.cloudstream3.MovieSearchResponse
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class JetFilmIzleParserTest {
    private val provider = JetFilmIzle()

    @Test
    fun testParseMovieCard() {
        val stream = javaClass.classLoader?.getResourceAsStream("jetfilmizle_home.html")
        assertNotNull("jetfilmizle_home.html fixture missing", stream)

        val doc = Jsoup.parse(stream!!, "UTF-8", provider.mainUrl)
        val cards = doc.select("article.movie")
        assertEquals(2, cards.size)

        val item1 = provider.parseSearchItem(cards[0]) as? MovieSearchResponse
        assertNotNull("Item 1 failed to parse", item1)
        assertEquals("Kara Şövalye", item1?.name)
        assertEquals("https://jetfilmizle.vip/film/the-dark-knight-izle.html", item1?.url)
        assertEquals(2008, item1?.year)

        val item2 = provider.parseSearchItem(cards[1]) as? MovieSearchResponse
        assertNotNull("Item 2 failed to parse", item2)
        assertEquals("Gladyatör", item2?.name)
        assertEquals("https://jetfilmizle.vip/film/gladiator-izle.html", item2?.url)
        assertEquals(2000, item2?.year)
    }
}
