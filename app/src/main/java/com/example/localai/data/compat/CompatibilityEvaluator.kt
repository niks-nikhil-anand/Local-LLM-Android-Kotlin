package com.example.localai.data.compat

import com.example.localai.data.catalog.IncompatibilityReason
import com.example.localai.data.catalog.RuntimeCompatibility
import com.example.localai.data.catalog.formatBytes
import com.example.localai.data.gguf.GgmlType
import com.example.localai.data.gguf.GgufContentKind
import com.example.localai.data.gguf.GgufFailure
import com.example.localai.data.gguf.GgufModelInfo
import com.example.localai.data.gguf.GgufParseResult

/**
 * Outcome of the checks, from worst to best. [MEETS_REQUIREMENTS] means every rule passed against
 * the [RuntimeProfile]; it is not a promise that the model runs, because the profile itself may be
 * unverified (see [RuntimeProfile.verified]).
 */
enum class Verdict { INCOMPATIBLE, INSUFFICIENT_RESOURCES, UNKNOWN, MEETS_REQUIREMENTS }

enum class Severity {
    /** The model cannot run on this backend. */
    BLOCKER,

    /** The model could run, but not on this device right now (RAM or storage). */
    RESOURCE,

    /** Not enough information to decide; treated as "not confirmed". */
    UNCERTAIN,

    /** Worth showing, but does not change the verdict. */
    WARNING
}

enum class FindingCode {
    NOT_A_MODEL_FILE,
    BYTE_SPLIT_FILE,
    PROJECTOR_ONLY,
    ADAPTER_ONLY,
    NOT_GENERATIVE,
    SHARDS_MISSING,
    SHARD_COUNT_CONFLICT,
    DUPLICATE_SHARDS,
    SPLIT_UNSUPPORTED,
    HEADER_NOT_INSPECTED,
    HEADER_FETCH_FAILED,
    HEADER_INCOMPLETE,
    HEADER_INVALID,
    ARCHITECTURE_MISSING,
    ARCHITECTURE_UNSUPPORTED,
    ARCHITECTURE_UNKNOWN,
    QUANT_UNSUPPORTED,
    QUANT_UNKNOWN,
    QUANT_LABEL_MISMATCH,
    UNQUANTIZED,
    VISION_REQUIRED,
    AUDIO_REQUIRED,
    VISION_UNAVAILABLE,
    INCOMPLETE_DOWNLOAD,
    SIZE_MISMATCH,
    FILE_TRUNCATED,
    SIZE_UNKNOWN,
    MEMORY_UNESTIMABLE,
    CONTEXT_UNKNOWN,
    INSUFFICIENT_MEMORY,
    TIGHT_MEMORY,
    INSUFFICIENT_STORAGE,
    RAM_UNKNOWN,
    AVAILABLE_RAM_UNKNOWN,
    LOW_MEMORY_STATE,
    LOW_RAM_DEVICE,
    STORAGE_UNKNOWN,
    RESOURCES_STALE,
    RESOURCES_INCONSISTENT
}

/** One explainable reason behind a verdict. */
data class Finding(val code: FindingCode, val severity: Severity, val message: String)

/**
 * Estimated resident memory: weights + KV cache at [contextTokens] + a fixed allowance.
 * [kvCacheBytes] and [totalBytes] are null when the file does not give the attention shape.
 */
data class MemoryEstimate(
    val weightsBytes: Long,
    val kvCacheBytes: Long?,
    val overheadBytes: Long,
    val contextTokens: Long?,
    val totalBytes: Long?
)

data class CompatibilityAssessment(
    val verdict: Verdict,
    /** Every finding, blockers first. Never empty for a non-MEETS verdict. */
    val findings: List<Finding>,
    val memory: MemoryEstimate?,
    /** Bytes still to download (all files minus what is already local); null if sizes are unknown. */
    val remainingDownloadBytes: Long?,
    val profileVerified: Boolean
) {
    fun has(code: FindingCode): Boolean = findings.any { it.code == code }
}

