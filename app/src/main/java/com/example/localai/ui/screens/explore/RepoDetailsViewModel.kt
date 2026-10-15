package com.example.localai.ui.screens.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.localai.data.catalog.CatalogError
import com.example.localai.data.catalog.CatalogResult
import com.example.localai.data.catalog.HfModelSummary
import com.example.localai.data.catalog.HfRepoFile
import com.example.localai.data.catalog.HfRepoId
import com.example.localai.data.catalog.ModelCatalogRepository
import com.example.localai.data.compat.CompatibilityAssessment
import com.example.localai.data.compat.CompatibilityEvaluator
import com.example.localai.data.compat.ModelVariant
import com.example.localai.data.compat.RepoAnalysis
import com.example.localai.data.compat.RepoAnalyzer
import com.example.localai.data.compat.RuntimeProfile
import com.example.localai.data.device.DeviceResourceProvider
import com.example.localai.data.device.DeviceResourceSnapshot
import com.example.localai.data.device.RawDeviceReadings
import com.example.localai.data.catalog.DownloadStatus
import com.example.localai.data.download.DownloadController
import com.example.localai.data.download.EnqueueResult
import com.example.localai.data.download.ModelDownloadManager
import com.example.localai.data.download.toDownloadStatus
import com.example.localai.data.local.LocalDownloadState
import com.example.localai.data.catalog.formatBytes
import kotlinx.coroutines.flow.combine
import com.example.localai.data.gguf.GgufModelInfo
import com.example.localai.data.gguf.GgufParseResult
import com.example.localai.data.hf.GgufHeaderInspector
import com.example.localai.data.hf.HeaderInspection
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface Fetch<out T> {
    data object Loading : Fetch<Nothing>
    data class Ready<T>(val value: T) : Fetch<T>
    data class Failed(val error: CatalogError) : Fetch<Nothing>
}

/** Header inspection of one variant's primary file, started only when the user asks for it. */
sealed interface HeaderCheck {
    data object Running : HeaderCheck

    /** [info] is set only when the header was read and parsed successfully. */
    data class Done(val inspection: HeaderInspection, val info: GgufModelInfo?) : HeaderCheck
}

data class VariantAssessment(
    val variant: ModelVariant,
    val assessment: CompatibilityAssessment,
    val header: HeaderCheck? = null
)

sealed interface FilesState {
    data object Loading : FilesState

    /** The file tree could not be fetched. */
    data class FetchFailed(val error: CatalogError) : FilesState

    /** Files were fetched but analysis threw; no compatibility result is shown. */
    data class AnalysisFailed(val message: String) : FilesState

    data class Ready(val analysis: RepoAnalysis, val variants: List<VariantAssessment>) : FilesState
}

data class RepoDetailsUiState(
    val repoId: HfRepoId,
    val summary: Fetch<HfModelSummary> = Fetch.Loading,
    val files: FilesState = FilesState.Loading,
    /** The measurements the shown verdicts were computed with. */
    val device: DeviceResourceSnapshot? = null,
    val isRecheckingDevice: Boolean = false,
    /** Header checks by variant id. Absent means not requested. */
    val headerChecks: Map<String, HeaderCheck> = emptyMap(),
    /** Persisted download state by file path, for files of this repository that have a record. */
    val downloads: Map<String, DownloadStatus> = emptyMap(),
    /** One-off explanation when a download could not be started (e.g. not enough storage). */
    val downloadMessage: String? = null,
    val profile: RuntimeProfile
)

/**
 * One repository opened from Explore. Metadata and the file tree are fetched once, on demand;
 * files are classified and every variant is evaluated against a measured device snapshot.
 * A variant's GGUF header is read (bounded Range requests) only when the user asks for it; until
 * then its evaluation runs without metadata and is at best UNKNOWN, because a file name never
 * yields a passing verdict.
 */
