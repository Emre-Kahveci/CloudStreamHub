package com.cloudstream.tr.core.model

import com.lagradost.cloudstream3.SearchResponse
import java.util.Locale

object ProviderModels {

    /**
     * Standardizes source display names with explicit language / dubbing tags
     */
    fun formatSourceTitle(
        sourceName: String,
        isDubbed: Boolean = false,
        isSubbed: Boolean = false,
        resolution: String? = null
    ): String {
        val tags = mutableListOf<String>()
        if (isDubbed) tags.add("TR Dublaj")
        if (isSubbed) tags.add("TR Altyazı")
        if (resolution != null && resolution.isNotBlank()) tags.add(resolution.trim())

        return if (tags.isNotEmpty()) {
            "$sourceName [${tags.joinToString(" • ")}]"
        } else {
            sourceName
        }
    }

    /**
     * Normalizes a title for deduplication and matching (lowercased, punctuation stripped)
     */
    fun normalizeTitle(rawTitle: String): String {
        return rawTitle
            .lowercase(Locale("tr", "TR"))
            .replace(Regex("[^a-z0-9ğüşıöç\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Deduplicates search results across multiple queries or providers by URL and normalized title
     */
    fun dedupSearchResults(results: List<SearchResponse>): List<SearchResponse> {
        val seenUrls = mutableSetOf<String>()
        val seenTitles = mutableSetOf<String>()
        val output = mutableListOf<SearchResponse>()

        for (item in results) {
            val normUrl = item.url.trim().removeSuffix("/")
            val normTitle = normalizeTitle(item.name)

            if (seenUrls.contains(normUrl) || seenTitles.contains(normTitle)) {
                continue
            }
            seenUrls.add(normUrl)
            seenTitles.add(normTitle)
            output.add(item)
        }
        return output
    }
}
