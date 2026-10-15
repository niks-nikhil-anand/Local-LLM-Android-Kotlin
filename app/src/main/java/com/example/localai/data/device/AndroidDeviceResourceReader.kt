package com.example.localai.data.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import java.io.File

/**
 * Reads device resources through public Android APIs. Needs no permissions. Each query is
 * isolated so one failure leaves the others usable; failures are reported, not substituted.
 *
 * [storageDir] must be the directory models will be written to: free space differs per volume.
 */
class AndroidDeviceResourceReader(
    context: Context,
    private val storageDir: File = context.applicationContext.filesDir
) : DeviceResourceReader {

    private val activityManager =
        context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    override fun read(): RawDeviceReadings {
        val unavailable = mutableListOf<String>()

        val memory = activityManager?.let { am ->
            runCatching { ActivityManager.MemoryInfo().also(am::getMemoryInfo) }
                .onFailure { unavailable += "MemoryInfo: ${it.javaClass.simpleName}" }
                .getOrNull()
        } ?: null.also { if (activityManager == null) unavailable += "ActivityManager unavailable" }

        val lowRamDevice = activityManager?.let { am ->
            runCatching { am.isLowRamDevice }
                .onFailure { unavailable += "isLowRamDevice: ${it.javaClass.simpleName}" }
                .getOrNull()
        }

        val stat = runCatching { StatFs(storageDir.path) }
            .onFailure { unavailable += "StatFs(${storageDir.path}): ${it.javaClass.simpleName}" }
            .getOrNull()

        return RawDeviceReadings(
            totalRamBytes = memory?.totalMem,
            availableRamBytes = memory?.availMem,
            lowMemoryThresholdBytes = memory?.threshold,
            isLowMemory = memory?.lowMemory,
            isLowRamDevice = lowRamDevice,
            totalStorageBytes = stat?.totalBytes,
            availableStorageBytes = stat?.availableBytes,
            apiLevel = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            is64BitProcess = Process.is64Bit(),
            unavailable = unavailable
        )
    }

    companion object {
        /** Production provider: platform reader with the monotonic elapsed-realtime clock. */
        fun provider(context: Context, storageDir: File = context.applicationContext.filesDir): DeviceResourceProvider =
            CachingDeviceResourceProvider(
                reader = AndroidDeviceResourceReader(context, storageDir),
                clock = SystemClock::elapsedRealtime
            )
    }
}
