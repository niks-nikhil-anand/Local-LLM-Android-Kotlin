package com.example.localai.data.inference

import android.util.Log

/** Thrown by the JNI layer (constructed from native code; keep the constructor signature). */
class NativeLlamaException(val code: Int, message: String) : Exception(message)

/** Error codes raised by localai_llama.cpp. Keep in sync with its ErrorCode enum. */
object NativeErrorCode {
    const val INVALID_ARGUMENT = 1
    const val MODEL_LOAD_FAILED = 2
    const val CONTEXT_FAILED = 3
    const val TOKENIZE_FAILED = 4
    const val PROMPT_TOO_LONG = 5
    const val DECODE_FAILED = 6
}

/** Stop reasons reported by localai_llama.cpp. Keep in sync with its StopReason enum. */
object NativeStopReason {
    const val NONE = 0
    const val EOG = 1
    const val MAX_TOKENS = 2
    const val ABORTED = 3
    const val CONTEXT_FULL = 4
}

/**
 * The JNI surface, as an interface so [LlamaCppEngine]'s threading and error handling can be tested
 * without native code. Text is UTF-8 bytes in both directions. Handles are opaque; every method
 * except [requestStop] must be called from the engine's single native thread.
 */
interface LlamaNative {
    val isLoaded: Boolean
    val loadError: String?
    fun init(): ByteArray
    fun load(path: ByteArray, contextLength: Int, threads: Int, batchSize: Int): Long
    fun contextLength(handle: Long): Int
    fun describe(handle: Long): ByteArray
    fun applyTemplate(handle: Long, roles: Array<ByteArray>, contents: Array<ByteArray>, addAssistant: Boolean): ByteArray?
    fun startCompletion(handle: Long, prompt: ByteArray, maxTokens: Int, temperature: Float, topP: Float, seed: Int): Int
    fun nextToken(handle: Long): ByteArray?
    fun stopReason(handle: Long): Int
    fun requestStop(handle: Long)
    fun free(handle: Long)
}

/** Binds to liblocalai_llama.so. A device whose ABI has no build gets [isLoaded] = false, not a crash. */
object LlamaJni : LlamaNative {
    private const val TAG = "LocalAI-llama"

    private val loadResult: Result<Unit> by lazy {
        runCatching { System.loadLibrary("localai_llama") }
            .onFailure { Log.w(TAG, "Native inference library unavailable: ${it.javaClass.simpleName}") }
    }

    override val isLoaded: Boolean get() = loadResult.isSuccess
    override val loadError: String? get() = loadResult.exceptionOrNull()?.message

    override fun init(): ByteArray = nativeInit()
    override fun load(path: ByteArray, contextLength: Int, threads: Int, batchSize: Int) = nativeLoad(path, contextLength, threads, batchSize)
    override fun contextLength(handle: Long) = nativeContextLength(handle)
    override fun describe(handle: Long): ByteArray = nativeDescribe(handle)
    override fun applyTemplate(handle: Long, roles: Array<ByteArray>, contents: Array<ByteArray>, addAssistant: Boolean) =
        nativeApplyTemplate(handle, roles, contents, addAssistant)
    override fun startCompletion(handle: Long, prompt: ByteArray, maxTokens: Int, temperature: Float, topP: Float, seed: Int) =
        nativeStartCompletion(handle, prompt, maxTokens, temperature, topP, seed)
    override fun nextToken(handle: Long) = nativeNextToken(handle)
    override fun stopReason(handle: Long) = nativeStopReason(handle)
    override fun requestStop(handle: Long) = nativeRequestStop(handle)
    override fun free(handle: Long) = nativeFree(handle)

    @JvmStatic private external fun nativeInit(): ByteArray
    @JvmStatic private external fun nativeLoad(path: ByteArray, nCtx: Int, nThreads: Int, nBatch: Int): Long
    @JvmStatic private external fun nativeContextLength(handle: Long): Int
    @JvmStatic private external fun nativeDescribe(handle: Long): ByteArray
    @JvmStatic private external fun nativeApplyTemplate(handle: Long, roles: Array<ByteArray>, contents: Array<ByteArray>, addAssistant: Boolean): ByteArray?
    @JvmStatic private external fun nativeStartCompletion(handle: Long, prompt: ByteArray, maxTokens: Int, temperature: Float, topP: Float, seed: Int): Int
    @JvmStatic private external fun nativeNextToken(handle: Long): ByteArray?
    @JvmStatic private external fun nativeStopReason(handle: Long): Int
    @JvmStatic private external fun nativeRequestStop(handle: Long)
    @JvmStatic private external fun nativeFree(handle: Long)
}

/**
 * Joins UTF-8 pieces from the tokenizer into text without splitting multi-byte characters: a
 * token may end in the middle of a character, so incomplete trailing bytes wait for the next one.
 */
class Utf8Accumulator {
    private var pending = ByteArray(0)

    fun feed(bytes: ByteArray): String {
        val all = pending + bytes
        val complete = completePrefixLength(all)
        pending = all.copyOfRange(complete, all.size)
        return String(all, 0, complete, Charsets.UTF_8)
    }

    /** Whatever is left (an incomplete or invalid sequence) as replacement characters. */
    fun flush(): String = String(pending, Charsets.UTF_8).also { pending = ByteArray(0) }

    companion object {
        /** Length of the longest prefix that does not end inside a multi-byte UTF-8 sequence. */
        fun completePrefixLength(bytes: ByteArray): Int {
            val n = bytes.size
            // Look back at most 3 bytes for the lead byte of a possibly unfinished sequence.
            for (back in 1..minOf(3, n)) {
                val b = bytes[n - back].toInt() and 0xFF
                if (b and 0xC0 == 0x80) continue // continuation byte: keep looking for the lead
                val length = when {
                    b and 0x80 == 0 -> 1
                    b and 0xE0 == 0xC0 -> 2
                    b and 0xF0 == 0xE0 -> 3
                    b and 0xF8 == 0xF0 -> 4
                    else -> 1 // invalid lead byte: let the decoder replace it
                }
                return if (length > back) n - back else n
            }
            return n
        }
    }
}
