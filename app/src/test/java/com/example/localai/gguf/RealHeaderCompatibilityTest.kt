package com.example.localai.gguf

import com.example.localai.data.catalog.IncompatibilityReason
import com.example.localai.data.catalog.RuntimeCompatibility
import com.example.localai.data.compat.CompatibilityEvaluator
import com.example.localai.data.compat.DeviceCapabilities
import com.example.localai.data.compat.FindingCode
import com.example.localai.data.compat.RepoAnalyzer
import com.example.localai.data.compat.RuntimeProfile
import com.example.localai.data.compat.Severity
import com.example.localai.data.compat.Verdict
import com.example.localai.data.compat.toRuntimeCompatibility
import com.example.localai.data.gguf.GgufFailure
import com.example.localai.data.gguf.GgufParseResult
import com.example.localai.data.gguf.GgufParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The evaluator fed with a real GGUF header (stories260K, header bytes only). */
class RealHeaderCompatibilityTest {

    private val header = checkNotNull(javaClass.getResource("/fixtures/stories260K.header.gguf")).readBytes()
    private val realSize = 1_185_376L
    private val gb = 1024L * 1024 * 1024
    private val device = DeviceCapabilities(totalRamBytes = 8 * gb, availableRamBytes = 4 * gb, freeStorageBytes = 40 * gb)
    private val variant = RepoAnalyzer.analyze(listOf(hfFile("tinyllamas/stories260K.gguf", realSize))).variants.single()

    private fun evaluate(parse: GgufParseResult?, reason: String? = null) =
        CompatibilityEvaluator.evaluate(variant, parse, device, RuntimeProfile.DEFAULT_ASSUMED, headerUnavailableReason = reason)

    @Test
    fun aRealHeaderPassesTheImplementedChecksWithExplainedWarnings() {
        val result = evaluate(GgufParser.parse(header))
        assertEquals(Verdict.MEETS_REQUIREMENTS, result.verdict)
        // All tensors are F32: allowed, but flagged as unquantized.
        assertTrue(result.has(FindingCode.UNQUANTIZED))
        assertTrue(result.findings.all { it.severity == Severity.WARNING })
        // 5 layers * 2048 tokens * 4 KV heads * (8 + 8) head dims * 2 bytes.
        assertEquals(5L * 2048 * 4 * 16 * 2, result.memory!!.kvCacheBytes)
        assertEquals(realSize, result.memory!!.weightsBytes)
    }

    @Test
    fun passingChecksIsNotLoadability() {
        val result = evaluate(GgufParser.parse(header))
        assertEquals(
            RuntimeCompatibility.Incompatible(IncompatibilityReason.NO_RUNTIME),
            result.toRuntimeCompatibility(RuntimeProfile.DEFAULT_ASSUMED)
        )
        val unverified = RuntimeProfile.DEFAULT_ASSUMED.copy(engineId = "llama.cpp", verified = false)
        assertEquals(RuntimeCompatibility.NotEvaluated, result.toRuntimeCompatibility(unverified))
    }

    @Test
    fun aListedSizeTooSmallForTheRealTensorDirectoryIsCaught() {
        val tooSmall = RepoAnalyzer.analyze(listOf(hfFile("tinyllamas/stories260K.gguf", realSize - 1))).variants.single()
        val result = CompatibilityEvaluator.evaluate(tooSmall, GgufParser.parse(header), device)
        assertEquals(Verdict.INCOMPATIBLE, result.verdict)
        assertTrue(result.has(FindingCode.FILE_TRUNCATED))
    }

    @Test
    fun incompleteInspectionIsUnknownNeverAPass() {
        for (cut in listOf(4, 100, 5_000, header.size - 1)) {
            val parse = GgufParser.parse(header.copyOf(cut))
            assertEquals(GgufFailure.TRUNCATED, (parse as GgufParseResult.Failure).kind)
            val result = evaluate(parse)
            assertEquals("cut at $cut", Verdict.UNKNOWN, result.verdict)
            assertTrue(result.has(FindingCode.HEADER_INCOMPLETE))
        }
        val overBudget = evaluate(GgufParser.parse(header, maxHeaderBytes = 8_192))
        assertEquals(Verdict.UNKNOWN, overBudget.verdict)
    }

    @Test
    fun aFailedFetchIsUnknownWithItsReason() {
        val result = evaluate(null, reason = "Reading the file timed out")
        assertEquals(Verdict.UNKNOWN, result.verdict)
        assertTrue(result.has(FindingCode.HEADER_FETCH_FAILED))
        assertFalse(result.has(FindingCode.HEADER_NOT_INSPECTED))
        assertTrue(result.findings.first { it.code == FindingCode.HEADER_FETCH_FAILED }.message.contains("timed out"))
    }
}
