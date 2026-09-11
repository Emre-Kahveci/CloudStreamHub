package com.cloudstream.tr.yesilcamtv

import com.lagradost.cloudstream3.MovieSearchResponse
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class YesilCamTvParserTest {
    private val provider = YesilCamTv()

    @Test
    fun testParseSearchItem() {
        val stream = javaClass.classLoader?.getResourceAsStream("yesilcam_home.html")
        assertNotNull("yesilcam_home.html fixture missing", stream)

        val doc = Jsoup.parse(stream!!, "UTF-8", provider.mainUrl)
        val posts = doc.select("article.post")
        assertEquals(2, posts.size)

        val item1 = provider.parseSearchItem(posts[0]) as? MovieSearchResponse
        assertNotNull("Item 1 failed to parse", item1)
        assertEquals("Tosun Paşa", item1?.name)
        assertEquals("https://yesilcamtv.com.tr/tosun-pasa-izle/", item1?.url)
        assertEquals(1976, item1?.year)

        val item2 = provider.parseSearchItem(posts[1]) as? MovieSearchResponse
        assertNotNull("Item 2 failed to parse", item2)
        assertEquals("Kibar Feyzo", item2?.name)
        assertEquals(1978, item2?.year)
    }

    @Test
    fun testParseLoadMetadata() = runBlocking {
        val stream = javaClass.classLoader?.getResourceAsStream("yesilcam_detail.html")
        assertNotNull("yesilcam_detail.html fixture missing", stream)

        val doc = Jsoup.parse(stream!!, "UTF-8", provider.mainUrl)
        val loadResponse = provider.parseLoadMetadata(doc, "https://yesilcamtv.com.tr/tosun-pasa-izle/")
        assertNotNull("Load response failed to parse", loadResponse)
        assertEquals("Tosun Paşa", loadResponse?.name)
        assertEquals(1976, loadResponse?.year)
    }
}
