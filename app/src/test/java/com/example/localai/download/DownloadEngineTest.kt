package com.example.localai.download

import com.example.localai.data.download.DownloadEngine
import com.example.localai.data.download.DownloadFailureKind
import com.example.localai.data.download.DownloadListener
import com.example.localai.data.download.DownloadOutcome
import com.example.localai.data.download.DownloadSpec
import com.example.localai.data.download.ResumeState
import com.example.localai.data.download.Verification
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** A file served by MockWebServer that honours "bytes=N-" like the Hub CDN. */
internal class RangeServer(private val server: MockWebServer, var content: ByteArray, var etag: String? = "\"v1\"") {
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    var override: (RecordedRequest, Int) -> MockResponse? = { _, _ -> null }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                override(request, requests.size)?.let { return it }
                val range = request.headers["Range"]
                val builder = MockResponse.Builder()
                etag?.let { builder.addHeader("ETag", it) }
                if (range == null) return builder.code(200).body(Buffer().write(content)).build()
                val start = Regex("""bytes=(\d+)-""").matchEntire(range)!!.groupValues[1].toLong()
                if (start >= content.size) {
                    return builder.code(416).addHeader("Content-Range", "bytes */${content.size}").build()
                }
                return builder.code(206)
                    .addHeader("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
                    .body(Buffer().write(content, start.toInt(), (content.size - start).toInt()))
                    .build()
            }
        }
    }
}

class DownloadEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var files: RangeServer
    private val content = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private lateinit var part: File
    private lateinit var final: File

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        files = RangeServer(server, content)
        val dir = temp.newFolder("models")
        part = File(dir, "abc.gguf.part")
        final = File(dir, "abc.gguf")
    }

    @After
    fun tearDown() = server.close()

    private fun engine(
        free: Long = Long.MAX_VALUE,
        client: OkHttpClient = OkHttpClient(),
        syncEvery: Long = 64 * 1024
    ) = DownloadEngine(client, freeSpace = { free }, storageReserveBytes = 1000, syncEveryBytes = syncEvery, progressEveryBytes = 50_000)

    private fun spec(size: Long? = content.size.toLong(), sha: String? = sha256Hex(content)) =
        DownloadSpec(server.url("/o/r/resolve/main/m.gguf"), size, sha)

    private class Recorder : DownloadListener {
        val progress = mutableListOf<Long>()
        val durable = mutableListOf<Long>()
        var verifying = false
        var etag: String? = null
        var total: Long? = null
        override suspend fun onResponse(totalBytes: Long?, etag: String?) { total = totalBytes; this.etag = etag }
        override suspend fun onProgress(bytes: Long, totalBytes: Long?) { progress += bytes }
        override suspend fun onDurable(bytes: Long) { durable += bytes }
        override suspend fun onVerifying() { verifying = true }
    }

    private fun run(
        spec: DownloadSpec = spec(),
        resume: ResumeState = ResumeState(),
        engine: DownloadEngine = engine(),
        listener: Recorder = Recorder()
    ): DownloadOutcome = runBlocking { engine.download(spec, part, final, resume, listener) }

    private fun DownloadOutcome.kind() = (this as? DownloadOutcome.Failed)?.kind ?: error("Expected failure but was $this")

    // ---- Success ----

    @Test
    fun streamsToDiskVerifiesSha256AndPromotes() {
        val recorder = Recorder()
        val outcome = run(listener = recorder) as DownloadOutcome.Completed
        assertEquals(Verification.SHA256, outcome.verification)
        assertEquals(content.size.toLong(), outcome.sizeBytes)
        assertArrayEquals(content, final.readBytes())
        assertFalse(part.exists())
        assertEquals("\"v1\"", outcome.etag)
        // Progress is monotonic and ends at the exact byte count.
        assertEquals(recorder.progress.sorted(), recorder.progress)
        assertEquals(content.size.toLong(), recorder.progress.last())
        assertEquals(content.size.toLong(), recorder.durable.last())
        assertTrue(recorder.verifying)
        val request = files.requests.single()
        assertEquals(null, request.headers["Range"])
        assertEquals("identity", request.headers["Accept-Encoding"])
        assertEquals(null, request.headers["Authorization"])
    }

    @Test
    fun withoutAChecksumOnlyTheSizeIsVerified() {
        val outcome = run(spec(sha = null)) as DownloadOutcome.Completed
        assertEquals(Verification.SIZE_ONLY, outcome.verification)
    }

    @Test
    fun followsRedirectsToTheCdnAndKeepsTheRange() {
        part.writeBytes(content.copyOf(1000))
        files.override = { request, _ ->
            if (request.url.encodedPath.contains("/resolve/")) {
                MockResponse.Builder().code(302).addHeader("Location", server.url("/cdn/blob").toString()).build()
            } else null
        }
        val outcome = run(resume = ResumeState(1000, "\"v1\"", content.size.toLong()))
        assertTrue(outcome is DownloadOutcome.Completed)
        assertEquals("bytes=1000-", files.requests.last().headers["Range"])
        assertArrayEquals(content, final.readBytes())
    }

    // ---- Resume ----

    @Test
    fun resumesWithA206FromTheDurableOffset() {
        part.writeBytes(content.copyOf(120_000))
        val outcome = run(resume = ResumeState(100_000, "\"v1\"", content.size.toLong()))
        assertTrue(outcome is DownloadOutcome.Completed)
        // Bytes after the durable point were discarded and fetched again.
        assertEquals("bytes=100000-", files.requests.single().headers["Range"])
        assertArrayEquals(content, final.readBytes())
    }

    @Test
    fun garbageAfterTheDurablePointCannotCorruptTheFile() {
        part.writeBytes(content.copyOf(100_000) + ByteArray(50_000) { 7 })
        val outcome = run(resume = ResumeState(100_000, "\"v1\"", content.size.toLong()))
        assertTrue(outcome is DownloadOutcome.Completed)
        assertArrayEquals(content, final.readBytes())
    }

    @Test
    fun a200ToAResumeRequestRestartsWithoutAppending() {
        part.writeBytes(content.copyOf(100_000))
        files.override = { _, _ -> MockResponse.Builder().code(200).body(Buffer().write(content)).build() }
        val outcome = run(resume = ResumeState(100_000, "\"v1\"", content.size.toLong()))
        assertTrue(outcome is DownloadOutcome.Completed)
        assertEquals(content.size.toLong(), final.length())
        assertArrayEquals(content, final.readBytes())
    }

    @Test
    fun aChangedEtagRestartsFromZero() {
        part.writeBytes(content.copyOf(100_000))
        files.etag = "\"v2\""
        val outcome = run(resume = ResumeState(100_000, "\"v1\"", content.size.toLong()))
        assertTrue(outcome is DownloadOutcome.Completed)
        assertEquals(listOf("bytes=100000-", null), files.requests.map { it.headers["Range"] })
        assertArrayEquals(content, final.readBytes())
    }

    @Test
    fun aChangedTotalSizeRestartsFromZero() {
        part.writeBytes(content.copyOf(100_000))
        // The partial file came from an older 300 005-byte version of the file.
        val outcome = run(spec(size = null), ResumeState(100_000, "\"v1\"", content.size + 5L))
        assertTrue(outcome is DownloadOutcome.Completed)
        assertEquals(listOf("bytes=100000-", null), files.requests.map { it.headers["Range"] })
        assertArrayEquals(content, final.readBytes())
    }

    @Test
    fun a416WithACompletePartialFileVerifiesBeforeSucceeding() {
        part.writeBytes(content)
        val outcome = run(resume = ResumeState(content.size.toLong(), "\"v1\"", content.size.toLong()))
        assertTrue(outcome is DownloadOutcome.Completed)
        assertEquals("bytes=${content.size}-", files.requests.single().headers["Range"])
        assertArrayEquals(content, final.readBytes())
    }

    @Test
    fun a416WithACorruptCompletePartialFileFailsTheChecksum() {
        part.writeBytes(ByteArray(content.size))
        val outcome = run(resume = ResumeState(content.size.toLong(), "\"v1\"", content.size.toLong()))
        assertEquals(DownloadFailureKind.CHECKSUM_MISMATCH, outcome.kind())
        assertFalse(final.exists())
        assertFalse(part.exists())
    }

    @Test
    fun a416ThatDoesNotMatchThePartialRestarts() {
        part.writeBytes(content.copyOf(100_000))
        files.override = { request, n ->
            if (n == 1) MockResponse.Builder().code(416).addHeader("Content-Range", "bytes */50000").build() else null
        }
        val outcome = run(spec(size = null), ResumeState(100_000, "\"v1\"", null))
        assertTrue(outcome is DownloadOutcome.Completed)
        assertEquals(null, files.requests.last().headers["Range"])
    }

    @Test
    fun invalidContentRangeIsRejected() {
        val cases = listOf(
            null,
            "bytes 100000-299999",
            "bytes 99999-299999/300000",
            "bytes 100000-200000/300000",
            "bytes 100000-299999/*"
        )
        for (value in cases) {
            part.writeBytes(content.copyOf(100_000))
            files.override = { _, _ ->
                val b = MockResponse.Builder().code(206).body(Buffer().write(content, 100_000, 200_000))
                value?.let { b.addHeader("Content-Range", it) }
                b.build()
            }
            val outcome = run(resume = ResumeState(100_000, null, null))
            assertEquals("Content-Range $value", DownloadFailureKind.INVALID_RESPONSE, outcome.kind())
            assertFalse(final.exists())
        }
    }

    // ---- Integrity ----

    @Test
    fun aChecksumMismatchDiscardsTheFileAndNeverPromotes() {
        val outcome = run(spec(sha = "0".repeat(64)))
        assertEquals(DownloadFailureKind.CHECKSUM_MISMATCH, outcome.kind())
        assertFalse(final.exists())
        assertFalse(part.exists())
    }

    @Test
    fun aServerSizeDifferentFromTheListingFails() {
        val outcome = run(spec(size = content.size + 1L))
        assertEquals(DownloadFailureKind.SIZE_MISMATCH, outcome.kind())
        assertFalse(final.exists())
    }

    @Test
    fun aServerSendingMoreThanItsStatedSizeIsInvalid() {
        // Chunked, so no Content-Length: only the listing says 1 000 bytes, but 300 000 arrive.
        files.override = { _, _ -> MockResponse.Builder().code(200).chunkedBody(Buffer().write(content), 4096).build() }
        val outcome = run(spec(size = 1000, sha = null))
        assertEquals(DownloadFailureKind.INVALID_RESPONSE, outcome.kind())
        assertFalse(final.exists())
    }

    @Test
    fun aDownloadWithNoSizeAnywhereIsNotDeclaredComplete() {
        files.override = { _, _ ->
            MockResponse.Builder().code(200).chunkedBody(Buffer().write(content), 4096).build()
        }
        val outcome = run(spec(size = null, sha = null))
        assertEquals(DownloadFailureKind.UNVERIFIABLE, outcome.kind())
        assertFalse(final.exists())
    }

    // ---- Storage ----

    @Test
    fun insufficientStorageFailsBeforeAnyRequest() {
        val outcome = run(engine = engine(free = 1000))
        assertEquals(DownloadFailureKind.INSUFFICIENT_STORAGE, outcome.kind())
        assertTrue(files.requests.isEmpty())
    }

    @Test
    fun storageCheckAccountsForBytesAlreadyDownloaded() {
        part.writeBytes(content.copyOf(250_000))
        // 50 000 bytes remain; 60 000 free covers them plus the 1 000 byte reserve.
        val outcome = run(resume = ResumeState(250_000, "\"v1\"", content.size.toLong()), engine = engine(free = 60_000))
        assertTrue(outcome is DownloadOutcome.Completed)
    }

    // ---- HTTP and network failures ----

    @Test
    fun httpErrorsMapToKindsThatKeepGatedAccessDistinct() {
        val cases = mapOf(
            401 to DownloadFailureKind.ACCESS_REQUIRED,
            403 to DownloadFailureKind.ACCESS_REQUIRED,
            404 to DownloadFailureKind.NOT_FOUND,
            429 to DownloadFailureKind.RATE_LIMITED,
            500 to DownloadFailureKind.SERVER,
            503 to DownloadFailureKind.SERVER,
            418 to DownloadFailureKind.INVALID_RESPONSE
        )
        for ((code, kind) in cases) {
            files.override = { _, _ -> MockResponse.Builder().code(code).addHeader("Retry-After", "45").build() }
            val outcome = run() as DownloadOutcome.Failed
            assertEquals("HTTP $code", kind, outcome.kind)
            if (code == 429) assertEquals(45L, outcome.retryAfterSeconds)
        }
        assertFalse(DownloadFailureKind.ACCESS_REQUIRED.retryable)
        assertFalse(DownloadFailureKind.NOT_FOUND.retryable)
        assertTrue(DownloadFailureKind.NETWORK.retryable)
        assertTrue(DownloadFailureKind.SERVER.retryable)
    }

    @Test
    fun aConnectionLostMidwayKeepsTheDurableBytesForResume() {
        files.override = { _, _ ->
            MockResponse.Builder().code(200).body(Buffer().write(content))
                .throttleBody(65_536, 10, TimeUnit.MILLISECONDS)
                .onResponseBody(SocketEffect.CloseSocket())
                .build()
        }
        val recorder = Recorder()
        val outcome = run(listener = recorder)
        assertEquals(DownloadFailureKind.NETWORK, outcome.kind())
        assertFalse(final.exists())
        val durable = recorder.durable.lastOrNull() ?: 0L
        assertTrue(part.length() >= durable)

        // A second attempt resumes and completes the identical file.
        files.override = { _, _ -> null }
        val resumed = run(resume = ResumeState(durable, recorder.etag, recorder.total))
        assertTrue(resumed is DownloadOutcome.Completed)
        assertArrayEquals(content, final.readBytes())
    }

    @Test
    fun aStalledServerTimesOut() {
        files.override = { _, _ ->
            MockResponse.Builder().code(200).body(Buffer().write(content)).bodyDelay(2, TimeUnit.SECONDS).build()
        }
        val client = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        assertEquals(DownloadFailureKind.TIMEOUT, run(engine = engine(client = client)).kind())
    }

    @Test
    fun anUnreachableServerIsANetworkFailure() {
        val s = spec()
        server.close()
        assertEquals(DownloadFailureKind.NETWORK, run(s).kind())
    }

    // ---- Cancellation ----

    @Test
    fun cancellationStopsTheTransferClosesTheFileAndNeverPromotes() = runBlocking {
        val big = ByteArray(5_000_000) { (it % 97).toByte() }
        files.content = big
        files.override = { _, _ ->
            MockResponse.Builder().code(200).body(Buffer().write(big)).throttleBody(32_768, 20, TimeUnit.MILLISECONDS).build()
        }
        val started = CompletableDeferred<Unit>()
        val listener = object : DownloadListener {
            override suspend fun onProgress(bytes: Long, totalBytes: Long?) { started.complete(Unit) }
        }
        val job = async(Dispatchers.Default) {
            engine().download(DownloadSpec(server.url("/m"), big.size.toLong(), null), part, final, ResumeState(), listener)
        }
        withTimeout(10_000) { started.await() }
        job.cancel()
        runCatching { job.await() }
        assertTrue(job.isCancelled)
        assertFalse(final.exists())
        assertTrue(part.exists() && part.length() < big.size)
        // The file handle was closed: on Windows an open file could not be deleted.
        assertTrue(part.delete())
    }
}
