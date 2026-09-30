package com.cloudstream.tr.core.streaming

import com.cloudstream.tr.core.network.PreflightResult
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

object SmartPlayEngine {
    fun calculateScore(link: ExtractorLink, preflight: PreflightResult?): Int {
        var score = 0
        val name = link.name.lowercase()

        // 1. Accessibility & Preflight Status
        if (preflight != null) {
            if (!preflight.isValid) return -1000 // Invalidate
        }

        // 2. Latency
        if (preflight != null) {
            val latency = preflight.latencyMs
            when {
                latency in 1..299 -> score += 400
                latency in 300..599 -> score += 200
                latency > 1500 -> score -= 300
            }
        }

        // 1. Connection / Transport Type
        when {
            link.source.contains("4khdhub", ignoreCase = true) || name.contains("hub-cloud") || name.contains("fsl") -> {
                score += 1500
            }
            name.contains("debrid") -> {
                score += 1500
            }
            link.type == ExtractorLinkType.VIDEO || link.type == ExtractorLinkType.M3U8 -> {
                if (name.contains("dublaj") || name.contains("türkçe ses") || name.contains("tr dub") ||
                    name.contains("altyazı") || name.contains("tr alt")) {
                    score += 1400
                } else {
                    score += 1000
                }
            }
            link.type == ExtractorLinkType.MAGNET || link.type == ExtractorLinkType.TORRENT -> {
                score += 600
                val seeds = Regex("""\((\d+) seeds\)""").find(link.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (seeds >= 50) score += 300
                else if (seeds >= 30) score += 200
                else if (seeds >= 10) score += 100
            }
        }

        // 2. Resolution & Quality Priority
        when (link.quality) {
            Qualities.P2160.value -> score += 500
            Qualities.P1080.value -> score += 300
            Qualities.P720.value -> score += 100
        }

        // 3. Release
        if (name.contains("remux")) score += 400
        else if (name.contains("blu-ray") || name.contains("bluray")) score += 200
        else if (name.contains("web-dl")) score += 100

        // 4. Video Formats
        if (name.contains("dolby vision") || name.contains("dv")) score += 300
        else if (name.contains("hdr10+")) score += 250
        else if (name.contains("hdr")) score += 150

        // 5. Audio Quality
        if (name.contains("truehd atmos 7.1")) score += 350
        else if (name.contains("dts-hd ma 7.1") || name.contains("dts-hd ma")) score += 300
        else if (name.contains("atmos 7.1") || name.contains("atmos")) score += 250
        else if (name.contains("5.1") || name.contains("dd")) score += 100

        val pref = com.cloudstream.tr.core.resolvers.DebridConfig.audioPreference
        if (pref == "TR_DUB" && (name.contains("dublaj") || name.contains("türkçe ses"))) score += 500
        else if (pref == "ORIGINAL" && !(name.contains("dublaj") || name.contains("türkçe ses"))) score += 500

        return score
    }
}
