package com.example.localai.gguf

import com.example.localai.data.catalog.IncompatibilityReason
import com.example.localai.data.catalog.RuntimeCompatibility
import com.example.localai.data.compat.CompatibilityAssessment
import com.example.localai.data.compat.CompatibilityEvaluator
import com.example.localai.data.compat.DeviceCapabilities
import com.example.localai.data.compat.FindingCode
import com.example.localai.data.compat.ModelVariant
import com.example.localai.data.compat.RepoAnalyzer
import com.example.localai.data.compat.RuntimeProfile
import com.example.localai.data.compat.Severity
import com.example.localai.data.compat.Verdict
import com.example.localai.data.compat.toRuntimeCompatibility
import com.example.localai.data.gguf.GgmlType
import com.example.localai.data.gguf.GgufParseResult
import com.example.localai.data.gguf.GgufParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityEvaluatorTest {

    private val gb = 1024L * 1024 * 1024
    private val phone = DeviceCapabilities(totalRamBytes = 8 * gb, availableRamBytes = 4 * gb, freeStorageBytes = 50 * gb)
    private val splitProfile = RuntimeProfile.DEFAULT_ASSUMED.copy(supportsSplitModels = true)

    private fun parsed(builder: GgufBuilder): GgufParseResult = GgufParser.parse(builder.header())

    private fun variant(vararg files: Pair<String, Long?>): ModelVariant =
        RepoAnalyzer.analyze(files.map { (path, size) -> hfFile(path, size) }).variants.single()

    /** A variant whose listed size matches the synthetic file, so no size contradiction arises. */
    private fun goodVariant(builder: GgufBuilder = GgufBuilder.tinyLlama(), name: String = "tiny-Q4_K_M.gguf") =
        variant(name to builder.file().size.toLong())

    private fun evaluate(
        variant: ModelVariant,
        header: GgufParseResult?,
        device: DeviceCapabilities = phone,
        profile: RuntimeProfile = RuntimeProfile.DEFAULT_ASSUMED,
        local: Map<String, Long> = emptyMap()
    ) = CompatibilityEvaluator.evaluate(variant, header, device, profile, local)

    private fun CompatibilityAssessment.codes(severity: Severity? = null) =
        findings.filter { severity == null || it.severity == severity }.map { it.code }

    // ---- The core guarantee: a .gguf name proves nothing ----

    @Test
    fun aGgufNameAloneIsNeverCompatible() {
        val names = listOf("model-Q4_K_M.gguf", "qwen2.5-0.5b-instruct-q4_k_m.gguf", "m-Q8_0.gguf", "x.gguf", "llama-IQ4_XS.gguf")
        for (name in names) {
            val result = evaluate(variant(name to 400_000_000L), header = null)
            assertEquals(name, Verdict.UNKNOWN, result.verdict)
            assertTrue(result.has(FindingCode.HEADER_NOT_INSPECTED))
        }
    }

    @Test
    fun aGgufFileThatIsNotGgufIsIncompatible() {
        val result = evaluate(goodVariant(), GgufParser.parse("<html>".toByteArray() + ByteArray(64)))
        assertEquals(Verdict.INCOMPATIBLE, result.verdict)
        assertTrue(result.has(FindingCode.NOT_A_MODEL_FILE))
    }

    @Test
    fun aValidModelMeetsRequirementsButIsFlaggedUnverified() {
        val builder = GgufBuilder.tinyLlama()
        val result = evaluate(goodVariant(builder), parsed(builder))
        assertEquals(Verdict.MEETS_REQUIREMENTS, result.verdict)
        assertFalse(result.profileVerified)
        assertTrue(result.codes(Severity.BLOCKER).isEmpty())
    }

    @Test
    fun memoryEstimateAddsWeightsKvCacheAndOverhead() {
        val builder = GgufBuilder.tinyLlama()
        val v = goodVariant(builder)
        val memory = evaluate(v, parsed(builder)).memory!!
        // 2 layers * 2048 tokens * 2 kv heads * (16 + 16) key/value width * 2 bytes (f16)
        assertEquals(2L * 2048 * 2 * 32 * 2, memory.kvCacheBytes)
        assertEquals(2048L, memory.contextTokens)
        assertEquals(v.totalSizeBytes, memory.weightsBytes)
        assertEquals(memory.weightsBytes + memory.kvCacheBytes!! + memory.overheadBytes, memory.totalBytes)
    }

    @Test
    fun contextIsCappedAtTheProfileContext() {
        val builder = GgufBuilder.tinyLlama(contextLength = 131_072)
        val memory = evaluate(goodVariant(builder), parsed(builder)).memory!!
        assertEquals(RuntimeProfile.DEFAULT_ASSUMED.contextTokens.toLong(), memory.contextTokens)
    }

    // ---- Architecture ----

    @Test
    fun architectureRules() {
        fun verdictFor(arch: String?): Verdict {
            val builder = GgufBuilder.tinyLlama(architecture = arch ?: "llama").also { }
            val header = if (arch == null) {
                parsed(GgufBuilder().u32("llama.block_count", 1))
            } else {
                parsed(builder)
            }
            return evaluate(goodVariant(builder), header).verdict
        }
        assertEquals(Verdict.MEETS_REQUIREMENTS, verdictFor("qwen2"))
        assertEquals(Verdict.MEETS_REQUIREMENTS, verdictFor("gemma3"))
        assertEquals(Verdict.INCOMPATIBLE, verdictFor("whisper"))
        assertEquals(Verdict.UNKNOWN, verdictFor("brand-new-arch"))
        assertEquals(Verdict.UNKNOWN, verdictFor(null))
    }

    @Test
    fun visionRequiredArchitecturesNeedAVisionRuntime() {
        val builder = GgufBuilder.tinyLlama(architecture = "qwen2vl")
        val blocked = evaluate(goodVariant(builder), parsed(builder))
        assertEquals(Verdict.INCOMPATIBLE, blocked.verdict)
        assertTrue(blocked.has(FindingCode.VISION_REQUIRED))

        val withVision = evaluate(
            goodVariant(builder), parsed(builder),
            profile = RuntimeProfile.DEFAULT_ASSUMED.copy(supportsVision = true)
        )
        assertFalse(withVision.has(FindingCode.VISION_REQUIRED))
        assertEquals(Verdict.UNKNOWN, withVision.verdict) // qwen2vl is still not a known-good architecture
    }

    @Test
    fun projectorAdapterAndEmbeddingFilesAreNotStandaloneModels() {
        fun check(builder: GgufBuilder, code: FindingCode) {
            val result = evaluate(goodVariant(builder), parsed(builder))
            assertEquals(Verdict.INCOMPATIBLE, result.verdict)
            assertTrue("expected $code in ${result.codes()}", result.has(code))
        }
        check(GgufBuilder.tinyLlama(architecture = "clip"), FindingCode.PROJECTOR_ONLY)
        check(GgufBuilder.tinyLlama { string("general.type", "adapter") }, FindingCode.ADAPTER_ONLY)
        check(GgufBuilder.tinyLlama(architecture = "bert"), FindingCode.NOT_GENERATIVE)
    }

    @Test
    fun aCompanionProjectorOnlyAddsATextOnlyWarning() {
        val builder = GgufBuilder.tinyLlama()
        val v = variant("m-Q4_K_M.gguf" to builder.file().size.toLong(), "mmproj-m-f16.gguf" to 1000L)
        val result = evaluate(v, parsed(builder))
        assertEquals(Verdict.MEETS_REQUIREMENTS, result.verdict)
        assertTrue(result.has(FindingCode.VISION_UNAVAILABLE))
        assertEquals(Severity.WARNING, result.findings.first { it.code == FindingCode.VISION_UNAVAILABLE }.severity)
    }

    // ---- Quantization ----

    @Test
    fun quantizationRulesFromTensorTypes() {
        fun verdictFor(type: GgmlType, fileType: Long? = null) = GgufBuilder.tinyLlama(fileType = fileType, weightType = type).let {
            evaluate(goodVariant(it), parsed(it))
        }
        assertEquals(Verdict.MEETS_REQUIREMENTS, verdictFor(GgmlType.Q6_K, 18).verdict)
        assertEquals(Verdict.MEETS_REQUIREMENTS, verdictFor(GgmlType.IQ4_XS, 30).verdict)
        assertEquals(Verdict.INCOMPATIBLE, verdictFor(GgmlType.Q8_K).verdict)
        assertTrue(verdictFor(GgmlType.Q8_K).has(FindingCode.QUANT_UNSUPPORTED))
        assertEquals(Verdict.UNKNOWN, verdictFor(GgmlType.MXFP4).verdict)
        assertEquals(Verdict.UNKNOWN, verdictFor(GgmlType.TQ1_0).verdict)
        // Unquantized is allowed but warned about.
        val f16 = verdictFor(GgmlType.F16, 1)
        assertEquals(Verdict.MEETS_REQUIREMENTS, f16.verdict)
        assertTrue(f16.has(FindingCode.UNQUANTIZED))
    }

    @Test
    fun anUnsupportedTensorTypeMixedIntoAnOtherwiseSupportedFileBlocksIt() {
        val builder = GgufBuilder.tinyLlama { tensor("odd", listOf(256), GgmlType.Q8_K) }
        assertEquals(Verdict.INCOMPATIBLE, evaluate(goodVariant(builder), parsed(builder)).verdict)
    }

    @Test
    fun unrecognisedTensorTypeIdsAreUnknownNotAssumedFine() {
        val builder = GgufBuilder.tinyLlama { tensorOfTypeId("new", listOf(32), 99) }
        val result = evaluate(variant("m-Q4_K_M.gguf" to 100_000L), parsed(builder))
        assertEquals(Verdict.UNKNOWN, result.verdict)
        assertTrue(result.has(FindingCode.QUANT_UNKNOWN))
    }

    @Test
    fun nameAndMetadataThatDisagreeUseTheMetadataAndWarn() {
        val builder = GgufBuilder.tinyLlama() // metadata: Q4_K_M
        val result = evaluate(goodVariant(builder, "tiny-Q8_0.gguf"), parsed(builder))
        assertEquals(Verdict.MEETS_REQUIREMENTS, result.verdict)
        assertEquals(Severity.WARNING, result.findings.first { it.code == FindingCode.QUANT_LABEL_MISMATCH }.severity)
    }

    @Test
    fun contradictoryNameCannotHideAnUnsupportedQuantization() {
        val builder = GgufBuilder.tinyLlama(weightType = GgmlType.Q8_K, fileType = null)
        val result = evaluate(goodVariant(builder, "tiny-Q4_K_M.gguf"), parsed(builder))
        assertEquals(Verdict.INCOMPATIBLE, result.verdict)
    }

    @Test
    fun nameOnlyQuantizationRules() {
        assertTrue(evaluate(variant("m-Q8_K.gguf" to 100L), null).has(FindingCode.QUANT_UNSUPPORTED))
        assertTrue(evaluate(variant("m-Q9_Z.gguf" to 100L), null).has(FindingCode.QUANT_UNKNOWN)) // label not recognised
        assertTrue(evaluate(variant("m-Q8_1.gguf" to 100L), null).has(FindingCode.QUANT_UNSUPPORTED))
        assertTrue(evaluate(variant("model.gguf" to 100L), null).has(FindingCode.QUANT_UNKNOWN))
    }

    // ---- Shards ----

    private fun shardParts(count: Int, splitCount: Long? = count.toLong()): Pair<ModelVariant, GgufParseResult> {
        val builder = GgufBuilder.tinyLlama { splitCount?.let { u32("split.count", it) } }
        val size = builder.file().size.toLong()
        val v = variant(*(1..count).map { "big-Q4_K_M-%05d-of-%05d.gguf".format(it, count) to size }.toTypedArray())
        return v to parsed(builder)
    }

    @Test
    fun shardedModelsAreBlockedWhileTheProfileCannotLoadThem() {
        val (v, header) = shardParts(3)
        val result = evaluate(v, header)
        assertEquals(Verdict.INCOMPATIBLE, result.verdict)
        assertTrue(result.has(FindingCode.SPLIT_UNSUPPORTED))
        assertEquals(
            RuntimeCompatibility.Incompatible(IncompatibilityReason.MULTI_PART_UNSUPPORTED),
            result.toRuntimeCompatibility(RuntimeProfile.DEFAULT_ASSUMED)
        )
    }

    @Test
    fun aCompleteShardSetMeetsRequirementsWhenSplitsAreSupported() {
        val (v, header) = shardParts(3)
        val result = evaluate(v, header, profile = splitProfile)
        assertEquals(result.codes().toString(), Verdict.MEETS_REQUIREMENTS, result.verdict)
        // Memory counts every shard, not just the first file.
        assertEquals(v.totalSizeBytes, result.memory!!.weightsBytes)
    }

    @Test
    fun missingShardsBlockEvenWithSplitSupport() {
        val builder = GgufBuilder.tinyLlama { u32("split.count", 3) }
        val size = builder.file().size.toLong()
        val v = variant("m-00001-of-00003.gguf" to size, "m-00003-of-00003.gguf" to size)
        val result = evaluate(v, parsed(builder), profile = splitProfile)
        assertEquals(Verdict.INCOMPATIBLE, result.verdict)
        assertTrue(result.has(FindingCode.SHARDS_MISSING))
    }

    @Test
    fun shardCountInMetadataMustMatchTheFileNames() {
        val (v, header) = shardParts(3, splitCount = 4)
        val result = evaluate(v, header, profile = splitProfile)
        assertEquals(Verdict.INCOMPATIBLE, result.verdict)
        assertTrue(result.has(FindingCode.SHARD_COUNT_CONFLICT))
    }

    @Test
    fun aSingleFileThatSaysItIsOnePartOfManyIsIncomplete() {
        val builder = GgufBuilder.tinyLlama { u32("split.count", 3) }
        val result = evaluate(goodVariant(builder), parsed(builder), profile = splitProfile)
        assertEquals(Verdict.INCOMPATIBLE, result.verdict)
        assertTrue(result.has(FindingCode.SHARDS_MISSING))
    }

    @Test
    fun conflictingShardNamesAndAmbiguousDuplicatesAreNotCompatible() {
        val builder = GgufBuilder.tinyLlama()
        val size = builder.file().size.toLong()
        val conflict = variant("m-00001-of-00002.gguf" to size, "m-00001-of-00003.gguf" to size, "m-00002-of-00003.gguf" to size)
        val conflictResult = evaluate(conflict, parsed(builder), profile = splitProfile)
        assertEquals(Verdict.INCOMPATIBLE, conflictResult.verdict)
        assertTrue(conflictResult.has(FindingCode.SHARD_COUNT_CONFLICT))

        val duplicate = variant("M-00001-of-00002.gguf" to size, "m-00001-of-00002.gguf" to size, "m-00002-of-00002.gguf" to size)
        val duplicateResult = evaluate(duplicate, parsed(builder), profile = splitProfile)
        assertEquals(Verdict.UNKNOWN, duplicateResult.verdict)
        assertTrue(duplicateResult.has(FindingCode.DUPLICATE_SHARDS))
    }

    // ---- Header and file integrity ----

    @Test
    fun truncatedHeaderIsUncertainButCorruptHeaderIsIncompatible() {
        val bytes = GgufBuilder.tinyLlama().header()
        val truncated = evaluate(goodVariant(), GgufParser.parse(bytes.copyOf(bytes.size / 2)))
        assertEquals(Verdict.UNKNOWN, truncated.verdict)
        assertTrue(truncated.has(FindingCode.HEADER_INCOMPLETE))

        val corrupt = evaluate(goodVariant(), parsed(GgufBuilder().rawKey("x", 42)))
        assertEquals(Verdict.INCOMPATIBLE, corrupt.verdict)
        assertTrue(corrupt.has(FindingCode.HEADER_INVALID))

        val badVersion = evaluate(goodVariant(), GgufParser.parse(GgufBuilder(version = 1).header()))
        assertEquals(Verdict.INCOMPATIBLE, badVersion.verdict)
    }

    @Test
    fun malformedTensorSizesAreCorruption() {
        val builder = GgufBuilder.tinyLlama { tensor("bad", listOf(100, 4), GgmlType.Q4_K) }
        val result = evaluate(variant("m-Q4_K_M.gguf" to 1_000_000L), parsed(builder))
        assertEquals(Verdict.INCOMPATIBLE, result.verdict)
        assertTrue(result.has(FindingCode.HEADER_INVALID))
    }

    @Test
    fun incompleteDownloadsAreNeverCompatible() {
        val builder = GgufBuilder.tinyLlama()
        val size = builder.file().size.toLong()
        val v = goodVariant(builder)
        val partial = evaluate(v, parsed(builder), local = mapOf(v.primaryFile.path to size - 10))
        assertEquals(Verdict.INCOMPATIBLE, partial.verdict)
        assertTrue(partial.has(FindingCode.INCOMPLETE_DOWNLOAD))
        assertTrue(partial.has(FindingCode.FILE_TRUNCATED))

        val oversized = evaluate(v, parsed(builder), local = mapOf(v.primaryFile.path to size + 5))
        assertTrue(oversized.has(FindingCode.SIZE_MISMATCH))

        val complete = evaluate(v, parsed(builder), local = mapOf(v.primaryFile.path to size))
        assertEquals(Verdict.MEETS_REQUIREMENTS, complete.verdict)
    }

    @Test
    fun aListedSizeSmallerThanTheHeaderRequiresIsContradictory() {
        val builder = GgufBuilder.tinyLlama()
        val tooSmall = variant("m-Q4_K_M.gguf" to builder.file().size.toLong() - 100)
        val result = evaluate(tooSmall, parsed(builder))
        assertEquals(Verdict.INCOMPATIBLE, result.verdict)
        assertTrue(result.has(FindingCode.FILE_TRUNCATED))
    }

    @Test
    fun missingAttentionShapeMakesMemoryUnestimableNotAssumedFine() {
        val builder = GgufBuilder().string("general.architecture", "llama").u32("general.file_type", 15)
            .tensor("w", listOf(256, 8), GgmlType.Q4_K)
        val result = evaluate(variant("m-Q4_K_M.gguf" to builder.file().size.toLong()), parsed(builder))
        assertEquals(Verdict.UNKNOWN, result.verdict)
        assertTrue(result.has(FindingCode.MEMORY_UNESTIMABLE))
        assertNull(result.memory!!.kvCacheBytes)
        assertTrue(result.has(FindingCode.CONTEXT_UNKNOWN))
    }

    // ---- Device resources ----

    @Test
    fun aModelBiggerThanRamIsInsufficientEvenFromTheFileNameAlone() {
        val result = evaluate(variant("big-Q4_K_M.gguf" to 9 * gb), header = null)
        assertEquals(Verdict.INSUFFICIENT_RESOURCES, result.verdict)
        assertTrue(result.has(FindingCode.INSUFFICIENT_MEMORY))
        // Both facts are reported: too big, and the header has not been checked.
        assertTrue(result.has(FindingCode.HEADER_NOT_INSPECTED))
    }

    @Test
    fun memoryIncludingKvCacheCanTipAModelOverTheLimit() {
        val builder = GgufBuilder.tinyLlama()
        val device = phone.copy(totalRamBytes = 1 * gb, availableRamBytes = 1 * gb, freeStorageBytes = 50 * gb)
        val v = variant("m-Q4_K_M.gguf" to (1 * gb - 400L * 1024 * 1024)) // weights alone fit in 85%, with KV they do not
        val result = evaluate(v, parsed(builder), device = device)
        assertEquals(Verdict.INSUFFICIENT_RESOURCES, result.verdict)
    }

    @Test
    fun tightMemoryIsAWarningNotAFailure() {
        val builder = GgufBuilder.tinyLlama()
        val device = phone.copy(totalRamBytes = 8 * gb, availableRamBytes = 2 * gb)
        val v = variant("m-Q4_K_M.gguf" to (2 * gb - 300L * 1024 * 1024))
        val result = evaluate(v, parsed(builder), device = device)
        assertEquals(Verdict.MEETS_REQUIREMENTS, result.verdict)
        assertTrue(result.has(FindingCode.TIGHT_MEMORY))
    }

    @Test
    fun storageIsCheckedAgainstTheRemainingDownload() {
        val builder = GgufBuilder.tinyLlama()
        val v = variant("m-Q4_K_M.gguf" to (3 * gb))
        val low = phone.copy(freeStorageBytes = 3 * gb)
        val short = evaluate(v, parsed(builder), device = low)
        assertEquals(Verdict.INSUFFICIENT_RESOURCES, short.verdict)
        assertTrue(short.has(FindingCode.INSUFFICIENT_STORAGE))
        assertEquals(3 * gb, short.remainingDownloadBytes)

        // Part of the file is already local, so less storage is needed.
        val resumed = evaluate(v, parsed(builder), device = low.copy(freeStorageBytes = 2 * gb), local = mapOf(v.primaryFile.path to 2 * gb))
        assertFalse(resumed.has(FindingCode.INSUFFICIENT_STORAGE))
        assertEquals(1 * gb, resumed.remainingDownloadBytes)
    }

    @Test
    fun unknownFileSizesAreNotInvented() {
        val builder = GgufBuilder.tinyLlama()
        val result = evaluate(variant("m-Q4_K_M.gguf" to null), parsed(builder))
        assertNull(result.remainingDownloadBytes)
        // Weights size falls back to what the tensor directory implies, so memory is still estimated.
        assertNotNull(result.memory)
    }

    @Test
    fun definiteBlockersOutrankResourceProblemsWhichOutrankUncertainty() {
        val huge = variant("m-Q8_1-00001-of-00002.gguf" to 20 * gb, "m-Q8_1-00002-of-00002.gguf" to 20 * gb)
        val result = evaluate(huge, header = null)
        assertEquals(Verdict.INCOMPATIBLE, result.verdict)
        assertEquals(Severity.BLOCKER, result.findings.first().severity)
        assertTrue(result.has(FindingCode.INSUFFICIENT_MEMORY))
    }

    // ---- Mapping to the app-wide runtime state ----

    @Test
    fun withoutAnEngineNothingIsEverLoadable() {
        val builder = GgufBuilder.tinyLlama()
        val result = evaluate(goodVariant(builder), parsed(builder))
        assertEquals(Verdict.MEETS_REQUIREMENTS, result.verdict)
        assertEquals(
            RuntimeCompatibility.Incompatible(IncompatibilityReason.NO_RUNTIME),
            result.toRuntimeCompatibility(RuntimeProfile.DEFAULT_ASSUMED)
        )
    }

    @Test
    fun anUnverifiedEngineNeverProducesLoadable() {
        val engine = RuntimeProfile.DEFAULT_ASSUMED.copy(engineId = "llama.cpp", verified = false)
        val builder = GgufBuilder.tinyLlama()
        val result = evaluate(goodVariant(builder), parsed(builder), profile = engine)
        assertEquals(RuntimeCompatibility.NotEvaluated, result.toRuntimeCompatibility(engine))
    }

    @Test
    fun onlyAVerifiedEngineAndAPassingVerdictProduceLoadable() {
        val engine = RuntimeProfile.DEFAULT_ASSUMED.copy(engineId = "llama.cpp", verified = true)
        val builder = GgufBuilder.tinyLlama()
        val ok = evaluate(goodVariant(builder), parsed(builder), profile = engine)
        assertEquals(RuntimeCompatibility.Loadable("llama.cpp"), ok.toRuntimeCompatibility(engine))

        val unknown = evaluate(goodVariant(builder), null, profile = engine)
        assertEquals(RuntimeCompatibility.NotEvaluated, unknown.toRuntimeCompatibility(engine))

        val blocked = evaluate(goodVariant(GgufBuilder.tinyLlama(architecture = "clip")), parsed(GgufBuilder.tinyLlama(architecture = "clip")), profile = engine)
        assertEquals(
            RuntimeCompatibility.Incompatible(IncompatibilityReason.AUXILIARY_FILE),
            blocked.toRuntimeCompatibility(engine)
        )
    }
}