/**
 * Decides, with stated reasons, whether a [ModelVariant] fits the [RuntimeProfile] and device.
 * Pure function of its inputs: no I/O, no model loading. Assumptions live in [RuntimeProfile].
 *
 * Inputs: [header] is the parse result of the variant's primary file (null if not read yet;
 * [headerUnavailableReason] then says why, e.g. the download of its first bytes failed);
 * [localFileSizes] maps repository path to the bytes actually on disk (empty before download).
 */
object CompatibilityEvaluator {

    fun evaluate(
        variant: ModelVariant,
        header: GgufParseResult?,
        device: DeviceCapabilities,
        profile: RuntimeProfile = RuntimeProfile.DEFAULT_ASSUMED,
        localFileSizes: Map<String, Long> = emptyMap(),
        headerUnavailableReason: String? = null
    ): CompatibilityAssessment {
        val findings = mutableListOf<Finding>()
        fun add(code: FindingCode, severity: Severity, message: String) {
            findings += Finding(code, severity, message)
        }

        checkStructure(variant, profile, ::add)
        checkLocalFiles(variant, localFileSizes, ::add)

        var info: GgufModelInfo? = null
        when (header) {
            null -> {
                if (headerUnavailableReason != null) {
                    add(FindingCode.HEADER_FETCH_FAILED, Severity.UNCERTAIN,
                        "The file header could not be read ($headerUnavailableReason), so architecture, quantization and context length are unverified.")
                } else {
                    add(FindingCode.HEADER_NOT_INSPECTED, Severity.UNCERTAIN,
                        "The file header has not been read, so architecture, quantization and context length are unverified.")
                }
                checkQuantFromName(variant, profile, ::add)
            }
            is GgufParseResult.Failure -> reportHeaderFailure(header, ::add)
            is GgufParseResult.Success -> {
                info = GgufModelInfo.from(header.header)
                checkMetadata(variant, info, profile, localFileSizes, ::add)
            }
        }

        val memory = estimateMemory(variant, info, profile)
        checkSnapshot(device, profile, ::add)
        checkMemory(memory, device, profile, ::add)
        val remaining = remainingDownload(variant, localFileSizes)
        checkStorage(variant, remaining, device, profile, ::add)

        val ordered = findings.sortedBy { it.severity.ordinal }
        val verdict = when (ordered.firstOrNull { it.severity != Severity.WARNING }?.severity) {
            Severity.BLOCKER -> Verdict.INCOMPATIBLE
            Severity.RESOURCE -> Verdict.INSUFFICIENT_RESOURCES
            Severity.UNCERTAIN -> Verdict.UNKNOWN
            else -> Verdict.MEETS_REQUIREMENTS
        }
        return CompatibilityAssessment(verdict, ordered, memory, remaining, profile.verified)
    }

    // ---- Name- and structure-level rules ----

    private fun checkStructure(variant: ModelVariant, profile: RuntimeProfile, add: Adder) {
        if (variant.isSharded) {
            if (variant.shardCountConflict) {
                add(FindingCode.SHARD_COUNT_CONFLICT, Severity.BLOCKER,
                    "Shard file names disagree about how many parts the model has, or an index is out of range.")
            } else if (variant.missingShards.isNotEmpty()) {
                add(FindingCode.SHARDS_MISSING, Severity.BLOCKER,
                    "Missing shard(s) ${variant.missingShards.joinToString()} of ${variant.declaredShardCount}.")
            }
            if (variant.duplicateShards.isNotEmpty()) {
                add(FindingCode.DUPLICATE_SHARDS, Severity.UNCERTAIN,
                    "Shard(s) ${variant.duplicateShards.joinToString()} appear more than once, so the intended set is ambiguous.")
            }
            if (!profile.supportsSplitModels) {
                add(FindingCode.SPLIT_UNSUPPORTED, Severity.BLOCKER,
                    "Multi-part models are not supported by this runtime profile yet.")
            }
        }
    }

    private fun checkQuantFromName(variant: ModelVariant, profile: RuntimeProfile, add: Adder) {
        val label = variant.quantLabel
        if (label == null) {
            add(FindingCode.QUANT_UNKNOWN, Severity.UNCERTAIN, "The file name does not state a quantization.")
            return
        }
        quantFinding(label, variant.quantType, profile, add)
    }

