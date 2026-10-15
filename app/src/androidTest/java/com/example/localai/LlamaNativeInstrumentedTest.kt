package com.example.localai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.localai.data.inference.EngineState
import com.example.localai.data.inference.GenerationEvent
import com.example.localai.data.inference.GenerationParams
import com.example.localai.data.inference.InferenceConfig
import com.example.localai.data.inference.InferenceErrorKind
import com.example.localai.data.inference.InferenceException
import com.example.localai.data.inference.LlamaCppEngine
import com.example.localai.data.inference.LlamaJni
import com.example.localai.data.inference.PromptMessage
import com.example.localai.data.inference.StopReason
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real llama.cpp through JNI on the device/emulator. Needs the tiny test model (not committed):
 *
 *   adb push stories260K.gguf /data/local/tmp/localai/stories260K.gguf
 *
 * Source: https://huggingface.co/ggml-org/models (tinyllamas/stories260K.gguf, a 260K-parameter
 * llama2.c story model, 1,185,376 bytes). The SHA-256 is checked before use; tests are skipped,
 * not failed, when the file is absent.
 */
@RunWith(AndroidJUnit4::class)
class LlamaNativeInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val config = InferenceConfig(contextLength = 256, threads = 2, batchSize = 64)
    private val prompt = listOf(PromptMessage(PromptMessage.USER, "Once upon a time"))

    private fun model(): File {
        val target = File(context.cacheDir, "stories260K.gguf")
        if (!target.exists()) {
            val source = File("/data/local/tmp/localai/stories260K.gguf")
            assumeTrue("Push the test model to ${source.path}", source.canRead())
            source.copyTo(target, overwrite = true)
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(target.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d", sha)
        return target
    }

    private fun text(events: List<GenerationEvent>) = events.filterIsInstance<GenerationEvent.Token>().joinToString("") { it.text }

    @Test
    fun theNativeLibraryLoadsOnThisAbi() {
        assertTrue("liblocalai_llama.so failed to load: ${LlamaJni.loadError}", LlamaJni.isLoaded)
        assertTrue(LlamaCppEngine().isAvailable)
    }

    @Test
    fun invalidAndTruncatedFilesAreRejectedWithoutCrashing() = runBlocking {
        val engine = LlamaCppEngine()
        val garbage = File(context.cacheDir, "garbage.gguf").apply { writeBytes(ByteArray(4096) { 7 }) }
        val e1 = runCatching { engine.load(garbage.path, config) }.exceptionOrNull() as InferenceException
        assertEquals(InferenceErrorKind.MODEL_LOAD_FAILED, e1.kind)

        val truncated = File(context.cacheDir, "truncated.gguf").apply { writeBytes(model().readBytes().copyOf(50_000)) }
        val e2 = runCatching { engine.load(truncated.path, config) }.exceptionOrNull() as InferenceException
        assertEquals(InferenceErrorKind.MODEL_LOAD_FAILED, e2.kind)
        assertTrue(engine.state.value is EngineState.LoadFailed)

        // The engine is still healthy: a valid model loads afterwards.
        engine.load(model().path, config)
        assertTrue(engine.state.value is EngineState.Ready)
        engine.unload()
    }

    @Test
    fun aRealModelGeneratesTextOnDevice() = runBlocking {
        val engine = LlamaCppEngine()
        engine.load(model().path, config)
        val loaded = (engine.state.value as EngineState.Ready).model
        assertEquals(256, loaded.contextLength)
        Log.i(TAG, "loaded ${loaded.description}: ${loaded.parameterCount} params, ${loaded.sizeBytes} bytes in ${loaded.loadMillis} ms")

        val events = withTimeout(60_000) { engine.generate(prompt, GenerationParams(maxTokens = 48, temperature = 0f)).toList() }
        val output = text(events)
        val done = events.last() as GenerationEvent.Finished
        Log.i(
            TAG,
            "generated ${done.generatedTokens} tokens (${done.reason}) from ${done.promptTokens} prompt tokens; " +
                "first token ${done.firstTokenMillis} ms, total ${done.totalMillis} ms, chars=${output.length}"
        )
        assertTrue("no text was generated", output.isNotBlank())
        assertTrue(done.generatedTokens in 1..48)
        assertTrue(done.reason == StopReason.MAX_TOKENS || done.reason == StopReason.END_OF_TEXT)

        // Greedy decoding is deterministic: the same prompt gives the same text.
        val again = text(engine.generate(prompt, GenerationParams(maxTokens = 48, temperature = 0f)).toList())
        assertEquals(output, again)
        engine.unload()
        assertEquals(EngineState.NoModel, engine.state.value)
    }

    @Test
    fun stopInterruptsARunningGeneration() = runBlocking {
        val engine = LlamaCppEngine()
        engine.load(model().path, config)
        val start = System.nanoTime()
        val events = mutableListOf<GenerationEvent>()
        engine.generate(prompt, GenerationParams(maxTokens = 10_000, temperature = 0.8f)).collect { event ->
            events += event
            if (event is GenerationEvent.Token && events.size == 3) engine.stop()
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val done = events.last() as GenerationEvent.Finished
        assertEquals(StopReason.STOPPED, done.reason)
        assertTrue("stopped after ${done.generatedTokens} tokens", done.generatedTokens < 20)
        Log.i(TAG, "stop honoured after ${done.generatedTokens} tokens, ${elapsedMs} ms")
        engine.unload()
    }

    @Test
    fun cancellingTheCollectorReleasesTheEngine() = runBlocking {
        val engine = LlamaCppEngine()
        engine.load(model().path, config)
        // takeWhile cancels the upstream after the first token.
        engine.generate(prompt, GenerationParams(maxTokens = 10_000)).takeWhile { it !is GenerationEvent.Token }.toList()
        val next = withTimeout(30_000) { engine.generate(prompt, GenerationParams(maxTokens = 8, temperature = 0f)).toList() }
        assertTrue(next.last() is GenerationEvent.Finished)
        engine.unload()
    }

    @Test
    fun repeatedLoadUnloadCyclesStayHealthy() = runBlocking {
        val engine = LlamaCppEngine()
        repeat(5) {
            engine.load(model().path, config)
            val out = engine.generate(prompt, GenerationParams(maxTokens = 4, temperature = 0f)).first { it is GenerationEvent.Finished }
            assertTrue(out is GenerationEvent.Finished)
            engine.unload()
        }
        val error = runCatching { engine.generate(prompt).toList() }.exceptionOrNull() as InferenceException
        assertEquals(InferenceErrorKind.NO_MODEL_LOADED, error.kind)
    }

    private companion object {
        const val TAG = "LocalAI-llama-test"
    }
}
