package com.example.localai.data.download

import com.example.localai.data.hf.retryAfterSeconds
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource

/** Why a download stopped. [retryable] failures may be retried automatically (bounded). */
enum class DownloadFailureKind(val description: String, val retryable: Boolean) {
    ACCESS_REQUIRED("Hugging Face requires access to this file (gated or private). This app does not sign in.", false),
    NOT_FOUND("The file was not found on Hugging Face.", false),
    RATE_LIMITED("Hugging Face is limiting requests.", true),
    SERVER("Hugging Face returned a server error.", true),
    NETWORK("The connection was lost.", true),
    TIMEOUT("The download timed out.", true),
    INVALID_RESPONSE("The server's response was invalid.", true),
    REMOTE_CHANGED("The file changed on Hugging Face during the download.", true),
    SIZE_MISMATCH("The file's size does not match the repository listing.", false),
    CHECKSUM_MISMATCH("The file's SHA-256 does not match the repository's checksum; it was discarded.", true),
    UNVERIFIABLE("Neither the repository nor the server stated the file size, so completeness cannot be checked.", false),
    INSUFFICIENT_STORAGE("Not enough free storage on this device.", false),
    STORAGE_ERROR("The file could not be written to storage.", false),
    FILE_MISSING("The downloaded file is missing or damaged.", false)
}

/** What to download and what the finished file must match. */
data class DownloadSpec(
    val url: HttpUrl,
    /** Size from the repository listing; the authority for completeness when present. */
    val expectedSizeBytes: Long?,
    /** LFS SHA-256 from the repository listing; verified when present. */
    val expectedSha256: String?
)

/** Persisted resume information from an earlier attempt. */
data class ResumeState(
    /** Bytes known to be fsynced in the partial file; anything beyond is discarded. */
    val durableBytes: Long = 0,
    val etag: String? = null,
    val totalBytes: Long? = null
)

enum class Verification { SHA256, SIZE_ONLY }

sealed interface DownloadOutcome {
    data class Completed(val file: File, val sizeBytes: Long, val verification: Verification, val etag: String?) : DownloadOutcome
    data class Failed(val kind: DownloadFailureKind, val detail: String, val retryAfterSeconds: Long? = null) : DownloadOutcome
}

/** Progress callbacks; all are called on the engine's I/O dispatcher. */
interface DownloadListener {
    /** A response was accepted. Persist [etag]/[totalBytes] so a later resume can detect changes. */
    suspend fun onResponse(totalBytes: Long?, etag: String?) {}

    /** Bytes received so far (throttled). Not necessarily durable. */
    suspend fun onProgress(bytes: Long, totalBytes: Long?) {}

    /** [bytes] are fsynced; safe to persist as the resume point. */
    suspend fun onDurable(bytes: Long) {}

    suspend fun onVerifying() {}
}

/**
 * Streams one file to disk with resumable HTTP Range requests.
 *
 * - Writes only to [partFile]; [finalFile] appears (atomic move) after size and, when a
 *   trustworthy checksum is known, SHA-256 verification succeed.
 * - Resumes from the last fsynced byte. A 206 must match the request exactly and report the same
 *   total; a changed ETag or total restarts from zero once. A 200 to a resume request truncates
 *   the partial file first, so bytes are never appended twice. 416 is success only when the
 *   partial file already holds the whole file and then passes verification.
 * - Holds one 64 KiB buffer in memory regardless of model size; no size limit is imposed.
 * - Cancellation closes the HTTP call and the file; the partial file is left for the caller.
 *
 * CDN behaviour this relies on was checked against huggingface.co (2026-10-15): /resolve answers
 * 302 to a signed CDN URL that honours Range and sends a strong ETag, but ignores If-Range; the
 * ETag is not the content SHA-256, so it is used only to detect change.
 */
