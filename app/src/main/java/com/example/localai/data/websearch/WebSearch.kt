package com.example.localai.data.websearch

import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** One search result: shown to the user as a source link and given to the model as untrusted text. */
data class WebResult(val title: String, val url: String, val snippet: String)

class WebSearchException(message: String) : Exception(message)

/** A search service. Only the query is ever sent: no conversation, attachments or identifiers. */
interface WebSearchProvider {
    /** Who receives the query, as shown to the user before they opt in. */
    val name: String

    /** At most [WebSearchLimits.MAX_RESULTS] results; empty when nothing matched. */
    suspend fun search(query: String): List<WebResult>
}

object WebSearchLimits {
    const val MAX_RESULTS = 3
    const val MAX_SNIPPET_CHARS = 600
    const val MAX_TITLE_CHARS = 120
    const val MAX_QUERY_CHARS = 200
    const val MAX_QUERY_WORDS = 12
    const val MAX_RESPONSE_BYTES = 256L * 1024

    private val fillerWords = setOf(
        "who", "what", "whats", "when", "where", "which", "why", "how", "is", "are", "was", "were", "does", "do",
        "did", "can", "could", "would", "will", "should", "you", "please", "tell", "me", "about", "explain", "the", "a", "an"
    )

    /**
     * The search query derived from the user's message: question words and punctuation are dropped
     * and the length is bounded. This text is all that leaves the device.
     */
    fun queryFrom(message: String): String {
        val words = message
            .replace(Regex("[^\\p{L}\\p{N}\\s'-]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        val kept = words.filterNot { it.lowercase(Locale.ROOT).replace("'", "") in fillerWords }.ifEmpty { words }
        return kept.take(MAX_QUERY_WORDS).joinToString(" ").take(MAX_QUERY_CHARS).trim()
    }
}

/**
 * Searches English Wikipedia through the public MediaWiki API (no key or account). One GET request
 * per search carries only the query; no cookies or identifiers are sent. Responses are size-capped,
 * every field is bounded, and only https links on wikipedia.org are kept.
 */
class WikipediaSearchProvider(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : WebSearchProvider {

    override val name = "Wikipedia"

    override suspend fun search(query: String): List<WebResult> = withContext(dispatcher) {
        val q = query.trim().take(WebSearchLimits.MAX_QUERY_CHARS)
        if (q.isEmpty()) return@withContext emptyList()
        val url = baseUrl.newBuilder()
            .encodedPath("/w/api.php")
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("generator", "search")
            .addQueryParameter("gsrsearch", q)
            .addQueryParameter("gsrlimit", WebSearchLimits.MAX_RESULTS.toString())
            .addQueryParameter("prop", "extracts|info")
            .addQueryParameter("exintro", "1")
            .addQueryParameter("explaintext", "1")
            .addQueryParameter("exchars", WebSearchLimits.MAX_SNIPPET_CHARS.toString())
            .addQueryParameter("exlimit", WebSearchLimits.MAX_RESULTS.toString())
            .addQueryParameter("inprop", "url")
            .addQueryParameter("redirects", "1")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        val call = client.newCall(request)
        val cancel = coroutineContext[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        val body = try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw WebSearchException("Wikipedia returned HTTP ${response.code}")
                val source = response.body.source()
                if (source.request(WebSearchLimits.MAX_RESPONSE_BYTES + 1)) {
                    throw WebSearchException("Wikipedia's response was unexpectedly large")
                }
                source.buffer.readUtf8()
            }
        } catch (e: IOException) {
            throw WebSearchException(if (e.message?.contains("Unable to resolve host") == true) "no internet connection" else "network error (${e.javaClass.simpleName})")
        } finally {
            cancel?.dispose()
        }
        parse(body)
    }

    internal fun parse(body: String): List<WebResult> {
        val dto = try {
            json.decodeFromString<QueryResponse>(body)
        } catch (e: SerializationException) {
            throw WebSearchException("Wikipedia's response could not be read")
        } catch (e: IllegalArgumentException) {
            throw WebSearchException("Wikipedia's response could not be read")
        }
        return dto.query?.pages.orEmpty()
            .sortedBy { it.index ?: Int.MAX_VALUE }
            .mapNotNull { page ->
                val title = page.title?.let(::clean)?.take(WebSearchLimits.MAX_TITLE_CHARS)?.takeIf { it.isNotBlank() }
                val link = page.fullurl?.let(::safeLink)
                if (title == null || link == null) null
                else WebResult(title, link, clean(page.extract.orEmpty()).take(WebSearchLimits.MAX_SNIPPET_CHARS))
            }
            .take(WebSearchLimits.MAX_RESULTS)
    }

    /** Only https links on wikipedia.org are shown or opened. */
    private fun safeLink(raw: String): String? {
        val url = raw.toHttpUrlOrNull() ?: return null
        val host = url.host.lowercase(Locale.ROOT)
        val trusted = url.isHttps && (host == "wikipedia.org" || host.endsWith(".wikipedia.org"))
        return if (trusted) url.toString() else null
    }

    private fun clean(text: String): String =
        text.replace(Regex("\\p{Cntrl}"), " ").replace(Regex("\\s+"), " ").trim()

    @Serializable
    internal data class QueryResponse(val query: Query? = null)

    @Serializable
    internal data class Query(val pages: List<Page>? = null)

    @Serializable
    internal data class Page(
        val title: String? = null,
        val index: Int? = null,
        val extract: String? = null,
        val fullurl: String? = null
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://en.wikipedia.org/"
        private const val USER_AGENT = "LocalAI-Android/1.0 (on-device chat app; optional Wikipedia search)"
        private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    }
}