class RepoDetailsViewModel(
    repoId: HfRepoId,
    private val catalog: ModelCatalogRepository,
    private val deviceResources: DeviceResourceProvider,
    private val headerInspector: GgufHeaderInspector,
    private val downloads: DownloadController,
    private val clock: () -> Long,
    private val profile: RuntimeProfile = RuntimeProfile.DEFAULT_ASSUMED,
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val _state = MutableStateFlow(RepoDetailsUiState(repoId = repoId, profile = profile))
    val state: StateFlow<RepoDetailsUiState> = _state.asStateFlow()

    private var files: List<HfRepoFile>? = null
    private var summaryJob: Job? = null
    private var filesJob: Job? = null
    // Each inspection reads up to a few MB; two at a time keeps the Hub and the connection calm.
    private val inspectionPermits = Semaphore(MAX_PARALLEL_INSPECTIONS)

    /** Verified on-disk sizes of this repository's completed downloads, by path. */
    private var completedSizes: Map<String, Long> = emptyMap()

    init {
        loadSummary()
        loadFiles()
        observeDownloads()
    }

    fun download(variantId: String) = withVariant(variantId) { variant ->
        val result = downloads.enqueue(variant.primaryFile)
        _state.update { it.copy(downloadMessage = enqueueMessage(result)) }
    }

    fun pauseDownload(variantId: String) = withVariant(variantId) { downloads.pause(ModelDownloadManager.idFor(it.primaryFile)) }

    fun cancelDownload(variantId: String) = withVariant(variantId) { downloads.cancel(ModelDownloadManager.idFor(it.primaryFile)) }

    fun retryDownload(variantId: String) = withVariant(variantId) { variant ->
        val result = downloads.retry(ModelDownloadManager.idFor(variant.primaryFile))
        _state.update { it.copy(downloadMessage = enqueueMessage(result)) }
    }

    fun deleteDownload(variantId: String) = withVariant(variantId) { downloads.delete(ModelDownloadManager.idFor(it.primaryFile)) }

    fun dismissDownloadMessage() = _state.update { it.copy(downloadMessage = null) }

    private fun withVariant(variantId: String, action: suspend (ModelVariant) -> Unit) {
        val ready = _state.value.files as? FilesState.Ready ?: return
        val variant = ready.variants.firstOrNull { it.variant.id == variantId }?.variant ?: return
        viewModelScope.launch { action(variant) }
    }

    private fun enqueueMessage(result: EnqueueResult): String? = when (result) {
        EnqueueResult.Started, EnqueueResult.AlreadyActive, EnqueueResult.AlreadyDownloaded -> null
        is EnqueueResult.InsufficientStorage ->
            "Not enough storage: ${formatBytes(result.neededBytes)} plus a reserve is needed, ${formatBytes(result.freeBytes)} is free."
    }

    private fun observeDownloads() {
        val repo = _state.value.repoId.value
        viewModelScope.launch {
            combine(downloads.observe(), downloads.liveProgress) { rows, live -> rows to live }.collect { (rows, live) ->
                val mine = rows.filter { it.repoId == repo }
                _state.update { st -> st.copy(downloads = mine.associate { it.path to it.toDownloadStatus(live[it.id]) }) }
                val completed = mine.filter { it.state == LocalDownloadState.COMPLETED.name }.associate { it.path to it.downloadedBytes }
                if (completed != completedSizes) {
                    completedSizes = completed
                    if (_state.value.files is FilesState.Ready) evaluate(refreshDevice = false)
                }
            }
        }
    }

    fun retrySummary() {
        if (_state.value.summary !is Fetch.Failed) return
        loadSummary()
    }

    fun retryFiles() {
        val current = _state.value.files
        if (current is FilesState.Loading || current is FilesState.Ready) return
        if (current is FilesState.AnalysisFailed && files != null) {
            evaluate(refreshDevice = true)
        } else {
            loadFiles()
        }
    }

    /** Measures the device again and re-evaluates the already fetched files; no network request. */
    fun recheckDevice() {
        if (_state.value.isRecheckingDevice) return
        if (files == null) {
            retryFiles()
            return
        }
        evaluate(refreshDevice = true)
    }

    /** Reads the header of [variantId]'s primary file. Ignored while that check is running. */
    fun inspectHeader(variantId: String) {
        val ready = _state.value.files as? FilesState.Ready ?: return
        val variant = ready.variants.firstOrNull { it.variant.id == variantId }?.variant ?: return
        if (_state.value.headerChecks[variantId] == HeaderCheck.Running) return
        _state.update { it.copy(headerChecks = it.headerChecks + (variantId to HeaderCheck.Running)) }
        viewModelScope.launch {
            val inspection = inspectionPermits.withPermit {
                try {
                    headerInspector.inspect(variant.primaryFile)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    HeaderInspection.FetchFailed(
                        com.example.localai.data.hf.HeaderFetchFailure.NETWORK,
                        e.message ?: e.javaClass.simpleName
                    )
                }
            }
            val info = ((inspection as? HeaderInspection.Inspected)?.result as? GgufParseResult.Success)
                ?.let { runCatching { GgufModelInfo.from(it.header) }.getOrNull() }
            _state.update { it.copy(headerChecks = it.headerChecks + (variantId to HeaderCheck.Done(inspection, info))) }
            evaluate(refreshDevice = false)
        }
    }

    private fun loadSummary() {
        summaryJob?.cancel()
        _state.update { it.copy(summary = Fetch.Loading) }
        summaryJob = viewModelScope.launch {
            val result = catalog.modelDetails(_state.value.repoId)
            _state.update {
                it.copy(
                    summary = when (result) {
                        is CatalogResult.Success -> Fetch.Ready(result.value)
                        is CatalogResult.Failure -> Fetch.Failed(result.error)
                    }
                )
            }
        }
    }

    private fun loadFiles() {
        filesJob?.cancel()
        _state.update { it.copy(files = FilesState.Loading) }
        filesJob = viewModelScope.launch {
            when (val result = catalog.listFiles(_state.value.repoId)) {
                is CatalogResult.Failure -> _state.update { it.copy(files = FilesState.FetchFailed(result.error)) }
                is CatalogResult.Success -> {
                    files = result.value
                    runEvaluation(refreshDevice = false)
                }
            }
        }
    }

    private fun evaluate(refreshDevice: Boolean) {
        filesJob?.cancel()
        filesJob = viewModelScope.launch { runEvaluation(refreshDevice) }
    }

    private suspend fun runEvaluation(refreshDevice: Boolean) {
        val fileList = files ?: return
        _state.update { it.copy(isRecheckingDevice = refreshDevice) }
        val snapshot = measure(refreshDevice)
        val outcome = withContext(analysisDispatcher) {
            try {
                val analysis = RepoAnalyzer.analyze(fileList)
                val capabilities = snapshot.toCapabilities(clock())
                val checks = _state.value.headerChecks
                val variants = analysis.variants
                    .map { variant ->
                        val check = checks[variant.id]
                        val inspection = (check as? HeaderCheck.Done)?.inspection
                        val assessment = CompatibilityEvaluator.evaluate(
                            variant,
                            header = (inspection as? HeaderInspection.Inspected)?.result,
                            device = capabilities,
                            profile = profile,
                            // Only verified, completed files count as present; partial files would read as incomplete models.
                            localFileSizes = completedSizes.filterKeys { path -> variant.files.any { it.path == path } },
                            headerUnavailableReason = (inspection as? HeaderInspection.FetchFailed)?.reason?.description
                        )
                        VariantAssessment(variant, assessment, check)
                    }
                    .sortedWith(compareByDescending<VariantAssessment> { it.assessment.verdict.ordinal }
                        .thenBy { it.variant.totalSizeBytes ?: Long.MAX_VALUE })
                FilesState.Ready(analysis, variants)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                FilesState.AnalysisFailed(e.message ?: e.javaClass.simpleName)
            }
        }
        _state.update { current ->
            // Attach checks that changed while this evaluation ran; their re-evaluation follows.
            val merged = (outcome as? FilesState.Ready)?.let { ready ->
                ready.copy(variants = ready.variants.map { v -> v.copy(header = current.headerChecks[v.variant.id]) })
            } ?: outcome
            current.copy(files = merged, device = snapshot, isRecheckingDevice = false)
        }
    }

    private suspend fun measure(refresh: Boolean): DeviceResourceSnapshot = try {
        if (refresh) deviceResources.refresh() else deviceResources.snapshot()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        // Unknown measurements make every resource check UNCERTAIN; nothing is assumed.
        DeviceResourceSnapshot.from(RawDeviceReadings(unavailable = listOf("Device query failed: ${e.javaClass.simpleName}")), clock())
    }

    private companion object {
        const val MAX_PARALLEL_INSPECTIONS = 2
    }
}
