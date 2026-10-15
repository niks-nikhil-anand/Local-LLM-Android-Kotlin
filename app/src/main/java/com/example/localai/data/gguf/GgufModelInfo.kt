package com.example.localai.data.gguf

/** What a GGUF file claims to contain. Purely descriptive: no runtime decisions are made here. */
enum class GgufContentKind {
    /** A language model (the default when the metadata does not say otherwise). */
    LANGUAGE_MODEL,

    /** Embedding model (encoder or pooled output), not a text generator. */
    EMBEDDING_MODEL,

    /** Vision/audio projector (mmproj) that only works next to a language model. */
    MULTIMODAL_PROJECTOR,

    /** LoRA or other adapter that only works on top of a base model. */
    ADAPTER
}

/** Facts derived from a [GgufHeader]. Every field is null when the file does not provide it. */
data class GgufModelInfo(
    val ggufVersion: Int,
    val architecture: String?,
    val name: String?,
    /** Free-form size label such as "0.5B", exactly as the file states it. */
    val sizeLabel: String?,
    val kind: GgufContentKind,
    /** Sum of all tensor element counts: the real parameter count of this file. */
    val parameterCount: Long?,
    val contextLength: Long?,
    val embeddingLength: Long?,
    val blockCount: Long?,
    val headCount: Long?,
    /** Null also when the file stores per-layer values, which this analysis does not model. */
    val headCountKv: Long?,
    val keyLength: Long?,
    val valueLength: Long?,
    val expertCount: Long?,
    /** general.file_type decoded, e.g. "Q4_K_M". Informational: files mix tensor types. */
    val fileTypeLabel: String?,
    val fileTypeBase: GgmlType?,
    /** The tensor type holding most weights (tensors with at least two dimensions). */
    val dominantWeightType: GgmlType?,
    /** Tensor type ids seen in the file, mapped to tensor counts. Unknown ids are included. */
    val tensorTypeCounts: Map<Int, Int>,
    val tokenizerModel: String?,
    val vocabularySize: Long?,
    val hasChatTemplate: Boolean,
    val hasVisionEncoder: Boolean,
    val hasAudioEncoder: Boolean,
    val splitIndex: Int?,
    val splitCount: Int?,
    /** Bytes of tensor data implied by the tensor directory, or null if any tensor is unsizable. */
    val tensorDataBytes: Long?,
    /** Smallest file size that can hold the header and every tensor, or null if unknown. */
    val minimumFileBytes: Long?,
    /** Tensors whose declared size is impossible (e.g. a row not a multiple of the block size). */
    val malformedTensors: List<String>,
    val alignment: Int,
    val headerSizeBytes: Long
) {
    val unknownTensorTypeIds: Set<Int>
        get() = tensorTypeCounts.keys.filter { GgmlType.fromId(it) == null }.toSet()

    val tensorTypes: Set<GgmlType>
        get() = tensorTypeCounts.keys.mapNotNull { GgmlType.fromId(it) }.toSet()

    companion object {
        private val EMBEDDING_ARCHITECTURES = setOf("bert", "nomic-bert", "nomic-bert-moe", "jina-bert-v2", "neo-bert")

        fun from(header: GgufHeader): GgufModelInfo {
            val arch = header.string("general.architecture")
            fun archLong(suffix: String): Long? = arch?.let { header.long("$it.$suffix") }

            val tensors = header.tensors
            val sizes = tensors.map { it.byteSize }
            val malformed = tensors.filter { it.type != null && it.byteSize == null }.map { it.name }
            val dataBytes = if (tensors.isEmpty()) {
                0L
            } else if (sizes.any { it == null }) {
                null
            } else {
                try {
                    tensors.indices.maxOf { Math.addExact(tensors[it].offset, sizes[it]!!) }
                } catch (_: ArithmeticException) {
                    null
                }
            }
            val minimumFile = dataBytes?.let {
                try {
                    Math.addExact(header.dataOffset, it)
                } catch (_: ArithmeticException) {
                    null
                }
            }

            val elementSum = tensors.map { it.elementCount }
            val parameters = if (elementSum.any { it == null }) null else elementSum.sumOf { it!! }
            val fileType = header.long("general.file_type")?.toInt()
            val hasVision = header.bool("clip.has_vision_encoder") == true || arch == "clip"
            val hasAudio = header.bool("clip.has_audio_encoder") == true

            return GgufModelInfo(
                ggufVersion = header.version,
                architecture = arch,
                name = header.string("general.name"),
                sizeLabel = header.string("general.size_label"),
                kind = kindOf(header, arch),
                parameterCount = parameters,
                contextLength = archLong("context_length"),
                embeddingLength = archLong("embedding_length"),
                blockCount = archLong("block_count"),
                headCount = archLong("attention.head_count"),
                headCountKv = headCountKv(header, arch),
                keyLength = archLong("attention.key_length"),
                valueLength = archLong("attention.value_length"),
                expertCount = archLong("expert_count"),
                fileTypeLabel = fileType?.let { Quantization.fileTypeLabel(it) },
                fileTypeBase = fileType?.let { Quantization.fileTypeBaseType(it) },
                dominantWeightType = dominantType(tensors),
                tensorTypeCounts = tensors.groupingBy { it.typeId }.eachCount(),
                tokenizerModel = header.string("tokenizer.ggml.model"),
                vocabularySize = header.arrayLength("tokenizer.ggml.tokens") ?: archLong("vocab_size"),
                hasChatTemplate = !header.string("tokenizer.chat_template").isNullOrBlank(),
                hasVisionEncoder = hasVision,
                hasAudioEncoder = hasAudio,
                splitIndex = header.long("split.no")?.toInt(),
                splitCount = header.long("split.count")?.toInt(),
                tensorDataBytes = dataBytes,
                minimumFileBytes = minimumFile,
                malformedTensors = malformed,
                alignment = header.alignment,
                headerSizeBytes = header.headerSizeBytes
            )
        }

        /** llama.cpp defaults head_count_kv to head_count; per-layer arrays are not modelled. */
        private fun headCountKv(header: GgufHeader, arch: String?): Long? {
            if (arch == null) return null
            val kvKey = "$arch.attention.head_count_kv"
            if (header.metadata[kvKey] is GgufValue.ArrayOf) return null
            return header.long(kvKey) ?: header.long("$arch.attention.head_count")
        }

        private fun kindOf(header: GgufHeader, arch: String?): GgufContentKind {
            val declared = header.string("general.type")
            return when {
                declared == "mmproj" || arch == "clip" -> GgufContentKind.MULTIMODAL_PROJECTOR
                declared == "adapter" || arch == "lora" -> GgufContentKind.ADAPTER
                arch in EMBEDDING_ARCHITECTURES ||
                    (arch != null && header.long("$arch.pooling_type") != null) -> GgufContentKind.EMBEDDING_MODEL
                else -> GgufContentKind.LANGUAGE_MODEL
            }
        }

        private fun dominantType(tensors: List<GgufTensorInfo>): GgmlType? {
            val weights = tensors.filter { it.dims.size >= 2 }.ifEmpty { tensors }
            val byType = HashMap<GgmlType, Long>()
            for (t in weights) {
                val type = t.type ?: continue
                val elements = t.elementCount ?: continue
                byType.merge(type, elements, Long::plus)
            }
            return byType.maxByOrNull { it.value }?.key
        }
    }
}
