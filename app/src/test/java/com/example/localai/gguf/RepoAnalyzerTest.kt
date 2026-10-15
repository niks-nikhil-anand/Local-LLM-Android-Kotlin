package com.example.localai.gguf

import com.example.localai.data.catalog.HfRepoFile
import com.example.localai.data.catalog.HfRepoId
import com.example.localai.data.compat.FileClassifier
import com.example.localai.data.compat.FileRole
import com.example.localai.data.compat.RepoAnalyzer
import com.example.localai.data.gguf.GgmlType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun hfFile(path: String, size: Long? = 1000L) = HfRepoFile(HfRepoId("o/r"), "main", path, size, null)

class RepoAnalyzerTest {

    private fun role(path: String) = FileClassifier.classify(hfFile(path)).role

    @Test
    fun classifiesFilesByRole() {
        assertEquals(FileRole.GGUF_MODEL, role("qwen2.5-0.5b-instruct-q4_k_m.gguf"))
        assertEquals(FileRole.GGUF_MODEL, role("MODEL.GGUF"))
        assertEquals(FileRole.GGUF_PROJECTOR, role("mmproj-model-f16.gguf"))
        assertEquals(FileRole.GGUF_PROJECTOR, role("sub/Model-MMPROJ-Q8_0.gguf"))
        assertEquals(FileRole.GGUF_BYTE_SPLIT, role("model-q4_k_m.gguf.part1of2"))
        assertEquals(FileRole.OTHER_WEIGHTS_FORMAT, role("model.safetensors"))
        assertEquals(FileRole.OTHER_WEIGHTS_FORMAT, role("model-00001-of-00002.safetensors"))
        assertEquals(FileRole.OTHER_WEIGHTS_FORMAT, role("pytorch_model.bin"))
        assertEquals(FileRole.OTHER_WEIGHTS_FORMAT, role("model.onnx"))
        assertEquals(FileRole.OTHER_WEIGHTS_FORMAT, role("gemma.litertlm"))
        assertEquals(FileRole.OTHER_WEIGHTS_FORMAT, role("model.task"))
        assertEquals(FileRole.TOKENIZER_OR_CONFIG, role("tokenizer.json"))
        assertEquals(FileRole.TOKENIZER_OR_CONFIG, role("config.json"))
        assertEquals(FileRole.DOCUMENTATION, role("README.md"))
        assertEquals(FileRole.DOCUMENTATION, role("LICENSE"))
        assertEquals(FileRole.OTHER, role(".gitattributes"))
    }

    @Test
    fun readsQuantizationAndShardInfoFromNames() {
        val single = FileClassifier.classify(hfFile("qwen2.5-0.5b-instruct-q4_k_m.gguf"))
        assertEquals("Q4_K_M", single.quantLabel)
        assertEquals(GgmlType.Q4_K, single.quantType)
        assertNull(single.shard)

        val shard = FileClassifier.classify(hfFile("dir/Model-Q8_0-00002-of-00005.gguf")).shard!!
        assertEquals(2, shard.index)
        assertEquals(5, shard.total)
        assertEquals("Model-Q8_0", shard.baseName)
        assertEquals("Q8_0", FileClassifier.classify(hfFile("Model-Q8_0-00002-of-00005.gguf")).quantLabel)
    }

    @Test
    fun singleFilesBecomeSeparateVariants() {
        val analysis = RepoAnalyzer.analyze(
            listOf(hfFile("m-Q4_K_M.gguf", 4), hfFile("m-Q8_0.gguf", 8), hfFile("README.md"), hfFile("config.json"))
        )
        assertEquals(listOf("m-Q4_K_M.gguf", "m-Q8_0.gguf"), analysis.variants.map { it.id })
        assertTrue(analysis.variants.all { !it.isSharded && it.hasCompleteShardSet })
        assertEquals(4L, analysis.variants[0].totalSizeBytes)
        assertEquals(2, analysis.ignored.size)
    }