    private fun quantFinding(label: String, type: GgmlType?, profile: RuntimeProfile, add: Adder) {
        when (profile.quantSupport(type)) {
            QuantSupport.SUPPORTED -> if (type?.isFullPrecision == true) {
                add(FindingCode.UNQUANTIZED, Severity.WARNING,
                    "$label is unquantized: very large and slow on a phone compared with a 4-bit or 8-bit variant.")
            }
            QuantSupport.UNSUPPORTED -> add(FindingCode.QUANT_UNSUPPORTED, Severity.BLOCKER,
                "Quantization $label is not supported by this runtime profile.")
            QuantSupport.UNKNOWN -> add(FindingCode.QUANT_UNKNOWN, Severity.UNCERTAIN,
                "Quantization $label is not known to work with this runtime profile.")
        }
    }

    // ---- Local files ----

    private fun checkLocalFiles(variant: ModelVariant, local: Map<String, Long>, add: Adder) {
        for (file in variant.files) {
            val onDisk = local[file.path] ?: continue
            val expected = file.sizeBytes ?: continue
            if (onDisk < expected) {
                add(FindingCode.INCOMPLETE_DOWNLOAD, Severity.BLOCKER,
                    "${file.fileName} is incomplete: ${formatBytes(onDisk)} of ${formatBytes(expected)}.")
            } else if (onDisk > expected) {
                add(FindingCode.SIZE_MISMATCH, Severity.BLOCKER,
                    "${file.fileName} is ${formatBytes(onDisk)} but the repository lists ${formatBytes(expected)}.")
            }
        }
    }

    // ---- Header failures and metadata rules ----

    private fun reportHeaderFailure(failure: GgufParseResult.Failure, add: Adder) {
        when (failure.kind) {
            GgufFailure.TRUNCATED, GgufFailure.TOO_LARGE -> add(FindingCode.HEADER_INCOMPLETE, Severity.UNCERTAIN,
                "The header could not be read completely (${failure.message}); the file may be incomplete.")
            GgufFailure.NOT_GGUF -> add(FindingCode.NOT_A_MODEL_FILE, Severity.BLOCKER,
                "The file is not a GGUF file despite its name.")
            else -> add(FindingCode.HEADER_INVALID, Severity.BLOCKER, "Invalid GGUF header: ${failure.message}")
        }
    }

    private fun checkMetadata(
        variant: ModelVariant,
        info: GgufModelInfo,
        profile: RuntimeProfile,
        local: Map<String, Long>,
        add: Adder
    ) {
        when (info.kind) {
            GgufContentKind.MULTIMODAL_PROJECTOR -> add(FindingCode.PROJECTOR_ONLY, Severity.BLOCKER,
                "This is a vision/audio projector, not a standalone model.")
            GgufContentKind.ADAPTER -> add(FindingCode.ADAPTER_ONLY, Severity.BLOCKER,
                "This is an adapter that needs a base model, not a standalone model.")
            GgufContentKind.EMBEDDING_MODEL -> add(FindingCode.NOT_GENERATIVE, Severity.BLOCKER,
                "This is an embedding model and cannot generate chat replies.")
            GgufContentKind.LANGUAGE_MODEL -> Unit
        }

        val arch = info.architecture
        when {
            arch == null -> add(FindingCode.ARCHITECTURE_MISSING, Severity.UNCERTAIN,
                "The file does not state its architecture.")
            arch in profile.unsupportedArchitectures -> add(FindingCode.ARCHITECTURE_UNSUPPORTED, Severity.BLOCKER,
                "Architecture '$arch' is not supported.")
            arch in profile.visionRequiredArchitectures && !profile.supportsVision -> add(
                FindingCode.VISION_REQUIRED, Severity.BLOCKER,
                "Architecture '$arch' needs a vision component this runtime does not support.")
            arch !in profile.supportedArchitectures -> add(FindingCode.ARCHITECTURE_UNKNOWN, Severity.UNCERTAIN,
                "Architecture '$arch' is not known to work with this runtime profile.")
        }
        if (info.hasAudioEncoder && !profile.supportsAudio && info.kind == GgufContentKind.LANGUAGE_MODEL) {
            add(FindingCode.AUDIO_REQUIRED, Severity.WARNING, "The model advertises an audio encoder; audio input is unavailable.")
        }
        if (variant.companionProjectors.isNotEmpty() && !profile.supportsVision) {
            add(FindingCode.VISION_UNAVAILABLE, Severity.WARNING,
                "The repository has a vision projector; image input is unavailable, text only.")
        }

        checkQuantFromMetadata(variant, info, profile, add)
        checkSplitMetadata(variant, info, add)
        checkSizes(variant, info, local, add)

        if (info.contextLength == null) {
            add(FindingCode.CONTEXT_UNKNOWN, Severity.WARNING,
                "The file does not state a context length; the runtime default of ${profile.contextTokens} tokens is assumed.")
        }
    }

