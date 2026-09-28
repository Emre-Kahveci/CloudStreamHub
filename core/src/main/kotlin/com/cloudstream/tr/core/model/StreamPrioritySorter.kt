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
        var score = 0
        val name = link.name.lowercase()

        // 1. Connection / Transport Type
        when {
            // Direct DDL CDN (4KHDHub, HubCloud, FSL Server)
            link.source.contains("4khdhub", ignoreCase = true) || name.contains("hub-cloud") || name.contains("fsl") -> {
                score += 1000
            }
            // Debrid Unrestricted CDN
            name.contains("debrid") -> {
                score += 900
            }
            // Direct Video HTTP/HLS stream from verified TR sites
            link.type == ExtractorLinkType.VIDEO || link.type == ExtractorLinkType.M3U8 -> {
                score += 400
            }
            // Torrent P2P Magnet link (fallback option)
            link.type == ExtractorLinkType.MAGNET || link.type == ExtractorLinkType.TORRENT -> {
                score += 100
            }
        }

        // 2. Language & Local Audio Preference
        if (name.contains("dublaj") || name.contains("türkçe ses") || name.contains("tr dub")) {
            score += 300
        } else if (name.contains("altyazı") || name.contains("tr alt")) {
            score += 150
        }

        // 3. Resolution Priority
        when (link.quality) {
            Qualities.P2160.value -> score += 200
            Qualities.P1080.value -> score += 100
            Qualities.P720.value -> score += 50
        }

        // 4. Audio Quality Bonus
        if (name.contains("atmos")) {
            score += 50
        } else if (name.contains("5.1") || name.contains("7.1") || name.contains("dd")) {
            score += 30
        }

        return score
    }

    /**
     * Sorts a list of ExtractorLink descending by priority score.
     */
    fun sortByPriority(links: List<ExtractorLink>): List<ExtractorLink> {
        return links.sortedByDescending { getPriorityScore(it) }
    }
}
