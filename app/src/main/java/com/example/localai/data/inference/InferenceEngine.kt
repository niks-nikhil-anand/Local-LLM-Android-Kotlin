package com.example.localai.data.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** One message of the conversation sent to the model. [role] is "system", "user" or "assistant". */
data class PromptMessage(val role: String, val content: String) {
    companion object {
        const val SYSTEM = "system"
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

/**
 * Runtime settings for a loaded model. Defaults are conservative for phones: a 2048-token context
 * keeps the KV cache small, and threads leave cores for the UI (same rule as upstream's Android
 * example: processors - 2, clamped to 2..4).
 */
data class InferenceConfig(
    val contextLength: Int = 2048,
    val threads: Int = defaultThreads(),
    val batchSize: Int = 256
) {
    init {
        require(contextLength > 0 && threads > 0 && batchSize > 0)
    }

    companion object {
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)
    }
}

/** Sampling settings for one reply. temperature <= 0 means greedy (deterministic) decoding. */
data class GenerationParams(
    val maxTokens: Int = 256,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val seed: Int = (System.nanoTime() and 0x7fffffff).toInt()
)

enum class InferenceErrorKind(val description: String) {
    ENGINE_UNAVAILABLE("The on-device inference engine is not available on this device."),
    FILE_NOT_FOUND("The model file is missing or cannot be read."),
    MODEL_LOAD_FAILED("The model could not be loaded by llama.cpp."),
    CONTEXT_FAILED("The model loaded, but there was not enough memory to start it with this context length."),
    NO_MODEL_LOADED("No model is loaded."),
    BUSY("A reply is already being generated."),
    PROMPT_TOO_LONG("The conversation is longer than the model's context."),
    GENERATION_FAILED("Generating a reply failed."),
    INVALID_ARGUMENT("Invalid inference settings.")
}

class InferenceException(val kind: InferenceErrorKind, detail: String? = null) :
    Exception(listOfNotNull(kind.description, detail).joinToString(" "))

/** Facts reported by the engine after a successful load — measured, not estimated. */
data class LoadedModel(
    val path: String,
    val description: String,
    val sizeBytes: Long,
    val parameterCount: Long,
    val contextLength: Int,
    val loadMillis: Long
)

sealed interface EngineState {
    data class Unavailable(val reason: String) : EngineState
    data object NoModel : EngineState
    data class Loading(val path: String) : EngineState
    data class Ready(val model: LoadedModel) : EngineState
    data class Generating(val model: LoadedModel) : EngineState
    /** The last load failed; no model is loaded. */
    data class LoadFailed(val error: InferenceException) : EngineState
}

enum class StopReason { END_OF_TEXT, MAX_TOKENS, STOPPED, CONTEXT_FULL }

sealed interface GenerationEvent {
    /** Newly generated text (complete UTF-8 characters only). */
    data class Token(val text: String) : GenerationEvent

    data class Finished(
        val reason: StopReason,
        val promptTokens: Int,
        val generatedTokens: Int,
        /** Time to first generated token, including prompt evaluation; null if none was produced. */
        val firstTokenMillis: Long?,
        val totalMillis: Long
    ) : GenerationEvent
}

/**
 * Boundary between chat and a local runtime. Implementations run entirely on the device: loading
 * and generation never use the network.
 */
interface InferenceEngine {
    /** Stable identifier, e.g. llama.cpp. */
    val engineId: String

    /** Whether the engine's native code is actually present and loadable on this device. */
    val isAvailable: Boolean

    val state: StateFlow<EngineState>

    /**
     * Loads a fully downloaded, verified model file, replacing (and freeing) any loaded model.
     * Throws [InferenceException]; on failure no model is loaded.
     */
    suspend fun load(modelPath: String, config: InferenceConfig = InferenceConfig())

    /**
     * Generates a reply to [messages]. Only one generation runs at a time (a second one fails with
     * [InferenceErrorKind.BUSY]). Cancelling collection stops generation.
     */
    fun generate(messages: List<PromptMessage>, params: GenerationParams = GenerationParams()): Flow<GenerationEvent>

    /** Asks a running generation to stop at the next opportunity. Safe from any thread. */
    fun stop()

    /** Stops any generation and frees the model. Safe to call when nothing is loaded. */
    suspend fun unload()
}
