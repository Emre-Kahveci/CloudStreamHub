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

        // 3. Debrid & Fast CDN
        if (link.source.contains("4khdhub", ignoreCase = true) || name.contains("hub-cloud") || name.contains("fsl")) {
            score += 1500
        }
        if (name.contains("debrid")) {
            score += 1200
        }

        // 4. Local Direct Source
        if (link.type == ExtractorLinkType.VIDEO || link.type == ExtractorLinkType.M3U8) {
            if (name.contains("dublaj") || name.contains("türkçe ses") || name.contains("tr dub") ||
                name.contains("altyazı") || name.contains("tr alt")) {
                score += 1800
            } else {
                score += 1000
            }
        }

        // 5. Torrent Health (Mock for now, or just resolution based)
        if (link.type == ExtractorLinkType.MAGNET || link.type == ExtractorLinkType.TORRENT) {
            // we could check name for seeder count if it was passed there
            if (name.contains("remux") && (name.contains("4k") || name.contains("2160p") || link.quality == Qualities.P2160.value)) {
                score += 300
            }
            score += 100
        }

        return score
    }
}
