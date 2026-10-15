package com.example.localai.data.hf

import com.example.localai.data.catalog.HfRepoFile
import com.example.localai.data.gguf.GgufParseResult
import com.example.localai.data.gguf.GgufParser
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource

/**
 * Byte budget for header inspection. Chunks start at [initialChunkBytes] and double up to
 * [maxChunkBytes]; nothing past [maxHeaderBytes] is ever requested.
 *
 * Basis for the defaults (measured 2026-10-15 on real Hub files): GGUF headers are dominated by
 * the tokenizer arrays. Phi-3.5 mini needs 0.7 MB, Qwen2.5 5.9 MB, Gemma 3 6.5 MB and Llama 3.2
 * 7.8 MB. 16 MiB leaves room for larger vocabularies while staying far below any model's weights.
 */
data class HeaderFetchPolicy(
    val initialChunkBytes: Long = 1L * 1024 * 1024,
    val maxChunkBytes: Long = 8L * 1024 * 1024,
    val maxHeaderBytes: Long = 16L * 1024 * 1024
) {
    init {
        require(initialChunkBytes in 1..maxChunkBytes && maxChunkBytes <= maxHeaderBytes)
    }
}

/** Why the header bytes could not be fetched. Distinct from a header that was read but is invalid. */
enum class HeaderFetchFailure(val description: String) {
    ACCESS_REQUIRED("Hugging Face requires access to this file (gated or private)"),
    NOT_FOUND("The file was not found on Hugging Face"),
    RATE_LIMITED("Hugging Face is limiting requests"),
    NETWORK("Network error while reading the file"),
    TIMEOUT("Reading the file timed out"),
    SERVER("Hugging Face returned a server error"),
    RANGE_NOT_SUPPORTED("The server does not support partial downloads for this file"),
    INVALID_RESPONSE("The server's partial-download response was invalid"),
    SIZE_MISMATCH("The file's size differs from the size listed by the repository")
}

sealed interface HeaderInspection {
    /**
     * The first bytes were read and handed to [GgufParser]. [result] may still be a failure:
     * not a GGUF file, unsupported version, or more header than the budget allows.
     */
    data class Inspected(
        val result: GgufParseResult,
        /** Bytes actually received; never the whole model. */
        val bytesRead: Long,
        /** Total file size from Content-Range or Content-Length, when the server stated it. */
        val fileSizeBytes: Long?,
        /** False when the server ignored Range and the response was cut off after the budget. */
        val rangeSupported: Boolean
    ) : HeaderInspection

    data class FetchFailed(val reason: HeaderFetchFailure, val detail: String) : HeaderInspection
}

/** Reads the GGUF header of one repository file. */
fun interface GgufHeaderInspector {
    suspend fun inspect(file: HfRepoFile): HeaderInspection
}

/**
 * Fetches the start of a GGUF file from the Hub with HTTP Range requests and parses it with the
 * existing [GgufParser] while streaming: chunks are requested only as the parser needs them, so
 * a 6 MB header costs about 6 MB, never the model. Unauthenticated; redirects to the Hub CDN are
 * followed, but never from HTTPS to HTTP. No bytes are written to disk.
 */
