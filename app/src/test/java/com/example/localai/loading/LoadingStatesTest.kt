package com.example.localai.loading

import com.example.localai.data.download.DownloadController
import com.example.localai.data.inference.EngineState
import com.example.localai.data.inference.ModelLoadStatus
import com.example.localai.data.local.LocalModelEntity
import com.example.localai.explore.FakeDeviceProvider
import com.example.localai.explore.FakeDownloads
import com.example.localai.ui.screens.home.HomeText
import com.example.localai.ui.screens.home.HomeUiState
import com.example.localai.ui.screens.mymodels.MyModelsViewModel
import com.example.localai.ui.screens.settings.EngineAvailability
import com.example.localai.ui.screens.settings.SettingsText
import com.example.localai.ui.screens.settings.SettingsUiState
import com.example.localai.ui.state.ChatModelState
import com.example.localai.ui.state.LoadPhase
import com.example.localai.ui.state.modelRuntimeState
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Loading states end in real content, a real empty state or an honest error — never stay pending. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoadingStatesTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---- Chat: model preparation phases come from the session's real status ----

    @Test
    fun modelPreparationReportsTheRealPhase() {
        val id = "o/r/main/m.gguf"
        assertEquals(
            ChatModelState.Loading("m.gguf", LoadPhase.CHECKING),
            modelRuntimeState(EngineState.NoModel, null, mapOf(id to ModelLoadStatus.Checking))
        )
        assertEquals(
            ChatModelState.Loading("m.gguf", LoadPhase.LOADING),
            modelRuntimeState(EngineState.NoModel, null, mapOf(id to ModelLoadStatus.Loading))
        )
        assertEquals(ChatModelState.NoModel, modelRuntimeState(EngineState.NoModel, null, emptyMap()))
    }

    // ---- My Models: a read failure is an error, not "no models", and Retry recovers ----

    private class FlakyDownloads(private val inner: FakeDownloads) : DownloadController by inner {
        var fail = true
        override fun observe(): Flow<List<LocalModelEntity>> =
            if (fail) flow { throw IOException("disk I/O error") } else inner.observe()
    }

    @Test
    fun aRecordReadFailureEndsLoadingWithAnErrorAndRetryRecovers() = runTest(dispatcher) {
        val inner = FakeDownloads()
        val downloads = FlakyDownloads(inner)
        val vm = MyModelsViewModel(downloads, FakeDeviceProvider(), clock = { 0L }, ioDispatcher = dispatcher)
        assertTrue(vm.state.value.loading)
        advanceUntilIdle()
        val failed = vm.state.value
        assertFalse(failed.loading)
        assertEquals("Downloaded models could not be read: disk I/O error", failed.error)
        assertTrue(failed.items.isEmpty())

        downloads.fail = false
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }

    // ---- Home and Settings: "loading" and "unavailable" are kept apart ----

    @Test
    fun homeDeviceValuesAreLoadingUntilMeasuredThenUnavailableIfMeasuringFailed() {
        val pending = HomeUiState()
        assertEquals(HomeText.LOADING, HomeText.memory(pending))
        assertEquals(HomeText.LOADING, HomeText.badge(pending))
        assertNull(HomeText.shown(HomeText.memory(pending)))

        val failed = HomeUiState(deviceMeasured = true)
        assertEquals(HomeText.UNAVAILABLE, HomeText.memory(failed))
        assertEquals("Not measured", HomeText.badge(failed))
        assertEquals(HomeText.UNAVAILABLE, HomeText.shown(HomeText.memory(failed)))
    }

    @Test
    fun settingsNeverShowsUnavailableWhileStillChecking() {
        val initial = SettingsUiState()
        assertEquals(EngineAvailability.Checking, initial.engine)
        assertEquals(SettingsText.LOADING, SettingsText.engine(initial))
        assertEquals(SettingsText.LOADING, SettingsText.ram(initial))
        assertEquals(SettingsText.UNAVAILABLE, SettingsText.ram(initial.copy(deviceMeasured = true)))
    }
}
