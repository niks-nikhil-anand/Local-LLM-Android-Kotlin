package com.example.localai.inference

import com.example.localai.data.inference.ActiveModel
import com.example.localai.data.inference.EngineState
import com.example.localai.data.inference.GenerationEvent
import com.example.localai.data.inference.GenerationParams
import com.example.localai.data.inference.InferenceConfig
import com.example.localai.data.inference.InferenceEngine
import com.example.localai.data.inference.InferenceErrorKind
import com.example.localai.data.inference.InferenceException
import com.example.localai.data.inference.LoadedModel
import com.example.localai.data.inference.ModelLoadStatus
import com.example.localai.data.inference.ModelSessionManager
import com.example.localai.data.inference.PromptMessage
import com.example.localai.data.inference.StopReason
import com.example.localai.data.local.LocalModelEntity
import com.example.localai.explore.FakeDeviceProvider
import com.example.localai.explore.FakeDownloads
import com.example.localai.explore.normalDevice
import com.example.localai.gguf.GgufBuilder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** In-memory engine: records calls, loads succeed unless [loadFailure] is set. */
internal class FakeEngine(override val isAvailable: Boolean = true) : InferenceEngine {
    override val engineId = "fake"
    val loads = mutableListOf<Pair<String, InferenceConfig>>()
    var unloads = 0
    var stops = 0
    var loadFailure: InferenceException? = null
    var reply: List<String> = listOf("Hi", " there")
    var generateFailure: InferenceException? = null
    val prompts = mutableListOf<List<PromptMessage>>()
    private val _state = MutableStateFlow<EngineState>(if (isAvailable) EngineState.NoModel else EngineState.Unavailable("no lib"))
    override val state: StateFlow<EngineState> = _state

    override suspend fun load(modelPath: String, config: InferenceConfig) {
        loads += modelPath to config
        loadFailure?.let { _state.value = EngineState.LoadFailed(it); throw it }
        _state.value = EngineState.Ready(LoadedModel(modelPath, "fake", File(modelPath).length(), 1, config.contextLength, 5))
    }

    override fun generate(messages: List<PromptMessage>, params: GenerationParams): Flow<GenerationEvent> = flow {
        prompts += messages
        generateFailure?.let { throw it }
        reply.forEach { emit(GenerationEvent.Token(it)) }
        emit(GenerationEvent.Finished(StopReason.END_OF_TEXT, 3, reply.size, 1, 10))
    }

    override fun stop() { stops++ }

    override suspend fun unload() {
        unloads++
        _state.value = EngineState.NoModel
    }
}

class ModelSessionManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val downloads = FakeDownloads()
    private val device = FakeDeviceProvider()
    private val engine = FakeEngine()

    private fun manager(e: InferenceEngine = engine) =
        ModelSessionManager(e, downloads, device, clock = { 0L }, ioDispatcher = Dispatchers.Unconfined)

    /** A completed record backed by a real (synthetic) GGUF file on disk. */
    private fun model(name: String = "tiny-Q4_K_M.gguf", builder: GgufBuilder = GgufBuilder.tinyLlama(), verification: String? = "SHA256"): LocalModelEntity {
        val bytes = builder.file()
        val file = temp.newFile(name).apply { writeBytes(bytes) }
        val row = LocalModelEntity(
            id = "o/r/main/$name", repoId = "o/r", revision = "main", path = name, expectedSizeBytes = bytes.size.toLong(),
            expectedSha256 = null, downloadedBytes = bytes.size.toLong(), state = "COMPLETED", failureReason = null,
            updatedAtMillis = 0, verification = verification
        )
        downloads.files[row.id] = file
        return row
    }

    private fun ModelSessionManager.status(row: LocalModelEntity) = loadStatus.value[row.id]

    @Test
    fun aValidModelPassesTheChecksAndIsLoadedByTheEngine() = runBlocking {
        val m = manager()
        val row = model()
        assertTrue(m.load(row))
        val loaded = m.status(row) as ModelLoadStatus.Loaded
        assertEquals(2048, loaded.model.contextLength)
        assertEquals(ActiveModel(row.id, "o/r", "tiny-Q4_K_M.gguf", loaded.model), m.active.value)
        assertEquals(downloads.files[row.id]!!.path, engine.loads.single().first)
        assertEquals(1, device.refreshes) // memory is measured right before loading
    }

    @Test
    fun theContextIsCappedAtTheModelsOwnLimit() = runBlocking {
        val m = manager()
        assertTrue(m.load(model(builder = GgufBuilder.tinyLlama(contextLength = 512))))
        assertEquals(512, engine.loads.single().second.contextLength)
    }

    @Test
    fun incompleteMissingOrResizedFilesAreRejectedBeforeTheEngine() = runBlocking {
        val m = manager()
        val partial = model("a.gguf").copy(state = "PAUSED")
        assertFalse(m.load(partial))
        assertTrue((m.status(partial) as ModelLoadStatus.Failed).rejected)

        val missing = model("b.gguf").also { downloads.files[it.id]!!.delete() }
        assertFalse(m.load(missing))
        assertTrue((m.status(missing) as ModelLoadStatus.Failed).message.contains("missing"))

        val resized = model("c.gguf").also { downloads.files[it.id]!!.appendBytes(ByteArray(3)) }
        assertFalse(m.load(resized))
        assertTrue((m.status(resized) as ModelLoadStatus.Failed).message.contains("download it again"))
        assertTrue(engine.loads.isEmpty())
    }

    @Test
    fun invalidAndUnsupportedFilesAreRejectedWithReasons() = runBlocking {
        val m = manager()
        val notGguf = model("x.gguf").also { r -> downloads.files[r.id]!!.writeBytes(ByteArray(r.downloadedBytes.toInt()) { 1 }) }
        assertFalse(m.load(notGguf))
        assertEquals("The file is not a GGUF model.", (m.status(notGguf) as ModelLoadStatus.Failed).message)

        val projector = model("clip.gguf", GgufBuilder.tinyLlama(architecture = "clip"))
        assertFalse(m.load(projector))
        assertTrue((m.status(projector) as ModelLoadStatus.Failed).message.contains("projector"))

        val whisper = model("w.gguf", GgufBuilder.tinyLlama(architecture = "whisper"))
        assertFalse(m.load(whisper))
        assertTrue(engine.loads.isEmpty())
    }

    @Test
    fun anUnknownArchitectureIsTriedBecauseTheEngineDecides() = runBlocking {
        val m = manager()
        val row = model("n.gguf", GgufBuilder.tinyLlama(architecture = "brand-new-arch"))
        assertTrue(m.load(row))
        val notes = (m.status(row) as ModelLoadStatus.Loaded).notes
        assertTrue(notes.any { it.contains("brand-new-arch") })
    }

    @Test
    fun lowMemoryRejectsTheLoad() = runBlocking {
        device.readings = normalDevice.copy(isLowMemory = true)
        val m = manager()
        val row = model()
        assertFalse(m.load(row))
        assertTrue((m.status(row) as ModelLoadStatus.Failed).message.contains("low on memory"))
        assertTrue(engine.loads.isEmpty())
    }

    @Test
    fun aNativeLoadFailureKeepsTheFileAndLoadsNothing() = runBlocking {
        val m = manager()
        val row = model()
        engine.loadFailure = InferenceException(InferenceErrorKind.CONTEXT_FAILED, "oom")
        assertFalse(m.load(row))
        val failed = m.status(row) as ModelLoadStatus.Failed
        assertFalse(failed.rejected)
        assertTrue(failed.message.contains("not enough memory"))
        assertNull(m.active.value)
        assertTrue(downloads.files[row.id]!!.exists())
        assertTrue(downloads.calls.none { it.startsWith("delete") })
    }

    @Test
    fun switchingModelsReplacesTheActiveOne() = runBlocking {
        val m = manager()
        val a = model("a.gguf")
        val b = model("b.gguf")
        m.load(a)
        m.load(b)
        assertEquals(b.id, m.active.value?.recordId)
        assertNull(m.status(a)) // the old model's "Loaded" is cleared
        assertEquals(2, engine.loads.size)
        // Loading the active model again is a no-op.
        assertTrue(m.load(b))
        assertEquals(2, engine.loads.size)
    }

    @Test
    fun unloadAndReleaseClearTheSession() = runBlocking {
        val m = manager()
        val a = model("a.gguf")
        m.load(a)
        m.release("someone-else")
        assertEquals(a.id, m.active.value?.recordId)
        m.release(a.id)
        assertNull(m.active.value)
        assertEquals(1, engine.unloads)
        m.load(a)
        m.unload()
        assertNull(m.active.value)
        assertTrue(m.loadStatus.value.isEmpty())
    }

    @Test
    fun noEngineMeansNoLoad() = runBlocking {
        val noEngine = FakeEngine(isAvailable = false)
        val m = manager(noEngine)
        val row = model()
        assertFalse(m.load(row))
        assertTrue((m.status(row) as ModelLoadStatus.Failed).message.contains("not available"))
        assertTrue(noEngine.loads.isEmpty())
    }

    @Test
    fun generateAndStopGoToTheEngine() = runBlocking {
        val m = manager()
        m.load(model())
        val events = m.generate(listOf(PromptMessage("user", "hi"))).toList()
        assertEquals(3, events.size)
        m.stop()
        assertEquals(1, engine.stops)
    }
}
