package com.cloudstream.tr.hub

import com.cloudstream.tr.core.model.ProviderModels
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import kotlin.math.abs
import kotlin.math.max

object HubMatchingEngine {

    /**
     * Calculates Jaccard token overlap similarity between two normalized strings.
     */
    fun tokenSimilarity(s1: String, s2: String): Double {
        val t1 = s1.split(" ").filter { it.isNotBlank() }.toSet()
        val t2 = s2.split(" ").filter { it.isNotBlank() }.toSet()
        if (t1.isEmpty() || t2.isEmpty()) return 0.0
        val intersection = t1.intersect(t2).size
        val union = t1.union(t2).size
        return intersection.toDouble() / union.toDouble()
    }

    /**
     * Matches search candidates against target title, year, and media type with high confidence.
     * Returns null if no candidate reaches the confidence threshold (>= 0.80).
     * NEVER falls back to an arbitrary first result.
     */
    fun findConfidentMatch(
        candidates: List<SearchResponse>,
        targetTitle: String,
        targetYear: Int? = null,
        isMovie: Boolean? = null,
        minConfidence: Double = 0.80
    ): SearchResponse? {
        if (candidates.isEmpty()) return null

        val normTarget = ProviderModels.normalizeTitle(targetTitle)
        var bestCandidate: SearchResponse? = null
        var highestScore = 0.0

        for (item in candidates) {
            val normName = ProviderModels.normalizeTitle(item.name)
            var score = 0.0

            // 1. Title match
            if (normName == normTarget) {
                score += 0.85
            } else if (normName.contains(normTarget) || normTarget.contains(normName)) {
                val ratio = normTarget.length.toDouble() / max(normName.length, 1)
                score += (0.65 * ratio).coerceIn(0.40, 0.75)
            } else {
                val sim = tokenSimilarity(normTarget, normName)
                score += (0.70 * sim)
            }

            // 2. Year validation
            val itemYear: Int? = when (item) {
                is MovieSearchResponse -> item.year
                is TvSeriesSearchResponse -> item.year
                is AnimeSearchResponse -> item.year
                else -> null
            }
            if (targetYear != null && itemYear != null) {
                val diff = abs(targetYear - itemYear)
                if (diff == 0) {
                    score += 0.20
                } else if (diff == 1) {
                    score += 0.05
                } else {
                    // Mismatched year penalty
                    score -= 0.35
                }
            } else if (score >= 0.80) {
                score += 0.05
            }

            // 3. Media type validation (prevent cross-matching movie with tv series)
            if (isMovie != null) {
                val candidateIsMovie = when (item) {
                    is MovieSearchResponse -> true
                    is TvSeriesSearchResponse -> false
                    else -> item.type == TvType.Movie
                }
                val candidateIsSeries = when (item) {
                    is TvSeriesSearchResponse -> true
                    is MovieSearchResponse -> false
                    else -> item.type == TvType.TvSeries
                }
                if (isMovie && candidateIsSeries) {
                    score -= 0.50
                } else if (!isMovie && candidateIsMovie) {
                    score -= 0.50
                }
            }

            if (score > highestScore) {
                highestScore = score
                bestCandidate = item
            }
        }

        return if (highestScore >= minConfidence) bestCandidate else null
    }
}