class DownloadEngine(
    client: OkHttpClient,
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val storageReserveBytes: Long = 512L * 1024 * 1024,
    private val syncEveryBytes: Long = 8L * 1024 * 1024,
    private val progressEveryBytes: Long = 512L * 1024,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val client = client.newBuilder().followSslRedirects(false).build()

    private class Stop(val outcome: DownloadOutcome.Failed) : Exception(outcome.detail, null, false, false)

    private class Restart(val reason: String) : Exception(reason, null, false, false)

    suspend fun download(
        spec: DownloadSpec,
        partFile: File,
        finalFile: File,
        resume: ResumeState,
        listener: DownloadListener = object : DownloadListener {}
    ): DownloadOutcome = withContext(dispatcher) {
        var state = resume
        var restartsLeft = 1
        while (true) {
            try {
                return@withContext attempt(spec, partFile, finalFile, state, listener)
            } catch (r: Restart) {
                if (restartsLeft-- <= 0) {
                    return@withContext DownloadOutcome.Failed(DownloadFailureKind.REMOTE_CHANGED, r.reason)
                }
                partFile.delete()
                state = ResumeState()
                listener.onDurable(0)
            } catch (s: Stop) {
                return@withContext s.outcome
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private suspend fun attempt(
        spec: DownloadSpec,
        partFile: File,
        finalFile: File,
        resume: ResumeState,
        listener: DownloadListener
    ): DownloadOutcome {
        val expected = spec.expectedSizeBytes
        partFile.parentFile?.mkdirs()

        // Keep only bytes known to be durable; a crash may leave unflushed garbage after them.
        var offset = if (partFile.exists()) minOf(partFile.length(), resume.durableBytes) else 0L
        if (expected != null && offset > expected) offset = 0
        truncate(partFile, offset)
        val priorEtag = resume.etag.takeIf { offset > 0 }
        val priorTotal = resume.totalBytes.takeIf { offset > 0 }

        val knownTotal = expected ?: priorTotal
        if (knownTotal != null) checkStorage(partFile, knownTotal - offset)

        val request = Request.Builder()
            .url(spec.url)
            .header("Accept-Encoding", "identity") // offsets refer to the stored bytes
            .header("User-Agent", USER_AGENT)
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()
        val call = client.newCall(request)
        val cancelOnCompletion = coroutineContext[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            val response = try {
                call.execute()
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                throw Stop(networkFailure(e))
            }
            response.use {
                return when (response.code) {
                    206 -> receivePartial(response, spec, partFile, finalFile, offset, priorEtag, priorTotal, listener)
                    200 -> receiveFull(response, spec, partFile, finalFile, offset, listener)
                    416 -> rangeNotSatisfiable(response, spec, partFile, finalFile, offset, listener)
                    401, 403 -> fail(DownloadFailureKind.ACCESS_REQUIRED, "HTTP ${response.code}")
                    404 -> fail(DownloadFailureKind.NOT_FOUND, "HTTP 404")
                    408 -> fail(DownloadFailureKind.TIMEOUT, "HTTP 408")
                    429 -> {
                        val wait = retryAfterSeconds(response.headers)
                        throw Stop(DownloadOutcome.Failed(DownloadFailureKind.RATE_LIMITED, "HTTP 429", wait))
                    }
                    in 500..599 -> fail(DownloadFailureKind.SERVER, "HTTP ${response.code}")
                    else -> fail(DownloadFailureKind.INVALID_RESPONSE, "HTTP ${response.code}")
                }
            }
        } finally {
            cancelOnCompletion?.dispose()
        }
    }

    private suspend fun receivePartial(
        response: Response,
        spec: DownloadSpec,
        partFile: File,
        finalFile: File,
        offset: Long,
        priorEtag: String?,
        priorTotal: Long?,
        listener: DownloadListener
    ): DownloadOutcome {
        val header = response.header("Content-Range")
            ?: fail(DownloadFailureKind.INVALID_RESPONSE, "HTTP 206 without Content-Range")
        val match = CONTENT_RANGE.matchEntire(header.trim())
            ?: fail(DownloadFailureKind.INVALID_RESPONSE, "Unreadable Content-Range '$header'")
        val first = match.groupValues[1].toLongOrNull()
        val last = match.groupValues[2].toLongOrNull()
        val total = match.groupValues[3].toLongOrNull()
            ?: fail(DownloadFailureKind.INVALID_RESPONSE, "Content-Range '$header' has no total size")
        if (offset == 0L) fail(DownloadFailureKind.INVALID_RESPONSE, "HTTP 206 to a request without Range")
        if (first != offset || last != total - 1 || last < first) {
            fail(DownloadFailureKind.INVALID_RESPONSE, "Content-Range '$header' does not continue byte $offset to the end")
        }
        val etag = response.header("ETag")
        // The same bytes must still be served: same total and, if both are known, the same ETag.
        if (priorTotal != null && priorTotal != total) throw Restart("The file size changed from $priorTotal to $total bytes")
        if (priorEtag != null && etag != null && priorEtag != etag) throw Restart("The file's ETag changed")
        spec.expectedSizeBytes?.let { if (it != total) throw Stop(sizeMismatch(total, it)) }
        listener.onResponse(total, etag)
        val digest = spec.expectedSha256?.let { prefixDigest(partFile, offset) }
        stream(response.body.source(), partFile, offset, total, digest, listener)
        return verifyAndPromote(spec, partFile, finalFile, total, digest, etag, listener)
    }

    private suspend fun receiveFull(
        response: Response,
        spec: DownloadSpec,
        partFile: File,
        finalFile: File,
        offset: Long,
        listener: DownloadListener
    ): DownloadOutcome {
        val length = response.body.contentLength().takeIf { it >= 0 }
        spec.expectedSizeBytes?.let { expected -> if (length != null && length != expected) throw Stop(sizeMismatch(length, expected)) }
        // A 200 always carries the whole file: drop any partial bytes instead of appending to them.
        if (offset > 0) {
            truncate(partFile, 0)
            listener.onDurable(0)
        }
        val total = spec.expectedSizeBytes ?: length
        total?.let { checkStorage(partFile, it) }
        val etag = response.header("ETag")
        listener.onResponse(total, etag)
        val digest = spec.expectedSha256?.let { MessageDigest.getInstance("SHA-256") }
        stream(response.body.source(), partFile, 0, total, digest, listener)
        return verifyAndPromote(spec, partFile, finalFile, total, digest, etag, listener)
    }

    private suspend fun rangeNotSatisfiable(
        response: Response,
        spec: DownloadSpec,
        partFile: File,
        finalFile: File,
        offset: Long,
        listener: DownloadListener
    ): DownloadOutcome {
        val total = response.header("Content-Range")?.trim()?.removePrefix("bytes */")?.toLongOrNull()
        if (offset == 0L) fail(DownloadFailureKind.INVALID_RESPONSE, "HTTP 416 for a request without Range")
        // The partial file may already hold everything; that is only success after verification.
        val complete = total == offset && (spec.expectedSizeBytes == null || spec.expectedSizeBytes == offset)
        if (!complete) throw Restart("HTTP 416: the server reports ${total ?: "an unknown size"}, the partial file has $offset bytes")
        val digest = spec.expectedSha256?.let { prefixDigest(partFile, offset) }
        return verifyAndPromote(spec, partFile, finalFile, total, digest, response.header("ETag"), listener)
    }

    private suspend fun stream(
        body: BufferedSource,
        partFile: File,
        start: Long,
        total: Long?,
        digest: MessageDigest?,
        listener: DownloadListener
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        var written = start
        var sinceSync = 0L
        var sinceProgress = 0L
        FileOutputStream(partFile, true).use { out ->
            while (true) {
                coroutineContext.ensureActive()
                val n = try {
                    body.read(buffer)
                } catch (e: IOException) {
                    coroutineContext.ensureActive()
                    syncQuietly(out, listener, written)
                    throw Stop(networkFailure(e))
                }
                if (n < 0) break
                if (total != null && written + n > total) {
                    fail(DownloadFailureKind.INVALID_RESPONSE, "The server sent more than the stated $total bytes")
                }
                try {
                    out.write(buffer, 0, n)
                } catch (e: IOException) {
                    throw Stop(writeFailure(partFile, e))
                }
                digest?.update(buffer, 0, n)
                written += n
                sinceSync += n
                sinceProgress += n
                if (sinceSync >= syncEveryBytes) {
                    sync(out, partFile)
                    listener.onDurable(written)
                    sinceSync = 0
                }
                if (sinceProgress >= progressEveryBytes) {
                    listener.onProgress(written, total)
                    sinceProgress = 0
                }
            }
            sync(out, partFile)
            listener.onDurable(written)
            listener.onProgress(written, total)
        }
        if (total != null && written < total) {
            fail(DownloadFailureKind.NETWORK, "The connection ended after $written of $total bytes")
        }
    }

    private suspend fun verifyAndPromote(
        spec: DownloadSpec,
        partFile: File,
        finalFile: File,
        serverTotal: Long?,
        digest: MessageDigest?,
        etag: String?,
        listener: DownloadListener
    ): DownloadOutcome {
        listener.onVerifying()
        val size = partFile.length()
        val required = spec.expectedSizeBytes ?: serverTotal
            ?: run {
                partFile.delete()
                fail(DownloadFailureKind.UNVERIFIABLE, "No size from the repository or the server")
            }
        if (size != required) {
            partFile.delete()
            throw Stop(sizeMismatch(size, required))
        }
        val verification = if (spec.expectedSha256 != null) {
            val actual = digest!!.digest().toHex()
            if (!actual.equals(spec.expectedSha256, ignoreCase = true)) {
                partFile.delete()
                fail(DownloadFailureKind.CHECKSUM_MISMATCH, "Expected ${spec.expectedSha256}, got $actual")
            }
            Verification.SHA256
        } else {
            Verification.SIZE_ONLY
        }
        try {
            Files.move(partFile.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            fail(DownloadFailureKind.STORAGE_ERROR, "Could not move the verified file into place: ${e.message}")
        }
        return DownloadOutcome.Completed(finalFile, size, verification, etag)
    }

    private fun checkStorage(partFile: File, remaining: Long) {
        val dir = partFile.parentFile ?: return
        val free = freeSpace(dir)
        if (remaining + storageReserveBytes > free) {
            fail(DownloadFailureKind.INSUFFICIENT_STORAGE, "Needs $remaining more bytes plus a $storageReserveBytes byte reserve; $free free")
        }
    }

    private suspend fun prefixDigest(partFile: File, length: Long): MessageDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        if (length == 0L) return digest
        val buffer = ByteArray(BUFFER_BYTES)
        FileInputStream(partFile).use { input ->
            var left = length
            while (left > 0) {
                coroutineContext.ensureActive()
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) fail(DownloadFailureKind.STORAGE_ERROR, "The partial file is shorter than expected")
                digest.update(buffer, 0, n)
                left -= n
            }
        }
        return digest
    }

    private fun truncate(file: File, length: Long) {
        try {
            RandomAccessFile(file, "rw").use { it.setLength(length) }
        } catch (e: IOException) {
            fail(DownloadFailureKind.STORAGE_ERROR, "Could not prepare the partial file: ${e.message}")
        }
    }

    private fun sync(out: FileOutputStream, partFile: File) {
        try {
            out.flush()
            out.fd.sync()
        } catch (e: IOException) {
            throw Stop(writeFailure(partFile, e))
        }
    }

    /** Best effort before reporting a network failure, so the resume point is as late as possible. */
    private suspend fun syncQuietly(out: FileOutputStream, listener: DownloadListener, written: Long) {
        val synced = runCatching { out.flush(); out.fd.sync() }.isSuccess
        if (synced) listener.onDurable(written)
    }

    private fun writeFailure(partFile: File, e: IOException): DownloadOutcome.Failed {
        val free = partFile.parentFile?.let(freeSpace) ?: Long.MAX_VALUE
        return if (free < BUFFER_BYTES * 2L) {
            DownloadOutcome.Failed(DownloadFailureKind.INSUFFICIENT_STORAGE, "Storage is full: ${e.message}")
        } else {
            DownloadOutcome.Failed(DownloadFailureKind.STORAGE_ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun networkFailure(e: IOException): DownloadOutcome.Failed = when (e) {
        is InterruptedIOException -> DownloadOutcome.Failed(DownloadFailureKind.TIMEOUT, e.message ?: "timeout")
        else -> DownloadOutcome.Failed(DownloadFailureKind.NETWORK, e.message ?: e.javaClass.simpleName)
    }

    private fun sizeMismatch(actual: Long, expected: Long) =
        DownloadOutcome.Failed(DownloadFailureKind.SIZE_MISMATCH, "$actual bytes, expected $expected")

    private fun fail(kind: DownloadFailureKind, detail: String): Nothing = throw Stop(DownloadOutcome.Failed(kind, detail))

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val USER_AGENT = "LocalAI-Android/1.0 (model download)"
        val CONTENT_RANGE = Regex("""^bytes (\d+)-(\d+)/(\d+|\*)$""")

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