    private fun checkQuantFromMetadata(variant: ModelVariant, info: GgufModelInfo, profile: RuntimeProfile, add: Adder) {
        if (info.malformedTensors.isNotEmpty()) {
            add(FindingCode.HEADER_INVALID, Severity.BLOCKER,
                "Tensor(s) with impossible sizes: ${info.malformedTensors.take(3).joinToString()}.")
        }
        if (info.unknownTensorTypeIds.isNotEmpty()) {
            add(FindingCode.QUANT_UNKNOWN, Severity.UNCERTAIN,
                "Unrecognised tensor type id(s) ${info.unknownTensorTypeIds.sorted().joinToString()}.")
        }
        val label = info.fileTypeLabel ?: info.dominantWeightType?.label
        if (info.tensorTypes.isEmpty() && info.unknownTensorTypeIds.isEmpty()) {
            add(FindingCode.QUANT_UNKNOWN, Severity.UNCERTAIN, "The file lists no tensors.")
        }
        // Every tensor type present must be usable; one unsupported type is enough to fail the load.
        for (type in info.tensorTypes) {
            when (profile.quantSupport(type)) {
                QuantSupport.UNSUPPORTED -> add(FindingCode.QUANT_UNSUPPORTED, Severity.BLOCKER,
                    "The file contains ${type.label} tensors, which this runtime profile does not support.")
                QuantSupport.UNKNOWN -> add(FindingCode.QUANT_UNKNOWN, Severity.UNCERTAIN,
                    "The file contains ${type.label} tensors, which are not known to work with this runtime profile.")
                QuantSupport.SUPPORTED -> Unit
            }
        }
        val dominant = info.dominantWeightType
        if (dominant != null && dominant.isFullPrecision && info.tensorTypes.all { profile.quantSupport(it) == QuantSupport.SUPPORTED }) {
            add(FindingCode.UNQUANTIZED, Severity.WARNING,
                "${label ?: dominant.label} weights are unquantized: very large and slow on a phone.")
        }
        // Contradiction between the file name and the file's own metadata: trust the metadata, say so.
        val named = variant.quantType
        val declared = info.fileTypeBase
        if (named != null && declared != null && named != declared) {
            add(FindingCode.QUANT_LABEL_MISMATCH, Severity.WARNING,
                "The file name says ${variant.quantLabel} but the file's metadata says ${info.fileTypeLabel}; the metadata is used.")
        }
    }

    private fun checkSplitMetadata(variant: ModelVariant, info: GgufModelInfo, add: Adder) {
        val count = info.splitCount ?: return
        if (count <= 1) return
        if (!variant.isSharded) {
            add(FindingCode.SHARDS_MISSING, Severity.BLOCKER,
                "The file declares it is one part of $count, but no complete set was found.")
        } else if (variant.declaredShardCount != null && variant.declaredShardCount != count) {
            add(FindingCode.SHARD_COUNT_CONFLICT, Severity.BLOCKER,
                "The file names list ${variant.declaredShardCount} parts but the file says $count.")
        }
    }

