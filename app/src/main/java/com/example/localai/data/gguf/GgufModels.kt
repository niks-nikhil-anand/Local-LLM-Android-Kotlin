package com.example.localai.data.gguf

/** A metadata value. Large arrays (tokens, merges, scores) are summarised, never materialised. */
sealed interface GgufValue {
    data class Integer(val value: Long) : GgufValue
    data class Decimal(val value: Double) : GgufValue
    data class Bool(val value: Boolean) : GgufValue
    data class Text(val value: String) : GgufValue
    data class ArrayOf(val elementType: Int, val length: Long) : GgufValue
}

data class GgufTensorInfo(
    val name: String,
    /** GGUF order: dims[0] is the contiguous row. */
    val dims: List<Long>,
    val typeId: Int,
    /** Offset of the tensor data relative to the start of the data section. */
    val offset: Long
) {
    val type: GgmlType? get() = GgmlType.fromId(typeId)

    /** Number of elements, or null on overflow. */
    val elementCount: Long?
        get() = try {
            dims.fold(1L) { acc, d -> Math.multiplyExact(acc, d) }
        } catch (_: ArithmeticException) {
            null
        }

    /** Stored size in bytes, or null for unknown types, misaligned rows or overflow. */
    val byteSize: Long? get() = type?.tensorBytes(dims)
}

/** Parsed GGUF header: metadata key/values and the tensor directory. No tensor data is read. */
data class GgufHeader(
    val version: Int,
    val metadata: Map<String, GgufValue>,
    val tensors: List<GgufTensorInfo>,
    /** Bytes consumed by the header, i.e. where the (unaligned) data section would begin. */
    val headerSizeBytes: Long,
    val alignment: Int
) {
    fun string(key: String): String? = (metadata[key] as? GgufValue.Text)?.value
    fun long(key: String): Long? = (metadata[key] as? GgufValue.Integer)?.value
    fun bool(key: String): Boolean? = (metadata[key] as? GgufValue.Bool)?.value
    fun arrayLength(key: String): Long? = (metadata[key] as? GgufValue.ArrayOf)?.length

    /** Start of the tensor data section: the header size rounded up to the alignment. */
    val dataOffset: Long get() = (headerSizeBytes + alignment - 1) / alignment * alignment
}

enum class GgufFailure {
    /** Does not start with the GGUF magic. */
    NOT_GGUF,
    UNSUPPORTED_VERSION,
    UNSUPPORTED_ENDIANNESS,

    /** The input ended before the header did: an incomplete download or too few bytes were read. */
    TRUNCATED,

    /** The header is larger than the byte budget given to the parser. */
    TOO_LARGE,

    /** Structurally invalid: absurd counts or lengths, bad types, duplicate keys. */
    CORRUPT
}

sealed interface GgufParseResult {
    data class Success(val header: GgufHeader) : GgufParseResult
    data class Failure(val kind: GgufFailure, val message: String) : GgufParseResult
}
