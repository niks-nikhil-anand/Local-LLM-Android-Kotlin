package com.example.localai.data.compat

import com.example.localai.data.catalog.HfRepoFile
import com.example.localai.data.gguf.GgmlType

/**
 * One loadable unit found in a repository: a single GGUF, or all the shards of a split GGUF.
 * Built from file names and sizes only; whether it can run is decided by [CompatibilityEvaluator].
 */
data class ModelVariant(
    /** Stable id: the path of the single file, or the shared path prefix of the shards. */
    val id: String,
    /** Files of this variant ordered by shard index (one file when not sharded). */
    val files: List<HfRepoFile>,
    val quantLabel: String?,
    val quantType: GgmlType?,
    /** Total shard count when the names agree on one; null when not sharded or in conflict. */
    val declaredShardCount: Int?,
    val isSharded: Boolean,
    val missingShards: List<Int>,
    /** Shard indices that occur more than once. */
    val duplicateShards: List<Int>,
    /** The shard names disagree on how many shards exist, or an index is out of range. */
    val shardCountConflict: Boolean,
    /** Projector files sitting in the same directory; they extend, not replace, the model. */
    val companionProjectors: List<HfRepoFile>
) {
    /** Sum of file sizes, or null when any size is unknown. */
    val totalSizeBytes: Long?
        get() = if (files.any { it.sizeBytes == null }) null else files.sumOf { it.sizeBytes!! }

    val hasCompleteShardSet: Boolean
        get() = !isSharded || (missingShards.isEmpty() && duplicateShards.isEmpty() && !shardCountConflict)

    /** The file whose header describes the model: the single file or the first shard. */
    val primaryFile: HfRepoFile get() = files.first()
}

/** Everything found in one repository file list, grouped by role. */
data class RepoAnalysis(
    val variants: List<ModelVariant>,
    val projectors: List<HfRepoFile>,
    /** Weights in formats this runtime does not load (safetensors, ONNX, LiteRT, byte-split GGUF...). */
    val otherFormatWeights: List<FileClassification>,
    val ignored: List<FileClassification>
)

object RepoAnalyzer {

    fun analyze(files: List<HfRepoFile>): RepoAnalysis {
        val classified = files.map(FileClassifier::classify)
        val byRole = classified.groupBy { it.role }
        val projectors = byRole[FileRole.GGUF_PROJECTOR].orEmpty().map { it.file }
        val models = byRole[FileRole.GGUF_MODEL].orEmpty()

        val singles = models.filter { it.shard == null }
        val shardGroups = models.filter { it.shard != null }
            .groupBy { directoryOf(it.file.path) + it.shard!!.baseName.lowercase() }

        val variants = singles.map { single ->
            ModelVariant(
                id = single.file.path,
                files = listOf(single.file),
                quantLabel = single.quantLabel,
                quantType = single.quantType,
                declaredShardCount = null,
                isSharded = false,
                missingShards = emptyList(),
                duplicateShards = emptyList(),
                shardCountConflict = false,
                companionProjectors = projectorsFor(single.file, projectors)
            )
        } + shardGroups.values.map { group -> shardedVariant(group, projectors) }

        return RepoAnalysis(
            variants = variants.sortedBy { it.id.lowercase() },
            projectors = projectors,
            otherFormatWeights = byRole[FileRole.OTHER_WEIGHTS_FORMAT].orEmpty() +
                byRole[FileRole.GGUF_BYTE_SPLIT].orEmpty(),
            ignored = classified.filter {
                it.role == FileRole.TOKENIZER_OR_CONFIG || it.role == FileRole.DOCUMENTATION || it.role == FileRole.OTHER
            }
        )
    }

    private fun shardedVariant(group: List<FileClassification>, projectors: List<HfRepoFile>): ModelVariant {
        val shards = group.sortedWith(compareBy({ it.shard!!.index }, { it.file.path }))
        val totals = shards.map { it.shard!!.total }.toSet()
        val indices = shards.map { it.shard!!.index }
        val duplicates = indices.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        val conflict = totals.size > 1 || indices.any { it < 1 || it > totals.max() }
        val declared = totals.singleOrNull()
        val missing = if (declared != null && !conflict) (1..declared).filter { it !in indices } else emptyList()
        val first = shards.first()
        val prefix = directoryOf(first.file.path) + first.shard!!.baseName
        return ModelVariant(
            id = prefix,
            files = shards.map { it.file },
            quantLabel = first.quantLabel,
            quantType = first.quantType,
            declaredShardCount = declared,
            isSharded = true,
            missingShards = missing,
            duplicateShards = duplicates,
            shardCountConflict = conflict,
            companionProjectors = projectorsFor(first.file, projectors)
        )
    }

    private fun projectorsFor(file: HfRepoFile, projectors: List<HfRepoFile>): List<HfRepoFile> =
        projectors.filter { directoryOf(it.path) == directoryOf(file.path) }

    private fun directoryOf(path: String): String = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
}