    @Test
    fun completeShardSetsGroupIntoOneVariant() {
        val analysis = RepoAnalyzer.analyze(
            listOf(3, 1, 2).map { hfFile("big-Q4_K_M-0000$it-of-00003.gguf", 100L * it) }
        )
        val variant = analysis.variants.single()
        assertTrue(variant.isSharded)
        assertEquals(3, variant.declaredShardCount)
        assertEquals("big-Q4_K_M-00001-of-00003.gguf", variant.primaryFile.path)
        assertTrue(variant.hasCompleteShardSet)
        assertEquals(600L, variant.totalSizeBytes)
        assertEquals("Q4_K_M", variant.quantLabel)
    }

    @Test
    fun missingShardsAreReported() {
        val variant = RepoAnalyzer.analyze(
            listOf(hfFile("m-00001-of-00004.gguf"), hfFile("m-00004-of-00004.gguf"))
        ).variants.single()
        assertEquals(listOf(2, 3), variant.missingShards)
        assertFalse(variant.hasCompleteShardSet)
    }

    @Test
    fun mismatchedShardCountsAreAConflict() {
        val variant = RepoAnalyzer.analyze(
            listOf(hfFile("m-00001-of-00002.gguf"), hfFile("m-00001-of-00003.gguf"), hfFile("m-00002-of-00003.gguf"))
        ).variants.single()
        assertTrue(variant.shardCountConflict)
        assertEquals(listOf(1), variant.duplicateShards)
        assertNull(variant.declaredShardCount)
        assertFalse(variant.hasCompleteShardSet)
    }

    @Test
    fun shardIndexBeyondTotalIsAConflict() {
        val variant = RepoAnalyzer.analyze(listOf(hfFile("m-00001-of-00002.gguf"), hfFile("m-00003-of-00002.gguf"))).variants.single()
        assertTrue(variant.shardCountConflict)
    }

    @Test
    fun caseVariantDuplicatesAreDetected() {
        val variant = RepoAnalyzer.analyze(
            listOf(hfFile("Model-00001-of-00002.gguf"), hfFile("model-00001-of-00002.gguf"), hfFile("model-00002-of-00002.gguf"))
        ).variants.single()
        assertEquals(listOf(1), variant.duplicateShards)
        assertFalse(variant.hasCompleteShardSet)
    }

    @Test
    fun unknownSizeMakesTotalUnknown() {
        val variant = RepoAnalyzer.analyze(listOf(hfFile("m-00001-of-00002.gguf", 5), hfFile("m-00002-of-00002.gguf", null))).variants.single()
        assertNull(variant.totalSizeBytes)
    }

    @Test
    fun shardedAndSingleFilesWithTheSameNameStayDistinct() {
        val analysis = RepoAnalyzer.analyze(listOf(hfFile("m-Q4_K_M.gguf"), hfFile("m-Q4_K_M-00001-of-00001.gguf")))
        assertEquals(2, analysis.variants.size)
        assertEquals(1, analysis.variants.count { it.isSharded })
    }

    @Test
    fun projectorsAttachToVariantsInTheSameDirectoryOnly() {
        val analysis = RepoAnalyzer.analyze(
            listOf(hfFile("vl-Q4_K_M.gguf"), hfFile("mmproj-vl-f16.gguf"), hfFile("other/x-Q4_K_M.gguf"))
        )
        assertEquals(listOf("mmproj-vl-f16.gguf"), analysis.projectors.map { it.path })
        assertEquals(1, analysis.variants.first { it.id == "vl-Q4_K_M.gguf" }.companionProjectors.size)
        assertTrue(analysis.variants.first { it.id == "other/x-Q4_K_M.gguf" }.companionProjectors.isEmpty())
        // The projector is never offered as a model of its own.
        assertFalse(analysis.variants.any { "mmproj" in it.id })
    }

    @Test
    fun otherFormatsAreListedButNeverVariants() {
        val analysis = RepoAnalyzer.analyze(
            listOf(hfFile("model.safetensors"), hfFile("model.gguf.part1of2"), hfFile("model.gguf.part2of2"))
        )
        assertTrue(analysis.variants.isEmpty())
        assertEquals(3, analysis.otherFormatWeights.size)
    }

    @Test
    fun emptyRepositoryYieldsNothing() {
        val analysis = RepoAnalyzer.analyze(emptyList())
        assertTrue(analysis.variants.isEmpty() && analysis.projectors.isEmpty())
    }
}
