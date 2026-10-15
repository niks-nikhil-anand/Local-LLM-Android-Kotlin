package com.example.localai.explore

import com.example.localai.data.catalog.CatalogAccess
import com.example.localai.data.catalog.CatalogError
import com.example.localai.data.catalog.CatalogResult
import com.example.localai.data.catalog.HfRepoFile
import com.example.localai.data.catalog.HfRepoId
import com.example.localai.data.catalog.IncompatibilityReason
import com.example.localai.data.catalog.RuntimeCompatibility
import com.example.localai.data.compat.FindingCode
import com.example.localai.data.compat.RuntimeProfile
import com.example.localai.data.compat.Verdict
import com.example.localai.data.compat.toRuntimeCompatibility
import com.example.localai.data.gguf.GgufParser
import com.example.localai.data.hf.GgufHeaderInspector
import com.example.localai.data.hf.HeaderFetchFailure
import com.example.localai.data.hf.HeaderInspection
import com.example.localai.gguf.GgufBuilder
import com.example.localai.ui.screens.explore.Fetch
import com.example.localai.ui.screens.explore.HeaderCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import com.example.localai.ui.screens.explore.FilesState
import com.example.localai.ui.screens.explore.RepoDetailsViewModel
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepoDetailsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repo = HfRepoId("Qwen/Qwen2.5-0.5B-Instruct-GGUF")
    private val catalog = FakeCatalog()
    private val device = FakeDeviceProvider()
    private var clock: () -> Long = { 0L }
    private val inspections = mutableListOf<HfRepoFile>()
    private var inspectResult: suspend (HfRepoFile) -> HeaderInspection = { error("no inspection expected") }
    private val downloads = FakeDownloads()
    private val inspector = GgufHeaderInspector { file -> inspections += file; inspectResult(file) }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun file(path: String, size: Long? = 400_000_000L) = HfRepoFile(repo, "main", path, size, null)

    private fun vm() = RepoDetailsViewModel(repo, catalog, device, inspector, downloads, clock = { clock() }, analysisDispatcher = dispatcher)

    private fun RepoDetailsViewModel.ready(): FilesState.Ready =
        state.value.files as? FilesState.Ready ?: error("Expected Ready but was ${state.value.files}")

    @Test
    fun realFileListIsClassifiedAndEveryVariantEvaluated() = runTest(dispatcher) {
        catalog.onFiles = {
            CatalogResult.Success(
                listOf(
                    file("qwen2.5-0.5b-instruct-q4_k_m.gguf", 397_808_192),
                    file("qwen2.5-0.5b-instruct-q8_0.gguf", 675_710_816),
                    file("mmproj-qwen-f16.gguf", 100_000_000),
                    file("model.safetensors"),
                    file("README.md", 4856)
                )
            )
        }
        val vm = vm()
        advanceUntilIdle()
        val ready = vm.ready()
        assertEquals(2, ready.variants.size)
        assertEquals(1, ready.analysis.projectors.size)
        assertEquals(1, ready.analysis.otherFormatWeights.size)
        // Without headers nothing can pass: a .gguf name proves nothing.
        assertTrue(ready.variants.all { it.assessment.verdict == Verdict.UNKNOWN })
        assertTrue(ready.variants.all { it.assessment.has(FindingCode.HEADER_NOT_INSPECTED) })
        assertTrue(ready.variants.all { it.assessment.has(FindingCode.VISION_UNAVAILABLE).not() }) // needs metadata
        // And with no engine nothing is loadable.
        ready.variants.forEach {
            assertEquals(
                RuntimeCompatibility.Incompatible(IncompatibilityReason.NO_RUNTIME),
                it.assessment.toRuntimeCompatibility(RuntimeProfile.DEFAULT_ASSUMED)
            )
        }
        assertEquals(8 * GB, vm.state.value.device?.totalRamBytes)
        assertEquals(1, catalog.fileCalls.size)
        assertEquals(1, device.snapshots)
    }

    @Test
    fun repositoryWithoutGgufFiles() = runTest(dispatcher) {
        catalog.onFiles = { CatalogResult.Success(listOf(file("model.safetensors"), file("config.json", 100))) }
        val vm = vm()
        advanceUntilIdle()
        val ready = vm.ready()
        assertTrue(ready.variants.isEmpty())
        assertEquals(1, ready.analysis.otherFormatWeights.size)
    }

    @Test
    fun incompleteShardSetIsIncompatible() = runTest(dispatcher) {
        catalog.onFiles = { CatalogResult.Success(listOf(file("big-Q4_K_M-00001-of-00003.gguf"), file("big-Q4_K_M-00003-of-00003.gguf"))) }
        val vm = vm()
        advanceUntilIdle()
        val assessment = vm.ready().variants.single().assessment
        assertEquals(Verdict.INCOMPATIBLE, assessment.verdict)
        assertTrue(assessment.has(FindingCode.SHARDS_MISSING))
    }

    @Test
    fun modelLargerThanRamIsInsufficientResources() = runTest(dispatcher) {
        catalog.onFiles = { CatalogResult.Success(listOf(file("huge-Q4_K_M.gguf", 9 * GB))) }
        val vm = vm()
        advanceUntilIdle()
        assertEquals(Verdict.INSUFFICIENT_RESOURCES, vm.ready().variants.single().assessment.verdict)
    }

    @Test
    fun variantsAreOrderedBestVerdictFirst() = runTest(dispatcher) {
        catalog.onFiles = {
            CatalogResult.Success(listOf(file("huge-Q4_K_M.gguf", 9 * GB), file("a-00001-of-00002.gguf"), file("small-Q4_K_M.gguf", 100)))
        }
        val vm = vm()
        advanceUntilIdle()
        assertEquals(
            listOf(Verdict.UNKNOWN, Verdict.INSUFFICIENT_RESOURCES, Verdict.INCOMPATIBLE),
            vm.ready().variants.map { it.assessment.verdict }
        )
    }

    @Test
    fun fileListFailureCanBeRetried() = runTest(dispatcher) {
        catalog.onFiles = { CatalogResult.Failure(CatalogError.Malformed("page 2")) }
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.state.value.files is FilesState.FetchFailed)

        catalog.onFiles = { CatalogResult.Success(listOf(file("m-Q4_K_M.gguf"))) }
        vm.retryFiles()
        advanceUntilIdle()
        assertEquals(1, vm.ready().variants.size)
        assertEquals(2, catalog.fileCalls.size)
    }

    @Test
    fun summaryFailureDoesNotHideTheFiles() = runTest(dispatcher) {
        catalog.onDetails = { CatalogResult.Failure(CatalogError.RateLimited(60)) }
        catalog.onFiles = { CatalogResult.Success(listOf(file("m-Q4_K_M.gguf"))) }
        val vm = vm()
        advanceUntilIdle()
        assertEquals(Fetch.Failed(CatalogError.RateLimited(60)), vm.state.value.summary)
        assertEquals(1, vm.ready().variants.size)

        catalog.onDetails = { CatalogResult.Success(summary(it.value)) }
        vm.retrySummary()
        advanceUntilIdle()
        assertTrue(vm.state.value.summary is Fetch.Ready)
    }

    @Test
    fun gatedRepositoryIsReportedAsGated() = runTest(dispatcher) {
        catalog.onDetails = { CatalogResult.Success(summary(it.value, CatalogAccess.Gated("manual"))) }
        catalog.onFiles = { CatalogResult.Failure(CatalogError.AccessRequired) }
        val vm = vm()
        advanceUntilIdle()
        assertEquals(CatalogAccess.Gated("manual"), (vm.state.value.summary as Fetch.Ready).value.access)
        assertEquals(FilesState.FetchFailed(CatalogError.AccessRequired), vm.state.value.files)
    }

    @Test
    fun deviceProviderFailureGivesUnknownNeverAPass() = runTest(dispatcher) {
        device.fail = true
        catalog.onFiles = { CatalogResult.Success(listOf(file("m-Q4_K_M.gguf"))) }
        val vm = vm()
        advanceUntilIdle()
        val assessment = vm.ready().variants.single().assessment
        assertEquals(Verdict.UNKNOWN, assessment.verdict)
        assertTrue(assessment.has(FindingCode.RAM_UNKNOWN))
        assertTrue(assessment.has(FindingCode.STORAGE_UNKNOWN))
        assertEquals(null, vm.state.value.device?.totalRamBytes)
    }

    @Test
    fun recheckMeasuresAgainWithoutRefetchingFiles() = runTest(dispatcher) {
        catalog.onFiles = { CatalogResult.Success(listOf(file("m-Q4_K_M.gguf", 3 * GB))) }
        val vm = vm()
        advanceUntilIdle()
        assertEquals(Verdict.UNKNOWN, vm.ready().variants.single().assessment.verdict)

        device.readings = normalDevice.copy(availableStorageBytes = 1 * GB) // storage filled up meanwhile
        vm.recheckDevice()
        advanceUntilIdle()
        val assessment = vm.ready().variants.single().assessment
        assertEquals(Verdict.INSUFFICIENT_RESOURCES, assessment.verdict)
        assertTrue(assessment.has(FindingCode.INSUFFICIENT_STORAGE))
        assertEquals(1, device.refreshes)
        assertEquals(1, catalog.fileCalls.size)
        assertFalse(vm.state.value.isRecheckingDevice)
    }

    @Test
    fun staleMeasurementsAreReportedAsStale() = runTest(dispatcher) {
        catalog.onFiles = { CatalogResult.Success(listOf(file("m-Q4_K_M.gguf"))) }
        clock = { 0L }
        val provider = FakeDeviceProvider(clock = { 0L })
        val vm = RepoDetailsViewModel(repo, catalog, provider, inspector, downloads, clock = { 120_000L }, analysisDispatcher = dispatcher)
        advanceUntilIdle()
        assertTrue(vm.ready().variants.single().assessment.has(FindingCode.RESOURCES_STALE))
    }

    @Test
    fun analysisFailureShowsNoCompatibilityResult() = runTest(dispatcher) {
        catalog.onFiles = { CatalogResult.Success(listOf(file("m-Q4_K_M.gguf"))) }
        clock = { throw IllegalStateException("clock broke") }
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.state.value.files is FilesState.AnalysisFailed)

        clock = { 0L }
        vm.retryFiles()
        advanceUntilIdle()
        assertEquals(1, vm.ready().variants.size)
        assertEquals(1, catalog.fileCalls.size) // files were reused
    }

    @Test
    fun loadsEachRepositoryOnlyOnce() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.retrySummary() // not failed: ignored
        vm.retryFiles() // already ready: ignored
        advanceUntilIdle()
        assertEquals(1, catalog.detailCalls.size)
        assertEquals(1, catalog.fileCalls.size)
    }

    // ---- Header inspection (Step 6) ----

    private fun singleTinyModel(): ByteArray {
        val bytes = GgufBuilder.tinyLlama().file()
        catalog.onFiles = { CatalogResult.Success(listOf(file("tiny-Q4_K_M.gguf", bytes.size.toLong()), file("other-Q8_0.gguf"))) }
        return bytes
    }

    @Test
    fun nothingIsInspectedUntilRequested() = runTest(dispatcher) {
        singleTinyModel()
        val vm = vm()
        advanceUntilIdle()
        assertTrue(inspections.isEmpty())
        assertTrue(vm.ready().variants.all { it.header == null })
    }

    @Test
    fun aRequestedInspectionFeedsTheRealHeaderIntoTheEvaluator() = runTest(dispatcher) {
        val bytes = singleTinyModel()
        inspectResult = { HeaderInspection.Inspected(GgufParser.parse(bytes), bytesRead = bytes.size.toLong(), fileSizeBytes = bytes.size.toLong(), rangeSupported = true) }
        val vm = vm()
        advanceUntilIdle()
        vm.inspectHeader("tiny-Q4_K_M.gguf")
        advanceUntilIdle()
        assertEquals(listOf("tiny-Q4_K_M.gguf"), inspections.map { it.path }) // only the requested variant
        val tiny = vm.ready().variants.first { it.variant.id == "tiny-Q4_K_M.gguf" }
        assertEquals(Verdict.MEETS_REQUIREMENTS, tiny.assessment.verdict)
        assertFalse(tiny.assessment.has(FindingCode.HEADER_NOT_INSPECTED))
        assertEquals("llama", (tiny.header as HeaderCheck.Done).info?.architecture)
        assertTrue(tiny.assessment.memory!!.kvCacheBytes != null)
        // Passing checks still does not make anything loadable without an engine.
        assertEquals(
            RuntimeCompatibility.Incompatible(IncompatibilityReason.NO_RUNTIME),
            tiny.assessment.toRuntimeCompatibility(RuntimeProfile.DEFAULT_ASSUMED)
        )
        val other = vm.ready().variants.first { it.variant.id == "other-Q8_0.gguf" }
        assertTrue(other.assessment.has(FindingCode.HEADER_NOT_INSPECTED))
    }

    @Test
    fun aFailedFetchIsUnknownWithItsReasonAndCanBeRetried() = runTest(dispatcher) {
        val bytes = singleTinyModel()
        inspectResult = { HeaderInspection.FetchFailed(HeaderFetchFailure.ACCESS_REQUIRED, "HTTP 401") }
        val vm = vm()
        advanceUntilIdle()
        vm.inspectHeader("tiny-Q4_K_M.gguf")
        advanceUntilIdle()
        val failed = vm.ready().variants.first { it.variant.id == "tiny-Q4_K_M.gguf" }.assessment
        assertEquals(Verdict.UNKNOWN, failed.verdict)
        assertTrue(failed.has(FindingCode.HEADER_FETCH_FAILED))
        assertTrue(failed.findings.first { it.code == FindingCode.HEADER_FETCH_FAILED }.message.contains("gated or private"))

        inspectResult = { HeaderInspection.Inspected(GgufParser.parse(bytes), bytes.size.toLong(), bytes.size.toLong(), true) }
        vm.inspectHeader("tiny-Q4_K_M.gguf")
        advanceUntilIdle()
        assertEquals(Verdict.MEETS_REQUIREMENTS, vm.ready().variants.first { it.variant.id == "tiny-Q4_K_M.gguf" }.assessment.verdict)
        assertEquals(2, inspections.size)
    }

    @Test
    fun anIncompleteHeaderNeverPasses() = runTest(dispatcher) {
        val bytes = singleTinyModel()
        inspectResult = { HeaderInspection.Inspected(GgufParser.parse(bytes, maxHeaderBytes = 64), 64, bytes.size.toLong(), true) }
        val vm = vm()
        advanceUntilIdle()
        vm.inspectHeader("tiny-Q4_K_M.gguf")
        advanceUntilIdle()
        val assessment = vm.ready().variants.first { it.variant.id == "tiny-Q4_K_M.gguf" }.assessment
        assertEquals(Verdict.UNKNOWN, assessment.verdict)
        assertTrue(assessment.has(FindingCode.HEADER_INCOMPLETE))
    }

    @Test
    fun repeatedTapsStartOneInspection() = runTest(dispatcher) {
        val bytes = singleTinyModel()
        val gate = CompletableDeferred<Unit>()
        inspectResult = { gate.await(); HeaderInspection.Inspected(GgufParser.parse(bytes), 1, null, true) }
        val vm = vm()
        advanceUntilIdle()
        repeat(4) { vm.inspectHeader("tiny-Q4_K_M.gguf") }
        runCurrent()
        assertEquals(HeaderCheck.Running, vm.state.value.headerChecks["tiny-Q4_K_M.gguf"])
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, inspections.size)
    }

    @Test
    fun deviceRecheckKeepsInspectionResultsWithoutRefetching() = runTest(dispatcher) {
        val bytes = singleTinyModel()
        inspectResult = { HeaderInspection.Inspected(GgufParser.parse(bytes), bytes.size.toLong(), null, true) }
        val vm = vm()
        advanceUntilIdle()
        vm.inspectHeader("tiny-Q4_K_M.gguf")
        advanceUntilIdle()
        device.readings = normalDevice.copy(isLowMemory = true)
        vm.recheckDevice()
        advanceUntilIdle()
        val assessment = vm.ready().variants.first { it.variant.id == "tiny-Q4_K_M.gguf" }.assessment
        assertEquals(Verdict.INSUFFICIENT_RESOURCES, assessment.verdict)
        assertFalse(assessment.has(FindingCode.HEADER_NOT_INSPECTED))
        assertEquals(1, inspections.size)
    }

    @Test
    fun anInspectorThatThrowsIsAFailedFetchNotACrash() = runTest(dispatcher) {
        singleTinyModel()
        inspectResult = { throw IllegalStateException("socket exploded") }
        val vm = vm()
        advanceUntilIdle()
        vm.inspectHeader("tiny-Q4_K_M.gguf")
        advanceUntilIdle()
        val check = vm.state.value.headerChecks["tiny-Q4_K_M.gguf"] as HeaderCheck.Done
        assertTrue(check.inspection is HeaderInspection.FetchFailed)
        assertEquals(Verdict.UNKNOWN, vm.ready().variants.first { it.variant.id == "tiny-Q4_K_M.gguf" }.assessment.verdict)
    }

    @Test
    fun unknownVariantIdsAreIgnored() = runTest(dispatcher) {
        singleTinyModel()
        val vm = vm()
        advanceUntilIdle()
        vm.inspectHeader("does-not-exist.gguf")
        advanceUntilIdle()
        assertTrue(inspections.isEmpty())
    }

    // ---- Downloads (Step 7) ----

    private fun entity(path: String, state: String, bytes: Long = 0, size: Long? = 400_000_000L, failureKind: String? = null) =
        com.example.localai.data.local.LocalModelEntity(
            id = "${repo.value}/main/$path", repoId = repo.value, revision = "main", path = path,
            expectedSizeBytes = size, expectedSha256 = null, downloadedBytes = bytes, state = state,
            failureReason = null, updatedAtMillis = 0, failureKind = failureKind
        )

    @Test
    fun downloadActionsGoThroughTheController() = runTest(dispatcher) {
        catalog.onFiles = { CatalogResult.Success(listOf(file("m-Q4_K_M.gguf"))) }
        val vm = vm()
        advanceUntilIdle()
        vm.download("m-Q4_K_M.gguf")
        vm.pauseDownload("m-Q4_K_M.gguf")
        vm.retryDownload("m-Q4_K_M.gguf")
        vm.cancelDownload("m-Q4_K_M.gguf")
        vm.deleteDownload("m-Q4_K_M.gguf")
        advanceUntilIdle()
        val id = "${repo.value}/main/m-Q4_K_M.gguf"
        assertEquals(listOf("enqueue:m-Q4_K_M.gguf", "pause:$id", "retry:$id", "cancel:$id", "delete:$id"), downloads.calls)
    }

    @Test
    fun persistedAndLiveDownloadStateIsShownPerFile() = runTest(dispatcher) {
        catalog.onFiles = { CatalogResult.Success(listOf(file("a-Q4_K_M.gguf"), file("b-Q8_0.gguf"))) }
        val vm = vm()
        advanceUntilIdle()
        downloads.rows.value = listOf(
            entity("a-Q4_K_M.gguf", "DOWNLOADING", bytes = 1_000),
            entity("b-Q8_0.gguf", "FAILED", failureKind = "ACCESS_REQUIRED"),
            entity("other.gguf", "COMPLETED").copy(repoId = "someone/else") // another repository
        )
        downloads.liveProgress.value = mapOf("${repo.value}/main/a-Q4_K_M.gguf" to 5_000L)
        advanceUntilIdle()
        val map = vm.state.value.downloads
        assertEquals(com.example.localai.data.catalog.DownloadStatus.Downloading(5_000, 400_000_000L), map["a-Q4_K_M.gguf"])
        val failed = map["b-Q8_0.gguf"] as com.example.localai.data.catalog.DownloadStatus.Failed
        assertFalse(failed.retryable)
        assertEquals(2, map.size)
    }

    @Test
    fun aCompletedDownloadCountsAsPresentButNeverAsLoadable() = runTest(dispatcher) {
        catalog.onFiles = { CatalogResult.Success(listOf(file("m-Q4_K_M.gguf", 400_000_000L))) }
        device.readings = normalDevice.copy(availableStorageBytes = 1 * GB / 2) // too little for a new download
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.ready().variants.single().assessment.has(FindingCode.INSUFFICIENT_STORAGE))

        downloads.rows.value = listOf(entity("m-Q4_K_M.gguf", "COMPLETED", bytes = 400_000_000L))
        advanceUntilIdle()
        val assessment = vm.ready().variants.single().assessment
        assertFalse(assessment.has(FindingCode.INSUFFICIENT_STORAGE)) // nothing left to download
        assertEquals(0L, assessment.remainingDownloadBytes)
        assertEquals(
            RuntimeCompatibility.Incompatible(IncompatibilityReason.NO_RUNTIME),
            assessment.toRuntimeCompatibility(RuntimeProfile.DEFAULT_ASSUMED)
        )
    }

    @Test
    fun insufficientStorageExplainsWhyTheDownloadDidNotStart() = runTest(dispatcher) {
        catalog.onFiles = { CatalogResult.Success(listOf(file("m-Q4_K_M.gguf"))) }
        downloads.enqueueResult = com.example.localai.data.download.EnqueueResult.InsufficientStorage(400_000_000L, 100_000_000L)
        val vm = vm()
        advanceUntilIdle()
        vm.download("m-Q4_K_M.gguf")
        advanceUntilIdle()
        assertTrue(vm.state.value.downloadMessage!!.startsWith("Not enough storage"))
        vm.dismissDownloadMessage()
        assertEquals(null, vm.state.value.downloadMessage)
    }

    @Test
    fun downloadsAreOfferedOnlyForEligibleVariants() = runTest(dispatcher) {
        catalog.onFiles = {
            CatalogResult.Success(listOf(file("m-Q4_K_M.gguf"), file("s-00001-of-00002.gguf"), file("s-00002-of-00002.gguf")))
        }
        val vm = vm()
        advanceUntilIdle()
        val variants = vm.ready().variants
        val single = variants.first { !it.variant.isSharded }
        val sharded = variants.first { it.variant.isSharded }
        assertEquals(null, com.example.localai.ui.screens.explore.downloadBlockReason(single, CatalogAccess.Public))
        assertTrue(com.example.localai.ui.screens.explore.downloadBlockReason(sharded, CatalogAccess.Public)!!.contains("multi-part"))
        assertTrue(com.example.localai.ui.screens.explore.downloadBlockReason(single, CatalogAccess.Gated("manual"))!!.contains("gated"))
    }
}
