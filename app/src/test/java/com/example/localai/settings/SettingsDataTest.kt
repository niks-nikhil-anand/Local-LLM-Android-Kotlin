package com.example.localai.settings

import com.example.localai.data.AppInfo
import com.example.localai.data.LicenceNotices
import com.example.localai.data.download.LocalModelInventory
import com.example.localai.data.inference.ModelSessionManager
import com.example.localai.data.local.LocalModelEntity
import com.example.localai.explore.FakeDeviceProvider
import com.example.localai.explore.FakeDownloads
import com.example.localai.gguf.GgufBuilder
import com.example.localai.inference.FakeEngine
import com.example.localai.ui.screens.home.HomeViewModel
import com.example.localai.ui.screens.mymodels.MyModelsViewModel
import com.example.localai.ui.screens.settings.EngineAvailability
import com.example.localai.ui.screens.settings.SettingsText
import com.example.localai.ui.screens.settings.SettingsUiState
import com.example.localai.ui.screens.settings.SettingsViewModel
import com.example.localai.ui.screens.settings.licenceParagraphs
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDataTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val downloads = FakeDownloads()
    private val device = FakeDeviceProvider()
    private val engine = FakeEngine()
    private val app = AppInfo("LocalAI", "1.0", 1, "Google", "Pixel 9", "17", 37, "arm64-v8a", listOf("arm64-v8a"))

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun completed(name: String, size: Int = 1000, gguf: Boolean = false): LocalModelEntity {
        val bytes = if (gguf) GgufBuilder.tinyLlama().file() else ByteArray(size)
        val file = temp.newFile(name).apply { writeBytes(bytes) }
        val row = LocalModelEntity(
            "o/r/main/$name", "o/r", "main", name, bytes.size.toLong(), null, bytes.size.toLong(), "COMPLETED", null, 0
        )
        downloads.files[row.id] = file
        return row
    }

    private val inventory by lazy { LocalModelInventory(downloads, dispatcher) }
    private val session by lazy { ModelSessionManager(engine, downloads, device, clock = { 0L }, ioDispatcher = dispatcher) }

    private fun settings(s: ModelSessionManager? = session) = SettingsViewModel(inventory, s, device) { app }

    // ---- Real values ----

    @Test
    fun showsTheRealDownloadedModelCountAndMeasuredStorage() = runTest(dispatcher) {
        downloads.rows.value = listOf(completed("a.gguf", 1_048_576), completed("b.gguf", 2_097_152))
        val vm = settings()
        advanceUntilIdle()
        assertEquals("2 models · 3.0 MB", SettingsText.downloadedModels(vm.state.value))
    }

    @Test
    fun settingsHomeAndMyModelsAgree() = runTest(dispatcher) {
        downloads.rows.value = listOf(
            completed("a.gguf", 1_500), completed("b.gguf", 2_500),
            completed("gone.gguf", 99).also { downloads.files[it.id]!!.delete() }
        )
        val s = settings()
        val home = HomeViewModel(inventory, session, device)
        val myModels = MyModelsViewModel(downloads, device, clock = { 0L }, ioDispatcher = dispatcher)
        advanceUntilIdle()
        val fromSettings = s.state.value.inventory!!
        val fromHome = home.state.value.inventory!!
        assertEquals(2, fromSettings.modelCount)
        assertEquals(fromHome, fromSettings)
        assertEquals(fromSettings.modelBytes, myModels.state.value.completedBytes)
    }

    @Test
    fun deletionUpdatesCountAndStorage() = runTest(dispatcher) {
        val a = completed("a.gguf", 1024)
        val b = completed("b.gguf", 1024)
        downloads.rows.value = listOf(a, b)
        val vm = settings()
        advanceUntilIdle()
        assertEquals("2 models · 2.0 KB", SettingsText.downloadedModels(vm.state.value))
        downloads.files[b.id]!!.delete()
        downloads.rows.value = listOf(a)
        advanceUntilIdle()
        assertEquals("1 model · 1.0 KB", SettingsText.downloadedModels(vm.state.value))
    }

    @Test
    fun loadedModelFollowsTheSessionNotTheDownloads() = runTest(dispatcher) {
        val a = completed("a.gguf", gguf = true)
        downloads.rows.value = listOf(a)
        val vm = settings()
        advanceUntilIdle()
        assertEquals("None", SettingsText.loadedModel(vm.state.value))
        assertEquals(EngineAvailability.Available, vm.state.value.engine)

        assertTrue(session.load(a))
        advanceUntilIdle()
        assertEquals("a.gguf", SettingsText.loadedModel(vm.state.value))

        session.unload()
        advanceUntilIdle()
        assertEquals("None", SettingsText.loadedModel(vm.state.value))
    }

    @Test
    fun anUnavailableRuntimeIsNeverReportedAsReady() = runTest(dispatcher) {
        val unavailable = ModelSessionManager(FakeEngine(isAvailable = false), downloads, device, clock = { 0L }, ioDispatcher = dispatcher)
        val vm = settings(unavailable)
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.engine is EngineAvailability.Unavailable)
        assertTrue(SettingsText.engine(s).startsWith("Unavailable"))
        assertFalse(SettingsText.engine(s).contains("available ·"))
        assertEquals("None (engine unavailable)", SettingsText.loadedModel(s))

        val none = settings(null)
        advanceUntilIdle()
        assertTrue(SettingsText.engine(none.state.value).startsWith("Unavailable"))
    }

    @Test
    fun emptyAndUnreadableDataShowNoFakeValues() = runTest(dispatcher) {
        val vm = settings()
        assertEquals(SettingsText.LOADING, SettingsText.downloadedModels(vm.state.value))
        advanceUntilIdle()
        assertEquals("0 models · 0 B", SettingsText.downloadedModels(vm.state.value)) // a real zero

        val blank = SettingsUiState(inventoryError = "Downloaded models could not be read: disk error", deviceMeasured = true)
        assertEquals(SettingsText.UNAVAILABLE, SettingsText.downloadedModels(blank))
        assertEquals(SettingsText.UNAVAILABLE, SettingsText.ram(blank))
        assertEquals(SettingsText.UNAVAILABLE, SettingsText.version(blank))
        assertEquals(SettingsText.UNAVAILABLE, SettingsText.abi(blank))
    }

    @Test
    fun deviceFailureIsUnavailableNotGuessed() = runTest(dispatcher) {
        device.fail = true
        val vm = settings()
        advanceUntilIdle()
        assertEquals(SettingsText.UNAVAILABLE, SettingsText.ram(vm.state.value))
    }

    @Test
    fun ramLabelsKeepTotalAndAvailableApart() = runTest(dispatcher) {
        val vm = settings()
        advanceUntilIdle()
        assertEquals("8.0 GB total · 4.0 GB available now", SettingsText.ram(vm.state.value))
    }

    @Test
    fun appAndDeviceTextComeFromAppInfo() {
        val s = SettingsUiState(app = app)
        assertEquals("1.0 (1)", SettingsText.version(s))
        assertEquals("Google Pixel 9", SettingsText.device(s))
        assertEquals("Android 17 (API 37)", SettingsText.android(s))
        assertEquals("arm64-v8a", SettingsText.abi(s))
    }

    @Test
    fun theInstalledAbiIsReadFromTheNativeLibraryFolder() {
        assertEquals("arm64-v8a", AppInfo.abiFromNativeLibraryDir("/data/app/~~x/com.example.localai-1/lib/arm64"))
        assertEquals("x86_64", AppInfo.abiFromNativeLibraryDir("/data/app/com.example.localai-1/lib/x86_64"))
        assertEquals(null, AppInfo.abiFromNativeLibraryDir(null))
    }

    // ---- Licences ----

    private val bundledNotices = File("src/main/assets/${LicenceNotices.ASSET_PATH}")

    @Test
    fun theBundledNoticesLoadCompletelyAndUnchanged() {
        assertTrue("run from the app module", bundledNotices.isFile)
        val expected = bundledNotices.readText()
        val loaded = LicenceNotices.load { bundledNotices.inputStream() }.getOrThrow()
        assertEquals(expected, loaded)
        // Key notices are present verbatim.
        assertTrue(loaded.contains("Copyright (c) 2023-2026 The ggml authors"))
        assertTrue(loaded.contains("THE SOFTWARE IS PROVIDED \"AS IS\""))
        assertTrue(loaded.contains("Apache License"))
        assertTrue(loaded.contains("END OF TERMS AND CONDITIONS"))
        assertTrue(loaded.contains("Mozilla Public License"))
        // Splitting for display loses nothing.
        assertEquals(loaded, licenceParagraphs(loaded).joinToString("\n\n"))
    }

    @Test
    fun aMissingOrEmptyLicenceAssetIsAnError() {
        val missing = LicenceNotices.load { throw FileNotFoundException("licenses/THIRD_PARTY_NOTICES.txt") }
        assertTrue(missing.isFailure)
        val empty = LicenceNotices.load { "   ".byteInputStream() }
        assertTrue(empty.isFailure)
    }
}