    private fun checkSizes(variant: ModelVariant, info: GgufModelInfo, local: Map<String, Long>, add: Adder) {
        val minimum = info.minimumFileBytes ?: return
        val primary = variant.primaryFile
        val listed = primary.sizeBytes
        if (listed != null && listed < minimum) {
            add(FindingCode.FILE_TRUNCATED, Severity.BLOCKER,
                "The repository lists ${formatBytes(listed)} but the header needs at least ${formatBytes(minimum)}.")
        }
        val onDisk = local[primary.path]
        if (onDisk != null && onDisk < minimum) {
            add(FindingCode.FILE_TRUNCATED, Severity.BLOCKER,
                "The local file is ${formatBytes(onDisk)} but the header needs at least ${formatBytes(minimum)}.")
        }
    }

    // ---- Resources ----

    private fun estimateMemory(variant: ModelVariant, info: GgufModelInfo?, profile: RuntimeProfile): MemoryEstimate? {
        val weights = variant.totalSizeBytes ?: info?.tensorDataBytes ?: return null
        val overhead = profile.computeOverheadBytes
        if (info == null) return MemoryEstimate(weights, null, overhead, null, null)

        val context = minOf(info.contextLength ?: profile.contextTokens.toLong(), profile.contextTokens.toLong())
        val keyLen = info.keyLength ?: info.embeddingLength?.let { e -> info.headCount?.takeIf { it > 0 }?.let { e / it } }
        val valueLen = info.valueLength ?: keyLen
        val layers = info.blockCount
        val kvHeads = info.headCountKv
        val kv = if (layers != null && kvHeads != null && keyLen != null && valueLen != null) {
            try {
                Math.multiplyExact(
                    Math.multiplyExact(layers, context),
                    Math.multiplyExact(kvHeads, Math.addExact(keyLen, valueLen)) * profile.kvBytesPerElement
                )
            } catch (_: ArithmeticException) {
                null
            }
        } else {
            null
        }
        return MemoryEstimate(weights, kv, overhead, context, kv?.let { weights + it + overhead })
    }

    /** Facts about the measurement itself: stale or contradictory readings are not trusted. */
    private fun checkSnapshot(device: DeviceCapabilities, profile: RuntimeProfile, add: Adder) {
        if (device.anomalies.isNotEmpty()) {
            add(FindingCode.RESOURCES_INCONSISTENT, Severity.UNCERTAIN,
                "Some device readings were impossible and were ignored: ${device.anomalies.joinToString("; ")}.")
        }
        val age = device.snapshotAgeMillis
        if (age != null && age > profile.maxSnapshotAgeMillis) {
            add(FindingCode.RESOURCES_STALE, Severity.UNCERTAIN,
                "Device measurements are ${age / 1000} s old; memory and storage must be measured again.")
        }
        if (device.isLowMemory == true) {
            add(FindingCode.LOW_MEMORY_STATE, Severity.RESOURCE,
                "Android reports the device is low on memory right now; loading a model now would likely fail.")
        }
        if (device.isLowRamDevice == true) {
            add(FindingCode.LOW_RAM_DEVICE, Severity.WARNING,
                "Android reports this as a low-RAM device; the system reclaims memory aggressively.")
        }
    }

    /**
     * Memory rules. Measured: total RAM, available RAM, Android's low-memory threshold and state.
     * Estimated: everything in [memory]. Available RAM is shared with other apps and changes
     * constantly, so it only produces warnings; the hard limit is a share of total RAM.
     */
    private fun checkMemory(memory: MemoryEstimate?, device: DeviceCapabilities, profile: RuntimeProfile, add: Adder) {
        if (memory == null) {
            add(FindingCode.SIZE_UNKNOWN, Severity.UNCERTAIN, "The model size is unknown, so memory use cannot be estimated.")
            return
        }
        // Weights alone already decide a clear failure, even without the attention shape.
        val needed = memory.totalBytes ?: (memory.weightsBytes + memory.overheadBytes)
        val total = device.totalRamBytes
        if (total == null) {
            add(FindingCode.RAM_UNKNOWN, Severity.UNCERTAIN,
                "The device's total RAM could not be measured, so it is unknown whether ${formatBytes(needed)} fits.")
        } else if (needed > (total * profile.maxTotalRamFraction).toLong()) {
            add(FindingCode.INSUFFICIENT_MEMORY, Severity.RESOURCE,
                "Needs about ${formatBytes(needed)}${if (memory.totalBytes == null) " (weights only)" else ""} of RAM; " +
                    "this device has ${formatBytes(total)} in total.")
            return
        }
        if (memory.totalBytes == null) {
            add(FindingCode.MEMORY_UNESTIMABLE, Severity.UNCERTAIN,
                "The model's attention layout is unknown (header not read, or not stated in it), so the KV-cache size " +
                    "and total memory cannot be estimated.")
            return
        }
        val available = device.availableRamBytes
        if (available == null) {
            add(FindingCode.AVAILABLE_RAM_UNKNOWN, Severity.WARNING,
                "Currently available RAM could not be measured.")
            return
        }
        // Prefer Android's own threshold: below it the system starts killing processes.
        val threshold = device.lowMemoryThresholdBytes
        val headroom = if (threshold != null) available - threshold else (available * profile.tightAvailableRamFraction).toLong()
        if (memory.totalBytes > headroom) {
            add(FindingCode.TIGHT_MEMORY, Severity.WARNING,
                "Needs about ${formatBytes(memory.totalBytes)} but only ${formatBytes(available)} is free right now" +
                    (threshold?.let { ", of which Android keeps ${formatBytes(it)} in reserve" } ?: "") +
                    "; close other apps or expect failures.")
        }
    }

