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
        return com.cloudstream.tr.core.streaming.SmartPlayEngine.calculateScore(link, null)
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
