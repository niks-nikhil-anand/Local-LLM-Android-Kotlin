package com.example.localai.data.compat

import com.example.localai.data.catalog.HfRepoFile
import com.example.localai.data.gguf.GgmlType
import com.example.localai.data.gguf.Quantization

/** What a repository file is, judged from its name alone. Says nothing about whether it runs. */
enum class FileRole {
    /** A .gguf file that may hold language-model weights (or a shard of them). */
    GGUF_MODEL,

    /** A multimodal projector (mmproj) that needs a separate language model. */
    GGUF_PROJECTOR,

    /** A raw byte-split of one GGUF (model.gguf.part1of2): must be concatenated, not loadable. */
    GGUF_BYTE_SPLIT,

    /** Model weights in a format other than GGUF (safetensors, ONNX, LiteRT, PyTorch...). */
    OTHER_WEIGHTS_FORMAT,

    TOKENIZER_OR_CONFIG,
    DOCUMENTATION,
    OTHER
}

/** Position within a gguf-split set: file [index] of [total], both 1-based, sharing [baseName]. */
data class ShardInfo(val index: Int, val total: Int, val baseName: String)

data class FileClassification(
    val file: HfRepoFile,
    val role: FileRole,
    /** Upper-case file extension, e.g. GGUF or SAFETENSORS; empty if there is none. */
    val format: String,
    /** Quantization name found in the filename, e.g. Q4_K_M. A hint only; metadata is authoritative. */
    val quantLabel: String?,
    val quantType: GgmlType?,
    val shard: ShardInfo?
)

object FileClassifier {

    private val shardRegex = Regex("""^(.+)-(\d{5})-of-(\d{5})\.gguf$""", RegexOption.IGNORE_CASE)
    private val byteSplitRegex = Regex("""\.gguf\.(part\d+of\d+|\d+)$""", RegexOption.IGNORE_CASE)

    private val otherWeightFormats = setOf(
        "safetensors", "bin", "pt", "pth", "ckpt", "h5", "msgpack", "onnx", "tflite",
        "task", "litertlm", "mlmodel", "pb", "npz", "gptq"
    )
    private val tokenizerOrConfigNames = setOf(
        "tokenizer.json", "tokenizer.model", "tokenizer_config.json", "config.json",
        "generation_config.json", "special_tokens_map.json", "vocab.json", "merges.txt",
        "added_tokens.json", "preprocessor_config.json", "chat_template.json", "chat_template.jinja"
    )
    private val documentationExtensions = setOf("md", "txt", "png", "jpg", "jpeg", "gif", "svg", "webp", "yaml", "yml")

    fun classify(file: HfRepoFile): FileClassification {
        val name = file.fileName
        val lower = name.lowercase()
        val ext = file.extension
        val format = ext.uppercase()

        fun result(role: FileRole, label: String? = null, shard: ShardInfo? = null) = FileClassification(
            file = file,
            role = role,
            format = format,
            quantLabel = label,
            quantType = label?.let { Quantization.baseType(it) },
            shard = shard
        )

        return when {
            byteSplitRegex.containsMatchIn(lower) -> result(FileRole.GGUF_BYTE_SPLIT)
            ext == "gguf" -> {
                val match = shardRegex.matchEntire(name)
                val shard = match?.let {
                    ShardInfo(it.groupValues[2].toInt(), it.groupValues[3].toInt(), it.groupValues[1])
                }
                val label = Quantization.parseLabel(shard?.baseName ?: name.removeSuffix(".$ext"))
                val role = if ("mmproj" in lower) FileRole.GGUF_PROJECTOR else FileRole.GGUF_MODEL
                result(role, label, shard)
            }
            ext in otherWeightFormats -> result(FileRole.OTHER_WEIGHTS_FORMAT)
            lower in tokenizerOrConfigNames || lower.endsWith(".jinja") -> result(FileRole.TOKENIZER_OR_CONFIG)
            ext in documentationExtensions || lower.startsWith("license") -> result(FileRole.DOCUMENTATION)
            else -> result(FileRole.OTHER)
        }
    }
}
