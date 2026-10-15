package com.example.localai.gguf

import com.example.localai.data.gguf.GgmlType
import com.example.localai.data.gguf.GgufFailure
import com.example.localai.data.gguf.GgufParseResult
import com.example.localai.data.gguf.GgufParser
import com.example.localai.data.gguf.GgufValue
import java.io.ByteArrayInputStream
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufParserTest {

    private fun parse(bytes: ByteArray, budget: Long = GgufParser.DEFAULT_MAX_HEADER_BYTES) =
        GgufParser.parse(bytes, budget)

    private fun failure(result: GgufParseResult): GgufFailure =
        (result as? GgufParseResult.Failure)?.kind ?: error("Expected failure but was $result")

    private fun success(result: GgufParseResult) =
        (result as? GgufParseResult.Success)?.header ?: error("Expected success but was $result")

    @Test
    fun parsesMetadataAndTensorDirectory() {
        val bytes = GgufBuilder.tinyLlama {
            bool("flag", true)
            f32("rope", 0.5f)
            u64("big", 5_000_000_000L)
        }.file()
        val header = success(parse(bytes))
        assertEquals(3, header.version)
        assertEquals("llama", header.string("general.architecture"))
        assertEquals(2048L, header.long("llama.context_length"))
        assertEquals(true, header.bool("flag"))
        assertEquals(5_000_000_000L, header.long("big"))
        assertEquals(0.5, (header.metadata["rope"] as GgufValue.Decimal).value, 0.0)
        assertEquals(3, header.tensors.size)
        val first = header.tensors.first()
        assertEquals("token_embd.weight", first.name)
        assertEquals(listOf(256L, 32L), first.dims)
        assertEquals(GgmlType.Q4_K, first.type)
        assertEquals(256L * 32 / 256 * 144, first.byteSize)
        assertEquals(32, header.alignment)
        assertEquals(0L, header.dataOffset % 32)
        assertTrue(header.dataOffset >= header.headerSizeBytes)
    }

    @Test
    fun acceptsVersion2() {
        assertEquals(2, success(parse(GgufBuilder(version = 2).string("general.architecture", "llama").header())).version)
    }

    @Test
    fun summarisesLargeArraysWithoutStoringThem() {
        val bytes = GgufBuilder.tinyLlama {
            stringArray("tokenizer.ggml.tokens.big", 100_000)
            u32Array("scores", 50_000)
        }.header()
        val header = success(parse(bytes))
        assertEquals(GgufValue.ArrayOf(8, 100_000), header.metadata["tokenizer.ggml.tokens.big"])
        assertEquals(GgufValue.ArrayOf(4, 50_000), header.metadata["scores"])
    }

    @Test
    fun readsOnlyTheHeaderNotTheTensorData() {
        val bytes = GgufBuilder.tinyLlama().file()
        val stream = ByteArrayInputStream(bytes)
        val header = success(GgufParser.parse(stream))
        assertEquals(bytes.size - header.headerSizeBytes.toInt(), stream.available())
    }

    @Test
    fun honoursCustomAlignment() {
        val header = success(parse(GgufBuilder().alignment(64).string("general.architecture", "llama").header()))
        assertEquals(64, header.alignment)
        assertEquals(0L, header.dataOffset % 64)
    }

    // ---- Malformed input ----

    @Test
    fun rejectsNonGgufAndEmptyInput() {
        assertEquals(GgufFailure.NOT_GGUF, failure(parse("<html>not a model</html>".toByteArray())))
        assertEquals(GgufFailure.TRUNCATED, failure(parse(ByteArray(0))))
        assertEquals(GgufFailure.TRUNCATED, failure(parse("GG".toByteArray())))
    }

    @Test
    fun rejectsUnsupportedVersionsAndEndianness() {
        assertEquals(GgufFailure.UNSUPPORTED_VERSION, failure(parse(GgufBuilder(version = 1).header())))
        assertEquals(GgufFailure.UNSUPPORTED_VERSION, failure(parse(GgufBuilder(version = 99).header())))
        assertEquals(
            GgufFailure.UNSUPPORTED_ENDIANNESS,
            failure(parse(GgufBuilder(version = 3, bigEndianVersion = true).header()))
        )
    }

    @Test
    fun everyTruncationPointIsReportedAsTruncated() {
        val bytes = GgufBuilder.tinyLlama().header()
        for (length in 0 until bytes.size) {
            val result = parse(bytes.copyOf(length))
            val kind = failure(result)
            assertTrue("length $length gave $kind", kind == GgufFailure.TRUNCATED || kind == GgufFailure.NOT_GGUF)
        }
        success(parse(bytes))
    }

    @Test
    fun rejectsImplausibleCounts() {
        assertEquals(GgufFailure.CORRUPT, failure(parse(GgufBuilder().declareCounts(kv = 5_000_000_000L).header())))
        assertEquals(GgufFailure.CORRUPT, failure(parse(GgufBuilder().declareCounts(tensorCount = Long.MAX_VALUE).header())))
        assertEquals(GgufFailure.CORRUPT, failure(parse(GgufBuilder().declareCounts(kv = -1).header())))
    }

    @Test
    fun rejectsOversizedArraysAndStrings() {
        val hugeArray = GgufBuilder().rawArrayHeader("tokens", 8, 4_000_000_000L).header()
        assertEquals(GgufFailure.CORRUPT, failure(parse(hugeArray)))
        // A string claiming 2^40 bytes must not allocate anything.
        val hugeString = GgufBuilder().rawKey("general.name", 8).header() +
            java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(1L shl 40).array()
        assertEquals(GgufFailure.CORRUPT, failure(parse(hugeString)))
    }

    @Test
    fun rejectsUnknownValueTypesAndNestedArrays() {
        assertEquals(GgufFailure.CORRUPT, failure(parse(GgufBuilder().rawKey("x", 42).header())))
        assertEquals(GgufFailure.CORRUPT, failure(parse(GgufBuilder().rawArrayHeader("x", 77, 1).header())))
    }

    @Test
    fun rejectsDuplicateKeysBadAlignmentAndBadTensors() {
        val duplicate = GgufBuilder().string("general.name", "a").string("general.name", "b").header()
        assertEquals(GgufFailure.CORRUPT, failure(parse(duplicate)))
        assertEquals(GgufFailure.CORRUPT, failure(parse(GgufBuilder().alignment(48).header())))
        val fiveDims = GgufBuilder().tensor("t", listOf(1, 1, 1, 1, 1), GgmlType.F32).header()
        assertEquals(GgufFailure.CORRUPT, failure(parse(fiveDims)))
    }

    @Test
    fun enforcesTheHeaderByteBudget() {
        val bytes = GgufBuilder.tinyLlama().header()
        assertEquals(GgufFailure.TOO_LARGE, failure(parse(bytes, budget = 64)))
        success(parse(bytes, budget = bytes.size.toLong()))
    }

    @Test
    fun randomBytesNeverThrow() {
        val random = Random(1234)
        val valid = GgufBuilder.tinyLlama().header()
        repeat(500) {
            val noise = ByteArray(random.nextInt(400)).also(random::nextBytes)
            parse(noise)
            // Flip bytes of a valid header: must yield a result, never an exception.
            val mutated = valid.copyOf().also { it[random.nextInt(it.size)] = random.nextInt().toByte() }
            parse(mutated, budget = 1_000_000)
        }
    }
}
