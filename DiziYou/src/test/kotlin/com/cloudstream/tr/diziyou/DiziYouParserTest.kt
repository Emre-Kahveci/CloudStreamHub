package com.cloudstream.tr.diziyou

import com.lagradost.cloudstream3.TvSeriesSearchResponse
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DiziYouParserTest {
    private val provider = DiziYou()

    @Test
    fun testParseCard() {
        val stream = javaClass.classLoader?.getResourceAsStream("diziyou_home.html")
        assertNotNull("diziyou_home.html fixture missing", stream)

        val doc = Jsoup.parse(stream!!, "UTF-8", provider.mainUrl)
        val cards = doc.select("div.cat-item")
        assertEquals(1, cards.size)

        val item1 = provider.parseCard(cards[0]) as? TvSeriesSearchResponse
        assertNotNull("Item 1 failed to parse", item1)
        assertEquals("Arcane", item1?.name)
        assertEquals("https://www.diziyou.one/dizi/arcane", item1?.url)
        assertEquals(2021, item1?.year)
    }
}
