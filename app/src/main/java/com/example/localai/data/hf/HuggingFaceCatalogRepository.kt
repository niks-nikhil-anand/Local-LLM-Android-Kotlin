package com.example.localai.data.hf

import com.example.localai.data.catalog.CatalogError
import com.example.localai.data.catalog.CatalogQuery
import com.example.localai.data.catalog.CatalogResult
import com.example.localai.data.catalog.HfModelSummary
import com.example.localai.data.catalog.HfRepoFile
import com.example.localai.data.catalog.HfRepoId
import com.example.localai.data.catalog.ModelCatalogRepository
import com.example.localai.data.catalog.Page
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * [ModelCatalogRepository] backed by the public Hugging Face Hub API. It never authenticates:
 * gated and private repositories are reported as [CatalogError.AccessRequired] or marked in
 * [HfModelSummary.access], and no access control is bypassed.
 *
 * The Hub answers 401 (not 404) for a repository that does not exist when the caller is
 * anonymous, so [CatalogError.AccessRequired] can also mean "no such repository".
 */
class HuggingFaceCatalogRepository(
    client: OkHttpClient = defaultClient(),
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    retryPolicy: RetryPolicy = RetryPolicy(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    pause: suspend (Long) -> Unit = { delay(it) }
) : ModelCatalogRepository {

    private val http = HubHttp(client, baseUrl, retryPolicy, dispatcher, pause)

    override suspend fun search(
        query: CatalogQuery,
        cursor: String?
    ): CatalogResult<Page<HfModelSummary>> {
        val url = if (cursor == null) {
            searchUrl(query)
        } else {
            cursor.toHttpUrlOrNull()?.takeIf { it.isSameOrigin(baseUrl) && it.encodedPath == LIST_PATH }
                ?: return CatalogResult.Failure(CatalogError.Malformed("Invalid pagination cursor"))
        }
        return when (val response = http.get(url)) {
            is CatalogResult.Failure -> response
            is CatalogResult.Success -> parseModelList(response.value)
        }
    }

    override suspend fun modelDetails(repoId: HfRepoId): CatalogResult<HfModelSummary> =
        when (val response = http.get(repoUrl(repoId).build())) {
            is CatalogResult.Failure -> response
            is CatalogResult.Success -> parse {
                json.decodeFromString<HfModelDto>(response.value.body).toSummary()
                    ?: throw SerializationException("Repository metadata has no valid id")
            }
        }

    override suspend fun listFiles(
        repoId: HfRepoId,
        revision: String
    ): CatalogResult<List<HfRepoFile>> {
        if (revision.isBlank()) return CatalogResult.Failure(CatalogError.NotFound)
        val files = mutableListOf<HfRepoFile>()
        var url: HttpUrl? = repoUrl(repoId)
            .addPathSegment("tree")
            .addPathSegment(revision) // one segment: a slash in a branch name is sent as %2F
            .addQueryParameter("recursive", "true")
            .build()
        var pages = 0
        while (url != null) {
            if (++pages > MAX_TREE_PAGES) {
                return CatalogResult.Failure(CatalogError.Malformed("Too many file listing pages"))
            }
            when (val response = http.get(url)) {
                // A partial file list could hide a shard or checksum, so any failure fails the call.
                is CatalogResult.Failure -> return response
                is CatalogResult.Success -> {
                    val parsed = parse { parseEntries(response.value.body, repoId, revision) }
                    if (parsed is CatalogResult.Failure) return parsed
                    files += (parsed as CatalogResult.Success).value
                    url = response.value.nextUrl
                }
            }
        }
        return CatalogResult.Success(files)
    }

    private fun searchUrl(query: CatalogQuery): HttpUrl {
        val builder = baseUrl.newBuilder().addPathSegments("api/models")
        query.search.trim().takeIf { it.isNotEmpty() }?.let { builder.addQueryParameter("search", it) }
        query.author?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.addQueryParameter("author", it) }
        if (query.ggufOnly) builder.addQueryParameter("filter", "gguf")
        query.pipelineTag?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { builder.addQueryParameter("pipeline_tag", it) }
        builder.addQueryParameter("sort", query.sort.apiValue)
        builder.addQueryParameter("direction", "-1")
        builder.addQueryParameter("limit", query.pageSize.coerceIn(1, MAX_PAGE_SIZE).toString())
        // expand selects exactly the fields we map; it cannot be combined with full=true.
        LIST_FIELDS.forEach { builder.addQueryParameter("expand[]", it) }
        return builder.build()
    }

    private fun repoUrl(repoId: HfRepoId): HttpUrl.Builder {
        val builder = baseUrl.newBuilder().addPathSegments("api/models")
        repoId.owner?.let { builder.addPathSegment(it) }
        return builder.addPathSegment(repoId.name)
    }

    private suspend fun parseModelList(response: HubResponse): CatalogResult<Page<HfModelSummary>> =
        parse {
            val array = json.parseToJsonElement(response.body) as? JsonArray
                ?: throw SerializationException("Expected a JSON array of models")
            // One malformed item must not discard the page.
            val items = array.mapNotNull { element ->
                runCatching { json.decodeFromJsonElement<HfModelDto>(element).toSummary() }.getOrNull()
            }
            Page(items, response.nextUrl?.toString())
        }

    private fun parseEntries(body: String, repoId: HfRepoId, revision: String): List<HfRepoFile> {
        val array = json.parseToJsonElement(body) as? JsonArray
            ?: throw SerializationException("Expected a JSON array of tree entries")
        return array.mapNotNull { element ->
            if (element !is JsonObject) return@mapNotNull null
            runCatching { json.decodeFromJsonElement<HfTreeEntryDto>(element).toFile(repoId, revision) }
                .getOrNull()
        }
    }

    /** Runs [block] off the main thread and converts parsing failures into [CatalogError.Malformed]. */
    private suspend fun <T> parse(block: () -> T): CatalogResult<T> = withContext(dispatcher) {
        try {
            CatalogResult.Success(block())
        } catch (e: SerializationException) {
            CatalogResult.Failure(CatalogError.Malformed(e.message))
        } catch (e: IllegalArgumentException) {
            CatalogResult.Failure(CatalogError.Malformed(e.message))
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://huggingface.co/"
        const val MAX_PAGE_SIZE = 100
        private const val MAX_TREE_PAGES = 50
        private const val LIST_PATH = "/api/models"
        private val LIST_FIELDS = listOf(
            "author", "downloads", "likes", "tags", "pipeline_tag", "lastModified", "gated", "private"
        )

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
