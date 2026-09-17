package com.cloudstream.tr.diziyou

import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
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

    @Test
    fun testParseLoadMetadataAndStablePayload() = runBlocking {
        val stream = javaClass.classLoader?.getResourceAsStream("diziyou_detail.html")
        assertNotNull("diziyou_detail.html fixture missing", stream)

        val doc = Jsoup.parse(stream!!, "UTF-8", provider.mainUrl)
        val url = "https://www.diziyou.one/dizi/arcane"
        val res = provider.parseLoadMetadata(doc, url) as? TvSeriesLoadResponse

        assertNotNull("Detail failed to parse", res)
        assertEquals("Arcane", res?.name)
        assertEquals(url, res?.url)
        assertEquals(2021, res?.year)
        assertTrue("Plot should contain Piltover", res?.plot?.contains("Piltover") ?: false)
        assertTrue("Tags should contain Animasyon", res?.tags?.contains("Animasyon") ?: false)

        val episodes = res?.episodes ?: emptyList()
        assertTrue("Episodes should not be empty", episodes.isNotEmpty())
        assertEquals(2, episodes.size)

        for (ep in episodes) {
            // Invariant: Episode.data must be stable episode page URL, never an ephemeral stream URL
            assertTrue("Episode data must start with http", ep.data.startsWith("http"))
            assertFalse("Episode data must not contain .m3u8", ep.data.contains(".m3u8"))
            assertFalse("Episode data must not contain token", ep.data.contains("token"))
        }
    }
}
