package com.example.localai.download

import com.example.localai.data.catalog.DownloadStatus
import com.example.localai.data.catalog.HfRepoFile
import com.example.localai.data.catalog.HfRepoId
import com.example.localai.data.catalog.IncompatibilityReason
import com.example.localai.data.catalog.RuntimeCompatibility
import com.example.localai.data.catalog.canLoad
import com.example.localai.data.download.DownloadEngine
import com.example.localai.data.download.DownloadScheduler
import com.example.localai.data.download.EnqueueResult
import com.example.localai.data.download.ModelDownloadManager
import com.example.localai.data.download.RunResult
import com.example.localai.data.download.toDownloadStatus
import com.example.localai.data.local.LocalDownloadState
import com.example.localai.data.local.LocalModelDao
import com.example.localai.data.local.LocalModelEntity
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** In-memory stand-in for the Room DAO with the same semantics (CAS updates included). */
internal class FakeDao : LocalModelDao {
    val rows = MutableStateFlow<Map<String, LocalModelEntity>>(emptyMap())
    override fun observeAll(): Flow<List<LocalModelEntity>> = rows.map { it.values.sortedByDescending { r -> r.updatedAtMillis } }
    override suspend fun all() = rows.value.values.toList()
    override suspend fun find(id: String) = rows.value[id]
    override suspend fun upsert(entity: LocalModelEntity) { rows.value = rows.value + (entity.id to entity) }
    override suspend fun insertIfAbsent(entity: LocalModelEntity): Long =
        if (entity.id in rows.value) -1 else { upsert(entity); 1 }
    override suspend fun updateProgress(id: String, bytes: Long, now: Long): Int {
        val r = rows.value[id]?.takeIf { it.state == "DOWNLOADING" } ?: return 0
        upsert(r.copy(downloadedBytes = bytes, updatedAtMillis = now)); return 1
    }
    override suspend fun transition(id: String, from: String, to: String, now: Long): Int {
        val r = rows.value[id]?.takeIf { it.state == from } ?: return 0
        upsert(r.copy(state = to, updatedAtMillis = now)); return 1
    }
    override suspend fun delete(id: String) { rows.value = rows.value - id }
}

internal class FakeScheduler : DownloadScheduler {
    val enqueued = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    override fun enqueue(id: String) { enqueued += id }
    override fun cancel(id: String) { cancelled += id }
}

class ModelDownloadManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var files: RangeServer
    private val content = ByteArray(200_000) { (it % 251).toByte() }
    private val dao = FakeDao()
    private val scheduler = FakeScheduler()
    private lateinit var dir: File
    private var free = Long.MAX_VALUE
    private val repoFile = HfRepoFile(HfRepoId("Qwen/Qwen2.5-0.5B-Instruct-GGUF"), "main", "qwen2.5-0.5b-instruct-q4_k_m.gguf", 200_000L, sha256Hex(ByteArray(200_000) { (it % 251).toByte() }))
    private val id = ModelDownloadManager.idFor(repoFile)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        files = RangeServer(server, content)
        dir = temp.newFolder("models")
    }

    @After
    fun tearDown() = server.close()

    private fun manager(maxAttempts: Int = 3) = ModelDownloadManager(
        dao = dao,
        engine = DownloadEngine(OkHttpClient(), freeSpace = { free }, storageReserveBytes = 1000, syncEveryBytes = 32_768),
        scheduler = scheduler,
        modelsDir = dir,
        baseUrl = server.url("/"),
        freeSpace = { free },
        storageReserveBytes = 1000,
        maxAttempts = maxAttempts,
        clock = { 1_000L }
    )

    private fun row() = runBlocking { dao.find(id) }!!

    // ---- Enqueue and dedupe ----

    @Test
    fun enqueueCreatesAQueuedRecordWithASafeFileName() = runBlocking {
        assertEquals(EnqueueResult.Started, manager().enqueue(repoFile))
        val r = row()
        assertEquals("QUEUED", r.state)
        assertEquals(listOf(id), scheduler.enqueued)
        assertTrue(r.localFileName!!.matches(Regex("[0-9a-f]{40}\\.gguf")))
        assertFalse(r.localFileName!!.contains("qwen"))
        assertEquals(repoFile.sha256, r.expectedSha256)
    }

    @Test
    fun aSecondEnqueueDoesNotStartASecondDownload() = runBlocking {
        val m = manager()
        m.enqueue(repoFile)
        assertEquals(EnqueueResult.AlreadyActive, m.enqueue(repoFile))
        assertEquals(1, dao.rows.value.size)
        // Re-asserting is harmless: the scheduler uses unique work with KEEP.
        assertTrue(scheduler.enqueued.all { it == id })
    }

    @Test
    fun aConcurrentRunnerForTheSameIdIsRefused() = runBlocking {
        val m = manager()
        m.enqueue(repoFile)
        assertEquals(RunResult.DONE, m.runDownload(id))
        assertEquals(RunResult.STOPPED, m.runDownload(id)) // completed: nothing to do
        assertEquals(1, files.requests.size)
    }

    @Test
    fun insufficientStorageIsReportedBeforeQueueing() = runBlocking {
        free = 50_000
        val result = manager().enqueue(repoFile)
        assertEquals(EnqueueResult.InsufficientStorage(200_000, 50_000), result)
        assertNull(dao.find(id))
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun pathsFromTheRepositoryNeverBecomeFilePaths() = runBlocking {
        val hostile = repoFile.copy(path = "../../evil.gguf")
        manager().enqueue(hostile)
        val r = dao.find(ModelDownloadManager.idFor(hostile))!!
        assertFalse(r.localFileName!!.contains(".."))
        assertFalse(r.localFileName!!.contains("/"))
        assertEquals(40 + ".gguf".length, ModelDownloadManager.fileNameFor("x").length) // 160-bit hash
    }

    // ---- Running ----

    @Test
    fun aSuccessfulRunCompletesWithAVerifiedFile() = runBlocking {
        val m = manager()
        m.enqueue(repoFile)
        val progress = mutableListOf<Long>()
        assertEquals(RunResult.DONE, m.runDownload(id) { b, _ -> progress += b })
        val r = row()
        assertEquals("COMPLETED", r.state)
        assertEquals("SHA256", r.verification)
        assertEquals(200_000L, r.downloadedBytes)
        assertArrayEquals(content, m.finalFile(r).readBytes())
        assertEquals(200_000L, progress.last())
        assertEquals(DownloadStatus.Completed, r.toDownloadStatus())
        assertTrue(m.liveProgress.value.isEmpty())
    }

    @Test
    fun aDownloadedModelIsStillNotLoadable() = runBlocking {
        val m = manager()
        m.enqueue(repoFile)
        m.runDownload(id)
        val status = row().toDownloadStatus()
        assertEquals(DownloadStatus.Completed, status)
        assertFalse(canLoad(status, RuntimeCompatibility.NotEvaluated))
        assertFalse(canLoad(status, RuntimeCompatibility.Incompatible(IncompatibilityReason.NO_RUNTIME)))
    }

    @Test
    fun transientFailuresRetryABoundedNumberOfTimes() = runBlocking {
        files.override = { _, _ -> MockResponse.Builder().code(503).build() }
        val m = manager(maxAttempts = 3)
        m.enqueue(repoFile)
        assertEquals(RunResult.RETRY, m.runDownload(id))
        assertEquals("QUEUED", row().state)
        assertEquals(RunResult.RETRY, m.runDownload(id))
        assertEquals(RunResult.FAILED, m.runDownload(id))
        val r = row()
        assertEquals("FAILED", r.state)
        assertEquals("SERVER", r.failureKind)
        assertTrue((r.toDownloadStatus() as DownloadStatus.Failed).retryable)
    }

    @Test
    fun gatedAccessFailsImmediatelyAndIsNotRetried() = runBlocking {
        files.override = { _, _ -> MockResponse.Builder().code(401).build() }
        val m = manager()
        m.enqueue(repoFile)
        assertEquals(RunResult.FAILED, m.runDownload(id))
        val r = row()
        assertEquals("ACCESS_REQUIRED", r.failureKind)
        val status = r.toDownloadStatus() as DownloadStatus.Failed
        assertFalse(status.retryable)
        assertTrue(status.reason.contains("gated"))
    }

    @Test
    fun aUserRetryResetsTheAttemptBudget() = runBlocking {
        files.override = { _, _ -> MockResponse.Builder().code(503).build() }
        val m = manager(maxAttempts = 1)
        m.enqueue(repoFile)
        assertEquals(RunResult.FAILED, m.runDownload(id))
        files.override = { _, _ -> null }
        assertEquals(EnqueueResult.Started, m.retry(id))
        assertEquals(0, row().attempts)
        assertEquals(RunResult.DONE, m.runDownload(id))
    }

    // ---- Pause, cancel, delete ----

    @Test
    fun pauseKeepsThePartialFileAndResumeContinuesIt() = runBlocking {
        val m = manager()
        m.enqueue(repoFile)
        val r = row()
        // Simulate an interrupted transfer: 80 000 durable bytes on disk.
        File(dir, r.localFileName + ".part").writeBytes(content.copyOf(80_000))
        dao.upsert(r.copy(state = "DOWNLOADING", downloadedBytes = 80_000, remoteEtag = "\"v1\"", remoteTotalBytes = 200_000))
        m.pause(id)
        assertEquals("PAUSED", row().state)
        assertEquals(listOf(id), scheduler.cancelled)
        assertEquals(DownloadStatus.Paused(80_000, 200_000), row().toDownloadStatus())
        assertEquals(RunResult.STOPPED, m.runDownload(id)) // a paused record is not run

        assertEquals(EnqueueResult.Started, m.retry(id))
        assertEquals(80_000L, row().downloadedBytes)
        assertEquals(RunResult.DONE, m.runDownload(id))
        assertEquals("bytes=80000-", files.requests.last().headers["Range"])
        assertArrayEquals(content, m.finalFile(row()).readBytes())
    }

    @Test
    fun cancelDeletesThePartialFile() = runBlocking {
        val m = manager()
        m.enqueue(repoFile)
        val part = File(dir, row().localFileName + ".part").apply { writeBytes(ByteArray(10)) }
        m.cancel(id)
        assertFalse(part.exists())
        assertEquals("CANCELLED", row().state)
        assertEquals(DownloadStatus.Cancelled, row().toDownloadStatus())
        assertEquals(listOf(id), scheduler.cancelled)
    }

    @Test
    fun deleteRemovesTheRecordAndFiles() = runBlocking {
        val m = manager()
        m.enqueue(repoFile)
        m.runDownload(id)
        val file = m.finalFile(row())
        assertTrue(file.exists())
        m.delete(id)
        assertFalse(file.exists())
        assertNull(dao.find(id))
    }

    // ---- Recovery ----

    @Test
    fun reconcileRequeuesAnInterruptedDownloadFromItsDurableBytes() = runBlocking {
        val m = manager()
        m.enqueue(repoFile)
        val r = row()
        File(dir, r.localFileName + ".part").writeBytes(content.copyOf(50_000))
        dao.upsert(r.copy(state = "DOWNLOADING", downloadedBytes = 60_000)) // claimed more than is on disk
        scheduler.enqueued.clear()
        m.reconcile()
        assertEquals("QUEUED", row().state)
        assertEquals(50_000L, row().downloadedBytes)
        assertEquals(listOf(id), scheduler.enqueued)
    }

    @Test
    fun reconcileNeverKeepsACompletedRecordWithoutItsFile() = runBlocking {
        val m = manager()
        m.enqueue(repoFile)
        m.runDownload(id)
        m.finalFile(row()).delete()
        m.reconcile()
        assertEquals("FAILED", row().state)
        assertEquals("FILE_MISSING", row().failureKind)
    }

    @Test
    fun reconcileReverifiesAFileMovedIntoPlaceJustBeforeACrash() = runBlocking {
        val m = manager()
        m.enqueue(repoFile)
        val r = row()
        m.finalFile(r).writeBytes(content)
        dao.upsert(r.copy(state = "VERIFYING", downloadedBytes = 150_000))
        m.reconcile()
        assertFalse(m.finalFile(r).exists())
        assertEquals("QUEUED", row().state)
        assertEquals(200_000L, row().downloadedBytes)
        // The next run asks only for bytes past the end, gets 416 and verifies the SHA-256.
        assertEquals(RunResult.DONE, m.runDownload(id))
        assertEquals("bytes=200000-", files.requests.single().headers["Range"])
        assertEquals("COMPLETED", row().state)
    }

    @Test
    fun reconcileDeletesOrphanedAndCancelledFilesOnly() = runBlocking {
        val m = manager()
        val orphan = File(dir, "a".repeat(40) + ".gguf.part").apply { writeBytes(ByteArray(5)) }
        val foreign = File(dir, "notes.txt").apply { writeText("keep") }
        m.enqueue(repoFile)
        val part = File(dir, row().localFileName + ".part").apply { writeBytes(ByteArray(5)) }
        dao.upsert(row().copy(state = LocalDownloadState.CANCELLED.name))
        m.reconcile()
        assertFalse(orphan.exists())
        assertFalse(part.exists())
        assertTrue(foreign.exists()) // never touches files it did not create
    }

    @Test
    fun aChecksumFailureDiscardsTheFileAndCanBeRetried() = runBlocking {
        val bad = repoFile.copy(sha256 = "0".repeat(64))
        val m = manager(maxAttempts = 1)
        m.enqueue(bad)
        val badId = ModelDownloadManager.idFor(bad)
        assertEquals(RunResult.FAILED, m.runDownload(badId))
        val r = dao.find(badId)!!
        assertEquals("CHECKSUM_MISMATCH", r.failureKind)
        assertFalse(m.finalFile(r).exists())
        assertFalse(File(dir, r.localFileName + ".part").exists())
    }

    // ---- Step 8: re-checking completed files ----

    @Test
    fun verifyCompletedFilesLeavesIntactFilesAlone() = runBlocking {
        val m = manager()
        m.enqueue(repoFile)
        m.runDownload(id)
        assertEquals(0, m.verifyCompletedFiles())
        assertEquals("COMPLETED", row().state)
    }

    @Test
    fun aMissingCompletedFileBecomesARetryableFailure() = runBlocking {
        val m = manager()
        m.enqueue(repoFile)
        m.runDownload(id)
        m.finalFile(row()).delete()
        assertEquals(1, m.verifyCompletedFiles())
        val status = row().toDownloadStatus() as DownloadStatus.Failed
        assertTrue(status.reason.contains("missing"))
        assertTrue(status.retryable)
        // Downloading again works and completes.
        assertEquals(EnqueueResult.Started, m.retry(id))
        assertEquals(RunResult.DONE, m.runDownload(id))
    }

    @Test
    fun aCompletedFileWhoseSizeChangedIsRemovedAndFailed() = runBlocking {
        val m = manager()
        m.enqueue(repoFile)
        m.runDownload(id)
        val file = m.finalFile(row())
        file.appendBytes(ByteArray(10))
        assertEquals(1, m.verifyCompletedFiles())
        assertFalse(file.exists())
        assertEquals("FILE_MISSING", row().failureKind)
        assertTrue((row().toDownloadStatus() as DownloadStatus.Failed).reason.contains("no longer matches"))
    }

    @Test
    fun deletingOneModelNeverTouchesAnotherOrUnrelatedFiles() = runBlocking {
        val m = manager()
        val other = repoFile.copy(path = "other-Q8_0.gguf")
        m.enqueue(repoFile)
        m.enqueue(other)
        m.runDownload(id)
        m.runDownload(ModelDownloadManager.idFor(other))
        val unrelated = File(dir, "notes.txt").apply { writeText("keep") }
        val otherFile = m.finalFile(dao.find(ModelDownloadManager.idFor(other))!!)
        m.delete(id)
        assertTrue(otherFile.exists())
        assertTrue(unrelated.exists())
        assertNull(dao.find(id))
        assertEquals(listOf(id), scheduler.cancelled) // its WorkManager job is cancelled too
    }
}
