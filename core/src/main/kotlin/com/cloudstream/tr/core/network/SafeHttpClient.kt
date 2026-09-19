package com.cloudstream.tr.core.network

import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * SafeHttpClient provides resilient networking for CloudStreamTR providers:
 * - Request deduplication for simultaneous in-flight identical requests (RD-005)
 * - Safe error handling preserving privacy and contract semantics
 * - Standardized headers and browser-like user agent
 */
object SafeHttpClient {
    const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val inFlightRequests = ConcurrentHashMap<String, Deferred<NiceResponse>>()
    private val mutex = Mutex()

    fun defaultHeaders(referer: String? = null, isAjax: Boolean = false): Map<String, String> {
        val map = mutableMapOf(
            "User-Agent" to DEFAULT_USER_AGENT,
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept" to "*/*"
        )
        if (referer != null) {
            map["Referer"] = referer
        }
        if (isAjax) {
            map["X-Requested-With"] = "XMLHttpRequest"
        }
        return map
    }

    /**
     * Executes an HTTP GET request with in-flight deduplication.
     * If another coroutine is already fetching this exact URL, awaits the same result.
     */
    suspend fun getDedup(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cacheTime: Int = 0
    ): NiceResponse = coroutineScope {
        val key = "GET:$url"
        val deferred = mutex.withLock {
            inFlightRequests.getOrPut(key) {
                async {
                    try {
                        val mergedHeaders = defaultHeaders().toMutableMap().apply { putAll(headers) }
                        app.get(url, headers = mergedHeaders, cacheTime = cacheTime)
                    } finally {
                        mutex.withLock {
                            inFlightRequests.remove(key)
                        }
                    }
                }
            }
        }
        deferred.await()
    }

    /**
     * Executes safe GET returning null on 404, DNS failure, or connection drop
     */
    suspend fun safeGet(
        url: String,
        headers: Map<String, String> = emptyMap(),
        provider: String = "Generic"
    ): NiceResponse? {
        return try {
            val mergedHeaders = defaultHeaders().toMutableMap().apply { putAll(headers) }
            app.get(url, headers = mergedHeaders)
        } catch (e: Exception) {
            com.cloudstream.tr.core.diagnostics.DiagnosticLogger.log(
                provider = provider,
                stage = com.cloudstream.tr.core.diagnostics.DiagnosticStage.DOMAIN,
                category = com.cloudstream.tr.core.diagnostics.DiagnosticCategory.NETWORK,
                message = "safeGet failed: ${e.message}",
                url = url,
                throwable = e
            )
            null
        }
    }

    /**
     * Executes safe POST returning null on network or parsing failure
     */
    suspend fun safePost(
        url: String,
        headers: Map<String, String> = emptyMap(),
        data: Map<String, String> = emptyMap(),
        jsonString: String? = null,
        provider: String = "Generic"
    ): NiceResponse? {
        return try {
            val mergedHeaders = defaultHeaders().toMutableMap().apply { putAll(headers) }
            if (jsonString != null) {
                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val body = jsonString.toRequestBody(mediaType)
                app.post(url, headers = mergedHeaders, requestBody = body)
            } else {
                app.post(url, headers = mergedHeaders, data = data)
            }
        } catch (e: Exception) {
            com.cloudstream.tr.core.diagnostics.DiagnosticLogger.log(
                provider = provider,
                stage = com.cloudstream.tr.core.diagnostics.DiagnosticStage.DOMAIN,
                category = com.cloudstream.tr.core.diagnostics.DiagnosticCategory.NETWORK,
                message = "safePost failed: ${e.message}",
                url = url,
                throwable = e
            )
            null
        }
    }
}
