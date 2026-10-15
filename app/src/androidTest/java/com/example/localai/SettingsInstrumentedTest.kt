package com.example.localai

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.localai.data.AppInfo
import com.example.localai.data.LicenceNotices
import com.example.localai.data.device.DeviceResourceSnapshot
import com.example.localai.data.device.RawDeviceReadings
import com.example.localai.data.download.InventoryModel
import com.example.localai.data.download.InventorySummary
import com.example.localai.data.prefs.ThemePreferenceStore
import com.example.localai.ui.screens.settings.EngineAvailability
import com.example.localai.ui.screens.settings.LicenceText
import com.example.localai.ui.screens.settings.LicencesScreen
import com.example.localai.ui.screens.settings.SettingsActions
import com.example.localai.ui.screens.settings.SettingsScreen
import com.example.localai.ui.screens.settings.SettingsUiState
import com.example.localai.ui.screens.settings.licenceParagraphs
import com.example.localai.ui.state.ChatModelState
import com.example.localai.ui.theme.LocalAITheme
import com.example.localai.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val calls = mutableListOf<String>()

    // ---- Real Android data ----

    @Test
    fun appInfoComesFromThePackageManagerAndBuild() {
        val info = AppInfo.read(context)
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(pkg.versionName, info.versionName)
        assertEquals(PackageInfoCompat.getLongVersionCode(pkg), info.versionCode)
        assertEquals(Build.MODEL, info.model)
        assertEquals(Build.VERSION.SDK_INT, info.sdkInt)
        assertEquals("LocalAI", info.appName)
        // The installed library ABI is one the device supports and one the APK ships.
        assertTrue(info.appAbi, info.appAbi in Build.SUPPORTED_ABIS)
        assertTrue(info.appAbi, info.appAbi in setOf("arm64-v8a", "x86_64"))
    }

    @Test
    fun theLicenceNoticesAreReadFromTheInstalledApk() {
        val text = LicenceNotices.load(context).getOrThrow()
        assertTrue(text.startsWith("THIRD-PARTY NOTICES"))
        assertTrue(text.contains("Copyright (c) 2023-2026 The ggml authors"))
        assertTrue(text.contains("END OF TERMS AND CONDITIONS"))
        assertTrue(text.trimEnd().endsWith("Licence text: https://mozilla.org/MPL/2.0/"))
    }

    @Test
    fun theThemePreferencePersistsAcrossStoreInstances() = runBlocking {
        val original = ThemePreferenceStore(context).themeMode.first()
        try {
            ThemePreferenceStore(context).setThemeMode(ThemeMode.DARK)
            // A new store instance reads the persisted file (what a restarted app does).
            assertEquals(ThemeMode.DARK, ThemePreferenceStore(context).themeMode.first())
            ThemePreferenceStore(context).setThemeMode(ThemeMode.LIGHT)
            assertEquals(ThemeMode.LIGHT, ThemePreferenceStore(context).themeMode.first())
        } finally {
            ThemePreferenceStore(context).setThemeMode(original)
        }
    }

    // ---- Settings screen ----

    private val actions = SettingsActions(
        onThemeSelect = { calls += "theme:$it" },
        onClearConversations = { calls += "clear" },
        onOpenMyModels = { calls += "my_models" },
        onOpenLicences = { calls += "licences" }
    )

    private fun showSettings(state: SettingsUiState) = compose.setContent {
        LocalAITheme { SettingsScreen(ThemeMode.SYSTEM, state, actions) }
    }

    private val realState = SettingsUiState(
        inventory = InventorySummary(listOf(InventoryModel("o/r/main/a.gguf", "o/r", "a.gguf", 20_986_944)), emptyList(), 0, 0),
        engine = EngineAvailability.Available,
        runtime = ChatModelState.Ready("a.gguf", 2048),
        device = DeviceResourceSnapshot.from(RawDeviceReadings(4L shl 30, 2L shl 30, null, false, false, 64L shl 30, 10L shl 30), 0),
        app = AppInfo("LocalAI", "1.0", 1, "Google", "sdk_gphone", "17", 37, "x86_64", listOf("x86_64"))
    )

    @Test
    fun settingsShowRealValuesAndNoDemoText() {
        showSettings(realState)
        compose.onNodeWithText("a.gguf · load or switch in My Models").assertIsDisplayed()
        compose.onNodeWithText("1 model · 20.0 MB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("llama.cpp v0.6.0 (CPU) · available").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1.0 (1)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("4.0 GB total · 2.0 GB available now").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Clear demo data").assertDoesNotExist()
        compose.onNodeWithText("Restore sample data").assertDoesNotExist()
        compose.onNodeWithText("1.0 (placeholder)").assertDoesNotExist()
    }

    @Test
    fun unimplementedControlsAreDisabledNotFake() {
        showSettings(realState)
        compose.onNodeWithText("Small").assertIsNotEnabled()
        compose.onNodeWithText("Automatic compatibility check").performScrollTo()
        compose.onNodeWithText("Medium").performClick()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun anUnavailableEngineIsNotShownAsReady() {
        showSettings(
            realState.copy(
                engine = EngineAvailability.Unavailable("dlopen failed: library not found"),
                runtime = ChatModelState.Unavailable("dlopen failed")
            )
        )
        compose.onNodeWithText("None (engine unavailable) · load or switch in My Models").assertIsDisplayed()
        compose.onNodeWithText("Unavailable: dlopen failed: library not found").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun controlsTriggerTheirActions() {
        showSettings(realState)
        compose.onNodeWithText("Dark").performClick()
        compose.onNodeWithText("Loaded model").performClick()
        compose.onNodeWithText("Clear conversations").performScrollTo().performClick()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("Open-source licences").performScrollTo().performClick()
        assertEquals(listOf("theme:DARK", "my_models", "clear", "licences"), calls)
    }

    // ---- Licences screen ----

    @Test
    fun theLicenceScreenShowsTheWholeBundledTextAndScrolls() {
        val text = LicenceNotices.load(context).getOrThrow()
        compose.setContent { LocalAITheme { LicencesScreen(LicenceText.Loaded(text), onBack = { calls += "back" }) } }
        compose.onNodeWithText("THIRD-PARTY NOTICES", substring = true).assertIsDisplayed()
        // The last paragraph is reachable by scrolling: nothing is truncated.
        val last = licenceParagraphs(text).last()
        compose.onNodeWithTag("licences").performScrollToNode(hasText(last))
        compose.onNodeWithText(last).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("back"), calls)
    }

    @Test
    fun aMissingLicenceAssetShowsAClearError() {
        compose.setContent { LocalAITheme { LicencesScreen(LicenceText.Failed("FileNotFoundException"), onBack = {}) } }
        compose.onNodeWithText("Licence notices could not be loaded").assertIsDisplayed()
        compose.onNodeWithText("missing or unreadable", substring = true).assertIsDisplayed()
    }
}
