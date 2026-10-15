package com.example.localai.data.gguf

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the header of a GGUF file (versions 2 and 3, little-endian) from the first bytes of the
 * file. It never reads tensor data, never throws on bad input, and bounds every count, length
 * and total byte count so a hostile or corrupt file cannot exhaust memory.
 */
object GgufParser {

    /** Default byte budget: enough for headers with large tokenizer arrays, far below a model. */
    const val DEFAULT_MAX_HEADER_BYTES = 128L * 1024 * 1024

    private const val MAGIC = 0x46554747 // "GGUF" read as little-endian u32
    private const val MAX_KV_COUNT = 100_000L
    private const val MAX_TENSOR_COUNT = 200_000L
    private const val MAX_ARRAY_LENGTH = 50_000_000L
    private const val MAX_KEY_BYTES = 4096L
    private const val MAX_STRING_BYTES = 4L * 1024 * 1024
    private const val MAX_TENSOR_NAME_BYTES = 1024L
    private const val MAX_DIMS = 4
    private const val DEFAULT_ALIGNMENT = 32

    fun parse(bytes: ByteArray, maxHeaderBytes: Long = DEFAULT_MAX_HEADER_BYTES): GgufParseResult =
        parse(bytes.inputStream(), maxHeaderBytes)

    fun parse(input: InputStream, maxHeaderBytes: Long = DEFAULT_MAX_HEADER_BYTES): GgufParseResult =
        try {
            GgufParseResult.Success(Reader(input, maxHeaderBytes).readHeader())
        } catch (e: GgufException) {
            GgufParseResult.Failure(e.kind, e.message.orEmpty())
        } catch (e: IOException) {
            GgufParseResult.Failure(GgufFailure.TRUNCATED, "Could not read the header: ${e.message}")
        } catch (e: RuntimeException) {
            GgufParseResult.Failure(GgufFailure.CORRUPT, "Unexpected header content: ${e.message}")
        }

    private class GgufException(val kind: GgufFailure, message: String) : Exception(message)

    private class Reader(private val input: InputStream, private val budget: Long) {
        private var position = 0L
        private val scratch = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

        fun readHeader(): GgufHeader {
            val magic = readU32()
            if (magic != MAGIC.toLong()) fail(GgufFailure.NOT_GGUF, "Missing GGUF magic number")
            val version = readU32()
            if (version > 0xFFFF) {
                fail(GgufFailure.UNSUPPORTED_ENDIANNESS, "Big-endian GGUF files are not supported")
            }
            if (version != 2L && version != 3L) {
                fail(GgufFailure.UNSUPPORTED_VERSION, "GGUF version $version is not supported")
            }
            val tensorCount = readU64()
            val kvCount = readU64()
            if (tensorCount !in 0..MAX_TENSOR_COUNT) fail(GgufFailure.CORRUPT, "Implausible tensor count $tensorCount")
            if (kvCount !in 0..MAX_KV_COUNT) fail(GgufFailure.CORRUPT, "Implausible metadata count $kvCount")

            val metadata = LinkedHashMap<String, GgufValue>()
            repeat(kvCount.toInt()) {
                val key = readString(MAX_KEY_BYTES)
                if (key.isEmpty()) fail(GgufFailure.CORRUPT, "Empty metadata key")
                val value = readValue(readU32().toInt(), depth = 0)
                if (metadata.put(key, value) != null) fail(GgufFailure.CORRUPT, "Duplicate metadata key '$key'")
            }

            val tensors = ArrayList<GgufTensorInfo>(minOf(tensorCount, 4096L).toInt())
            repeat(tensorCount.toInt()) {
                val name = readString(MAX_TENSOR_NAME_BYTES)
                val nDims = readU32()
                if (nDims !in 1..MAX_DIMS.toLong()) fail(GgufFailure.CORRUPT, "Tensor '$name' has $nDims dimensions")
                val dims = List(nDims.toInt()) { readU64() }
                val type = readU32()
                val offset = readU64()
                if (dims.any { it < 0 } || offset < 0) fail(GgufFailure.CORRUPT, "Tensor '$name' has a negative size or offset")
                tensors += GgufTensorInfo(name, dims, type.toInt(), offset)
            }

            val alignment = (metadata["general.alignment"] as? GgufValue.Integer)?.value ?: DEFAULT_ALIGNMENT.toLong()
            if (alignment <= 0 || alignment > 1 shl 20 || alignment and (alignment - 1) != 0L) {
                fail(GgufFailure.CORRUPT, "Invalid alignment $alignment")
            }
            return GgufHeader(version.toInt(), metadata, tensors, position, alignment.toInt())
        }