class GgufHeaderFetcher(
    client: OkHttpClient,
    private val baseUrl: HttpUrl = HuggingFaceCatalogRepository.DEFAULT_BASE_URL.toHttpUrl(),
    private val policy: HeaderFetchPolicy = HeaderFetchPolicy(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : GgufHeaderInspector {

    private val client = client.newBuilder().followSslRedirects(false).build()

    override suspend fun inspect(file: HfRepoFile): HeaderInspection = withContext(dispatcher) {
        val session = RangeSession(client, resolveUrl(file), policy, file.sizeBytes)
        val cancelOnCompletion = coroutineContext[Job]?.invokeOnCompletion { if (it != null) session.cancel() }
        try {
            val parsed = GgufParser.parse(session.stream, policy.maxHeaderBytes)
            coroutineContext.ensureActive() // a cancelled call surfaces as an IOException in the parser
            session.failure?.let { return@withContext it }
            HeaderInspection.Inspected(parsed, session.bytesRead, session.totalSize, session.rangeSupported)
        } finally {
            session.close()
            cancelOnCompletion?.dispose()
        }
    }

    internal fun resolveUrl(file: HfRepoFile): HttpUrl = hubResolveUrl(baseUrl, file)
}

/** `{base}/{repo}/resolve/{revision}/{path}`; the revision is one encoded segment. */
internal fun hubResolveUrl(baseUrl: HttpUrl, file: HfRepoFile): HttpUrl = baseUrl.newBuilder()
    .addPathSegments(file.repoId.value)
    .addPathSegment("resolve")
    .addPathSegment(file.revision)
    .addPathSegments(file.path)
    .build()

/** Thrown inside the stream to stop the parser; the real reason is kept in [RangeSession.failure]. */
private class RangeFetchStopped : IOException("range fetch stopped")

private val CONTENT_RANGE = Regex("""^bytes (\d+)-(\d+)/(\d+|\*)$""")

/**
 * Serves the parser from successive Range responses. Holds at most one chunk in memory. If the
 * server ignores Range (HTTP 200 for the first request), the plain body is read only up to the
 * budget and then closed, so the rest of the file is never transferred.
 */
private class RangeSession(
    private val client: OkHttpClient,
    private val url: HttpUrl,
    private val policy: HeaderFetchPolicy,
    private val expectedSize: Long?
) {
    var failure: HeaderInspection.FetchFailed? = null
        private set
    var bytesRead = 0L
        private set
    var totalSize: Long? = null
        private set
    var rangeSupported = true
        private set

    @Volatile
    private var currentCall: Call? = null
    private var plainResponse: Response? = null
    private var plainBody: BufferedSource? = null

    private var chunk = ByteArray(0)
    private var chunkPos = 0
    private var position = 0L // file offset of the next byte the parser will receive
    private var nextChunkSize = policy.initialChunkBytes
    private var exhausted = false

    val stream: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (chunkPos >= chunk.size && !fill()) return -1
            val n = minOf(len, chunk.size - chunkPos)
            System.arraycopy(chunk, chunkPos, b, off, n)
            chunkPos += n
            position += n
            return n
        }
    }

    fun cancel() {
        currentCall?.cancel()
    }

    fun close() {
        plainResponse?.close()
        plainResponse = null
        plainBody = null
    }

    /** Loads the next chunk; false at end of file or budget. Throws to abort on HTTP failure. */
    private fun fill(): Boolean {
        if (exhausted || position >= policy.maxHeaderBytes) return false
        totalSize?.let { if (position >= it) return false }
        val size = minOf(nextChunkSize, policy.maxHeaderBytes - position)
        nextChunkSize = minOf(nextChunkSize * 2, policy.maxChunkBytes)
        val data = plainBody?.let { readPlain(it, size) } ?: fetchRange(position, position + size - 1)
        if (data.isEmpty()) {
            exhausted = true
            return false
        }
        chunk = data
        chunkPos = 0
        return true
    }

    private fun readPlain(body: BufferedSource, size: Long): ByteArray {
        val buffer = okio.Buffer()
        try {
            while (buffer.size < size) {
                if (body.read(buffer, size - buffer.size) < 0) break
            }
        } catch (e: IOException) {
            stop(networkFailure(e))
        }
        bytesRead += buffer.size
        return buffer.readByteArray()
    }

    private fun fetchRange(start: Long, end: Long): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$end")
            // Byte offsets refer to the stored file; transparent gzip would break them.
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "LocalAI-Android/1.0 (GGUF header inspection)")
            .build()
        val call = client.newCall(request).also { currentCall = it }
        val response = try {
            call.execute()
        } catch (e: IOException) {
            stop(networkFailure(e))
        }
        when (response.code) {
            206 -> response.use { return readPartial(it, start, end) }
            200 -> {
                if (start != 0L) {
                    response.close()
                    stop(HeaderFetchFailure.RANGE_NOT_SUPPORTED, "HTTP 200 for a request starting at byte $start")
                }
                // Server ignored Range: read this body only up to the budget, then close it.
                rangeSupported = false
                plainResponse = response // closed by close(), also on the failure paths below
                val length = response.body.contentLength()
                if (length >= 0) checkTotal(length)
                val body = response.body.source().also { plainBody = it }
                return readPlain(body, end - start + 1)
            }
            416 -> response.use {
                // Requested start is at or past the end of the file.
                val total = it.header("Content-Range")?.substringAfter("bytes */", "")?.toLongOrNull()
                if (start == 0L || total == null || total > start) {
                    stop(HeaderFetchFailure.INVALID_RESPONSE, "HTTP 416 for bytes $start-$end")
                }
                checkTotal(total)
                return ByteArray(0)
            }
            401, 403 -> fail(response, HeaderFetchFailure.ACCESS_REQUIRED)
            404 -> fail(response, HeaderFetchFailure.NOT_FOUND)
            429 -> {
                val wait = retryAfterSeconds(response.headers)
                response.close()
                stop(HeaderFetchFailure.RATE_LIMITED, wait?.let { "Retry after $it s" } ?: "HTTP 429")
            }
            in 500..599 -> fail(response, HeaderFetchFailure.SERVER)
            else -> fail(response, HeaderFetchFailure.INVALID_RESPONSE)
        }
    }

    private fun readPartial(response: Response, start: Long, end: Long): ByteArray {
        val header = response.header("Content-Range")
            ?: stop(HeaderFetchFailure.INVALID_RESPONSE, "HTTP 206 without Content-Range")
        val match = CONTENT_RANGE.matchEntire(header.trim())
            ?: stop(HeaderFetchFailure.INVALID_RESPONSE, "Unreadable Content-Range '$header'")
        val first = match.groupValues[1].toLongOrNull()
        val last = match.groupValues[2].toLongOrNull()
        val total = match.groupValues[3].toLongOrNull()
        if (first == null || last == null || first != start || last < first || last > end) {
            stop(HeaderFetchFailure.INVALID_RESPONSE, "Content-Range '$header' does not match the requested bytes $start-$end")
        }
        if (total != null) {
            if (last >= total) stop(HeaderFetchFailure.INVALID_RESPONSE, "Content-Range '$header' is past the end of the file")
            checkTotal(total)
        }
        val length = (last - first + 1).toInt()
        val bytes = try {
            response.body.source().readByteArray(length.toLong())
        } catch (e: IOException) {
            stop(networkFailure(e))
        }
        bytesRead += bytes.size
        return bytes
    }

    private fun checkTotal(total: Long) {
        val known = totalSize
        if (known != null && known != total) {
            stop(HeaderFetchFailure.INVALID_RESPONSE, "The file size changed between requests ($known, then $total)")
        }
        if (expectedSize != null && expectedSize != total) {
            stop(HeaderFetchFailure.SIZE_MISMATCH, "The server reports $total bytes; the repository lists $expectedSize")
        }
        totalSize = total
    }

    private fun networkFailure(e: IOException): HeaderInspection.FetchFailed = when (e) {
        is RangeFetchStopped -> failure ?: HeaderInspection.FetchFailed(HeaderFetchFailure.NETWORK, "stopped")
        is InterruptedIOException -> HeaderInspection.FetchFailed(HeaderFetchFailure.TIMEOUT, e.message ?: "timeout")
        else -> HeaderInspection.FetchFailed(HeaderFetchFailure.NETWORK, e.message ?: e.javaClass.simpleName)
    }

    private fun fail(response: Response, reason: HeaderFetchFailure): Nothing {
        response.close()
        stop(reason, "HTTP ${response.code}")
    }

    private fun stop(reason: HeaderFetchFailure, detail: String): Nothing =
        stop(HeaderInspection.FetchFailed(reason, detail))

    private fun stop(result: HeaderInspection.FetchFailed): Nothing {
        failure = failure ?: result
        throw RangeFetchStopped()
    }
}
