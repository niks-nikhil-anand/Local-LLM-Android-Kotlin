package com.example.localai.data.device

import com.example.localai.data.compat.DeviceCapabilities

/**
 * Values exactly as the platform returned them, before validation. Null means the query failed
 * or is not available. Produced by a [DeviceResourceReader]; never used for decisions directly.
 */
data class RawDeviceReadings(
    val totalRamBytes: Long? = null,
    val availableRamBytes: Long? = null,
    val lowMemoryThresholdBytes: Long? = null,
    val isLowMemory: Boolean? = null,
    val isLowRamDevice: Boolean? = null,
    val totalStorageBytes: Long? = null,
    val availableStorageBytes: Long? = null,
    val apiLevel: Int? = null,
    val supportedAbis: List<String> = emptyList(),
    val is64BitProcess: Boolean? = null,
    /** Queries that threw or were unavailable, e.g. "StatFs: IllegalArgumentException". */
    val unavailable: List<String> = emptyList()
)

/**
 * Validated, point-in-time device facts. Every field is a measurement from an Android API or
 * null when it could not be measured or failed validation; nothing here is estimated or assumed.
 *
 * Sources (see [AndroidDeviceResourceReader]):
 * - RAM fields: `ActivityManager.getMemoryInfo()` (totalMem, availMem, threshold, lowMemory).
 *   totalMem is physical RAM visible to the kernel, not the JVM heap limit.
 * - [isLowRamDevice]: `ActivityManager.isLowRamDevice()`.
 * - Storage: `StatFs` on the directory where models will be stored (app-private internal storage).
 * - [apiLevel], [supportedAbis]: `Build.VERSION.SDK_INT`, `Build.SUPPORTED_ABIS`;
 *   [is64BitProcess]: `android.os.Process.is64Bit()`.
 */
data class DeviceResourceSnapshot(
    val totalRamBytes: Long?,
    /** RAM the system considers available now; shared with every other process, and volatile. */
    val availableRamBytes: Long?,
    /** Below this much available RAM Android considers itself low on memory and kills processes. */
    val lowMemoryThresholdBytes: Long?,
    val isLowMemory: Boolean?,
    val isLowRamDevice: Boolean?,
    val totalStorageBytes: Long?,
    val availableStorageBytes: Long?,
    val apiLevel: Int?,
    val supportedAbis: List<String>,
    val is64BitProcess: Boolean?,
    /** Clock reading (elapsed-realtime milliseconds) when the values were read. */
    val capturedAtMillis: Long,
    /** Readings that were rejected as impossible or contradictory; their fields are null. */
    val anomalies: List<String>,
    val unavailable: List<String>
) {
    /** Converts to the evaluator's input. [nowMillis] must come from the same clock. */
    fun toCapabilities(nowMillis: Long): DeviceCapabilities = DeviceCapabilities(
        totalRamBytes = totalRamBytes,
        availableRamBytes = availableRamBytes,
        freeStorageBytes = availableStorageBytes,
        lowMemoryThresholdBytes = lowMemoryThresholdBytes,
        isLowMemory = isLowMemory,
        isLowRamDevice = isLowRamDevice,
        snapshotAgeMillis = (nowMillis - capturedAtMillis).takeIf { it >= 0 },
        anomalies = anomalies
    )

    companion object {
        /** Validates [raw]. Impossible values become null and are recorded, never "corrected". */
        fun from(raw: RawDeviceReadings, capturedAtMillis: Long): DeviceResourceSnapshot {
            val anomalies = mutableListOf<String>()
            fun positive(name: String, value: Long?): Long? = when {
                value == null -> null
                value <= 0 -> null.also { anomalies += "$name reported as $value" }
                else -> value
            }
            fun nonNegative(name: String, value: Long?): Long? = when {
                value == null -> null
                value < 0 -> null.also { anomalies += "$name reported as $value" }
                else -> value
            }

            val totalRam = positive("Total RAM", raw.totalRamBytes)
            var availableRam = nonNegative("Available RAM", raw.availableRamBytes)
            if (availableRam != null && totalRam != null && availableRam > totalRam) {
                anomalies += "Available RAM ($availableRam) exceeds total RAM ($totalRam)"
                availableRam = null
            }
            var threshold = nonNegative("Low-memory threshold", raw.lowMemoryThresholdBytes)
            if (threshold != null && totalRam != null && threshold >= totalRam) {
                anomalies += "Low-memory threshold ($threshold) is not below total RAM ($totalRam)"
                threshold = null
            }

            val totalStorage = positive("Total storage", raw.totalStorageBytes)
            var availableStorage = nonNegative("Available storage", raw.availableStorageBytes)
            if (availableStorage != null && totalStorage != null && availableStorage > totalStorage) {
                anomalies += "Available storage ($availableStorage) exceeds total storage ($totalStorage)"
                availableStorage = null
            }

            return DeviceResourceSnapshot(
                totalRamBytes = totalRam,
                availableRamBytes = availableRam,
                lowMemoryThresholdBytes = threshold,
                isLowMemory = raw.isLowMemory,
                isLowRamDevice = raw.isLowRamDevice,
                totalStorageBytes = totalStorage,
                availableStorageBytes = availableStorage,
                apiLevel = raw.apiLevel?.takeIf { it > 0 },
                supportedAbis = raw.supportedAbis.filter { it.isNotBlank() },
                is64BitProcess = raw.is64BitProcess,
                capturedAtMillis = capturedAtMillis,
                anomalies = anomalies,
                unavailable = raw.unavailable
            )
        }
    }
}
