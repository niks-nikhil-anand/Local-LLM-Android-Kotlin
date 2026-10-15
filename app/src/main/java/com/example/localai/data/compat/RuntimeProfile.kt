package com.example.localai.data.compat

import com.example.localai.data.gguf.GgmlType

/**
 * Measured device facts for the fit checks, usually built from a
 * [com.example.localai.data.device.DeviceResourceSnapshot]. Null means "not measured": the
 * evaluator reports it as uncertain and never treats it as enough.
 */
data class DeviceCapabilities(
    val totalRamBytes: Long?,
    val availableRamBytes: Long?,
    val freeStorageBytes: Long?,
    val lowMemoryThresholdBytes: Long? = null,
    val isLowMemory: Boolean? = null,
    val isLowRamDevice: Boolean? = null,
    /** How old the measurements are; null when unknown (e.g. hand-built values in tests). */
    val snapshotAgeMillis: Long? = null,
    /** Readings rejected as impossible when the snapshot was validated. */
    val anomalies: List<String> = emptyList()
)

enum class QuantSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

/**
 * What the inference backend can do, as data, so rules never hard-code a backend.
 *
 * [DEFAULT_ASSUMED] describes upstream llama.cpp on arm64 Android *as assumed*: no engine is
 * built into this app yet, so [engineId] is null and [verified] is false. Every list below is an
 * assumption to be re-checked against the pinned llama.cpp revision when the native phase lands.
 */
data class RuntimeProfile(
    /** Null until an engine is actually present in the build. */
    val engineId: String?,
    /** True only after the lists below were checked against the shipped engine on a device. */
    val verified: Boolean,
    val supportedQuantizations: Set<GgmlType>,
    val unsupportedQuantizations: Set<GgmlType>,
    /** Architectures believed to load. Anything in neither list is "unknown", not "supported". */
    val supportedArchitectures: Set<String>,
    val unsupportedArchitectures: Set<String>,
    /** Architectures whose weights cannot be used without a vision component. */
    val visionRequiredArchitectures: Set<String>,
    val supportsSplitModels: Boolean,
    val supportsVision: Boolean,
    val supportsAudio: Boolean,
    /** Context length the app would request; the model's own limit caps it further. */
    val contextTokens: Int,
    /** KV-cache bytes per element (2 = f16 cache). */
    val kvBytesPerElement: Int,
    /** Fixed allowance for compute buffers and runtime structures. */
    val computeOverheadBytes: Long,
    /**
     * A model needing more than this share of total RAM is rejected. Heuristic: the kernel,
     * system services and the foreground UI keep part of RAM that no app can claim; the real
     * share varies by device and must be calibrated on hardware.
     */
    val maxTotalRamFraction: Double,
    /**
     * Fallback for the "tight memory" warning when Android does not report its low-memory
     * threshold: warn above this share of available RAM. Heuristic, not measured.
     */
    val tightAvailableRamFraction: Double,
    /** Free storage that must remain after a download, so the device is not filled completely. */
    val storageReserveBytes: Long,
    /** Measurements older than this are reported as stale and must be refreshed before acting. */
    val maxSnapshotAgeMillis: Long
) {
    fun quantSupport(type: GgmlType?): QuantSupport = when {
        type == null -> QuantSupport.UNKNOWN
        type in unsupportedQuantizations -> QuantSupport.UNSUPPORTED
        type in supportedQuantizations -> QuantSupport.SUPPORTED
        else -> QuantSupport.UNKNOWN
    }

    companion object {
        val DEFAULT_ASSUMED = RuntimeProfile(
            engineId = null,
            verified = false,
            supportedQuantizations = setOf(
                GgmlType.F32, GgmlType.F16, GgmlType.BF16,
                GgmlType.Q4_0, GgmlType.Q4_1, GgmlType.Q5_0, GgmlType.Q5_1, GgmlType.Q8_0,
                GgmlType.Q2_K, GgmlType.Q3_K, GgmlType.Q4_K, GgmlType.Q5_K, GgmlType.Q6_K,
                GgmlType.IQ1_S, GgmlType.IQ1_M, GgmlType.IQ2_XXS, GgmlType.IQ2_XS, GgmlType.IQ2_S,
                GgmlType.IQ3_XXS, GgmlType.IQ3_S, GgmlType.IQ4_NL, GgmlType.IQ4_XS
            ),
            // Types that exist for intermediate computation or non-LLM data, not for model weights.
            unsupportedQuantizations = setOf(
                GgmlType.Q8_1, GgmlType.Q8_K, GgmlType.I8, GgmlType.I16, GgmlType.I32, GgmlType.I64, GgmlType.F64
            ),
            supportedArchitectures = setOf(
                "llama", "qwen2", "qwen3", "gemma", "gemma2", "gemma3", "phi2", "phi3",
                "falcon", "starcoder2", "stablelm", "olmo", "olmo2", "granite", "smollm3", "internlm2"
            ),
            unsupportedArchitectures = setOf("whisper", "stable-diffusion", "flux", "bark", "encodec"),
            visionRequiredArchitectures = setOf("qwen2vl", "qwen25vl", "qwen3vl"),
            supportsSplitModels = false,
            supportsVision = false,
            supportsAudio = false,
            contextTokens = 4096,
            kvBytesPerElement = 2,
            computeOverheadBytes = 256L * 1024 * 1024,
            maxTotalRamFraction = 0.85,
            tightAvailableRamFraction = 0.9,
            storageReserveBytes = 512L * 1024 * 1024,
            maxSnapshotAgeMillis = 60_000L
        )

        /**
         * The engine actually built into this app: llama.cpp v0.6.0 (CPU, see LLAMA_CPP_REVISION.md).
         * Still [verified] = false: these lists are assumptions, and only a successful native load
         * shows that a particular model works. Unknown architectures stay UNKNOWN rather than
         * rejected, because llama.cpp itself is the final judge when it loads the file.
         */
        val LLAMA_CPP = DEFAULT_ASSUMED.copy(engineId = "llama.cpp", contextTokens = 2048)
    }
}
