package com.example.localai.mymodels

import com.example.localai.data.catalog.DownloadStatus
import com.example.localai.data.catalog.IncompatibilityReason
import com.example.localai.data.catalog.RuntimeCompatibility
import com.example.localai.data.compat.FindingCode
import com.example.localai.data.compat.Verdict
import com.example.localai.data.download.EnqueueResult
import com.example.localai.data.download.Verification
import com.example.localai.data.local.LocalModelEntity
import com.example.localai.explore.FakeDeviceProvider
import com.example.localai.explore.FakeDownloads
import com.example.localai.explore.GB
import com.example.localai.explore.normalDevice
import com.example.localai.gguf.GgufBuilder
import com.example.localai.ui.screens.mymodels.LocalAnalysis
import com.example.localai.ui.screens.mymodels.MyModelsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MyModelsViewModelTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val downloads = FakeDownloads()
    private val device = FakeDeviceProvider()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = MyModelsViewModel(downloads, device, clock = { 0L }, ioDispatcher = dispatcher)

    private fun row(
        path: String,
        state: String,
        bytes: Long = 0,
        size: Long? = 1_000_000L,
        verification: String? = null,
        failureKind: String? = null,
        repo: String = "o/r"
    ) = LocalModelEntity(
        id = "$repo/main/$path", repoId = repo, revision = "main", path = path, expectedSizeBytes = size,
        expectedSha256 = null, downloadedBytes = bytes, state = state, failureReason = null, updatedAtMillis = 0,
        verification = verification, failureKind = failureKind
    )

    /** A completed record whose file on disk is a real (synthetic) GGUF. */
    private fun completedGguf(path: String = "tiny-Q4_K_M.gguf", builder: GgufBuilder = GgufBuilder.tinyLlama()): LocalModelEntity {
        val bytes = builder.file()
        val file = temp.newFile(path.replace('/', '_')).apply { writeBytes(bytes) }
        val r = row(path, "COMPLETED", bytes = bytes.size.toLong(), size = bytes.size.toLong(), verification = "SHA256")
        downloads.files[r.id] = file
        return r
    }

    @Test
    fun noDownloadsShowsTheEmptyState() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.items.isEmpty())
        assertEquals(1, downloads.verifyCalls) // files are re-checked on open
    }

    @Test
    fun everyLifecycleStateIsShownFromPersistedRecords() = runTest(dispatcher) {
        downloads.rows.value = listOf(
            row("a.gguf", "DOWNLOADING", bytes = 100),
            row("b.gguf", "PAUSED", bytes = 400),
            row("c.gguf", "FAILED", failureKind = "NETWORK"),
            row("d.gguf", "FAILED", failureKind = "ACCESS_REQUIRED"),
            row("e.gguf", "CANCELLED"),
            row("f.gguf", "QUEUED")
        )
        downloads.liveProgress.value = mapOf("o/r/main/a.gguf" to 250L)
        val vm = vm()
        advanceUntilIdle()
        val byPath = vm.state.value.items.associateBy { it.path }
        assertEquals(DownloadStatus.Downloading(250, 1_000_000), byPath["a.gguf"]!!.status)
        assertEquals(DownloadStatus.Paused(400, 1_000_000), byPath["b.gguf"]!!.status)
        assertTrue((byPath["c.gguf"]!!.status as DownloadStatus.Failed).retryable)
        assertFalse((byPath["d.gguf"]!!.status as DownloadStatus.Failed).retryable)
        assertEquals(DownloadStatus.Cancelled, byPath["e.gguf"]!!.status)
        assertEquals(DownloadStatus.Queued, byPath["f.gguf"]!!.status)
        // Only completed files get a local analysis; nothing is invented for the rest.
        assertTrue(vm.state.value.items.all { it.analysis == LocalAnalysis.NotAvailable && it.verification == null })
        // Active downloads are listed first.
        assertEquals(DownloadStatus.Downloading(250, 1_000_000), vm.state.value.items.first().status)
    }

    @Test
    fun aCompletedFileIsAnalysedFromItsRealHeaderButIsNotLoadable() = runTest(dispatcher) {
        downloads.rows.value = listOf(completedGguf())
        val vm = vm()
        advanceUntilIdle()
        val item = vm.state.value.items.single()
        assertEquals(DownloadStatus.Completed, item.status)
        assertEquals(Verification.SHA256, item.verification)
        val analysis = item.analysis as LocalAnalysis.Ready
        assertEquals("llama", analysis.info?.architecture)
        assertEquals(Verdict.MEETS_REQUIREMENTS, analysis.assessment.verdict)
        assertFalse(analysis.assessment.has(FindingCode.HEADER_NOT_INSPECTED))
        assertEquals(0L, analysis.assessment.remainingDownloadBytes) // already on disk
        assertEquals(RuntimeCompatibility.Incompatible(IncompatibilityReason.NO_RUNTIME), analysis.runtime)
    }

    @Test
    fun sizeOnlyVerificationIsDistinguishedFromSha256() = runTest(dispatcher) {
        val sha = completedGguf("sha-Q4_K_M.gguf")
        val sizeOnly = completedGguf("size-Q4_K_M.gguf").copy(verification = "SIZE_ONLY")
        downloads.rows.value = listOf(sha, sizeOnly)
        val vm = vm()
        advanceUntilIdle()
        val byPath = vm.state.value.items.associateBy { it.path }
        assertEquals(Verification.SHA256, byPath["sha-Q4_K_M.gguf"]!!.verification)
        assertEquals(Verification.SIZE_ONLY, byPath["size-Q4_K_M.gguf"]!!.verification)
    }

    @Test
    fun aCompletedFileThatIsNotAModelIsReportedIncompatible() = runTest(dispatcher) {
        val r = row("fake.gguf", "COMPLETED", bytes = 2048, size = 2048, verification = "SIZE_ONLY")
        downloads.files[r.id] = temp.newFile("fake.gguf").apply { writeBytes(ByteArray(2048) { 'x'.code.toByte() }) }
        downloads.rows.value = listOf(r)
        val vm = vm()
        advanceUntilIdle()
        val analysis = vm.state.value.items.single().analysis as LocalAnalysis.Ready
        assertEquals(Verdict.INCOMPATIBLE, analysis.assessment.verdict)
        assertTrue(analysis.assessment.has(FindingCode.NOT_A_MODEL_FILE))
        assertTrue(analysis.runtime is RuntimeCompatibility.Incompatible)
    }

    @Test
    fun aMissingFileIsReportedNotAssumedPresent() = runTest(dispatcher) {
        downloads.rows.value = listOf(row("gone.gguf", "COMPLETED", bytes = 10, verification = "SHA256"))
        val vm = vm()
        advanceUntilIdle()
        val analysis = vm.state.value.items.single().analysis as LocalAnalysis.Failed
        assertTrue(analysis.message.contains("missing"))
    }

    @Test
    fun recordChangesFlowThroughWithoutReanalysingUnchangedFiles() = runTest(dispatcher) {
        val done = completedGguf()
        downloads.rows.value = listOf(done, row("b.gguf", "DOWNLOADING", bytes = 10))
        val vm = vm()
        advanceUntilIdle()
        val first = vm.state.value.items.first { it.id == done.id }.analysis
        downloads.liveProgress.value = mapOf("o/r/main/b.gguf" to 500L)
        advanceUntilIdle()
        assertSame(first, vm.state.value.items.first { it.id == done.id }.analysis)
        assertEquals(DownloadStatus.Downloading(500, 1_000_000), vm.state.value.items.first { it.path == "b.gguf" }.status)

        // Deleted elsewhere: the item disappears.
        downloads.rows.value = listOf(row("b.gguf", "DOWNLOADING", bytes = 10))
        advanceUntilIdle()
        assertEquals(listOf("b.gguf"), vm.state.value.items.map { it.path })
    }

    @Test
    fun storageTotalsSeparateVerifiedFilesFromPartialData() = runTest(dispatcher) {
        val done = completedGguf()
        downloads.rows.value = listOf(done, row("p.gguf", "PAUSED", bytes = 4_000))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(done.downloadedBytes, vm.state.value.completedBytes)
        assertEquals(4_000L, vm.state.value.partialBytes)
        assertEquals(128 * GB, vm.state.value.device?.totalStorageBytes)
    }

    @Test
    fun actionsUseTheExistingDownloadController() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.pause("x"); vm.cancel("y"); vm.retry("z"); vm.delete("w")
        advanceUntilIdle()
        assertEquals(listOf("pause:x", "cancel:y", "retry:z", "delete:w"), downloads.calls)
    }

    @Test
    fun aRetryWithoutEnoughStorageExplainsWhy() = runTest(dispatcher) {
        downloads.enqueueResult = EnqueueResult.InsufficientStorage(5 * GB, 1 * GB)
        val vm = vm()
        advanceUntilIdle()
        vm.retry("z")
        advanceUntilIdle()
        assertTrue(vm.state.value.message!!.startsWith("Not enough storage"))
        vm.dismissMessage()
        assertNull(vm.state.value.message)
    }

    @Test
    fun refreshRemeasuresRechecksAndReanalyses() = runTest(dispatcher) {
        downloads.rows.value = listOf(completedGguf())
        val vm = vm()
        advanceUntilIdle()
        device.readings = normalDevice.copy(isLowMemory = true)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, device.refreshes)
        assertEquals(2, downloads.verifyCalls)
        val analysis = vm.state.value.items.single().analysis as LocalAnalysis.Ready
        assertEquals(Verdict.INSUFFICIENT_RESOURCES, analysis.assessment.verdict)
        // Even when resources recover, no engine means not loadable.
        assertTrue(analysis.runtime is RuntimeCompatibility.Incompatible)
    }

    @Test
    fun deviceFailureMeansUnknownNotPassing() = runTest(dispatcher) {
        device.fail = true
        downloads.rows.value = listOf(completedGguf())
        val vm = vm()
        advanceUntilIdle()
        val analysis = vm.state.value.items.single().analysis as LocalAnalysis.Ready
        assertEquals(Verdict.UNKNOWN, analysis.assessment.verdict)
        assertTrue(analysis.assessment.has(FindingCode.RAM_UNKNOWN))
    }

    @Test
    fun otherRepositoriesAndPathsStayDistinct() = runTest(dispatcher) {
        downloads.rows.value = listOf(
            row("m.gguf", "PAUSED", repo = "a/one"),
            row("m.gguf", "PAUSED", repo = "b/two")
        )
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf("a/one", "b/two"), vm.state.value.items.map { it.repoId })
        assertEquals(listOf("one", "two"), vm.state.value.items.map { it.repoName })
    }

    @Test
    fun fileNameAndSizeComeFromTheRecord() = runTest(dispatcher) {
        downloads.rows.value = listOf(row("sub/dir/model-Q8_0.gguf", "QUEUED", size = null))
        val vm = vm()
        advanceUntilIdle()
        val item = vm.state.value.items.single()
        assertEquals("model-Q8_0.gguf", item.fileName)
        assertNull(item.sizeBytes) // unknown stays unknown
    }
}
