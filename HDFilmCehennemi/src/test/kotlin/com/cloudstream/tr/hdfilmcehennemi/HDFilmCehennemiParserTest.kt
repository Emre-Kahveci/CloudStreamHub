package com.cloudstream.tr.hdfilmcehennemi

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class HDFilmCehennemiParserTest {
    private val api = HDFilmCehennemi()

    @Test
    fun testParseSearchElement() {
        val html = """
            <a href="https://www.hdfilmcehennemi.nl/1-avatar-hd-film-izle-510/" class="search-result">
                <img src="https://www.hdfilmcehennemi.nl/images/thumb/poster/1-avatar-hd-film-izle.webp" alt="Avatar" />
                <h4 class="title">Avatar</h4>
            </a>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val el = doc.selectFirst("a")!!
        val res = api.parseSearchElement(el)

        assertNotNull("Search element result should not be null", res)
        assertEquals("Avatar", res!!.name)
        assertEquals("https://www.hdfilmcehennemi.nl/1-avatar-hd-film-izle-510/", res.url)
        assertEquals(TvType.Movie, res.type)
    }

    @Test
    fun testRapidrameDecoder() {
        val js = """
            function wg5(typ3r) {
                var asyjh = "EbGXsYbc0puF6J3g";
                var ifd = "bbvv";
                return "";
            }
        """.trimIndent()

        val arr = """["OElINTV","5dXA5L3","hKWnZsd","nRoMW5I","UGFpL25","TNjhOWD","ZHZnFrS","FBMNDh0","dmo2ZVh","3cy96RD","cvZ0tKQ","3IyNDcv","L2JCdmp","YeFdwOW","Z6aCsxW","FE2L2dN","OWZmaDl","aY3Q5K3","BUdVBUL","zQvSHJR","WmhXNCt","MbGZlck","41K3FoQ","jhiMHU3","NzBVdE0","9"]"""

        // Test decoder logic
        val decoded = RapidrameExtractor.decodeRapidrame(js, arr)
        assertEquals("https://srv12.cdnimages2401.shop/hls/avatar-2009-extended-trdualmp4-mFe66KAZjPw.mp4/txt/master.txt", decoded)
    }
}
