package com.cloudstream.tr.core.resolvers

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

data class LiveChannel(
    val name: String,
    val streamUrl: String,
    val posterUrl: String,
    val category: String
)

object LiveTvResolver {
    val channels = listOf(
        // Ulusal Kanallar
        LiveChannel(
            name = "TRT 1 HD",
            streamUrl = "https://tv-trt1.medya.trt.com.tr/master.m3u8",
            posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e7/TRT_1_logo_2021.svg/300px-TRT_1_logo_2021.svg.png",
            category = "Ulusal"
        ),
        LiveChannel(
            name = "ATV HD",
            streamUrl = "https://trkvz-live.ercdn.net/atvhd/atvhd.m3u8",
            posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c2/ATV_logo_2022.svg/300px-ATV_logo_2022.svg.png",
            category = "Ulusal"
        ),
        LiveChannel(
            name = "Kanal D HD",
            streamUrl = "https://live.duhancdn.com/kanald/smil:kanald.smil/playlist.m3u8",
            posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/5/52/Kanal_D_logo_2020.svg/300px-Kanal_D_logo_2020.svg.png",
            category = "Ulusal"
        ),
        LiveChannel(
            name = "Show TV HD",
            streamUrl = "https://cbs-live.ercdn.net/showtv/showtv.m3u8",
            posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/9/91/Show_TV_logo_2017.svg/300px-Show_TV_logo_2017.svg.png",
            category = "Ulusal"
        ),
        LiveChannel(
            name = "Star TV HD",
            streamUrl = "https://dogus-live.ercdn.net/startv/startv.m3u8",
            posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/7/7b/Star_TV_logo_2019.svg/300px-Star_TV_logo_2019.svg.png",
            category = "Ulusal"
        ),
        LiveChannel(
            name = "TV8 HD",
            streamUrl = "https://tv8-live.ercdn.net/tv8/tv8.m3u8",
            posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a2/TV8_logo_2014.svg/300px-TV8_logo_2014.svg.png",
            category = "Ulusal"
        ),

        // Spor Kanalları
        LiveChannel(
            name = "TRT Spor HD",
            streamUrl = "https://tv-trtsporyildiz.medya.trt.com.tr/master.m3u8",
            posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/b/b5/TRT_Spor_logo_2021.svg/300px-TRT_Spor_logo_2021.svg.png",
            category = "Spor"
        ),
        LiveChannel(
            name = "A Spor HD",
            streamUrl = "https://trkvz-live.ercdn.net/asporhd/asporhd.m3u8",
            posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/7/70/A_Spor_logo.svg/300px-A_Spor_logo.svg.png",
            category = "Spor"
        ),

        // Haber & Belgesel
        LiveChannel(
            name = "TRT Haber HD",
            streamUrl = "https://tv-trthaber.medya.trt.com.tr/master.m3u8",
            posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e7/TRT_Haber_logo_2021.svg/300px-TRT_Haber_logo_2021.svg.png",
            category = "Haber"
        ),
        LiveChannel(
            name = "NTV HD",
            streamUrl = "https://dogus-live.ercdn.net/ntv/ntv.m3u8",
            posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/7/78/NTV_logo_2019.svg/300px-NTV_logo_2019.svg.png",
            category = "Haber"
        ),
        LiveChannel(
            name = "Habertürk HD",
            streamUrl = "https://cbs-live.ercdn.net/haberturk/haberturk.m3u8",
            posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/0/07/Habert%C3%BCrk_TV_logo_2018.svg/300px-Habert%C3%BCrk_TV_logo_2018.svg.png",
            category = "Haber"
        ),
        LiveChannel(
            name = "Sözcü TV HD",
            streamUrl = "https://sozcutv-live.ercdn.net/sozcutv/sozcutv.m3u8",
            posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a4/S%C3%B6zc%C3%BC_TV_logo.svg/300px-S%C3%B6zc%C3%BC_TV_logo.svg.png",
            category = "Haber"
        ),
        LiveChannel(
            name = "TRT Belgesel HD",
            streamUrl = "https://tv-trtbelgesel.medya.trt.com.tr/master.m3u8",
            posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/4/4b/TRT_Belgesel_logo_2019.svg/300px-TRT_Belgesel_logo_2019.svg.png",
            category = "Belgesel"
        )
    )

    fun getSearchResponses(api: com.lagradost.cloudstream3.MainAPI): List<SearchResponse> {
        return channels.map { ch ->
            api.newLiveSearchResponse(
                name = ch.name,
                url = "live://${ch.name}?stream=${ch.streamUrl}",
                type = TvType.Live
            ) {
                this.posterUrl = ch.posterUrl
            }
        }
    }

    fun getExtractorLink(channelName: String, streamUrl: String): ExtractorLink {
        return ExtractorLink(
            source = "LiveTV",
            name = "📺 $channelName [Canlı Yayın]",
            url = streamUrl,
            referer = "",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.M3U8
        )
    }
}
