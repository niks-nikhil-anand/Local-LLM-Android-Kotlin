package com.example.localai.data.download

import com.example.localai.data.catalog.DownloadStatus
import com.example.localai.data.catalog.HfRepoFile
import com.example.localai.data.catalog.HfRepoId
import com.example.localai.data.hf.hubResolveUrl
import com.example.localai.data.local.LocalDownloadState
import com.example.localai.data.local.LocalModelDao
import com.example.localai.data.local.LocalModelEntity
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

/** Runs downloads outside the caller's lifetime (WorkManager in the app, a fake in tests). */
interface DownloadScheduler {
    /** Idempotent: an already scheduled or running download for [id] is kept, not duplicated. */
    fun enqueue(id: String)

    fun cancel(id: String)
}

sealed interface EnqueueResult {
    data object Started : EnqueueResult
    data object AlreadyActive : EnqueueResult
    data object AlreadyDownloaded : EnqueueResult
    data class InsufficientStorage(val neededBytes: Long, val freeBytes: Long) : EnqueueResult
}

/** What the worker should report to WorkManager. */
enum class RunResult { DONE, RETRY, FAILED, STOPPED }

/** User-facing download operations, as used by screens. */
interface DownloadController {
    fun observe(): Flow<List<LocalModelEntity>>
    val liveProgress: StateFlow<Map<String, Long>>
    suspend fun enqueue(file: HfRepoFile): EnqueueResult
    suspend fun pause(id: String)
    suspend fun cancel(id: String)
    suspend fun retry(id: String): EnqueueResult
    suspend fun delete(id: String)

    /** Where a record's verified file lives (only meaningful for COMPLETED records). */
    fun localFile(row: LocalModelEntity): File

    /**
     * Re-checks every COMPLETED record against its file. A missing file, or one whose size no
     * longer matches, turns the record into FAILED (FILE_MISSING); returns how many changed.
     */
    suspend fun verifyCompletedFiles(): Int
}

/**
 * Owns the download records (Room) and files (app-private [modelsDir]) and runs single downloads
 * through [DownloadEngine]. File names are derived from a hash of the record id, never from the
 * repository path, so a hostile path cannot escape the directory or collide with another file.
 *
 * Recovery policy: PAUSED and FAILED keep their partial file (resumable); CANCELLED and deleted
 * records lose it; integrity failures discard it. [reconcile] repairs records after process death.
 */
