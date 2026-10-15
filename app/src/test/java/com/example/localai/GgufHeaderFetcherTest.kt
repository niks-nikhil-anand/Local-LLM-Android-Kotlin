package com.example.localai

import com.example.localai.data.catalog.HfRepoFile
import com.example.localai.data.catalog.HfRepoId
import com.example.localai.data.gguf.GgufFailure
import com.example.localai.data.gguf.GgufModelInfo
import com.example.localai.data.gguf.GgufParseResult
import com.example.localai.data.hf.GgufHeaderFetcher
import com.example.localai.data.hf.HeaderFetchFailure
import com.example.localai.data.hf.HeaderFetchPolicy
import com.example.localai.data.hf.HeaderInspection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GgufHeaderFetcherTest {

    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** Real 14,152-byte header of stories260K.gguf followed by zeros up to the real file size. */
    private val header: ByteArray = checkNotNull(javaClass.getResource("/fixtures/stories260K.header.gguf")).readBytes()
    private val realSize = 1_185_376L
    private val file = header + ByteArray((realSize - header.size).toInt())

    private val repoFile = HfRepoFile(HfRepoId("ggml-org/models"), "main", "tinyllamas/stories260K.gguf", realSize, null)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun fetcher(policy: HeaderFetchPolicy = HeaderFetchPolicy(), client: OkHttpClient = OkHttpClient()) =
        GgufHeaderFetcher(client, baseUrl = server.url("/"), policy = policy)

    private fun inspect(
        policy: HeaderFetchPolicy = HeaderFetchPolicy(),
        target: HfRepoFile = repoFile,
        client: OkHttpClient = OkHttpClient()
    ) = runBlocking { fetcher(policy, client).inspect(target) }

    /** Serves [content] honouring single "bytes=a-b" ranges, like the Hub CDN. */
    private fun serveRanges(content: ByteArray, override: (RecordedRequest, Int) -> MockResponse? = { _, _ -> null }) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                override(request, requests.size)?.let { return it }
                val (start, end) = parseRange(request.headers["Range"]) ?: return MockResponse.Builder().code(400).build()
                if (start >= content.size) {
                    return MockResponse.Builder().code(416).addHeader("Content-Range", "bytes */${content.size}").build()
                }
                val last = minOf(end, content.size - 1L)
                return MockResponse.Builder()
                    .code(206)
                    .addHeader("Content-Range", "bytes $start-$last/${content.size}")
                    .body(Buffer().write(content, start.toInt(), (last - start + 1).toInt()))
                    .build()
            }
        }
    }

    private fun parseRange(value: String?): Pair<Long, Long>? {
        val m = Regex("""bytes=(\d+)-(\d+)""").matchEntire(value ?: return null) ?: return null
        return m.groupValues[1].toLong() to m.groupValues[2].toLong()
    }

    private fun HeaderInspection.inspected() = this as? HeaderInspection.Inspected ?: error("Expected Inspected but was $this")
    private fun HeaderInspection.failed() = (this as? HeaderInspection.FetchFailed)?.reason ?: error("Expected FetchFailed but was $this")

    // ---- Successful inspection ----

    @Test
    fun readsARealHeaderWithOneSmallRangeRequest() {
        serveRanges(file)
        val result = inspect().inspected()
        val parsed = (result.result as GgufParseResult.Success).header
        val info = GgufModelInfo.from(parsed)
        assertEquals("llama", info.architecture)
        assertEquals(2048L, info.contextLength)
        assertEquals(5L, info.blockCount)
        assertEquals(4L, info.headCountKv)
        assertEquals(512L, info.vocabularySize)
        assertEquals(14_152L, parsed.headerSizeBytes)
        // The tensor directory implies exactly the real file size.
        assertEquals(realSize, info.minimumFileBytes)

        assertEquals(realSize, result.fileSizeBytes)
        assertTrue(result.rangeSupported)
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals("bytes=0-1048575", request.headers["Range"])
        assertEquals("identity", request.headers["Accept-Encoding"])
        assertNull(request.headers["Authorization"])
        assertEquals("/ggml-org/models/resolve/main/tinyllamas/stories260K.gguf", request.url.encodedPath)
        // 1 MiB requested (the whole small file fits); never more than the budget.
        assertTrue(result.bytesRead <= 1_048_576)
    }

    @Test
    fun expandsInDoublingChunksOnlyAsFarAsTheHeaderNeeds() {
        serveRanges(file)
        val policy = HeaderFetchPolicy(initialChunkBytes = 4096, maxChunkBytes = 8192, maxHeaderBytes = 65_536)
        val result = inspect(policy).inspected()
        assertTrue(result.result is GgufParseResult.Success)
        assertEquals(listOf("bytes=0-4095", "bytes=4096-12287", "bytes=12288-20479"), requests.map { it.headers["Range"] })
        assertEquals(20_480L, result.bytesRead) // header is 14,152 bytes; the rest of the 1.1 MB file is untouched
    }

    @Test
    fun aHeaderLargerThanTheBudgetIsIncompleteNotPassed() {
        serveRanges(file)
        val policy = HeaderFetchPolicy(initialChunkBytes = 2048, maxChunkBytes = 4096, maxHeaderBytes = 8192)
        val result = inspect(policy).inspected()
        assertEquals(GgufFailure.TOO_LARGE, (result.result as GgufParseResult.Failure).kind)
        assertTrue(result.bytesRead <= 8192)
        assertTrue(requests.all { parseRange(it.headers["Range"])!!.second < 8192 })
    }

    @Test
    fun aFileThatEndsInsideItsHeaderIsTruncated() {
        val short = header.copyOf(5000)
        serveRanges(short)
        val result = inspect(HeaderFetchPolicy(4096, 8192, 65_536), repoFile.copy(sizeBytes = 5000)).inspected()
        assertEquals(GgufFailure.TRUNCATED, (result.result as GgufParseResult.Failure).kind)
    }

    @Test
    fun nonGgufContentIsReportedAsSuch() {
        serveRanges("<html>not a model</html>".toByteArray() + ByteArray(4096))
        val result = inspect(target = repoFile.copy(sizeBytes = null)).inspected()
        assertEquals(GgufFailure.NOT_GGUF, (result.result as GgufParseResult.Failure).kind)
    }

    @Test
    fun followsRedirectsAndKeepsTheRange() {
        serveRanges(file) { request, _ ->
            if (request.url.encodedPath.contains("/resolve/")) {
                MockResponse.Builder().code(302).addHeader("Location", server.url("/cdn/blob").toString()).build()
            } else null
        }
        val result = inspect().inspected()
        assertTrue(result.result is GgufParseResult.Success)
        assertEquals("/cdn/blob", requests.last().url.encodedPath)
        assertEquals("bytes=0-1048575", requests.last().headers["Range"])
    }

    @Test
    fun revisionIsEncodedAsOneSegment() {
        serveRanges(file)
        inspect(target = repoFile.copy(revision = "refs/pr/3"))
        assertEquals("/ggml-org/models/resolve/refs%2Fpr%2F3/tinyllamas/stories260K.gguf", requests.single().url.encodedPath)
    }

    // ---- Servers that ignore Range ----

    @Test
    fun aServerIgnoringRangeIsReadOnlyUpToTheBudget() {
        val big = file + ByteArray(8 * 1024 * 1024) // ~9 MB body
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return MockResponse.Builder().code(200).body(Buffer().write(big)).build()
            }
        }
        val policy = HeaderFetchPolicy(initialChunkBytes = 16_384, maxChunkBytes = 65_536, maxHeaderBytes = 1_048_576)
        val result = inspect(policy, repoFile.copy(sizeBytes = big.size.toLong())).inspected()
        assertTrue(result.result is GgufParseResult.Success)
        assertFalse(result.rangeSupported)
        assertEquals(1, requests.size)
        assertTrue("read ${result.bytesRead}", result.bytesRead <= 16_384) // header fits in the first chunk
    }

    @Test
    fun aServerIgnoringRangeOnALaterChunkFails() {
        serveRanges(file) { _, n -> if (n == 2) MockResponse.Builder().code(200).body(Buffer().write(file)).build() else null }
        val result = inspect(HeaderFetchPolicy(4096, 8192, 65_536))
        assertEquals(HeaderFetchFailure.RANGE_NOT_SUPPORTED, result.failed())
    }

    // ---- Invalid partial responses ----

    private fun partial(contentRange: String?, body: ByteArray = ByteArray(16)): MockResponse {
        val builder = MockResponse.Builder().code(206).body(Buffer().write(body))
        contentRange?.let { builder.addHeader("Content-Range", it) }
        return builder.build()
    }

    @Test
    fun invalidContentRangeValuesAreRejected() {
        val cases = listOf(
            null,                                // missing
            "bytes 0-15",                        // no total
            "items 0-15/100",                    // wrong unit
            "bytes 100-115/$realSize",           // wrong start
            "bytes 15-0/$realSize",              // reversed
            "bytes 0-2000000/$realSize",         // longer than requested
            "bytes 0-15/10"                      // past the end of the file
        )
        for (contentRange in cases) {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = partial(contentRange)
            }
            val reason = inspect(target = repoFile.copy(sizeBytes = null)).failed()
            assertEquals("Content-Range $contentRange", HeaderFetchFailure.INVALID_RESPONSE, reason)
        }
    }

    @Test
    fun aTotalSizeDifferentFromTheRepositoryListingIsAMismatch() {
        serveRanges(file)
        val reason = inspect(target = repoFile.copy(sizeBytes = realSize + 1)).failed()
        assertEquals(HeaderFetchFailure.SIZE_MISMATCH, reason)
    }

    @Test
    fun aTotalSizeThatChangesBetweenRequestsIsInvalid() {
        serveRanges(file) { request, n ->
            if (n == 2) partial("bytes 4096-12287/999999", ByteArray(8192)) else null
        }
        val reason = inspect(HeaderFetchPolicy(4096, 8192, 65_536), repoFile.copy(sizeBytes = null)).failed()
        assertEquals(HeaderFetchFailure.INVALID_RESPONSE, reason)
    }

    @Test
    fun aBodyShorterThanItsContentRangeIsANetworkFailure() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 0-4095/$realSize")
                    .body(Buffer().write(header, 0, 100))
                    .build()
        }
        assertEquals(HeaderFetchFailure.NETWORK, inspect().failed())
    }

    // ---- HTTP and network errors ----

    @Test
    fun httpErrorsMapToSpecificReasons() {
        val cases = mapOf(
            401 to HeaderFetchFailure.ACCESS_REQUIRED,
            403 to HeaderFetchFailure.ACCESS_REQUIRED,
            404 to HeaderFetchFailure.NOT_FOUND,
            429 to HeaderFetchFailure.RATE_LIMITED,
            503 to HeaderFetchFailure.SERVER,
            418 to HeaderFetchFailure.INVALID_RESPONSE
        )
        for ((code, expected) in cases) {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse.Builder().code(code).addHeader("Retry-After", "30").build()
            }
            assertEquals("HTTP $code", expected, inspect().failed())
        }
        val rateLimited = inspect() as HeaderInspection.FetchFailed
        assertTrue(rateLimited.detail.contains("HTTP 418"))
    }

    @Test
    fun rateLimitKeepsTheRetryDelay() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse.Builder().code(429).addHeader("Retry-After", "30").build()
        }
        val failed = inspect() as HeaderInspection.FetchFailed
        assertEquals("Retry after 30 s", failed.detail)
    }

    @Test
    fun aDroppedConnectionIsANetworkFailure() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 0-4095/$realSize")
                    .body(Buffer().write(header, 0, 4096))
                    .onResponseBody(SocketEffect.CloseSocket()) // connection lost while reading
                    .build()
        }
        assertEquals(HeaderFetchFailure.NETWORK, inspect().failed())
    }

    @Test
    fun aStalledResponseTimesOut() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 0-1023/$realSize")
                    .body(Buffer().write(header, 0, 1024))
                    .bodyDelay(2, TimeUnit.SECONDS)
                    .build()
        }
        val client = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        assertEquals(HeaderFetchFailure.TIMEOUT, inspect(client = client).failed())
    }

    @Test
    fun anUnreachableServerIsANetworkFailure() {
        val f = fetcher()
        server.close()
        assertEquals(HeaderFetchFailure.NETWORK, runBlocking { f.inspect(repoFile) }.failed())
    }
}
