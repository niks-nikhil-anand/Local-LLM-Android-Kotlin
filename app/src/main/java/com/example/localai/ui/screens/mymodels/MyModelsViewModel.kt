package com.example.localai.ui.screens.mymodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.localai.data.catalog.DownloadStatus
import com.example.localai.data.catalog.HfRepoFile
import com.example.localai.data.catalog.HfRepoId
import com.example.localai.data.catalog.RuntimeCompatibility
import com.example.localai.data.compat.CompatibilityAssessment
import com.example.localai.data.compat.CompatibilityEvaluator
import com.example.localai.data.compat.RepoAnalyzer
import com.example.localai.data.compat.RuntimeProfile
import com.example.localai.data.compat.toRuntimeCompatibility
import com.example.localai.data.device.DeviceResourceProvider
import com.example.localai.data.device.DeviceResourceSnapshot
import com.example.localai.data.device.RawDeviceReadings
import com.example.localai.data.download.DownloadController
import com.example.localai.data.download.EnqueueResult
import com.example.localai.data.download.Verification
import com.example.localai.data.download.toDownloadStatus
import com.example.localai.data.gguf.GgufModelInfo
import com.example.localai.data.inference.ModelLoadStatus
import com.example.localai.data.inference.ModelSessionManager
import com.example.localai.data.gguf.GgufParseResult
import com.example.localai.data.gguf.GgufParser
import com.example.localai.data.local.LocalDownloadState
import com.example.localai.data.local.LocalModelEntity
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Compatibility of a downloaded file, judged from its real on-disk header. */
sealed interface LocalAnalysis {
    /** Not downloaded (or not verified) yet, so there is nothing local to inspect. */
    data object NotAvailable : LocalAnalysis
    data object Running : LocalAnalysis
    data class Ready(val assessment: CompatibilityAssessment, val info: GgufModelInfo?, val runtime: RuntimeCompatibility) : LocalAnalysis
    data class Failed(val message: String) : LocalAnalysis
}

/** One download record as shown in My Models. Every value comes from the record or the file. */
data class MyModelItem(
    val id: String,
    val repoId: String,
    val revision: String,
    val path: String,
    val status: DownloadStatus,
    /** Size from the repository listing, or the verified size once completed; null if unknown. */
    val sizeBytes: Long?,
    /** How a COMPLETED file was verified; null otherwise. */
    val verification: Verification?,
    val analysis: LocalAnalysis,
    /** Outcome of the latest load request for this file; null when none was made. */
    val load: ModelLoadStatus? = null,
    /** True when this file is the model currently loaded in the engine. */
    val isActive: Boolean = false
) {
    val fileName: String get() = path.substringAfterLast('/')
    val repoName: String get() = repoId.substringAfterLast('/')
}

data class MyModelsUiState(
    /** True until the download records have been read once (or reading failed). */
    val loading: Boolean = true,
    /** Set when the download records could not be read; never shown as "no models". */
    val error: String? = null,
    /** Whether the on-device engine is present; loading needs it. */
    val engineAvailable: Boolean = false,
    val items: List<MyModelItem> = emptyList(),
    val device: DeviceResourceSnapshot? = null,
    /** Verified model files on disk. */
    val completedBytes: Long = 0,
    /** Durable bytes of unfinished downloads. */
    val partialBytes: Long = 0,
    /** Shown after an action that could not run, e.g. a retry without enough storage. */
    val message: String? = null
)

/**
 * My Models backed by the persisted download records (Room) and the files in app storage, through
 * the existing [DownloadController]. Completed files are analysed locally: their GGUF header is
 * read from disk and passed to [CompatibilityEvaluator] with a measured device snapshot. Loadability
 * comes from [toRuntimeCompatibility]; with no engine installed nothing is loadable.
 */