class ModelDownloadManager(
    private val dao: LocalModelDao,
    private val engine: DownloadEngine,
    private val scheduler: DownloadScheduler,
    private val modelsDir: File,
    private val baseUrl: HttpUrl,
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val storageReserveBytes: Long = 512L * 1024 * 1024,
    private val maxAttempts: Int = 5,
    private val clock: () -> Long = System::currentTimeMillis
) : DownloadController {

    private val mutex = Mutex()
    private val progress = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Ids with an attempt running in this process; [reconcile] must not touch them. */
    private val running = HashSet<String>()

    /** Bytes received by running downloads, finer-grained than the durable count in the database. */
    override val liveProgress: StateFlow<Map<String, Long>> = progress.asStateFlow()

    override fun observe(): Flow<List<LocalModelEntity>> = dao.observeAll()

    override suspend fun enqueue(file: HfRepoFile): EnqueueResult = mutex.withLock {
        val id = idFor(file)
        val existing = dao.find(id)
        when (existing?.state?.let(::stateOf)) {
            LocalDownloadState.QUEUED, LocalDownloadState.DOWNLOADING, LocalDownloadState.VERIFYING -> {
                scheduler.enqueue(id) // re-assert scheduling; unique work keeps one runner
                return EnqueueResult.AlreadyActive
            }
            LocalDownloadState.COMPLETED -> if (finalFile(existing).exists()) return EnqueueResult.AlreadyDownloaded
            else -> Unit
        }
        val fileName = existing?.localFileName ?: fileNameFor(id)
        val alreadyHave = existing?.let { partialBytes(it) } ?: 0L
        file.sizeBytes?.let { size ->
            modelsDir.mkdirs()
            val needed = size - alreadyHave
            val free = freeSpace(modelsDir)
            if (needed + storageReserveBytes > free) return EnqueueResult.InsufficientStorage(needed, free)
        }
        val now = clock()
        dao.upsert(
            LocalModelEntity(
                id = id,
                repoId = file.repoId.value,
                revision = file.revision,
                path = file.path,
                expectedSizeBytes = file.sizeBytes,
                expectedSha256 = file.sha256,
                downloadedBytes = alreadyHave,
                state = LocalDownloadState.QUEUED.name,
                failureReason = null,
                updatedAtMillis = now,
                localFileName = fileName,
                remoteTotalBytes = existing?.remoteTotalBytes.takeIf { alreadyHave > 0 },
                remoteEtag = existing?.remoteEtag.takeIf { alreadyHave > 0 },
                attempts = 0,
                createdAtMillis = existing?.createdAtMillis ?: now
            )
        )
        scheduler.enqueue(id)
        EnqueueResult.Started
    }

    override suspend fun retry(id: String): EnqueueResult {
        val row = dao.find(id) ?: return EnqueueResult.AlreadyActive
        return enqueue(HfRepoFile(HfRepoId(row.repoId), row.revision, row.path, row.expectedSizeBytes, row.expectedSha256))
    }

    /** Stops the download and keeps the partial file for resuming. */
    override suspend fun pause(id: String) = mutex.withLock {
        val row = dao.find(id) ?: return@withLock
        if (stateOf(row.state) !in ACTIVE) return@withLock
        dao.upsert(row.copy(state = LocalDownloadState.PAUSED.name, updatedAtMillis = clock()))
        scheduler.cancel(id)
        clearProgress(id)
    }

    /** Stops the download and deletes the partial file. */
    override suspend fun cancel(id: String) = mutex.withLock {
        val row = dao.find(id) ?: return@withLock
        if (stateOf(row.state) == LocalDownloadState.COMPLETED) return@withLock
        dao.upsert(
            row.copy(
                state = LocalDownloadState.CANCELLED.name, downloadedBytes = 0, remoteEtag = null,
                remoteTotalBytes = null, failureReason = null, failureKind = null, updatedAtMillis = clock()
            )
        )
        scheduler.cancel(id)
        partFile(row).delete()
        clearProgress(id)
    }

    /** Removes the record and every file belonging to it. */
    override suspend fun delete(id: String) = mutex.withLock {
        val row = dao.find(id) ?: return@withLock
        scheduler.cancel(id)
        partFile(row).delete()
        finalFile(row).delete()
        dao.delete(id)
        clearProgress(id)
    }

    /** Executes one attempt for [id]; called by the background worker. */
    suspend fun runDownload(id: String, onProgress: suspend (Long, Long?) -> Unit = { _, _ -> }): RunResult {
        val row = mutex.withLock {
            val current = dao.find(id) ?: return RunResult.STOPPED
            if (stateOf(current.state) !in ACTIVE || id in running) return RunResult.STOPPED
            running += id
            current.copy(state = LocalDownloadState.DOWNLOADING.name, attempts = current.attempts + 1, updatedAtMillis = clock())
                .also { dao.upsert(it) }
        }
        try {
            return attemptDownload(row, onProgress)
        } finally {
            withContext(NonCancellable) { mutex.withLock { running -= id } }
        }
    }

    private suspend fun attemptDownload(row: LocalModelEntity, onProgress: suspend (Long, Long?) -> Unit): RunResult {
        val id = row.id
        val spec = DownloadSpec(
            url = hubResolveUrl(baseUrl, HfRepoFile(HfRepoId(row.repoId), row.revision, row.path, row.expectedSizeBytes, row.expectedSha256)),
            expectedSizeBytes = row.expectedSizeBytes,
            expectedSha256 = row.expectedSha256
        )
        val listener = object : DownloadListener {
            override suspend fun onResponse(totalBytes: Long?, etag: String?) {
                updateIfDownloading(id) { it.copy(remoteTotalBytes = totalBytes, remoteEtag = etag) }
            }

            override suspend fun onProgress(bytes: Long, totalBytes: Long?) {
                progress.update { it + (id to bytes) }
                onProgress(bytes, totalBytes)
            }

            override suspend fun onDurable(bytes: Long) {
                dao.updateProgress(id, bytes, clock())
            }

            override suspend fun onVerifying() {
                dao.transition(id, LocalDownloadState.DOWNLOADING.name, LocalDownloadState.VERIFYING.name, clock())
            }
        }
        val outcome = try {
            engine.download(
                spec,
                partFile(row),
                finalFile(row),
                ResumeState(row.downloadedBytes, row.remoteEtag, row.remoteTotalBytes),
                listener
            )
        } catch (e: CancellationException) {
            // Stopped by the user (state already PAUSED/CANCELLED) or by the system: requeue the latter.
            withContext(NonCancellable) {
                mutex.withLock {
                    dao.find(id)?.takeIf { stateOf(it.state) == LocalDownloadState.DOWNLOADING || stateOf(it.state) == LocalDownloadState.VERIFYING }
                        ?.let { dao.upsert(it.copy(state = LocalDownloadState.QUEUED.name, attempts = it.attempts - 1, updatedAtMillis = clock())) }
                }
                clearProgress(id)
            }
            throw e
        }
        clearProgress(id)
        return mutex.withLock { finish(id, outcome) }
    }

    private suspend fun finish(id: String, outcome: DownloadOutcome): RunResult {
        val current = dao.find(id) ?: return RunResult.STOPPED
        val now = clock()
        return when (outcome) {
            is DownloadOutcome.Completed -> {
                // The verified file is in place; completing is correct even if a pause raced the end.
                dao.upsert(
                    current.copy(
                        state = LocalDownloadState.COMPLETED.name, downloadedBytes = outcome.sizeBytes,
                        failureReason = null, failureKind = null, attempts = 0, verification = outcome.verification.name,
                        remoteEtag = outcome.etag ?: current.remoteEtag, updatedAtMillis = now, completedAtMillis = now
                    )
                )
                RunResult.DONE
            }
            is DownloadOutcome.Failed -> {
                if (stateOf(current.state) !in ACTIVE) return RunResult.STOPPED // user paused or cancelled meanwhile
                val retry = outcome.kind.retryable && current.attempts < maxAttempts
                val partBytes = minOf(current.downloadedBytes, partFile(current).takeIf { it.exists() }?.length() ?: 0L)
                dao.upsert(
                    current.copy(
                        state = if (retry) LocalDownloadState.QUEUED.name else LocalDownloadState.FAILED.name,
                        failureKind = outcome.kind.name,
                        failureReason = outcome.detail,
                        downloadedBytes = partBytes,
                        updatedAtMillis = now
                    )
                )
                if (retry) RunResult.RETRY else RunResult.FAILED
            }
        }
    }

    /**
     * Repairs records and files after process death or a device restart: interrupted downloads are
     * requeued from their durable bytes, a COMPLETED record whose file is gone is marked FAILED, a
     * verified file that was moved into place just before a crash is re-verified, and files no
     * record owns are deleted.
     */
    suspend fun reconcile() = mutex.withLock {
        modelsDir.mkdirs()
        val rows = dao.all()
        val owned = HashSet<String>()
        for (row in rows) {
            if (row.id in running) {
                owned += (row.localFileName ?: fileNameFor(row.id)).let { listOf(it, "$it$PART_SUFFIX") }
                continue
            }
            val name = row.localFileName ?: fileNameFor(row.id)
            owned += name
            owned += "$name$PART_SUFFIX"
            val part = partFile(row)
            val final = finalFile(row)
            val now = clock()
            when (stateOf(row.state)) {
                LocalDownloadState.COMPLETED -> checkCompletedLocked(row)
                LocalDownloadState.CANCELLED -> {
                    part.delete()
                    final.delete()
                }
                else -> {
                    // A final file without a COMPLETED record means a crash right after promotion:
                    // turn it back into a partial file so the next run verifies it before completing.
                    if (final.exists() && !part.exists()) {
                        final.renameTo(part)
                        dao.upsert(row.copy(downloadedBytes = part.length(), updatedAtMillis = now))
                    } else if (final.exists()) {
                        final.delete()
                    }
                    val fresh = dao.find(row.id) ?: row
                    val onDisk = if (part.exists()) part.length() else 0L
                    val bytes = minOf(fresh.downloadedBytes, onDisk)
                    val state = stateOf(fresh.state)
                    val requeue = state == LocalDownloadState.DOWNLOADING || state == LocalDownloadState.VERIFYING || state == LocalDownloadState.QUEUED
                    dao.upsert(
                        fresh.copy(
                            downloadedBytes = bytes,
                            state = if (requeue) LocalDownloadState.QUEUED.name else fresh.state,
                            updatedAtMillis = now
                        )
                    )
                    if (requeue) scheduler.enqueue(row.id)
                }
            }
        }
        modelsDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in owned && file.name.matches(OWN_FILE)) file.delete()
        }
    }

    override fun localFile(row: LocalModelEntity): File = finalFile(row)

    override suspend fun verifyCompletedFiles(): Int = mutex.withLock {
        dao.all().count { stateOf(it.state) == LocalDownloadState.COMPLETED && it.id !in running && checkCompletedLocked(it) }
    }

    /**
     * A COMPLETED record must have its file with the verified size (the listing's, or the size
     * recorded at completion). Otherwise the damaged file is removed and the record becomes
     * FAILED so the user can download it again. Returns true when the record changed.
     */
    private suspend fun checkCompletedLocked(row: LocalModelEntity): Boolean {
        val final = finalFile(row)
        val expected = row.expectedSizeBytes ?: row.downloadedBytes
        val reason = when {
            !final.exists() -> "The downloaded file is missing from storage."
            final.length() != expected -> "The file's size (${final.length()} bytes) no longer matches the verified $expected bytes."
            else -> return false
        }
        final.delete()
        dao.upsert(
            row.copy(
                state = LocalDownloadState.FAILED.name, failureKind = DownloadFailureKind.FILE_MISSING.name,
                failureReason = reason, downloadedBytes = 0, verification = null, completedAtMillis = null,
                updatedAtMillis = clock()
            )
        )
        return true
    }

    /** The verified model file for a COMPLETED record. */
    fun finalFile(row: LocalModelEntity): File = File(modelsDir, row.localFileName ?: fileNameFor(row.id))

    private fun partFile(row: LocalModelEntity): File = File(modelsDir, (row.localFileName ?: fileNameFor(row.id)) + PART_SUFFIX)

    private fun partialBytes(row: LocalModelEntity): Long {
        val part = partFile(row)
        return if (part.exists()) minOf(part.length(), row.downloadedBytes) else 0L
    }

    private suspend fun updateIfDownloading(id: String, change: (LocalModelEntity) -> LocalModelEntity) = mutex.withLock {
        dao.find(id)?.takeIf { stateOf(it.state) == LocalDownloadState.DOWNLOADING }?.let { dao.upsert(change(it).copy(updatedAtMillis = clock())) }
    }

    private fun clearProgress(id: String) = progress.update { it - id }

    companion object {
        const val PART_SUFFIX = ".part"
        private val OWN_FILE = Regex("""^[0-9a-f]{40}\.gguf(\.part)?$""")
        private val ACTIVE = setOf(LocalDownloadState.QUEUED, LocalDownloadState.DOWNLOADING, LocalDownloadState.VERIFYING)

        fun idFor(file: HfRepoFile): String = "${file.repoId.value}/${file.revision}/${file.path}"

        /** Collision-resistant, path-free name: 160 bits of SHA-256 of the record id. */
        fun fileNameFor(id: String): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            return hash.take(20).joinToString("") { "%02x".format(it) } + ".gguf"
        }

        fun stateOf(name: String): LocalDownloadState? = runCatching { LocalDownloadState.valueOf(name) }.getOrNull()
    }
}

