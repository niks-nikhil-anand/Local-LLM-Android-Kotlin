package com.example.localai.device

import com.example.localai.data.device.CachingDeviceResourceProvider
import com.example.localai.data.device.DeviceResourceReader
import com.example.localai.data.device.DeviceResourceSnapshot
import com.example.localai.data.device.RawDeviceReadings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GB = 1024L * 1024 * 1024

internal val normalReadings = RawDeviceReadings(
    totalRamBytes = 8 * GB,
    availableRamBytes = 3 * GB,
    lowMemoryThresholdBytes = 512L * 1024 * 1024,
    isLowMemory = false,
    isLowRamDevice = false,
    totalStorageBytes = 128 * GB,
    availableStorageBytes = 40 * GB,
    apiLevel = 35,
    supportedAbis = listOf("arm64-v8a"),
    is64BitProcess = true
)

class DeviceResourceSnapshotTest {

    @Test
    fun validReadingsPassThroughUnchanged() {
        val s = DeviceResourceSnapshot.from(normalReadings, capturedAtMillis = 1_000)
        assertEquals(8 * GB, s.totalRamBytes)
        assertEquals(3 * GB, s.availableRamBytes)
        assertEquals(512L * 1024 * 1024, s.lowMemoryThresholdBytes)
        assertEquals(40 * GB, s.availableStorageBytes)
        assertEquals(35, s.apiLevel)
        assertEquals(listOf("arm64-v8a"), s.supportedAbis)
        assertTrue(s.anomalies.isEmpty())
    }

    @Test
    fun missingReadingsStayUnknown() {
        val s = DeviceResourceSnapshot.from(RawDeviceReadings(unavailable = listOf("StatFs: X")), 0)
        assertNull(s.totalRamBytes)
        assertNull(s.availableRamBytes)
        assertNull(s.availableStorageBytes)
        assertNull(s.isLowMemory)
        assertTrue(s.anomalies.isEmpty()) // unavailable is not the same as inconsistent
        assertEquals(listOf("StatFs: X"), s.unavailable)
    }

    @Test
    fun impossibleValuesAreRejectedNotCorrected() {
        val s = DeviceResourceSnapshot.from(
            normalReadings.copy(
                totalRamBytes = 0,
                availableRamBytes = -5,
                totalStorageBytes = -1,
                availableStorageBytes = -1,
                apiLevel = 0
            ),
            0
        )
        assertNull(s.totalRamBytes)
        assertNull(s.availableRamBytes)
        assertNull(s.totalStorageBytes)
        assertNull(s.availableStorageBytes)
        assertNull(s.apiLevel)
        assertEquals(4, s.anomalies.size)
    }

    @Test
    fun contradictoryValuesAreRejected() {
        val s = DeviceResourceSnapshot.from(
            normalReadings.copy(availableRamBytes = 9 * GB, availableStorageBytes = 200 * GB, lowMemoryThresholdBytes = 8 * GB),
            0
        )
        assertEquals(8 * GB, s.totalRamBytes) // the plausible side is kept
        assertNull(s.availableRamBytes)
        assertNull(s.availableStorageBytes)
        assertNull(s.lowMemoryThresholdBytes)
        assertEquals(3, s.anomalies.size)
    }

    @Test
    fun capabilitiesCarryAgeAndAnomalies() {
        val s = DeviceResourceSnapshot.from(normalReadings.copy(availableRamBytes = -1), capturedAtMillis = 1_000)
        val caps = s.toCapabilities(nowMillis = 4_000)
        assertEquals(3_000L, caps.snapshotAgeMillis)
        assertEquals(8 * GB, caps.totalRamBytes)
        assertEquals(40 * GB, caps.freeStorageBytes)
        assertNull(caps.availableRamBytes)
        assertEquals(1, caps.anomalies.size)
        // A clock that went backwards gives an unknown age, not a negative one.
        assertNull(s.toCapabilities(nowMillis = 500).snapshotAgeMillis)
    }
}

class CachingDeviceResourceProviderTest {

    private var now = 0L
    private var reads = 0
    private var next = normalReadings

    private val reader = DeviceResourceReader { reads++; next }
    private val provider = CachingDeviceResourceProvider(reader, clock = { now }, dispatcher = Dispatchers.Unconfined)

    @Test
    fun reusesAFreshSnapshotAndRemeasuresAStaleOne() = runBlocking {
        val first = provider.snapshot(maxAgeMillis = 10_000)
        now = 9_000
        assertSame(first, provider.snapshot(maxAgeMillis = 10_000))
        assertEquals(1, reads)

        now = 10_001
        next = normalReadings.copy(availableRamBytes = 1 * GB)
        val second = provider.snapshot(maxAgeMillis = 10_000)
        assertEquals(2, reads)
        assertEquals(1 * GB, second.availableRamBytes) // RAM changed between checks
        assertEquals(10_001L, second.capturedAtMillis)
    }

    @Test
    fun refreshAlwaysMeasures() = runBlocking {
        provider.snapshot()
        provider.refresh()
        provider.refresh()
        assertEquals(3, reads)
    }

    @Test
    fun aClockGoingBackwardsForcesANewMeasurement() = runBlocking {
        now = 5_000
        provider.snapshot()
        now = 1_000
        provider.snapshot()
        assertEquals(2, reads)
    }

    @Test
    fun aFailingReaderYieldsUnknownsInsteadOfCrashing() = runBlocking {
        val failing = CachingDeviceResourceProvider(
            DeviceResourceReader { throw SecurityException("denied") },
            clock = { now },
            dispatcher = Dispatchers.Unconfined
        )
        val s = failing.refresh()
        assertNull(s.totalRamBytes)
        assertNull(s.availableStorageBytes)
        assertEquals(listOf("Device query failed: SecurityException"), s.unavailable)
    }

    @Test
    fun concurrentCallersShareOneMeasurement() = runBlocking {
        val results = (1..20).map { async(Dispatchers.Default) { provider.snapshot(maxAgeMillis = 60_000) } }.awaitAll()
        assertEquals(1, reads)
        assertTrue(results.all { it === results.first() })
    }
}