class MyModelsViewModel(
    private val downloads: DownloadController,
    private val deviceResources: DeviceResourceProvider,
    private val clock: () -> Long,
    /** Shared engine owner; null only where no engine is wired (e.g. some tests). */
    private val session: ModelSessionManager? = null,
    private val profile: RuntimeProfile = if (session?.engineAvailable == true) RuntimeProfile.LLAMA_CPP else RuntimeProfile.DEFAULT_ASSUMED,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _state = MutableStateFlow(MyModelsUiState(engineAvailable = session?.engineAvailable == true))
    val state: StateFlow<MyModelsUiState> = _state.asStateFlow()

    /** Analyses keyed by record id and the verified size they were computed for. */
    private val analyses = HashMap<Pair<String, Long>, LocalAnalysis>()
    private var device: DeviceResourceSnapshot? = null
    private var rows: List<LocalModelEntity> = emptyList()
    private var live: Map<String, Long> = emptyMap()
    private var loadStatus: Map<String, ModelLoadStatus> = emptyMap()
    private var activeRecordId: String? = null

    init {
        viewModelScope.launch {
            // Repairs records whose file disappeared or changed size since completion.
            runCatching { downloads.verifyCompletedFiles() }
            device = measure(refresh = false)
            _state.update { it.copy(device = device) }
            observeRecords()
        }
        session?.let { s ->
            viewModelScope.launch {
                combine(s.loadStatus, s.active) { status, active -> status to active?.recordId }.collect { (status, activeId) ->
                    loadStatus = status
                    activeRecordId = activeId
                    publish()
                }
            }
        }
    }

    fun pause(id: String) = launch { downloads.pause(id) }
    fun cancel(id: String) = launch { downloads.cancel(id) }
    /** Deleting the loaded model unloads it first, so the engine never holds a deleted file. */
    fun delete(id: String) = launch {
        session?.release(id)
        downloads.delete(id)
    }

    /** Runs the pre-load checks and, if they pass, loads the model with the native engine. */
    fun loadModel(id: String) = launch {
        val row = rows.firstOrNull { it.id == id } ?: return@launch
        session?.load(row)
    }

    fun unloadModel() = launch { session?.unload() }

    fun retry(id: String) = launch {
        val result = downloads.retry(id)
        if (result is EnqueueResult.InsufficientStorage) {
            _state.update {
                it.copy(message = "Not enough storage to resume: ${com.example.localai.data.catalog.formatBytes(result.neededBytes)} " +
                    "plus a reserve is needed, ${com.example.localai.data.catalog.formatBytes(result.freeBytes)} is free.")
            }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private var recordsJob: Job? = null

    /** Follows the download records; a read failure becomes [MyModelsUiState.error]. */
    private fun observeRecords() {
        recordsJob?.cancel()
        recordsJob = viewModelScope.launch {
            combine(downloads.observe(), downloads.liveProgress) { r, l -> r to l }
                .catch { e ->
                    if (e is CancellationException) throw e
                    _state.update {
                        it.copy(loading = false, error = "Downloaded models could not be read: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                .collect { (r, l) ->
                    rows = r
                    live = l
                    publish()
                    analyseCompleted()
                }
        }
    }

    /** Measures the device again, re-checks the files and re-evaluates every completed model. */
    fun refresh() = launch {
        if (_state.value.error != null) {
            _state.update { it.copy(loading = true, error = null) }
            observeRecords()
        }
        runCatching { downloads.verifyCompletedFiles() }
        device = measure(refresh = true)
        analyses.clear()
        _state.update { it.copy(device = device) }
        publish()
        analyseCompleted()
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun publish() {
        // Same rule as Home: only completed records whose file exists with the verified size count.
        val summary = com.example.localai.data.download.InventorySummary.of(rows, downloads::localFile)
        val items = rows
            .map { row ->
                val status = row.toDownloadStatus(live[row.id])
                val completed = row.state == LocalDownloadState.COMPLETED.name
                MyModelItem(
                    id = row.id,
                    repoId = row.repoId,
                    revision = row.revision,
                    path = row.path,
                    status = status,
                    sizeBytes = if (completed) row.downloadedBytes else row.expectedSizeBytes ?: row.remoteTotalBytes,
                    verification = if (completed) row.verification?.let { runCatching { Verification.valueOf(it) }.getOrNull() } else null,
                    analysis = if (completed) analyses[row.id to row.downloadedBytes] ?: LocalAnalysis.Running else LocalAnalysis.NotAvailable,
                    load = loadStatus[row.id],
                    isActive = row.id == activeRecordId
                )
            }
            .sortedWith(compareBy<MyModelItem> { orderOf(it.status) }.thenBy { it.repoId.lowercase() }.thenBy { it.path })
        _state.update {
            it.copy(
                loading = false,
                error = null,
                items = items,
                completedBytes = summary.modelBytes,
                partialBytes = summary.partialBytes
            )
        }
    }

    private suspend fun analyseCompleted() {
        val pending = rows.filter { it.state == LocalDownloadState.COMPLETED.name && (it.id to it.downloadedBytes) !in analyses }
        if (pending.isEmpty()) return
        val snapshot = device ?: measure(refresh = false).also { device = it }
        for (row in pending) {
            analyses[row.id to row.downloadedBytes] = analyse(row, downloads.localFile(row), snapshot)
        }
        publish()
    }

    private suspend fun analyse(row: LocalModelEntity, file: File, snapshot: DeviceResourceSnapshot): LocalAnalysis =
        withContext(ioDispatcher) {
            try {
                if (!file.exists()) return@withContext LocalAnalysis.Failed("The file is missing from storage.")
                val header = BufferedInputStream(FileInputStream(file)).use { GgufParser.parse(it) }
                val repoFile = HfRepoFile(HfRepoId(row.repoId), row.revision, row.path, row.expectedSizeBytes ?: row.downloadedBytes, row.expectedSha256)
                val variant = RepoAnalyzer.analyze(listOf(repoFile)).variants.singleOrNull()
                    ?: return@withContext LocalAnalysis.Failed("The file is not a standalone GGUF model.")
                val assessment = CompatibilityEvaluator.evaluate(
                    variant = variant,
                    header = header,
                    device = snapshot.toCapabilities(clock()),
                    profile = profile,
                    localFileSizes = mapOf(row.path to file.length())
                )
                val info = (header as? GgufParseResult.Success)?.let { runCatching { GgufModelInfo.from(it.header) }.getOrNull() }
                LocalAnalysis.Ready(assessment, info, assessment.toRuntimeCompatibility(profile))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LocalAnalysis.Failed(e.message ?: e.javaClass.simpleName)
            }
        }

    private suspend fun measure(refresh: Boolean): DeviceResourceSnapshot = try {
        if (refresh) deviceResources.refresh() else deviceResources.snapshot()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        DeviceResourceSnapshot.from(RawDeviceReadings(unavailable = listOf("Device query failed: ${e.javaClass.simpleName}")), clock())
    }

    private fun orderOf(status: DownloadStatus): Int = when (status) {
        is DownloadStatus.Downloading, DownloadStatus.Verifying, DownloadStatus.Queued -> 0
        is DownloadStatus.Paused, is DownloadStatus.Failed -> 1
        DownloadStatus.Completed -> 2
        DownloadStatus.Cancelled, DownloadStatus.NotDownloaded -> 3
    }
}
