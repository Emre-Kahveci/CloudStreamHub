package com.cloudstream.tr.core.network

import com.cloudstream.tr.core.diagnostics.DiagnosticCategory
import com.cloudstream.tr.core.diagnostics.DiagnosticLogger
import com.cloudstream.tr.core.diagnostics.DiagnosticStage
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

data class PreflightResult(
    val isValid: Boolean,
    val streamType: ExtractorLinkType,
    val detectedMime: String? = null,
    val statusCode: Int = 0,
    val failureReason: String? = null,
    val media3ErrorCode: Int? = null
)

object StreamValidator {
    private const val DEFAULT_TIMEOUT_MS = 2000L

    /**
     * Checks if the given byte array matches known container/manifest magic headers.
     */
    fun inferTypeFromBytes(bytes: ByteArray): ExtractorLinkType? {
        if (bytes.isEmpty()) return null

        val textHeader = String(bytes.take(256).toByteArray(), Charsets.UTF_8).trimStart()
        if (textHeader.startsWith("#EXTM3U") || textHeader.contains("#EXT-X-STREAM-INF") || textHeader.contains("#EXT-X-TARGETDURATION")) {
            return ExtractorLinkType.M3U8
        }

        // EBML (Matroska / WebM): 0x1A, 0x45, 0xDF, 0xA3
        if (bytes.size >= 4 &&
            bytes[0] == 0x1A.toByte() &&
            bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() &&
            bytes[3] == 0xA3.toByte()
        ) {
            return ExtractorLinkType.VIDEO
        }

        // MP4: 'ftyp' at offset 4..7
        if (bytes.size >= 8) {
            val ftyp = String(bytes.sliceArray(4..7), Charsets.US_ASCII)
            if (ftyp == "ftyp" || ftyp == "moov") {
                return ExtractorLinkType.VIDEO
            }
        }

        // MPEG-TS sync byte 0x47
        if (bytes.size >= 188 && bytes[0] == 0x47.toByte() && bytes[188] == 0x47.toByte()) {
            return ExtractorLinkType.VIDEO
        }

        return null
    }

    /**
     * Infers stream type from explicit hint, content-type header, and URL path.
     */
    fun inferTypeFromMetadata(contentType: String?, url: String): ExtractorLinkType {
        val ct = contentType?.lowercase() ?: ""
        val cleanUrl = url.lowercase().substringBefore("?")

        if (ct.contains("application/vnd.apple.mpegurl") ||
            ct.contains("application/x-mpegurl") ||
            cleanUrl.endsWith(".m3u8") ||
            cleanUrl.contains("/hls/")
        ) {
            return ExtractorLinkType.M3U8
        }

        if (ct.contains("application/dash+xml") || cleanUrl.endsWith(".mpd")) {
            return ExtractorLinkType.DASH
        }

        if (ct.contains("video/") ||
            cleanUrl.endsWith(".mp4") ||
            cleanUrl.endsWith(".mkv") ||
            cleanUrl.endsWith(".webm")
        ) {
            return ExtractorLinkType.VIDEO
        }

        return ExtractorLinkType.VIDEO
    }

    /**
     * Determines if a response is an invalid media response (HTML error, bot challenge, JSON error).
     */
    fun isInvalidMediaBody(bodySample: String, contentType: String?): Boolean {
        val lower = bodySample.lowercase().trim()
        val ct = contentType?.lowercase() ?: ""

        if (ct.contains("text/html") ||
            lower.startsWith("<!doctype html") ||
            lower.startsWith("<html") ||
            lower.contains("just a moment...") ||
            lower.contains("cloudflare") ||
            lower.contains("security error") ||
            lower.contains("access denied") ||
            lower.contains("403 forbidden") ||
            lower.contains("404 not found")
        ) {
            return true
        }

        if (ct.contains("application/json") &&
            (lower.contains("\"error\"") || lower.contains("\"message\"") || lower.contains("\"status\":false"))
        ) {
            return true
        }

        return false
    }

