package com.example.localai

import com.example.localai.data.catalog.CatalogAccess
import com.example.localai.data.catalog.CatalogError
import com.example.localai.data.catalog.CatalogQuery
import com.example.localai.data.catalog.CatalogResult
import com.example.localai.data.catalog.CatalogSort
import com.example.localai.data.catalog.HfModelSummary
import com.example.localai.data.catalog.HfRepoFile
import com.example.localai.data.catalog.HfRepoId
import com.example.localai.data.catalog.Page
import com.example.localai.data.hf.HuggingFaceCatalogRepository
import com.example.localai.data.hf.RetryPolicy
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HuggingFaceCatalogRepositoryTest {

    private lateinit var server: MockWebServer
    private val pauses = mutableListOf<Long>()
    private val repoId = HfRepoId("Qwen/Qwen2.5-0.5B-Instruct-GGUF")

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun repository(policy: RetryPolicy = RetryPolicy()) = HuggingFaceCatalogRepository(
        baseUrl = server.url("/"),
        retryPolicy = policy,
        pause = { pauses += it }
    )

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

    private fun enqueueJson(body: String, code: Int = 200, vararg headers: Pair<String, String>) {
        val builder = MockResponse.Builder().code(code).body(body)
            .addHeader("Content-Type", "application/json")
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        server.enqueue(builder.build())
    }

    private fun enqueueStatus(code: Int, vararg headers: Pair<String, String>) =
        enqueueJson("{\"error\":\"x\"}", code, *headers)

    private fun <T> CatalogResult<T>.value(): T =
        (this as? CatalogResult.Success)?.value ?: error("Expected success but was $this")

    private fun CatalogResult<*>.error(): CatalogError =
        (this as? CatalogResult.Failure)?.error ?: error("Expected failure but was $this")

    private fun search(query: CatalogQuery = CatalogQuery(), cursor: String? = null) =
        runBlocking { repository().search(query, cursor) }

    // ---- Search: parsing ----

    @Test
    fun search_parsesRealListResponse() {
        enqueueJson(fixture("models_list.json"))
        val page = search().value()
        assertEquals(2, page.items.size)
        val first = page.items[0]
        assertEquals("unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF", first.repoId.value)
        assertEquals("unsloth", first.author) // not in the response: derived from the repo id
        assertEquals(3_445_398L, first.downloads)
        assertEquals(1149, first.likes)
        assertEquals("apache-2.0", first.license) // from the license: tag
        assertEquals("text-generation", first.pipelineTag)
        assertTrue(first.advertisesGguf)
        assertEquals(CatalogAccess.Public, first.access)
        assertNull(first.lastModified)
        assertNull(page.nextCursor)
    }

    @Test
    fun search_parsesExpandedFields() {
        enqueueJson(fixture("models_list_expanded.json"))
        val item = search().value().items.single()
        assertEquals("jialinyyzz/humanizer", item.repoId.value)
        assertEquals("jialinyyzz", item.author)
        assertEquals("2026-10-08T20:13:31.000Z", item.lastModified)
        assertEquals("apache-2.0", item.license)
        assertEquals(CatalogAccess.Public, item.access)
    }

    @Test
    fun search_toleratesMissingOptionalFieldsAndSkipsUnusableItems() {
        enqueueJson(
            """[
              {"id":"owner/bare"},
              {"downloads":5},
              {"id":"has space/x"},
              {"modelId":"legacy/only-model-id","likes":null,"tags":null,"downloads":"oops"},
              {"id":"owner/after","tags":["gguf"]}
            ]"""
        )
        val items = search().value().items
        // The item with a wrongly typed field and the ones without a valid id are dropped; the rest survive.
        assertEquals(listOf("owner/bare", "owner/after"), items.map { it.repoId.value })
        val bare = items[0]
        assertNull(bare.downloads)
        assertNull(bare.likes)
        assertNull(bare.license)
        assertNull(bare.pipelineTag)
        assertEquals(emptyList<String>(), bare.tags)
        assertFalse(bare.advertisesGguf)
    }

    @Test
    fun search_emptyResultIsSuccessWithNoItems() {
        enqueueJson("[]")
        val page = search().value()
        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor)
    }

    // ---- Search: request construction ----

    @Test
    fun search_sendsOnlySupportedParametersAndNoCredentials() {
        enqueueJson("[]")
        search(
            CatalogQuery(
                search = "  qwen  ",
                author = "Qwen",
                pipelineTag = "text-generation",
                ggufOnly = true,
                sort = CatalogSort.LIKES,
                pageSize = 500
            )
        )
        val request = server.takeRequest()
        val url = request.url
        assertEquals("/api/models", url.encodedPath)
        assertEquals("qwen", url.queryParameter("search"))
        assertEquals("Qwen", url.queryParameter("author"))
        assertEquals("gguf", url.queryParameter("filter"))
        assertEquals("text-generation", url.queryParameter("pipeline_tag"))
        assertEquals("likes", url.queryParameter("sort"))
        assertEquals("-1", url.queryParameter("direction"))
        assertEquals("100", url.queryParameter("limit")) // clamped to the supported maximum
        assertTrue(url.queryParameterValues("expand[]").containsAll(listOf("gated", "downloads", "tags")))
        assertNull(url.queryParameter("full"))
        assertNull(request.headers["Authorization"])
        assertEquals("application/json", request.headers["Accept"])
    }

    @Test
    fun search_omitsFiltersThatAreNotSet() {
        enqueueJson("[]")
        search(CatalogQuery(search = "", author = " ", pipelineTag = null, ggufOnly = false))
        val url = server.takeRequest().url
        assertNull(url.queryParameter("search"))
        assertNull(url.queryParameter("author"))
        assertNull(url.queryParameter("filter"))
        assertNull(url.queryParameter("pipeline_tag"))
    }

    // ---- Pagination ----

    @Test
    fun search_followsLinkHeaderCursor() {
        val nextUrl = server.url("/api/models?filter=gguf&cursor=abc123")
        enqueueJson(fixture("models_list.json"), 200, "Link" to "<$nextUrl>; rel=\"next\"")
        enqueueJson("[{\"id\":\"owner/second-page\"}]")

        val first = search().value()
        assertEquals(nextUrl.toString(), first.nextCursor)

        val second = search(cursor = first.nextCursor).value()
        assertEquals(listOf("owner/second-page"), second.items.map { it.repoId.value })
        assertNull(second.nextCursor)

        server.takeRequest()
        assertEquals("abc123", server.takeRequest().url.queryParameter("cursor"))
    }

    @Test
    fun search_rejectsCursorsOutsideTheHub() {
        val foreign = search(cursor = "https://evil.example/api/models?cursor=x").error()
        assertTrue(foreign is CatalogError.Malformed)
        val wrongPath = search(cursor = server.url("/api/other").toString()).error()
        assertTrue(wrongPath is CatalogError.Malformed)
        assertTrue(search(cursor = "not a url").error() is CatalogError.Malformed)
        assertEquals(0, server.requestCount) // nothing was requested
    }

    @Test
    fun search_rejectsLinkHeaderPointingToAnotherHost() {
        enqueueJson("[]", 200, "Link" to "<https://evil.example/api/models?cursor=x>; rel=\"next\"")
        assertTrue(search().error() is CatalogError.Malformed)
    }

    // ---- Repository metadata ----

    @Test
    fun modelDetails_parsesRealResponse() {
        enqueueJson(fixture("model_details.json"))
        val summary = runBlocking { repository().modelDetails(repoId) }.value()
        assertEquals(repoId, summary.repoId)
        assertEquals("Qwen", summary.author)
        assertEquals(269_141L, summary.downloads)
        assertEquals(150, summary.likes)
        assertEquals("apache-2.0", summary.license)
        assertEquals("2024-09-20T06:20:24.000Z", summary.lastModified)
        assertEquals(CatalogAccess.Public, summary.access)
        assertEquals("/api/models/Qwen/Qwen2.5-0.5B-Instruct-GGUF", server.takeRequest().url.encodedPath)
    }

    @Test
    fun modelDetails_marksGatedRepositories() {
        enqueueJson(fixture("model_details_gated.json"))
        val summary = runBlocking { repository().modelDetails(HfRepoId("meta-llama/Llama-3.2-1B")) }.value()
        assertEquals(CatalogAccess.Gated("manual"), summary.access)
        assertEquals("llama3.2", summary.license)
    }

    @Test
    fun modelDetails_mapsAccessVariants() {
        fun accessFor(json: String): CatalogAccess {
            enqueueJson(json)
            return runBlocking { repository().modelDetails(HfRepoId("o/r")) }.value().access
        }
        assertEquals(CatalogAccess.Public, accessFor("""{"id":"o/r","gated":false}"""))
        assertEquals(CatalogAccess.Gated("auto"), accessFor("""{"id":"o/r","gated":"auto"}"""))
        assertEquals(CatalogAccess.Gated(null), accessFor("""{"id":"o/r","gated":true}"""))
        // Unknown shapes are never reported as public.
        assertEquals(CatalogAccess.Gated(null), accessFor("""{"id":"o/r","gated":{"x":1}}"""))
        assertEquals(CatalogAccess.Restricted, accessFor("""{"id":"o/r","private":true}"""))
        assertEquals(CatalogAccess.Restricted, accessFor("""{"id":"o/r","disabled":true}"""))
    }

    @Test
    fun modelDetails_acceptsLicenseListAndNoLicense() {
        enqueueJson("""{"id":"o/r","cardData":{"license":["mit","apache-2.0"]}}""")
        assertEquals("mit", runBlocking { repository().modelDetails(HfRepoId("o/r")) }.value().license)
        enqueueJson("""{"id":"o/r","cardData":{"license":null}}""")
        assertNull(runBlocking { repository().modelDetails(HfRepoId("o/r")) }.value().license)
    }

    @Test
    fun modelDetails_ownerlessRepoUsesSinglePathSegment() {
        enqueueJson("""{"id":"gpt2"}""")
        runBlocking { repository().modelDetails(HfRepoId("gpt2")) }.value()
        assertEquals("/api/models/gpt2", server.takeRequest().url.encodedPath)
    }

    @Test
    fun modelDetails_responseWithoutIdIsMalformed() {
        enqueueJson("""{"downloads":1}""")
        assertTrue(runBlocking { repository().modelDetails(repoId) }.error() is CatalogError.Malformed)
    }

    // ---- File tree ----

    @Test
    fun listFiles_parsesRealTree() {
        enqueueJson(fixture("repo_tree.json"))
        val files = runBlocking { repository().listFiles(repoId) }.value()
        assertEquals(12, files.size)
        assertEquals(9, files.count { it.extension == "gguf" })

        val fp16 = files.first { it.path == "qwen2.5-0.5b-instruct-fp16.gguf" }
        assertEquals(1_266_425_696L, fp16.sizeBytes)
        assertEquals("8e0ae26000627ed62de0e78e41860af70094558b9d2913385c842a6aa06cf3fc", fp16.sha256)
        assertEquals("main", fp16.revision)
        assertEquals(
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-fp16.gguf",
            fp16.resolveUrl
        )

        val readme = files.first { it.path == "README.md" }
        assertEquals(4856L, readme.sizeBytes)
        assertNull(readme.sha256) // not an LFS file

        val request = server.takeRequest()
        assertEquals("/api/models/Qwen/Qwen2.5-0.5B-Instruct-GGUF/tree/main", request.url.encodedPath)
        assertEquals("true", request.url.queryParameter("recursive"))
    }

    @Test
    fun listFiles_followsPaginationAndSkipsDirectoriesAndUnsafePaths() {
        val next = server.url("/api/models/Qwen/Qwen2.5-0.5B-Instruct-GGUF/tree/main?recursive=true&cursor=p2")
        enqueueJson(
            """[{"type":"directory","path":"sub"},{"type":"file","path":"sub/a.gguf","size":1}]""",
            200,
            "Link" to "<$next>; rel=\"next\""
        )
        enqueueJson(
            """[{"type":"file","path":"../escape.gguf","size":2},
                {"type":"file","path":"/abs.gguf","size":3},
                {"type":"file","size":4},
                {"type":"file","path":"b.gguf"}]"""
        )
        val files = runBlocking { repository().listFiles(repoId) }.value()
        assertEquals(listOf("sub/a.gguf", "b.gguf"), files.map { it.path })
        assertNull(files[1].sizeBytes) // unavailable metadata stays unknown, never invented
        assertEquals(2, server.requestCount)
    }

    @Test
    fun listFiles_encodesRevisionAsSingleSegment() {
        enqueueJson("[]")
        runBlocking { repository().listFiles(repoId, "refs/pr/1") }.value()
        assertTrue(server.takeRequest().url.encodedPath.endsWith("/tree/refs%2Fpr%2F1"))
    }

    @Test
    fun listFiles_failureOnLaterPageFailsTheWholeCall() {
        val next = server.url("/api/models/Qwen/Qwen2.5-0.5B-Instruct-GGUF/tree/main?cursor=p2")
        enqueueJson("""[{"type":"file","path":"a.gguf","size":1}]""", 200, "Link" to "<$next>; rel=\"next\"")
        enqueueStatus(404)
        assertEquals(CatalogError.NotFound, runBlocking { repository().listFiles(repoId) }.error())
    }

    @Test
    fun listFiles_gatedRepoReportsAccessRequired() {
        enqueueStatus(403)
        assertEquals(CatalogError.AccessRequired, runBlocking { repository().listFiles(repoId) }.error())
    }

    // ---- Permanent errors: never retried ----

    @Test
    fun http401And403_mapToAccessRequiredWithoutRetry() {
        for (code in listOf(401, 403)) {
            val before = server.requestCount
            enqueueStatus(code)
            assertEquals(CatalogError.AccessRequired, search().error())
            assertEquals(1, server.requestCount - before)
        }
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun http404_mapsToNotFoundWithoutRetry() {
        enqueueStatus(404)
        assertEquals(CatalogError.NotFound, runBlocking { repository().modelDetails(repoId) }.error())
        assertEquals(1, server.requestCount)
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun otherClientErrors_areNotRetried() {
        enqueueStatus(400)
        assertEquals(CatalogError.Server(400), search().error())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun malformedJson_isReportedWithoutRetry() {
        enqueueJson("{not json")
        assertTrue(search().error() is CatalogError.Malformed)
        enqueueJson("""{"not":"an array"}""")
        assertTrue(search().error() is CatalogError.Malformed)
        enqueueJson("[]")
        assertTrue(runBlocking { repository().modelDetails(repoId) }.error() is CatalogError.Malformed)
        enqueueJson("""{"oops":true}""")
        assertTrue(runBlocking { repository().listFiles(repoId) }.error() is CatalogError.Malformed)
        assertEquals(4, server.requestCount)
        assertTrue(pauses.isEmpty())
    }

    // ---- Retryable errors: bounded backoff ----

    @Test
    fun rateLimit_respectsRetryAfterThenSucceeds() {
        enqueueStatus(429, "Retry-After" to "2")
        enqueueJson("[]")
        assertTrue(search().value().items.isEmpty())
        assertEquals(listOf(2000L), pauses)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun rateLimit_isBoundedAndReportsRetryAfter() {
        repeat(5) { enqueueStatus(429, "Retry-After" to "1") }
        assertEquals(CatalogError.RateLimited(1), search().error())
        assertEquals(3, server.requestCount) // maxAttempts, not indefinite
        assertEquals(listOf(1000L, 1000L), pauses)
    }

    @Test
    fun rateLimit_withLongRetryAfterIsSurfacedInsteadOfWaited() {
        enqueueStatus(429, "Retry-After" to "3600")
        assertEquals(CatalogError.RateLimited(3600), search().error())
        assertEquals(1, server.requestCount)
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun rateLimit_withoutRetryAfterUsesBackoff() {
        enqueueStatus(429)
        enqueueJson("[]")
        search().value()
        assertEquals(listOf(500L), pauses)
    }

    @Test
    fun serverError_retriesWithExponentialBackoffThenSucceeds() {
        enqueueStatus(503)
        enqueueStatus(500)
        enqueueJson("[]")
        search().value()
        assertEquals(listOf(500L, 1000L), pauses)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun serverError_givesUpAfterMaxAttempts() {
        repeat(5) { enqueueStatus(502) }
        assertEquals(CatalogError.Server(502), search().error())
        assertEquals(3, server.requestCount)
        assertEquals(listOf(500L, 1000L), pauses)
    }

    @Test
    fun backoffIsCapped() {
        val policy = RetryPolicy(maxAttempts = 6, baseDelayMillis = 1000, maxDelayMillis = 3000)
        repeat(6) { enqueueStatus(500) }
        assertEquals(CatalogError.Server(500), runBlocking { repository(policy).search(CatalogQuery()) }.error())
        assertEquals(listOf(1000L, 2000L, 3000L, 3000L, 3000L), pauses)
    }

    // ---- Network failures ----

    @Test
    fun networkFailure_mapsToOfflineAfterBoundedRetries() {
        repeat(5) { server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()) }
        assertEquals(CatalogError.Offline, search().error())
        assertEquals(3, server.requestCount)
        assertEquals(listOf(500L, 1000L), pauses)
    }

    @Test
    fun networkFailure_recoversWhenConnectionComesBack() {
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
        enqueueJson(fixture("models_list.json"))
        assertEquals(2, search().value().items.size)
    }

    @Test
    fun unreachableServer_mapsToOffline() {
        val repo = repository(RetryPolicy(maxAttempts = 1))
        server.close()
        assertEquals(CatalogError.Offline, runBlocking { repo.search(CatalogQuery()) }.error())
    }

    // ---- Types ----

    @Test
    fun resultsAreOrdinaryValues() {
        val page: Page<HfModelSummary> = Page(emptyList(), null)
        assertEquals(RetryPolicy().maxAttempts, 3)
        val file = HfRepoFile(repoId, "main", "a/b.gguf", null, null)
        assertEquals("b.gguf", file.fileName)
    }
}
