package com.example.localai.data.inference

import com.example.localai.data.catalog.HfRepoFile
import com.example.localai.data.catalog.HfRepoId
import com.example.localai.data.compat.CompatibilityEvaluator
import com.example.localai.data.compat.DeviceCapabilities
import com.example.localai.data.compat.FindingCode
import com.example.localai.data.compat.RepoAnalyzer
import com.example.localai.data.compat.RuntimeProfile
import com.example.localai.data.compat.Severity
import com.example.localai.data.device.DeviceResourceProvider
import com.example.localai.data.device.DeviceResourceSnapshot
import com.example.localai.data.device.RawDeviceReadings
import com.example.localai.data.download.DownloadController
import com.example.localai.data.gguf.GgufFailure
import com.example.localai.data.gguf.GgufModelInfo
import com.example.localai.data.gguf.GgufParseResult
import com.example.localai.data.gguf.GgufParser
import com.example.localai.data.local.LocalDownloadState
import com.example.localai.data.local.LocalModelEntity
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Checks a downloaded model before the engine is asked to load it. Everything here is read from
 * the local record and file; nothing touches the network. Passing means "worth trying", not
 * "will work": only a successful native load shows that.
 */
object ModelLoadPreflight {

    sealed interface Result {
        /** [notes] are non-blocking caveats (unverified architecture, size-only verification...). */
        data class Pass(val info: GgufModelInfo, val contextLength: Int, val notes: List<String>) : Result
        data class Reject(val reason: String) : Result
    }

    /** Findings that mean "do not even try": the file or the device rules the load out. */
    private val MEMORY_BLOCKERS = setOf(FindingCode.INSUFFICIENT_MEMORY, FindingCode.LOW_MEMORY_STATE)

    fun check(
        row: LocalModelEntity,
        file: File,
        device: DeviceCapabilities,
        profile: RuntimeProfile,
        requestedContext: Int
    ): Result {
        if (row.state != LocalDownloadState.COMPLETED.name) return Result.Reject("The download is not complete.")
        if (!file.isFile || !file.canRead()) return Result.Reject("The model file is missing or cannot be read.")
        val expected = row.expectedSizeBytes ?: row.downloadedBytes
        if (file.length() != expected) {
            return Result.Reject("The file is ${file.length()} bytes but ${expected} were verified; download it again.")
        }

        val header = BufferedInputStream(FileInputStream(file)).use { GgufParser.parse(it) }
        if (header is GgufParseResult.Failure) {
            return Result.Reject(
                when (header.kind) {
                    GgufFailure.NOT_GGUF -> "The file is not a GGUF model."
                    GgufFailure.UNSUPPORTED_VERSION, GgufFailure.UNSUPPORTED_ENDIANNESS -> "Unsupported GGUF format: ${header.message}"
                    else -> "The model's header is invalid: ${header.message}"
                }
            )
        }
        val info = GgufModelInfo.from((header as GgufParseResult.Success).header)

        val repoFile = HfRepoFile(HfRepoId(row.repoId), row.revision, row.path, expected, row.expectedSha256)
        val variant = RepoAnalyzer.analyze(listOf(repoFile)).variants.singleOrNull()
            ?: return Result.Reject("The file is not a standalone GGUF model.")
        val context = minOf(requestedContext.toLong(), info.contextLength ?: requestedContext.toLong()).toInt()
        val assessment = CompatibilityEvaluator.evaluate(
            variant = variant,
            header = header,
            device = device,
            profile = profile.copy(contextTokens = context),
            localFileSizes = mapOf(row.path to file.length())
        )
        assessment.findings.firstOrNull { it.severity == Severity.BLOCKER }?.let { return Result.Reject(it.message) }
        assessment.findings.firstOrNull { it.code in MEMORY_BLOCKERS }?.let { return Result.Reject(it.message) }

        val notes = assessment.findings
            .filter { it.severity == Severity.UNCERTAIN || it.severity == Severity.WARNING }
            .map { it.message }
            .toMutableList()
        if (row.verification != "SHA256") notes += "This file was verified by size only (no checksum was available)."
        return Result.Pass(info, context, notes)
    }
}

/** Per-model outcome of the most recent load request, kept in memory for the UI. */
sealed interface ModelLoadStatus {
    data object Checking : ModelLoadStatus
    data object Loading : ModelLoadStatus
    data class Loaded(val model: LoadedModel, val notes: List<String>) : ModelLoadStatus