    /**
     * Executes a bounded preflight check on a stream URL before emitting ExtractorLink.
     * Drops HTML error pages, Cloudflare challenges, and expired links that would cause 3003.
     */
    suspend fun validateStream(
        url: String,
        headers: Map<String, String> = emptyMap(),
        provider: String = "Generic",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): PreflightResult {
        if (url.isBlank() || !url.startsWith("http")) {
            return PreflightResult(
                isValid = false,
                streamType = ExtractorLinkType.VIDEO,
                failureReason = "INVALID_URL_FORMAT",
                media3ErrorCode = 2004
            )
        }

        return try {
            withTimeoutOrNull(timeoutMs) {
                val reqHeaders = headers.toMutableMap().apply {
                    putIfAbsent("User-Agent", SafeHttpClient.DEFAULT_USER_AGENT)
                    putIfAbsent("Range", "bytes=0-1024")
                }

                val response = app.get(url, headers = reqHeaders)
                val code = response.code
                val ct = response.headers["Content-Type"] ?: response.headers["content-type"]
                val bytes = response.body.bytes()
                val bodySample = String(bytes.take(256).toByteArray(), Charsets.UTF_8)

                if (code in listOf(401, 403, 404, 410, 429) || code >= 500) {
                    DiagnosticLogger.log(
                        provider = provider,
                        stage = DiagnosticStage.STREAM_PREFLIGHT,
                        category = DiagnosticCategory.HTTP,
                        message = "Preflight HTTP $code received for stream: ${DiagnosticLogger.redactUrl(url)}",
                        url = url,
                        httpStatus = code,
                        media3ErrorCode = 2004
                    )
                    return@withTimeoutOrNull PreflightResult(
                        isValid = false,
                        streamType = ExtractorLinkType.VIDEO,
                        statusCode = code,
                        failureReason = "HTTP_STATUS_$code",
                        media3ErrorCode = 2004
                    )
                }

                if (isInvalidMediaBody(bodySample, ct)) {
                    DiagnosticLogger.log(
                        provider = provider,
                        stage = DiagnosticStage.STREAM_PREFLIGHT,
                        category = DiagnosticCategory.CONTAINER,
                        message = "Preflight rejected HTML/challenge response (3003 prevention) for: ${DiagnosticLogger.redactUrl(url)}",
                        url = url,
                        httpStatus = code,
                        media3ErrorCode = 3003
                    )
                    return@withTimeoutOrNull PreflightResult(
                        isValid = false,
                        streamType = ExtractorLinkType.VIDEO,
                        statusCode = code,
                        failureReason = "INVALID_MEDIA_CONTAINER_HTML",
                        media3ErrorCode = 3003
                    )
                }

                val inferredFromBytes = inferTypeFromBytes(bytes)
                val finalType = inferredFromBytes ?: inferTypeFromMetadata(ct, url)

                PreflightResult(
                    isValid = true,
                    streamType = finalType,
                    detectedMime = ct,
                    statusCode = code
                )
            } ?: run {
                // Timeout elapsed: Fall back safely to metadata inference without blocking player
                val fallbackType = inferTypeFromMetadata(null, url)
                PreflightResult(
                    isValid = true,
                    streamType = fallbackType,
                    failureReason = "PREFLIGHT_TIMEOUT_FALLBACK"
                )
            }
        } catch (e: Exception) {
            DiagnosticLogger.log(
                provider = provider,
                stage = DiagnosticStage.STREAM_PREFLIGHT,
                category = DiagnosticCategory.NETWORK,
                message = "Preflight exception: ${e.message}",
                url = url,
                throwable = e,
                media3ErrorCode = 2001
            )
            // If preflight failed with a network error, allow playback attempt with metadata type
            PreflightResult(
                isValid = true,
                streamType = inferTypeFromMetadata(null, url),
                failureReason = "PREFLIGHT_EXCEPTION_FALLBACK"
            )
        }
    }
}
