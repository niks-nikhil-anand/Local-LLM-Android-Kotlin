package com.example.localai.data.catalog

/** Outcome of a catalog call; failures are values so the UI can show retry/limit states. */
sealed interface CatalogResult<out T> {
    data class Success<T>(val value: T) : CatalogResult<T>
    data class Failure(val error: CatalogError) : CatalogResult<Nothing>
}

sealed interface CatalogError {
    data object Offline : CatalogError
    data class RateLimited(val retryAfterSeconds: Long?) : CatalogError
    data object AccessRequired : CatalogError
    data object NotFound : CatalogError
    data class Server(val httpCode: Int) : CatalogError
    data class Malformed(val detail: String?) : CatalogError
}

/** One page of results. [nextCursor] is the opaque URL from the Hub's Link rel="next" header. */
data class Page<T>(val items: List<T>, val nextCursor: String?)

enum class CatalogSort(val apiValue: String) {
    DOWNLOADS("downloads"),
    LIKES("likes"),
    RECENT("lastModified")
}

data class CatalogQuery(
    val search: String = "",
    val author: String? = null,
    val pipelineTag: String? = "text-generation",
    val ggufOnly: Boolean = true,
    val sort: CatalogSort = CatalogSort.DOWNLOADS,
    val pageSize: Int = 30
)

/** Read-only view of the Hugging Face Hub. The UI depends on this, never on the network. */
interface ModelCatalogRepository {
    /** First page for [query], or the next page when [cursor] is given. */
    suspend fun search(query: CatalogQuery, cursor: String? = null): CatalogResult<Page<HfModelSummary>>

    suspend fun modelDetails(repoId: HfRepoId): CatalogResult<HfModelSummary>

    /** Files at [revision]. Called on demand when a repository is opened, never in bulk. */
    suspend fun listFiles(repoId: HfRepoId, revision: String = "main"): CatalogResult<List<HfRepoFile>>
}
