package com.example.localai.data.inference

import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [InferenceEngine] backed by llama.cpp through [LlamaNative].
 *
 * Threading and ownership:
 * - Every native call except [LlamaNative.requestStop] runs on [nativeDispatcher], a single thread,
 *   so the model/context are never used from two threads.
 * - [nativeMutex] is held for a whole load, unload or generation. Loading or unloading first asks a
 *   running generation to stop, then waits for it to release the mutex, so a handle is never freed
 *   while a generation is using it.
 * - The current handle lives in [session]; it is cleared under [sessionLock] before it is freed, and
 *   [stop] reads it under the same lock, so a stop request can never reach a freed handle.
 * - Native sections run as NonCancellable: a cancelled caller cannot leak a handle half-created.
 */
class LlamaCppEngine(
    private val native: LlamaNative = LlamaJni,
    private val nativeDispatcher: CoroutineDispatcher = defaultNativeDispatcher(),
    private val clock: () -> Long = SystemClock::elapsedRealtime
) : InferenceEngine {

    private class Session(val handle: Long, val model: LoadedModel)

    override val engineId: String = ENGINE_ID

    override val isAvailable: Boolean get() = native.isLoaded

    private val _state = MutableStateFlow<EngineState>(
        if (native.isLoaded) EngineState.NoModel else EngineState.Unavailable(native.loadError ?: "Native library not loaded")
    )
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val nativeMutex = Mutex()
    private val sessionLock = Any()
    private val generating = AtomicBoolean(false)
    private var initialised = false

    @Volatile
    private var session: Session? = null

    override suspend fun load(modelPath: String, config: InferenceConfig) {
        if (!native.isLoaded) throw InferenceException(InferenceErrorKind.ENGINE_UNAVAILABLE, native.loadError)
        val file = File(modelPath)
        if (!file.isFile || !file.canRead()) throw InferenceException(InferenceErrorKind.FILE_NOT_FOUND)
        stop()
        nativeMutex.withLock {
            _state.value = EngineState.Loading(modelPath)
            try {
                val loaded = withContext(NonCancellable + nativeDispatcher) {
                    ensureInitialised()
                    releaseLocked()
                    val start = clock()
                    val handle = native.load(modelPath.toByteArray(Charsets.UTF_8), config.contextLength, config.threads, config.batchSize)
                    val (desc, size, params) = describe(handle)
                    val model = LoadedModel(modelPath, desc, size, params, native.contextLength(handle), clock() - start)
                    synchronized(sessionLock) { session = Session(handle, model) }
                    model
                }
                _state.value = EngineState.Ready(loaded)
            } catch (e: NativeLlamaException) {
                throw fail(map(e))
            } catch (e: InferenceException) {
                throw fail(e)
            } catch (e: RuntimeException) {
                throw fail(InferenceException(InferenceErrorKind.MODEL_LOAD_FAILED, e.message))
            }
        }
    }

    override fun generate(messages: List<PromptMessage>, params: GenerationParams): Flow<GenerationEvent> = flow {
        // Checked in the caller's context, before switching to the (possibly busy) native thread.
        if (!generating.compareAndSet(false, true)) throw InferenceException(InferenceErrorKind.BUSY)
        try {
            emitAll(nativeGeneration(messages, params))
        } finally {
            generating.set(false)
        }
    }

    private fun nativeGeneration(messages: List<PromptMessage>, params: GenerationParams): Flow<GenerationEvent> = flow {
            nativeMutex.withLock {
                val current = session ?: throw InferenceException(InferenceErrorKind.NO_MODEL_LOADED)
                val handle = current.handle
                _state.value = EngineState.Generating(current.model)
                // Cancelling the collector also interrupts a long prompt evaluation inside llama_decode.
                val stopOnCancel = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                    if (cause != null) stop()
                }
                try {
                    val start = clock()
                    val prompt = formatPrompt(handle, messages)
                    val promptTokens = native.startCompletion(
                        handle, prompt.toByteArray(Charsets.UTF_8), params.maxTokens, params.temperature, params.topP, params.seed
                    )
                    if (promptTokens < 0) {
                        emit(GenerationEvent.Finished(StopReason.STOPPED, 0, 0, null, clock() - start))
                        return@withLock
                    }
                    val text = Utf8Accumulator()
                    var generated = 0
                    var firstToken: Long? = null
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val piece = native.nextToken(handle) ?: break
                        generated++
                        if (firstToken == null) firstToken = clock() - start
                        val chunk = text.feed(piece)
                        if (chunk.isNotEmpty()) emit(GenerationEvent.Token(chunk))
                    }
                    text.flush().takeIf { it.isNotEmpty() }?.let { emit(GenerationEvent.Token(it)) }
                    val reason = when (native.stopReason(handle)) {
                        NativeStopReason.EOG -> StopReason.END_OF_TEXT
                        NativeStopReason.ABORTED -> StopReason.STOPPED
                        NativeStopReason.CONTEXT_FULL -> StopReason.CONTEXT_FULL
                        else -> StopReason.MAX_TOKENS
                    }
                    emit(GenerationEvent.Finished(reason, promptTokens, generated, firstToken, clock() - start))
                } catch (e: NativeLlamaException) {
                    throw map(e)
                } finally {
                    stopOnCancel?.dispose()
                    session?.let { _state.value = EngineState.Ready(it.model) }
                }
            }
    }.flowOn(nativeDispatcher)

    override fun stop() {
        synchronized(sessionLock) { session?.let { native.requestStop(it.handle) } }
    }

    override suspend fun unload() {
        stop()
        nativeMutex.withLock {
            withContext(NonCancellable + nativeDispatcher) { releaseLocked() }
            if (native.isLoaded) _state.value = EngineState.NoModel
        }
    }

    /** Frees the current session. Caller holds [nativeMutex] and runs on [nativeDispatcher]. */
    private fun releaseLocked() {
        val old = synchronized(sessionLock) { session.also { session = null } } ?: return
        native.free(old.handle)
    }

    private fun ensureInitialised() {
        if (!initialised) {
            native.init()
            initialised = true
        }
    }

    private fun describe(handle: Long): Triple<String, Long, Long> {
        val parts = String(native.describe(handle), Charsets.UTF_8).split('|')
        return Triple(parts.getOrElse(0) { "" }, parts.getOrNull(1)?.toLongOrNull() ?: 0L, parts.getOrNull(2)?.toLongOrNull() ?: 0L)
    }

    /** The model's own chat template when llama.cpp supports it; otherwise [fallbackPrompt]. */
    private fun formatPrompt(handle: Long, messages: List<PromptMessage>): String {
        val roles = messages.map { it.role.toByteArray(Charsets.UTF_8) }.toTypedArray()
        val contents = messages.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray()
        return native.applyTemplate(handle, roles, contents, true)?.toString(Charsets.UTF_8) ?: fallbackPrompt(messages)
    }

    private fun fail(e: InferenceException): InferenceException {
        _state.value = EngineState.LoadFailed(e)
        return e
    }

    private fun map(e: NativeLlamaException): InferenceException = InferenceException(
        when (e.code) {
            NativeErrorCode.MODEL_LOAD_FAILED -> InferenceErrorKind.MODEL_LOAD_FAILED
            NativeErrorCode.CONTEXT_FAILED -> InferenceErrorKind.CONTEXT_FAILED
            NativeErrorCode.PROMPT_TOO_LONG -> InferenceErrorKind.PROMPT_TOO_LONG
            NativeErrorCode.INVALID_ARGUMENT -> InferenceErrorKind.INVALID_ARGUMENT
            else -> InferenceErrorKind.GENERATION_FAILED
        },
        e.message
    )

    companion object {
        const val ENGINE_ID = "llama.cpp"

        fun defaultNativeDispatcher(): CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "llama-native").apply { isDaemon = true } }.asCoroutineDispatcher()

        /**
         * Plain-text prompt for models without a usable chat template (typically base models).
         * Such models were not trained for chat, so replies are continuations, not answers.
         */
        fun fallbackPrompt(messages: List<PromptMessage>): String = buildString {
            for (m in messages) {
                val label = when (m.role) {
                    PromptMessage.SYSTEM -> "System"
                    PromptMessage.ASSISTANT -> "Assistant"
                    else -> "User"
                }
                append(label).append(": ").append(m.content.trim()).append("\n\n")
            }
            append("Assistant:")
        }
    }
}
