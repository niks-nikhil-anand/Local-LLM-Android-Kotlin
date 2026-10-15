package com.example.localai.gguf

import com.example.localai.data.gguf.GgmlType
import com.example.localai.data.gguf.GgufContentKind
import com.example.localai.data.gguf.GgufModelInfo
import com.example.localai.data.gguf.GgufParseResult
import com.example.localai.data.gguf.GgufParser
import com.example.localai.data.gguf.Quantization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufModelInfoTest {

    private fun info(builder: GgufBuilder): GgufModelInfo =
        GgufModelInfo.from((GgufParser.parse(builder.header()) as GgufParseResult.Success).header)

    @Test
    fun derivesDescriptiveFacts() {
        val builder = GgufBuilder.tinyLlama()
        val info = info(builder)
        assertEquals("llama", info.architecture)
        assertEquals("tiny-test", info.name)
        assertEquals("1M", info.sizeLabel)
        assertEquals(GgufContentKind.LANGUAGE_MODEL, info.kind)
        assertEquals(2048L, info.contextLength)
        assertEquals(64L, info.embeddingLength)
        assertEquals(2L, info.blockCount)
        assertEquals(4L, info.headCount)
        assertEquals(2L, info.headCountKv)
        assertEquals("Q4_K_M", info.fileTypeLabel)
        assertEquals(GgmlType.Q4_K, info.fileTypeBase)
        assertEquals(GgmlType.Q4_K, info.dominantWeightType) // the 1-D F32 norm does not count
        assertEquals("llama", info.tokenizerModel)
        assertEquals(3L, info.vocabularySize)
        assertTrue(info.hasChatTemplate)
        assertEquals(256L * 32 * 2 + 64, info.parameterCount)
        assertEquals(mapOf(GgmlType.Q4_K.id to 2, GgmlType.F32.id to 1), info.tensorTypeCounts)
    }

    @Test
    fun minimumFileSizeMatchesARealFile() {
        val builder = GgufBuilder.tinyLlama()
        val file = builder.file()
        val info = info(builder)
        assertEquals(file.size.toLong(), info.minimumFileBytes)
        assertTrue(info.tensorDataBytes!! > 0)
        assertTrue(info.malformedTensors.isEmpty())
    }

    @Test
    fun missingMetadataStaysNull() {
        val info = info(GgufBuilder().string("general.architecture", "llama"))
        assertNull(info.name)
        assertNull(info.contextLength)
        assertNull(info.fileTypeLabel)
        assertNull(info.dominantWeightType)
        assertNull(info.vocabularySize)
        assertFalse(info.hasChatTemplate)
        assertEquals(0L, info.tensorDataBytes)
        assertNull(info.headCountKv)
    }

    @Test
    fun headCountKvDefaultsToHeadCountButNotForPerLayerArrays() {
        val defaulted = info(GgufBuilder().string("general.architecture", "llama").u32("llama.attention.head_count", 8))
        assertEquals(8L, defaulted.headCountKv)
        val perLayer = info(
            GgufBuilder().string("general.architecture", "llama")
                .u32("llama.attention.head_count", 8)
                .u32Array("llama.attention.head_count_kv", 4)
        )
        assertNull(perLayer.headCountKv)
    }

    @Test
    fun detectsProjectorsAdaptersAndEmbeddingModels() {
        assertEquals(GgufContentKind.MULTIMODAL_PROJECTOR, info(GgufBuilder().string("general.architecture", "clip")).kind)
        assertEquals(
            GgufContentKind.MULTIMODAL_PROJECTOR,
            info(GgufBuilder().string("general.architecture", "llama").string("general.type", "mmproj")).kind
        )
        assertEquals(GgufContentKind.ADAPTER, info(GgufBuilder().string("general.architecture", "llama").string("general.type", "adapter")).kind)
        assertEquals(GgufContentKind.EMBEDDING_MODEL, info(GgufBuilder().string("general.architecture", "bert")).kind)
        assertEquals(
            GgufContentKind.EMBEDDING_MODEL,
            info(GgufBuilder().string("general.architecture", "llama").u32("llama.pooling_type", 1)).kind
        )
        assertTrue(info(GgufBuilder().string("general.architecture", "clip").bool("clip.has_audio_encoder", true)).hasAudioEncoder)
        assertTrue(info(GgufBuilder().string("general.architecture", "clip")).hasVisionEncoder)
    }

    @Test
    fun readsSplitMetadata() {
        val info = info(GgufBuilder().string("general.architecture", "llama").u32("split.no", 1).u32("split.count", 3))
        assertEquals(1, info.splitIndex)
        assertEquals(3, info.splitCount)
    }

    @Test
    fun unknownTensorTypesMakeTheSizeUnknown() {
        val info = info(GgufBuilder.tinyLlama { tensorOfTypeId("weird", listOf(32), 99) })
        assertEquals(setOf(99), info.unknownTensorTypeIds)
        assertNull(info.minimumFileBytes)
        assertNull(info.tensorDataBytes)
    }

    @Test
    fun rowsThatAreNotWholeBlocksAreMalformed() {
        val info = info(GgufBuilder().string("general.architecture", "llama").tensor("bad", listOf(100, 4), GgmlType.Q4_K))
        assertEquals(listOf("bad"), info.malformedTensors)
        assertNull(info.minimumFileBytes)
    }

    @Test
    fun parameterCountOverflowIsNullNotWrong() {
        val builder = GgufBuilder().string("general.architecture", "llama")
            .tensorOfTypeId("huge", listOf(Long.MAX_VALUE / 2, 8), GgmlType.F32.id)
        assertNull(info(builder).parameterCount)
    }

    // ---- Quantization names and sizes ----

    @Test
    fun parsesQuantizationLabelsFromFilenames() {
        val cases = mapOf(
            "qwen2.5-0.5b-instruct-q4_k_m" to "Q4_K_M",
            "Llama-3.2-1B-Instruct-Q8_0" to "Q8_0",
            "model-IQ3_XS" to "IQ3_XS",
            "model-UD-Q4_K_XL" to "Q4_K_XL",
            "gemma-2b.BF16" to "BF16",
            "model-fp16" to "F16",
            "phi-3-mini-4k-instruct-q4" to "Q4",
            "tinyllama-1.1b-chat-v1.0.Q5_K_S" to "Q5_K_S",
            "model-Q6_K" to "Q6_K",
            "Qwen3-0.6B-MXFP4" to "MXFP4",
            "llama-7b" to null,
            "model-mq4_k_m" to null
        )
        for ((name, expected) in cases) assertEquals(name, expected, Quantization.parseLabel(name))
    }

    @Test
    fun mapsLabelsToTensorTypes() {
        assertEquals(GgmlType.Q4_K, Quantization.baseType("Q4_K_M"))
        assertEquals(GgmlType.Q4_K, Quantization.baseType("Q4_K_XL"))
        assertEquals(GgmlType.Q3_K, Quantization.baseType("Q3_K_S"))
        assertEquals(GgmlType.Q2_K, Quantization.baseType("Q2_K"))
        assertEquals(GgmlType.IQ4_XS, Quantization.baseType("IQ4_XS"))
        assertEquals(GgmlType.IQ2_S, Quantization.baseType("IQ2_M"))
        assertEquals(GgmlType.Q8_0, Quantization.baseType("q8_0"))
        assertEquals(GgmlType.BF16, Quantization.baseType("BF16"))
        assertNull(Quantization.baseType("Q9_Z"))
    }

    @Test
    fun decodesFileTypeIds() {
        assertEquals("Q4_K_M", Quantization.fileTypeLabel(15))
        assertEquals("Q8_0", Quantization.fileTypeLabel(7))
        assertEquals(GgmlType.Q6_K, Quantization.fileTypeBaseType(18))
        assertNull(Quantization.fileTypeLabel(9999))
    }

    @Test
    fun computesTensorSizesFromBlockLayouts() {
        assertEquals(4096L, GgmlType.F32.tensorBytes(listOf(32, 32)))
        assertEquals(34L * 4, GgmlType.Q8_0.tensorBytes(listOf(128)))
        assertEquals(144L * 8, GgmlType.Q4_K.tensorBytes(listOf(256, 8)))
        assertNull(GgmlType.Q4_K.tensorBytes(listOf(255, 8)))
        assertNull(GgmlType.F32.tensorBytes(emptyList()))
        assertNull(GgmlType.F32.tensorBytes(listOf(Long.MAX_VALUE, 4)))
    }
}
