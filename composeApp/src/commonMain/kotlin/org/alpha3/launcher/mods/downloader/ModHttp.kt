package org.alpha3.launcher.mods.downloader

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import okio.IOException

/** The downloader's one HTTP client (OkHttp on Android, NSURLSession on iOS). */
object ModHttp {
    val client = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
        // Network failures, 5xx and 429 are tried again with a growing wait (Retry-After is honoured). Other errors
        // (404, 403...) go straight back to the caller.
        install(HttpRequestRetry) {
            maxRetries = 2
            retryIf { _, response -> response.status.value == 429 || response.status.value >= 500 }
            retryOnException(retryOnTimeout = true)
            exponentialDelay(base = 2.0, maxDelayMs = 10_000, respectRetryAfterHeader = true)
        }
    }

    /** The body of [url], throwing on an error status. */
    suspend fun getText(url: String, userAgent: String? = "Alpha3/2.71"): String {
        val response = client.get(url) { if (userAgent != null) header("User-Agent", userAgent) }
        if (!response.status.isSuccess()) throw IOException("HTTP ${response.status.value} for $url")
        return response.bodyAsText()
    }
}

/** The query parameter [name] of [url], undecoded. */
internal fun queryParameter(url: String, name: String): String? =
    Regex("[?&]${Regex.escape(name)}=([^&#]*)").find(url)?.groupValues?.get(1)
