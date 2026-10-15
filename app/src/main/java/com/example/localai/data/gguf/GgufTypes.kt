package com.example.localai.data.gguf

/**
 * Tensor storage types (ggml_type ids) with the block layout needed to compute tensor sizes.
 * Ids and layouts follow upstream ggml; types removed upstream are intentionally absent and
 * show up as unknown ids.
 */
enum class GgmlType(val id: Int, val label: String, val blockSize: Int, val typeSize: Int) {
    F32(0, "F32", 1, 4),
    F16(1, "F16", 1, 2),
    Q4_0(2, "Q4_0", 32, 18),
    Q4_1(3, "Q4_1", 32, 20),
    Q5_0(6, "Q5_0", 32, 22),
    Q5_1(7, "Q5_1", 32, 24),
    Q8_0(8, "Q8_0", 32, 34),
    Q8_1(9, "Q8_1", 32, 36),
    Q2_K(10, "Q2_K", 256, 84),
    Q3_K(11, "Q3_K", 256, 110),
    Q4_K(12, "Q4_K", 256, 144),
    Q5_K(13, "Q5_K", 256, 176),
    Q6_K(14, "Q6_K", 256, 210),
    Q8_K(15, "Q8_K", 256, 292),
    IQ2_XXS(16, "IQ2_XXS", 256, 66),
    IQ2_XS(17, "IQ2_XS", 256, 74),
    IQ3_XXS(18, "IQ3_XXS", 256, 98),
    IQ1_S(19, "IQ1_S", 256, 50),
    IQ4_NL(20, "IQ4_NL", 32, 18),
    IQ3_S(21, "IQ3_S", 256, 110),
    IQ2_S(22, "IQ2_S", 256, 82),
    IQ4_XS(23, "IQ4_XS", 256, 136),
    I8(24, "I8", 1, 1),
    I16(25, "I16", 1, 2),
    I32(26, "I32", 1, 4),
    I64(27, "I64", 1, 8),
    F64(28, "F64", 1, 8),
    IQ1_M(29, "IQ1_M", 256, 56),
    BF16(30, "BF16", 1, 2),
    TQ1_0(34, "TQ1_0", 256, 54),
    TQ2_0(35, "TQ2_0", 256, 66),
    MXFP4(39, "MXFP4", 32, 17);

    /** True for the unquantized floating-point types. */
    val isFullPrecision: Boolean get() = this == F32 || this == F16 || this == BF16 || this == F64

    /**
     * Bytes of a tensor with GGUF dimensions [dims] (dims[0] is the contiguous row), or null when
     * the row length is not a multiple of the block size or the size overflows a Long.
     */
    fun tensorBytes(dims: List<Long>): Long? {
        if (dims.isEmpty() || dims.any { it < 0 }) return null
        val row = dims[0]
        if (row % blockSize != 0L) return null
        return try {
            var bytes = Math.multiplyExact(row / blockSize, typeSize.toLong())
            for (i in 1 until dims.size) bytes = Math.multiplyExact(bytes, dims[i])
            bytes
        } catch (_: ArithmeticException) {
            null
        }
    }

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: Int): GgmlType? = byId[id]
    }
}

/** Value type codes of GGUF metadata. */
internal object GgufValueType {
    const val UINT8 = 0
    const val INT8 = 1
    const val UINT16 = 2
    const val INT16 = 3
    const val UINT32 = 4
    const val INT32 = 5
    const val FLOAT32 = 6
    const val BOOL = 7
    const val STRING = 8
    const val ARRAY = 9
    const val UINT64 = 10
    const val INT64 = 11
    const val FLOAT64 = 12

    /** Byte width of fixed-size scalars, or null for strings, arrays and unknown codes. */
    fun fixedSize(type: Int): Int? = when (type) {
        UINT8, INT8, BOOL -> 1
        UINT16, INT16 -> 2
        UINT32, INT32, FLOAT32 -> 4
        UINT64, INT64, FLOAT64 -> 8
        else -> null
    }
}

/**
 * Quantization names. [fileTypeLabel] decodes general.file_type (llama_ftype); [parseLabel] finds
 * a quantization name inside a filename. Both resolve to the dominant [GgmlType] where possible.
 */
object Quantization {

