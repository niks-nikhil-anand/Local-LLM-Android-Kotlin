package com.example.localai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.localai.data.device.DeviceResourceSnapshot
import com.example.localai.data.device.RawDeviceReadings
import com.example.localai.data.download.InventoryModel
import com.example.localai.data.download.InventorySummary
import com.example.localai.ui.screens.home.HomeScreen
import com.example.localai.ui.screens.home.HomeUiState
import com.example.localai.ui.state.ChatModelState
import com.example.localai.ui.theme.LocalAITheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Home renders the real state it is given; layout and navigation are unchanged. */
@RunWith(AndroidJUnit4::class)
class HomeScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val gb = 1L shl 30
    private val device = DeviceResourceSnapshot.from(
        RawDeviceReadings(4 * gb, 2 * gb, null, false, false, 64 * gb, 10 * gb), 0
    )

    private fun show(state: HomeUiState) = compose.setContent {
        LocalAITheme {
            HomeScreen(
                state = state,
                onExplore = { calls += "explore" },
                onMyModels = { calls += "my_models" },
                onSettings = { calls += "settings" },
                onOpenChat = { calls += "chat" }
            )
        }
    }

    private fun inventory(vararg sizes: Long) = InventorySummary(
        sizes.mapIndexed { i, s -> InventoryModel("o/r/main/m$i.gguf", "o/r", "m$i.gguf", s) }, emptyList(), 0, 0
    )

    @Test
    fun loadingShowsNoNumbers() {
        show(HomeUiState())
        // Memory, storage, model count and the badge are text-shaped placeholders until real data arrives.
        listOf("Memory loading", "Storage loading", "Downloaded models loading", "Measuring this device").forEach {
            compose.onNodeWithContentDescription(it).assertIsDisplayed()
        }
        assertTrue(compose.onAllNodesWithText("Loading…").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("No models downloaded").fetchSemanticsNodes().isEmpty())
        // Static content and quick actions stay usable while loading.
        compose.onNodeWithText("Explore Models").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("None").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun emptyInventoryShowsZeroHonestlyAndTheEmptyState() {
        show(HomeUiState(inventory = InventorySummary.EMPTY, device = device))
        compose.onNodeWithText("None").assertIsDisplayed()
        compose.onNodeWithText("Models 0 B · 10.0 GB free").assertIsDisplayed()
        compose.onNodeWithText("No model loaded").assertIsDisplayed()
        compose.onNodeWithTag("home").performScrollToNode(hasText("No models downloaded"))
        compose.onNodeWithText("No models downloaded").assertIsDisplayed()
    }

    @Test
    fun realCountsStorageAndTheLoadedModelAreShown() {
        show(
            HomeUiState(
                inventory = inventory(20_986_944, 18_901_824),
                device = device,
                runtime = ChatModelState.Ready("m1.gguf", 256),
                loadedRecordId = "o/r/main/m1.gguf"
            )
        )
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithText("Models 38.0 MB · 10.0 GB free").assertIsDisplayed()
        compose.onNodeWithText("Loaded: m1.gguf").assertIsDisplayed()
        compose.onNodeWithText("2.0 GB free of 4.0 GB").assertIsDisplayed()
        compose.onNodeWithText("Measured").assertIsDisplayed()
        compose.onNodeWithTag("home").performScrollToNode(hasText("Your models"))
        compose.onNodeWithTag("home").performScrollToNode(hasText("m1.gguf"))
        compose.onNodeWithText("Loaded").assertIsDisplayed()
    }

    @Test
    fun anErrorReplacesNumbersWithUnavailable() {
        show(HomeUiState(error = "Your downloaded models could not be read: disk I/O error", deviceMeasured = true))
        compose.onNodeWithText("Your downloaded models could not be read: disk I/O error").assertIsDisplayed()
        // Memory (not measured), storage and model count: unknown values say so, never 0.
        assertEquals(3, compose.onAllNodesWithText("Unavailable").fetchSemanticsNodes().size)
        assertTrue(compose.onAllNodesWithText("None").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun quickActionsAndSettingsNavigate() {
        show(HomeUiState(inventory = inventory(1000), device = device))
        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithTag("home").performScrollToNode(hasText("Explore Models"))
        compose.onNodeWithText("Explore Models").performClick()
        compose.onNodeWithText("Open My Models").performClick()
        compose.onNodeWithText("Open Chat").performClick()
        assertEquals(listOf("settings", "explore", "my_models", "chat"), calls)
    }
}