    private fun remainingDownload(variant: ModelVariant, local: Map<String, Long>): Long? {
        if (variant.files.any { it.sizeBytes == null }) return null
        return variant.files.sumOf { file ->
            val size = file.sizeBytes!!
            size - (local[file.path] ?: 0L).coerceIn(0, size)
        }
    }

    /** Storage rules: only the bytes still to download need space; finished files need none. */
    private fun checkStorage(
        variant: ModelVariant,
        remaining: Long?,
        device: DeviceCapabilities,
        profile: RuntimeProfile,
        add: Adder
    ) {
        if (remaining == null) {
            add(FindingCode.SIZE_UNKNOWN, Severity.WARNING,
                "The repository does not list all file sizes, so the download size is unknown.")
            return
        }
        if (remaining == 0L) return
        val free = device.freeStorageBytes
        if (free == null) {
            add(FindingCode.STORAGE_UNKNOWN, Severity.UNCERTAIN,
                "Free storage could not be measured, so it is unknown whether ${formatBytes(remaining)} fits.")
            return
        }
        if (remaining + profile.storageReserveBytes > free) {
            add(FindingCode.INSUFFICIENT_STORAGE, Severity.RESOURCE,
                "Needs ${formatBytes(remaining)} more storage plus a ${formatBytes(profile.storageReserveBytes)} reserve; " +
                    "${formatBytes(free)} is free.")
        }
    }
}

private typealias Adder = (FindingCode, Severity, String) -> Unit

/**
 * Maps an assessment onto the app-wide [RuntimeCompatibility]. Without an engine in the build
 * nothing is loadable; [RuntimeCompatibility.Loadable] needs a verified profile and a verdict of
 * [Verdict.MEETS_REQUIREMENTS].
 */
fun CompatibilityAssessment.toRuntimeCompatibility(profile: RuntimeProfile): RuntimeCompatibility {
    val blocker = findings.firstOrNull { it.severity == Severity.BLOCKER }
    if (blocker != null) {
        val reason = when (blocker.code) {
            FindingCode.SPLIT_UNSUPPORTED, FindingCode.SHARDS_MISSING, FindingCode.SHARD_COUNT_CONFLICT ->
                IncompatibilityReason.MULTI_PART_UNSUPPORTED
            FindingCode.PROJECTOR_ONLY, FindingCode.ADAPTER_ONLY -> IncompatibilityReason.AUXILIARY_FILE
            else -> IncompatibilityReason.UNSUPPORTED_FORMAT
        }
        return RuntimeCompatibility.Incompatible(reason)
    }
    val engine = profile.engineId ?: return RuntimeCompatibility.Incompatible(IncompatibilityReason.NO_RUNTIME)
    return if (profile.verified && verdict == Verdict.MEETS_REQUIREMENTS) {
        RuntimeCompatibility.Loadable(engine)
    } else {
        RuntimeCompatibility.NotEvaluated
    }
}