    private val fileTypes: Map<Int, Pair<String, GgmlType>> = mapOf(
        0 to ("F32" to GgmlType.F32),
        1 to ("F16" to GgmlType.F16),
        2 to ("Q4_0" to GgmlType.Q4_0),
        3 to ("Q4_1" to GgmlType.Q4_1),
        7 to ("Q8_0" to GgmlType.Q8_0),
        8 to ("Q5_0" to GgmlType.Q5_0),
        9 to ("Q5_1" to GgmlType.Q5_1),
        10 to ("Q2_K" to GgmlType.Q2_K),
        11 to ("Q3_K_S" to GgmlType.Q3_K),
        12 to ("Q3_K_M" to GgmlType.Q3_K),
        13 to ("Q3_K_L" to GgmlType.Q3_K),
        14 to ("Q4_K_S" to GgmlType.Q4_K),
        15 to ("Q4_K_M" to GgmlType.Q4_K),
        16 to ("Q5_K_S" to GgmlType.Q5_K),
        17 to ("Q5_K_M" to GgmlType.Q5_K),
        18 to ("Q6_K" to GgmlType.Q6_K),
        19 to ("IQ2_XXS" to GgmlType.IQ2_XXS),
        20 to ("IQ2_XS" to GgmlType.IQ2_XS),
        21 to ("Q2_K_S" to GgmlType.Q2_K),
        22 to ("IQ3_XS" to GgmlType.IQ3_S),
        23 to ("IQ3_XXS" to GgmlType.IQ3_XXS),
        24 to ("IQ1_S" to GgmlType.IQ1_S),
        25 to ("IQ4_NL" to GgmlType.IQ4_NL),
        26 to ("IQ3_S" to GgmlType.IQ3_S),
        27 to ("IQ3_M" to GgmlType.IQ3_S),
        28 to ("IQ2_S" to GgmlType.IQ2_S),
        29 to ("IQ2_M" to GgmlType.IQ2_S),
        30 to ("IQ4_XS" to GgmlType.IQ4_XS),
        31 to ("IQ1_M" to GgmlType.IQ1_M),
        32 to ("BF16" to GgmlType.BF16),
        36 to ("TQ1_0" to GgmlType.TQ1_0),
        37 to ("TQ2_0" to GgmlType.TQ2_0),
        38 to ("MXFP4" to GgmlType.MXFP4)
    )

    fun fileTypeLabel(id: Int): String? = fileTypes[id]?.first

    fun fileTypeBaseType(id: Int): GgmlType? = fileTypes[id]?.second

    // Quantization names embedded in filenames, e.g. "q4_k_m", "IQ3_XS", "UD-Q4_K_XL", "BF16".
    private val labelRegex = Regex(
        """(?<![A-Za-z0-9])((?:UD-)?(?:IQ\d(?:_[A-Z0-9]+)?|Q\d(?:_K)?(?:_[A-Z0-9]+)?|TQ[12]_0|BF16|FP16|F16|F32|MXFP4))(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE
    )

    /** Upper-cased quantization name found in [name], or null. FP16 is reported as F16. */
    fun parseLabel(name: String): String? {
        val raw = labelRegex.find(name)?.groupValues?.get(1)?.uppercase() ?: return null
        val label = raw.removePrefix("UD-")
        return if (label == "FP16") "F16" else label
    }

    /** The [GgmlType] a quantization label stands for, or null when it is not recognised. */
    fun baseType(label: String): GgmlType? {
        val l = label.uppercase()
        fileTypes.values.firstOrNull { it.first == l }?.let { return it.second }
        return when {
            l == "F32" -> GgmlType.F32
            l == "F16" -> GgmlType.F16
            l == "BF16" -> GgmlType.BF16
            l == "Q8_1" -> GgmlType.Q8_1
            l == "Q8_K" -> GgmlType.Q8_K
            l == "MXFP4" -> GgmlType.MXFP4
            l == "TQ1_0" -> GgmlType.TQ1_0
            l == "TQ2_0" -> GgmlType.TQ2_0
            l.startsWith("Q2_K") -> GgmlType.Q2_K
            l.startsWith("Q3_K") -> GgmlType.Q3_K
            l.startsWith("Q4_K") -> GgmlType.Q4_K
            l.startsWith("Q5_K") -> GgmlType.Q5_K
            l.startsWith("Q6_K") -> GgmlType.Q6_K
            l.startsWith("IQ1_S") -> GgmlType.IQ1_S
            l.startsWith("IQ1_M") -> GgmlType.IQ1_M
            l.startsWith("IQ2_XXS") -> GgmlType.IQ2_XXS
            l.startsWith("IQ2_XS") -> GgmlType.IQ2_XS
            l.startsWith("IQ2_S") || l.startsWith("IQ2_M") -> GgmlType.IQ2_S
            l.startsWith("IQ3_XXS") -> GgmlType.IQ3_XXS
            l.startsWith("IQ3_") -> GgmlType.IQ3_S
            l.startsWith("IQ4_NL") -> GgmlType.IQ4_NL
            l.startsWith("IQ4_XS") -> GgmlType.IQ4_XS
            else -> null
        }
    }
}
