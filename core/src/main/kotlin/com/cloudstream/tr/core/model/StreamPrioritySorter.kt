package com.cloudstream.tr.core.model

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

object StreamPrioritySorter {
    /**
     * Calculates an integer priority score for an ExtractorLink.
     * Higher score indicates higher playback preference (instant CDN > local TR > torrent fallback).
     */
    fun getPriorityScore(link: ExtractorLink): Int {
        var score = com.cloudstream.tr.core.streaming.SmartPlayEngine.calculateScore(link, null)
        val name = link.name.lowercase()

        // 1. Connection / Transport Type
        when {
            // Direct Video HTTP/HLS stream from verified TR sites (Local TR Direct Links)
            link.type == ExtractorLinkType.VIDEO || link.type == ExtractorLinkType.M3U8 -> {
                score += 2000
            }
            // Direct DDL CDN (4KHDHub, HubCloud, FSL Server)
            link.source.contains("4khdhub", ignoreCase = true) || name.contains("hub-cloud") || name.contains("fsl") -> {
                score += 1500
            }
            // Debrid Unrestricted CDN
            name.contains("debrid") -> {
                score += 1200
            }
            // Torrent P2P Magnet link (fallback option)
            link.type == ExtractorLinkType.MAGNET || link.type == ExtractorLinkType.TORRENT -> {
                score += 100
            }
        }

        // 2. Language & Local Audio Preference
        if (name.contains("dublaj") || name.contains("türkçe ses") || name.contains("tr dub")) {
            score += 500
        } else if (name.contains("altyazı") || name.contains("tr alt")) {
            score += 300
        }

        // 3. Resolution & Quality Priority
        when (link.quality) {
            Qualities.P2160.value -> score += 300
            Qualities.P1080.value -> score += 100
            Qualities.P720.value -> score += 50
        }

        if (name.contains("remux")) score += 400
        if (name.contains("blu-ray") || name.contains("bluray")) score += 200
        if (name.contains("web-dl")) score += 100

        // 4. Video Formats
        if (name.contains("dolby vision") || name.contains("dv")) score += 250
        if (name.contains("hdr10+")) score += 200
        else if (name.contains("hdr") || name.contains("10-bit")) score += 100

        // 5. Audio Quality Bonus
        if (name.contains("truehd atmos 7.1")) score += 300
        else if (name.contains("dts-hd ma 7.1") || name.contains("dts-hd ma")) score += 250
        else if (name.contains("atmos 7.1") || name.contains("ddp 5.1 atmos") || name.contains("atmos")) score += 200
        else if (name.contains("7.1")) score += 150
        else if (name.contains("5.1") || name.contains("dd")) score += 50

        return score
    }

    /**
     * Sorts a list of ExtractorLink descending by priority score.
     */
    fun sortByPriority(links: List<ExtractorLink>): List<ExtractorLink> {
        val sorted = links.sortedByDescending { getPriorityScore(it) }.toMutableList()
        if (sorted.isNotEmpty()) {
            val first = sorted[0]
            val smartPlayName = "[⚡ Smart Play] ${first.name}"
            val smartPlayLink = ExtractorLink(
                source = first.source,
                name = smartPlayName,
                url = first.url,
                referer = first.referer,
                quality = first.quality,
                type = first.type,
                headers = first.headers,
                extractorData = first.extractorData
            )
            sorted[0] = smartPlayLink
        }
        return sorted
    }
}
