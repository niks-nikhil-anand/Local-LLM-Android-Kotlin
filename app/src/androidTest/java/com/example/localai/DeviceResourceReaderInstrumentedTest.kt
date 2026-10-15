package com.example.localai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.localai.data.device.AndroidDeviceResourceReader
import com.example.localai.data.device.DeviceResourceSnapshot
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks the real Android queries on a device or emulator. The JVM unit tests cannot cover this:
 * android.jar stubs throw outside a device.
 */
@RunWith(AndroidJUnit4::class)
class DeviceResourceReaderInstrumentedTest {

    @Test
    fun readsPlausibleValuesFromThePlatform() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val raw = AndroidDeviceResourceReader(context).read()
        val snapshot = DeviceResourceSnapshot.from(raw, capturedAtMillis = 0)

        assertTrue("unavailable: ${raw.unavailable}", raw.unavailable.isEmpty())
        assertTrue("anomalies: ${snapshot.anomalies}", snapshot.anomalies.isEmpty())
        val totalRam = assertNotNullAndGet(snapshot.totalRamBytes)
        // Physical RAM, not the JVM heap limit.
        assertTrue(totalRam > Runtime.getRuntime().maxMemory())
        assertTrue(assertNotNullAndGet(snapshot.availableRamBytes) <= totalRam)
        assertTrue(assertNotNullAndGet(snapshot.availableStorageBytes) <= assertNotNullAndGet(snapshot.totalStorageBytes))
        assertNotNull(snapshot.isLowMemory)
        assertNotNull(snapshot.isLowRamDevice)
        assertTrue(snapshot.supportedAbis.isNotEmpty())
    }

    private fun assertNotNullAndGet(value: Long?): Long {
        assertNotNull(value)
        return value!!
    }
}
