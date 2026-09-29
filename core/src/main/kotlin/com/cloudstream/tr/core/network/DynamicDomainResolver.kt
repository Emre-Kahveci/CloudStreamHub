package com.cloudstream.tr.core.network

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DomainConfig(
    @JsonProperty("allowedHosts") val allowedHosts: List<String>? = null,
    @JsonProperty("mirrors") val mirrors: List<String>? = null
)

object DynamicDomainResolver {
    private const val GITHUB_DOMAINS_URL = "https://raw.githubusercontent.com/Emre-Kahveci/CloudStreamHub/master/domains.json"
    private var lastFetchTime = 0L
    private const val CACHE_DURATION_MS = 12 * 60 * 60 * 1000L // 12 hours
    private val domainsCache = mutableMapOf<String, DomainConfig>()
    private val currentDomainIndex = mutableMapOf<String, Int>()
    private val mutex = Mutex()

    suspend fun resolve(providerId: String, defaultUrl: String): String {
        fetchDomainsIfNeeded()
        val config = domainsCache[providerId] ?: return defaultUrl
        val hosts = config.mirrors ?: config.allowedHosts ?: return defaultUrl
        if (hosts.isEmpty()) return defaultUrl

        val index = currentDomainIndex[providerId] ?: 0
        return hosts[index % hosts.size]
    }

    suspend fun fallbackToNextMirror(providerId: String) {
        mutex.withLock {
            val config = domainsCache[providerId]
            val hosts = config?.mirrors ?: config?.allowedHosts
            if (!hosts.isNullOrEmpty()) {
                val current = currentDomainIndex[providerId] ?: 0
                currentDomainIndex[providerId] = current + 1
            }
        }
    }

    private suspend fun fetchDomainsIfNeeded() {
        if (System.currentTimeMillis() - lastFetchTime < CACHE_DURATION_MS && domainsCache.isNotEmpty()) {
            return
        }
        mutex.withLock {
            if (System.currentTimeMillis() - lastFetchTime < CACHE_DURATION_MS && domainsCache.isNotEmpty()) {
                return
            }
            try {
                val response = app.get(GITHUB_DOMAINS_URL).parsedSafe<Map<String, DomainConfig>>()
                if (response != null) {
                    domainsCache.clear()
                    domainsCache.putAll(response)
                    lastFetchTime = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                // Ignore and use default domains
            }
        }
    }
}