        private fun readValue(type: Int, depth: Int): GgufValue = when (type) {
            GgufValueType.UINT8 -> GgufValue.Integer(readFixed(1).get(0).toLong() and 0xFF)
            GgufValueType.INT8 -> GgufValue.Integer(readFixed(1).get(0).toLong())
            GgufValueType.UINT16 -> GgufValue.Integer(readFixed(2).short.toLong() and 0xFFFF)
            GgufValueType.INT16 -> GgufValue.Integer(readFixed(2).short.toLong())
            GgufValueType.UINT32 -> GgufValue.Integer(readFixed(4).int.toLong() and 0xFFFFFFFFL)
            GgufValueType.INT32 -> GgufValue.Integer(readFixed(4).int.toLong())
            GgufValueType.FLOAT32 -> GgufValue.Decimal(readFixed(4).float.toDouble())
            GgufValueType.BOOL -> GgufValue.Bool(readFixed(1).get(0) != 0.toByte())
            GgufValueType.STRING -> GgufValue.Text(readString(MAX_STRING_BYTES))
            GgufValueType.UINT64, GgufValueType.INT64 -> GgufValue.Integer(readFixed(8).long)
            GgufValueType.FLOAT64 -> GgufValue.Decimal(readFixed(8).double)
            GgufValueType.ARRAY -> readArray(depth)
            else -> fail(GgufFailure.CORRUPT, "Unknown metadata value type $type")
        }

        private fun readArray(depth: Int): GgufValue {
            if (depth >= 1) fail(GgufFailure.CORRUPT, "Nested arrays are too deep")
            val elementType = readU32().toInt()
            val length = readU64()
            if (length !in 0..MAX_ARRAY_LENGTH) fail(GgufFailure.CORRUPT, "Implausible array length $length")
            val fixed = GgufValueType.fixedSize(elementType)
            when {
                fixed != null -> skip(length * fixed) // at most 50M * 8 bytes, no overflow
                elementType == GgufValueType.STRING -> repeat(length.toInt()) { skip(readU64Bounded(MAX_STRING_BYTES)) }
                elementType == GgufValueType.ARRAY -> repeat(length.toInt()) { readArray(depth + 1) }
                else -> fail(GgufFailure.CORRUPT, "Unknown array element type $elementType")
            }
            return GgufValue.ArrayOf(elementType, length)
        }

        private fun readString(maxBytes: Long): String {
            val length = readU64Bounded(maxBytes)
            val bytes = ByteArray(length.toInt())
            readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        private fun readU64Bounded(max: Long): Long {
            val value = readU64()
            if (value < 0 || value > max) fail(GgufFailure.CORRUPT, "Implausible length $value")
            return value
        }

        private fun readU32(): Long = readFixed(4).int.toLong() and 0xFFFFFFFFL

        private fun readU64(): Long = readFixed(8).long

        private fun readFixed(n: Int): ByteBuffer {
            scratch.clear()
            readFully(scratch.array(), n)
            scratch.limit(n)
            return scratch
        }

        private fun readFully(target: ByteArray, count: Int = target.size) {
            charge(count.toLong())
            var read = 0
            while (read < count) {
                val n = input.read(target, read, count - read)
                if (n < 0) fail(GgufFailure.TRUNCATED, "Header ends unexpectedly")
                read += n
            }
        }

        private fun skip(count: Long) {
            charge(count)
            var left = count
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped > 0) {
                    left -= skipped
                } else {
                    if (input.read() < 0) fail(GgufFailure.TRUNCATED, "Header ends unexpectedly")
                    left--
                }
            }
        }

        private fun charge(bytes: Long) {
            if (bytes > budget - position) fail(GgufFailure.TOO_LARGE, "Header exceeds the $budget byte limit")
            position += bytes
        }

        private fun fail(kind: GgufFailure, message: String): Nothing = throw GgufException(kind, message)
    }
}
