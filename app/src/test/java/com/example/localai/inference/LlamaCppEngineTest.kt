package com.example.localai.inference

import com.example.localai.data.inference.EngineState
import com.example.localai.data.inference.GenerationEvent
import com.example.localai.data.inference.GenerationParams
import com.example.localai.data.inference.InferenceErrorKind
import com.example.localai.data.inference.InferenceException
import com.example.localai.data.inference.LlamaCppEngine
import com.example.localai.data.inference.LlamaNative
import com.example.localai.data.inference.NativeErrorCode
import com.example.localai.data.inference.NativeLlamaException
import com.example.localai.data.inference.NativeStopReason
import com.example.localai.data.inference.PromptMessage
import com.example.localai.data.inference.StopReason
import com.example.localai.data.inference.Utf8Accumulator
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Scripted stand-in for the JNI layer. It fails the test on any use of a freed or unknown handle,
 * on double frees, and on calls made from more than one thread.
 */
private class FakeNative(
    override val isLoaded: Boolean = true,
    var tokens: List<ByteArray> = listOf("Hello".toByteArray(), ", ".toByteArray(), "world".toByteArray()),
    var template: ((List<String>, List<String>) -> String?)? = { roles, contents ->
        roles.zip(contents).joinToString("") { (r, c) -> "<$r>$c" } + "<assistant>"
    }
) : LlamaNative {
    override val loadError: String? = if (isLoaded) null else "dlopen failed"
    var inits = 0
    var nextHandle = 100L
    val live = Collections.synchronizedSet(HashSet<Long>())
    val freed = Collections.synchronizedList(ArrayList<Long>())
    val prompts = Collections.synchronizedList(ArrayList<String>())
    val threads = Collections.synchronizedSet(HashSet<String>())
    var loadError2: NativeLlamaException? = null
    var startError: NativeLlamaException? = null
    var nextError: NativeLlamaException? = null
    /** When set, nextToken blocks on it (to hold a generation open). */
    var tokenGate: CountDownLatch? = null
    val insideNextToken = CountDownLatch(1)
    private val abort = AtomicBoolean(false)
    private var position = 0
    private var reason = NativeStopReason.NONE

    private fun check(handle: Long) {
        threads += Thread.currentThread().name
        if (handle !in live) fail("use of freed or unknown handle $handle")
    }

    override fun init(): ByteArray { inits++; return "CPU".toByteArray() }

    override fun load(path: ByteArray, contextLength: Int, threads: Int, batchSize: Int): Long {
        this.threads += Thread.currentThread().name
        loadError2?.let { throw it }
        return nextHandle++.also { live += it }
    }

    override fun contextLength(handle: Long): Int { check(handle); return 2048 }
    override fun describe(handle: Long): ByteArray { check(handle); return "fake 1M Q4_K|1000|2000".toByteArray() }

    override fun applyTemplate(handle: Long, roles: Array<ByteArray>, contents: Array<ByteArray>, addAssistant: Boolean): ByteArray? {
        check(handle)
        return template?.invoke(roles.map { String(it) }, contents.map { String(it) })?.toByteArray()
    }

    override fun startCompletion(handle: Long, prompt: ByteArray, maxTokens: Int, temperature: Float, topP: Float, seed: Int): Int {
        check(handle)
        startError?.let { throw it }
        abort.set(false)
        prompts += String(prompt)
        position = 0
        reason = NativeStopReason.NONE
        return 7
    }

    override fun nextToken(handle: Long): ByteArray? {
        check(handle)
        insideNextToken.countDown()
        tokenGate?.await(5, TimeUnit.SECONDS)
        nextError?.let { throw it }
        if (abort.get()) { reason = NativeStopReason.ABORTED; return null }
        if (position >= tokens.size) { reason = NativeStopReason.EOG; return null }
        return tokens[position++]
    }

    override fun stopReason(handle: Long): Int { check(handle); return reason }
    override fun requestStop(handle: Long) { if (handle in live) abort.set(true) }

    override fun free(handle: Long) {
        if (!live.remove(handle)) fail("double free of $handle")
        freed += handle
    }
}

class LlamaCppEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val messages = listOf(PromptMessage(PromptMessage.USER, "Hi"))

    private fun engine(native: FakeNative) = LlamaCppEngine(native, clock = { 0L })

    private fun modelFile(): File = temp.newFile("m.gguf").apply { writeBytes(ByteArray(16)) }

    private fun texts(events: List<GenerationEvent>) = events.filterIsInstance<GenerationEvent.Token>().joinToString("") { it.text }

    private fun finished(events: List<GenerationEvent>) = events.last() as GenerationEvent.Finished

    // ---- Availability and loading ----

    @Test
    fun aMissingNativeLibraryIsReportedNotCrashing() = runBlocking {
        val e = engine(FakeNative(isLoaded = false))
        assertFalse(e.isAvailable)
        assertTrue(e.state.value is EngineState.Unavailable)
        val error = runCatching { e.load(modelFile().path) }.exceptionOrNull() as InferenceException
        assertEquals(InferenceErrorKind.ENGINE_UNAVAILABLE, error.kind)
    }

    @Test
    fun loadingReportsTheEngineMeasuredFacts() = runBlocking {
        val native = FakeNative()
        val e = engine(native)
        assertEquals(EngineState.NoModel, e.state.value)
        e.load(modelFile().path)
        val ready = e.state.value as EngineState.Ready
        assertEquals("fake 1M Q4_K", ready.model.description)
        assertEquals(1000L, ready.model.sizeBytes)
        assertEquals(2000L, ready.model.parameterCount)
        assertEquals(2048, ready.model.contextLength)
        assertEquals(1, native.inits)
        // Every native call ran on the one native thread.
        assertTrue(native.threads.toString(), native.threads.all { it.startsWith("llama-native") })
    }

    @Test
    fun aMissingFileNeverReachesNativeCode() = runBlocking {
        val native = FakeNative()
        val error = runCatching { engine(native).load(File(temp.root, "nope.gguf").path) }.exceptionOrNull() as InferenceException
        assertEquals(InferenceErrorKind.FILE_NOT_FOUND, error.kind)
        assertEquals(0, native.inits)
    }

    @Test
    fun nativeLoadFailuresBecomeStructuredErrors() = runBlocking {
        val native = FakeNative()
        val e = engine(native)
        native.loadError2 = NativeLlamaException(NativeErrorCode.MODEL_LOAD_FAILED, "unsupported architecture")
        val first = runCatching { e.load(modelFile().path) }.exceptionOrNull() as InferenceException
        assertEquals(InferenceErrorKind.MODEL_LOAD_FAILED, first.kind)
        assertTrue(e.state.value is EngineState.LoadFailed)

        native.loadError2 = NativeLlamaException(NativeErrorCode.CONTEXT_FAILED, "oom")
        val second = runCatching { e.load(temp.newFile("b.gguf").path) }.exceptionOrNull() as InferenceException
        assertEquals(InferenceErrorKind.CONTEXT_FAILED, second.kind)
        assertTrue(native.live.isEmpty())
    }

    @Test
    fun loadingAnotherModelFreesThePreviousOneExactlyOnce() = runBlocking {
        val native = FakeNative()
        val e = engine(native)
        repeat(5) { i -> e.load(temp.newFile("m$i.gguf").path) }
        assertEquals(1, native.live.size)
        assertEquals(4, native.freed.size)
        e.unload()
        e.unload() // repeated cleanup is harmless
        assertTrue(native.live.isEmpty())
        assertEquals(EngineState.NoModel, e.state.value)
        assertEquals(1, native.inits)
    }

    // ---- Generation ----

    @Test
    fun generationStreamsTokensUsingTheModelTemplate() = runBlocking {
        val native = FakeNative()
        val e = engine(native)
        e.load(modelFile().path)
        val events = e.generate(messages).toList()
        assertEquals(listOf("Hello", ", ", "world"), events.filterIsInstance<GenerationEvent.Token>().map { it.text })
        val done = finished(events)
        assertEquals(StopReason.END_OF_TEXT, done.reason)
        assertEquals(3, done.generatedTokens)
        assertEquals(7, done.promptTokens)
        assertEquals("<user>Hi<assistant>", native.prompts.single())
        assertTrue(e.state.value is EngineState.Ready)
    }

    @Test
    fun modelsWithoutATemplateUseTheDocumentedFallback() = runBlocking {
        val native = FakeNative(template = null)
        val e = engine(native)
        e.load(modelFile().path)
        e.generate(listOf(PromptMessage("system", "Be brief."), PromptMessage("user", "Hi"))).toList()
        assertEquals("System: Be brief.\n\nUser: Hi\n\nAssistant:", native.prompts.single())
    }

    @Test
    fun multiByteCharactersSplitAcrossTokensAreJoined() = runBlocking {
        val emoji = "👍".toByteArray() // 4 bytes
        val native = FakeNative(tokens = listOf("ok ".toByteArray(), emoji.copyOfRange(0, 2), emoji.copyOfRange(2, 4), "!".toByteArray()))
        val e = engine(native)
        e.load(modelFile().path)
        val events = e.generate(messages).toList()
        assertEquals("ok 👍!", texts(events))
        // No replacement characters were emitted for the half-character.
        assertFalse(texts(events).contains('�'))
    }

    @Test
    fun generatingWithoutAModelFails() = runBlocking {
        val error = runCatching { engine(FakeNative()).generate(messages).toList() }.exceptionOrNull() as InferenceException
        assertEquals(InferenceErrorKind.NO_MODEL_LOADED, error.kind)
    }

    @Test
    fun overlappingGenerationsAreRefused() = runBlocking {
        val native = FakeNative()
        val gate = CountDownLatch(1)
        native.tokenGate = gate
        val e = engine(native)
        e.load(modelFile().path)
        val first = async(Dispatchers.Default) { e.generate(messages).toList() }
        assertTrue(native.insideNextToken.await(5, TimeUnit.SECONDS))
        val second = runCatching { e.generate(messages).toList() }.exceptionOrNull() as InferenceException
        assertEquals(InferenceErrorKind.BUSY, second.kind)
        gate.countDown()
        assertEquals("Hello, world", texts(first.await()))
    }

    @Test
    fun stopEndsGenerationAndTheModelStaysReady() = runBlocking {
        val native = FakeNative()
        val gate = CountDownLatch(1)
        native.tokenGate = gate
        val e = engine(native)
        e.load(modelFile().path)
        val run = async(Dispatchers.Default) { e.generate(messages).toList() }
        assertTrue(native.insideNextToken.await(5, TimeUnit.SECONDS))
        assertTrue(e.state.value is EngineState.Generating)
        e.stop()
        gate.countDown()
        val events = withTimeout(5_000) { run.await() }
        assertEquals(StopReason.STOPPED, finished(events).reason)
        assertTrue(e.state.value is EngineState.Ready)
        // And a new generation works afterwards.
        native.tokenGate = null
        assertEquals("Hello, world", texts(e.generate(messages).toList()))
    }

    @Test
    fun cancellingTheCollectorStopsNativeGeneration() = runBlocking {
        val native = FakeNative()
        val gate = CountDownLatch(1)
        native.tokenGate = gate
        val e = engine(native)
        e.load(modelFile().path)
        val job = launch(Dispatchers.Default) { e.generate(messages).collect() }
        assertTrue(native.insideNextToken.await(5, TimeUnit.SECONDS))
        job.cancel()
        gate.countDown()
        job.join()
        // The engine is free again and the native abort flag was raised.
        native.tokenGate = null
        assertEquals("Hello, world", texts(withTimeout(5_000) { e.generate(messages).toList() }))
    }

    @Test
    fun unloadDuringGenerationWaitsSoTheHandleIsNeverUsedAfterFree() = runBlocking {
        val native = FakeNative()
        val gate = CountDownLatch(1)
        native.tokenGate = gate
        val e = engine(native)
        e.load(modelFile().path)
        val run = async(Dispatchers.Default) { runCatching { e.generate(messages).toList() } }
        assertTrue(native.insideNextToken.await(5, TimeUnit.SECONDS))
        val unload = async(Dispatchers.Default) { e.unload() }
        gate.countDown()
        withTimeout(5_000) { unload.await() }
        val events = run.await().getOrThrow()
        assertEquals(StopReason.STOPPED, finished(events).reason)
        assertTrue(native.live.isEmpty()) // FakeNative.check() would have failed on use-after-free
        assertEquals(EngineState.NoModel, e.state.value)
    }

    @Test
    fun generationFailuresAreStructured() = runBlocking {
        val native = FakeNative()
        val e = engine(native)
        e.load(modelFile().path)
        native.startError = NativeLlamaException(NativeErrorCode.PROMPT_TOO_LONG, "3000 tokens")
        val tooLong = runCatching { e.generate(messages).toList() }.exceptionOrNull() as InferenceException
        assertEquals(InferenceErrorKind.PROMPT_TOO_LONG, tooLong.kind)
        native.startError = null
        native.nextError = NativeLlamaException(NativeErrorCode.DECODE_FAILED, "rc=-3")
        val failed = runCatching { e.generate(messages).toList() }.exceptionOrNull() as InferenceException
        assertEquals(InferenceErrorKind.GENERATION_FAILED, failed.kind)
        // The model is still loaded and usable after a failed generation.
        assertTrue(e.state.value is EngineState.Ready)
        native.nextError = null
        assertEquals("Hello, world", texts(e.generate(messages).toList()))
    }

    @Test
    fun maxTokensIsPassedThrough() = runBlocking {
        val native = FakeNative()
        val e = engine(native)
        e.load(modelFile().path)
        val events = e.generate(messages, GenerationParams(maxTokens = 2, temperature = 0f)).toList()
        assertTrue(events.last() is GenerationEvent.Finished)
    }

    // ---- UTF-8 assembly ----

    @Test
    fun utf8AccumulatorHoldsBackIncompleteSequencesOnly() {
        val acc = Utf8Accumulator()
        val euro = "€".toByteArray() // 3 bytes
        assertEquals("a", acc.feed("a".toByteArray() + euro.copyOfRange(0, 1)))
        assertEquals("", acc.feed(euro.copyOfRange(1, 2)))
        assertEquals("€b", acc.feed(euro.copyOfRange(2, 3) + "b".toByteArray()))
        assertEquals("", acc.flush())
        // A dangling partial sequence at the end becomes a replacement character, not silence.
        acc.feed(euro.copyOfRange(0, 2))
        assertEquals("�", acc.flush())
    }

    @Test
    fun completePrefixLengthHandlesAllSequenceLengths() {
        listOf("a", "é", "€", "👍").forEach { ch ->
            val bytes = ch.toByteArray()
            assertEquals(ch, bytes.size, Utf8Accumulator.completePrefixLength(bytes))
            for (cut in 1 until bytes.size) assertEquals(0, Utf8Accumulator.completePrefixLength(bytes.copyOfRange(0, cut)))
        }
    }
}