/**
 * Domain view of a record. [liveBytes] (from a running download) refines the durable byte count.
 * COMPLETED here only means a verified file is on disk; it says nothing about loadability.
 */
fun LocalModelEntity.toDownloadStatus(liveBytes: Long? = null): DownloadStatus {
    val total = expectedSizeBytes ?: remoteTotalBytes
    return when (ModelDownloadManager.stateOf(state)) {
        LocalDownloadState.QUEUED -> DownloadStatus.Queued
        LocalDownloadState.DOWNLOADING -> DownloadStatus.Downloading(maxOf(liveBytes ?: 0, downloadedBytes), total)
        LocalDownloadState.VERIFYING -> DownloadStatus.Verifying
        LocalDownloadState.COMPLETED -> DownloadStatus.Completed
        LocalDownloadState.PAUSED -> DownloadStatus.Paused(downloadedBytes, total)
        LocalDownloadState.CANCELLED -> DownloadStatus.Cancelled
        LocalDownloadState.FAILED, null -> {
            val kind = failureKind?.let { runCatching { DownloadFailureKind.valueOf(it) }.getOrNull() }
            DownloadStatus.Failed(
                // A missing/damaged file has a specific reason (missing vs. wrong size); show just that.
                reason = (if (kind == DownloadFailureKind.FILE_MISSING) failureReason else null)
                    ?: listOfNotNull(kind?.description, failureReason?.takeIf { kind == null }).joinToString(" ").ifEmpty { "Download failed." },
                retryable = kind?.let { it != DownloadFailureKind.ACCESS_REQUIRED && it != DownloadFailureKind.NOT_FOUND } ?: true
            )
        }
    }
}
