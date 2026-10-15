package com.example.localai.data.device

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Reads raw values from the platform. Implementations may block; callers run them off the main thread. */
fun interface DeviceResourceReader {
    fun read(): RawDeviceReadings
}

/** Source of device resource snapshots for compatibility checks. */
interface DeviceResourceProvider {
    /** A snapshot no older than [maxAgeMillis]; reuses the cached one when it is fresh enough. */
    suspend fun snapshot(maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS): DeviceResourceSnapshot

    /** Always measures again, e.g. when the user asks for a new evaluation or right before loading. */
    suspend fun refresh(): DeviceResourceSnapshot

    companion object {
        /**
         * Reuse window for evaluations shown in lists. Available RAM can change within seconds,
         * so this only avoids re-querying for every row on one screen; anything that acts on the
         * result (starting a download or a load) should call [refresh].
         */
        const val DEFAULT_MAX_AGE_MILLIS = 10_000L
    }
}

/**
 * Caches the last snapshot and re-reads on demand. Reads run on [dispatcher], never the caller's
 * thread, and are serialised so concurrent callers share one measurement. A reader that throws
 * yields a snapshot of unknowns instead of an exception.
 */
class CachingDeviceResourceProvider(
    private val reader: DeviceResourceReader,
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : DeviceResourceProvider {

    private val mutex = Mutex()
    private var cached: DeviceResourceSnapshot? = null

    override suspend fun snapshot(maxAgeMillis: Long): DeviceResourceSnapshot = mutex.withLock {
        val current = cached
        val age = current?.let { clock() - it.capturedAtMillis }
        if (current != null && age != null && age in 0..maxAgeMillis) current else measureLocked()
    }

    override suspend fun refresh(): DeviceResourceSnapshot = mutex.withLock { measureLocked() }

    private suspend fun measureLocked(): DeviceResourceSnapshot {
        val raw = withContext(dispatcher) {
            try {
                reader.read()
            } catch (e: RuntimeException) {
                RawDeviceReadings(unavailable = listOf("Device query failed: ${e.javaClass.simpleName}"))
            }
        }
        return DeviceResourceSnapshot.from(raw, clock()).also { cached = it }
    }
}
