package com.example.localai.data.hf

import com.example.localai.data.catalog.CatalogError
import com.example.localai.data.catalog.CatalogResult
import java.io.IOException
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Bounded retry behaviour for transient failures. Permanent errors are never retried. */
data class RetryPolicy(
    /** Total attempts including the first one. */
    val maxAttempts: Int = 3,
    val baseDelayMillis: Long = 500,
    val maxDelayMillis: Long = 8_000,
    /** A Retry-After longer than this is surfaced to the caller instead of being waited out. */
    val maxRetryAfterSeconds: Long = 30
) {
    init {
        require(maxAttempts >= 1)
    }

    fun backoffMillis(attempt: Int): Long =
        (baseDelayMillis shl (attempt - 1).coerceAtMost(20)).coerceIn(0, maxDelayMillis)
}

/** Successful response body plus the URL of the next page, if the Hub advertised one. */
internal data class HubResponse(val body: String, val nextUrl: HttpUrl?)

private class RawResponse(val code: Int, val headers: Headers, val body: String?)

/**
 * Executes unauthenticated GET requests against the Hub on [dispatcher] (never the main thread),
 * with bounded exponential backoff, Retry-After support and Link-header pagination.
 */
internal class HubHttp(
    private val client: OkHttpClient,
    private val origin: HttpUrl,
    private val retry: RetryPolicy,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {

    suspend fun get(url: HttpUrl): CatalogResult<HubResponse> {
        var attempt = 1
        while (true) {
            val outcome = attemptOnce(url)
            val failure = (outcome as? CatalogResult.Failure)?.error ?: return outcome
            val waitMillis = retryDelayMillis(failure, attempt)
            if (waitMillis == null || attempt >= retry.maxAttempts) return outcome
            pause(waitMillis)
            attempt++
        }
    }

    /** Null when the error is permanent or must be reported rather than waited out. */
    private fun retryDelayMillis(error: CatalogError, attempt: Int): Long? = when (error) {
        CatalogError.Offline -> retry.backoffMillis(attempt)
        is CatalogError.Server ->
            if (error.httpCode in RETRYABLE_SERVER_CODES) retry.backoffMillis(attempt) else null
        is CatalogError.RateLimited -> {
            val seconds = error.retryAfterSeconds
            when {
                seconds == null -> retry.backoffMillis(attempt)
                seconds > retry.maxRetryAfterSeconds -> null
                else -> seconds * 1000
            }
        }
        CatalogError.AccessRequired, CatalogError.NotFound, is CatalogError.Malformed -> null
    }

    private suspend fun attemptOnce(url: HttpUrl): CatalogResult<HubResponse> {
        val raw = try {
            execute(url)
        } catch (e: IOException) {
            coroutineContext.ensureActive() // a cancelled call also surfaces as IOException
            return CatalogResult.Failure(CatalogError.Offline)
        }
        return when (raw.code) {
            in 200..299 -> success(raw)
            401, 403 -> CatalogResult.Failure(CatalogError.AccessRequired)
            404 -> CatalogResult.Failure(CatalogError.NotFound)
            429 -> CatalogResult.Failure(CatalogError.RateLimited(retryAfterSeconds(raw.headers)))
            else -> CatalogResult.Failure(CatalogError.Server(raw.code))
        }
    }

    private fun success(raw: RawResponse): CatalogResult<HubResponse> {
        val body = raw.body ?: return CatalogResult.Failure(CatalogError.Malformed("Empty response body"))
        val next = parseNextLink(raw.headers, origin)
        if (next is NextLink.Foreign) {
            return CatalogResult.Failure(CatalogError.Malformed("Pagination link points to another host"))
        }
        return CatalogResult.Success(HubResponse(body, (next as? NextLink.Url)?.url))
    }

    private suspend fun execute(url: HttpUrl): RawResponse = withContext(dispatcher) {
        // No Authorization header is ever sent: only public endpoints are used.
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        val call = client.newCall(request)
        val cancelOnCompletion = coroutineContext[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            call.execute().use { response ->
                val body = if (response.isSuccessful) response.body.string() else null
                RawResponse(response.code, response.headers, body)
            }
        } finally {
            cancelOnCompletion?.dispose()
        }
    }

    private companion object {
        const val USER_AGENT = "LocalAI-Android/1.0 (public Hub API client)"
        val RETRYABLE_SERVER_CODES = setOf(500, 502, 503, 504)
    }
}

internal sealed interface NextLink {
    data object None : NextLink
    data class Url(val url: HttpUrl) : NextLink

    /** A next link that is not on the Hub origin; following it could leak requests elsewhere. */
    data object Foreign : NextLink
}

private val NEXT_LINK = Regex("""<([^>]+)>\s*;[^,]*\brel="?next"?""", RegexOption.IGNORE_CASE)

/** Reads the rel="next" target from the Link headers, resolved against [origin]. */
internal fun parseNextLink(headers: Headers, origin: HttpUrl): NextLink {
    for (value in headers.values("Link")) {
        val target = NEXT_LINK.find(value)?.groupValues?.get(1) ?: continue
        val url = origin.resolve(target) ?: return NextLink.Foreign
        return if (url.isSameOrigin(origin)) NextLink.Url(url) else NextLink.Foreign
    }
    return NextLink.None
}

internal fun HttpUrl.isSameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

/**
 * Seconds to wait before retrying a 429. Prefers Retry-After (seconds or HTTP date) and falls
 * back to the Hub's RateLimit header (`"api";r=0;t=126`, where t is seconds until reset).
 */
internal fun retryAfterSeconds(headers: Headers, now: ZonedDateTime = ZonedDateTime.now()): Long? {
    headers["Retry-After"]?.trim()?.let { value ->
        value.toLongOrNull()?.let { return it.coerceAtLeast(0) }
        runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME) }.getOrNull()
            ?.let { return Duration.between(now, it).seconds.coerceAtLeast(0) }
    }
    return headers["RateLimit"]?.let { Regex("""\bt=(\d+)""").find(it) }
        ?.groupValues?.get(1)?.toLongOrNull()
}