    /** [rejected] = refused by the pre-load checks; otherwise the native engine failed. */
    data class Failed(val message: String, val rejected: Boolean) : ModelLoadStatus
}

/** The model currently loaded in the engine, as shown to the user. No native handles here. */
data class ActiveModel(val recordId: String, val repoId: String, val fileName: String, val loaded: LoadedModel)

/**
 * The single owner of the inference engine for the whole process, shared by My Models (load,
 * unload) and Chat (generate, stop). Loading another model releases the previous one first (the
 * engine guarantees that); a failed load leaves the files untouched.
 */
class ModelSessionManager(
    private val engine: InferenceEngine,
    private val downloads: DownloadController,
    private val deviceResources: DeviceResourceProvider,
    private val clock: () -> Long,
    private val profile: RuntimeProfile = RuntimeProfile.LLAMA_CPP,
    private val config: InferenceConfig = InferenceConfig(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutex = Mutex()
    private val _active = MutableStateFlow<ActiveModel?>(null)
    private val _status = MutableStateFlow<Map<String, ModelLoadStatus>>(emptyMap())

    val engineAvailable: Boolean get() = engine.isAvailable
    val engineState: StateFlow<EngineState> get() = engine.state
    val active: StateFlow<ActiveModel?> = _active.asStateFlow()
    val loadStatus: StateFlow<Map<String, ModelLoadStatus>> = _status.asStateFlow()

    /** Checks and loads [row]. Returns true when the native engine loaded it. */
    suspend fun load(row: LocalModelEntity): Boolean = mutex.withLock {
        val id = row.id
        if (_active.value?.recordId == id) return true
        if (!engine.isAvailable) {
            setStatus(id, ModelLoadStatus.Failed(InferenceErrorKind.ENGINE_UNAVAILABLE.description, rejected = true))
            return false
        }
        setStatus(id, ModelLoadStatus.Checking)
        val file = downloads.localFile(row)
        val preflight = try {
            val snapshot = measure()
            withContext(ioDispatcher) {
                ModelLoadPreflight.check(row, file, snapshot.toCapabilities(clock()), profile, config.contextLength)
            }
        } catch (e: CancellationException) {
            clearStatus(id)
            throw e
        } catch (e: Exception) {
            ModelLoadPreflight.Result.Reject("The model could not be checked: ${e.message ?: e.javaClass.simpleName}")
        }
        if (preflight is ModelLoadPreflight.Result.Reject) {
            setStatus(id, ModelLoadStatus.Failed(preflight.reason, rejected = true))
            return false
        }
        preflight as ModelLoadPreflight.Result.Pass
        setStatus(id, ModelLoadStatus.Loading)
        val previous = _active.value
        return try {
            engine.load(file.path, config.copy(contextLength = preflight.contextLength))
            val loaded = (engine.state.value as? EngineState.Ready)?.model
                ?: throw InferenceException(InferenceErrorKind.MODEL_LOAD_FAILED, "The engine did not report a loaded model.")
            previous?.let { clearStatus(it.recordId) }
            _active.value = ActiveModel(id, row.repoId, row.path.substringAfterLast('/'), loaded)
            setStatus(id, ModelLoadStatus.Loaded(loaded, preflight.notes))
            true
        } catch (e: InferenceException) {
            // The engine frees the previous model before loading, so nothing is loaded now.
            previous?.let { clearStatus(it.recordId) }
            _active.value = null
            setStatus(id, ModelLoadStatus.Failed(e.message ?: e.kind.description, rejected = false))
            false
        }
    }

    suspend fun unload() = mutex.withLock {
        engine.unload()
        _active.value?.let { clearStatus(it.recordId) }
        _active.value = null
    }

    /** Unloads [recordId] if it is the active model (e.g. before deleting its file). */
    suspend fun release(recordId: String) {
        if (_active.value?.recordId == recordId) unload()
        clearStatus(recordId)
    }

    fun generate(messages: List<PromptMessage>, params: GenerationParams = GenerationParams()): Flow<GenerationEvent> =
        engine.generate(messages, params)

    fun stop() = engine.stop()

    private suspend fun measure(): DeviceResourceSnapshot = try {
        deviceResources.refresh() // memory changes quickly; measure right before loading
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        DeviceResourceSnapshot.from(RawDeviceReadings(unavailable = listOf("Device query failed")), clock())
    }

    private fun setStatus(id: String, status: ModelLoadStatus) = _status.update { it + (id to status) }
    private fun clearStatus(id: String) = _status.update { it - id }
}
