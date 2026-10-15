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
import com.example.localai.data.catalog.DownloadStatus
import com.example.localai.data.catalog.HfRepoFile
import com.example.localai.data.catalog.HfRepoId
import com.example.localai.data.catalog.IncompatibilityReason
import com.example.localai.data.catalog.RuntimeCompatibility
import com.example.localai.data.compat.CompatibilityEvaluator
import com.example.localai.data.compat.DeviceCapabilities
import com.example.localai.data.compat.RepoAnalyzer
import com.example.localai.data.download.Verification
import com.example.localai.ui.screens.mymodels.LocalAnalysis
import com.example.localai.ui.screens.mymodels.MyModelItem
import com.example.localai.ui.screens.mymodels.MyModelsActions
import com.example.localai.ui.screens.mymodels.MyModelsScreen
import com.example.localai.ui.screens.mymodels.MyModelsUiState
import com.example.localai.ui.theme.LocalAITheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MyModelsScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = MyModelsActions(
        onOpenDetails = { calls += "open:$it" },
        onPause = { calls += "pause:$it" },
        onResume = { calls += "resume:$it" },
        onCancel = { calls += "cancel:$it" },
        onDelete = { calls += "delete:$it" },
        onRefresh = { calls += "refresh" },
        onDismissMessage = { calls += "dismiss" },
        onExplore = { calls += "explore" }
    )

    private fun show(state: MyModelsUiState) = compose.setContent { LocalAITheme { MyModelsScreen(state, actions) } }

    private fun completed(path: String, verification: Verification): MyModelItem {
        val file = HfRepoFile(HfRepoId("o/r"), "main", path, 20_986_944, null)
        val variant = RepoAnalyzer.analyze(listOf(file)).variants.single()
        val assessment = CompatibilityEvaluator.evaluate(
            variant, null, DeviceCapabilities(8L shl 30, 4L shl 30, 40L shl 30), localFileSizes = mapOf(path to 20_986_944L)
        )
        return MyModelItem(
            id = "o/r/main/$path", repoId = "o/r", revision = "main", path = path,
            status = DownloadStatus.Completed, sizeBytes = 20_986_944, verification = verification,
            analysis = LocalAnalysis.Ready(assessment, null, RuntimeCompatibility.Incompatible(IncompatibilityReason.NO_RUNTIME))
        )
    }

    @Test
    fun emptyStateLeadsToExplore() {
        show(MyModelsUiState(loading = false))
        compose.onNodeWithTag("my_models").performScrollToNode(hasText("No models downloaded"))
        compose.onNodeWithText("Explore Models").performClick()
        assertEquals(listOf("explore"), calls)
    }

    @Test
    fun loadingIndicatorBeforeRecordsArrive() {
        show(MyModelsUiState(loading = true))
        compose.onNodeWithTag("my_models_loading").assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading downloaded models").assertIsDisplayed()
        // Neither a false empty state nor a false "could not be measured" while still loading.
        compose.onNodeWithText("No models downloaded").assertDoesNotExist()
        compose.onNodeWithText("could not be measured", substring = true).assertDoesNotExist()
    }

    @Test
    fun aRecordReadFailureIsAnErrorNotZeroModels() {
        show(MyModelsUiState(loading = false, error = "Downloaded models could not be read: disk I/O error"))
        compose.onNodeWithTag("my_models").performScrollToNode(hasText("Downloaded models could not be read"))
        compose.onNodeWithText("No models downloaded").assertDoesNotExist()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf("refresh"), calls)
    }

    @Test
    fun completedModelsShowVerificationAndAreNeverLoadable() {
        show(MyModelsUiState(loading = false, items = listOf(completed("a-Q4_K_M.gguf", Verification.SHA256), completed("b-Q8_0.gguf", Verification.SIZE_ONLY))))
        compose.onNodeWithTag("my_models").performScrollToNode(hasText("SHA-256 verified"))
        compose.onNodeWithText("SHA-256 verified").assertIsDisplayed()
        compose.onNodeWithTag("my_models").performScrollToNode(hasText("Size verified only (no checksum)"))
        compose.onNodeWithText("Size verified only (no checksum)").assertIsDisplayed()
        compose.onNodeWithTag("my_models").performScrollToNode(hasText("Not loadable", substring = true))
        // One per card: both completed files are equally not loadable.
        assertEquals(2, compose.onAllNodesWithText("Not loadable: no inference engine is installed in this build.").fetchSemanticsNodes().size)
    }

    @Test
    fun downloadingShowsProgressAndPauseAndCancel() {
        val item = MyModelItem("o/r/main/m.gguf", "o/r", "main", "m.gguf", DownloadStatus.Downloading(250_000, 1_000_000), 1_000_000, null, LocalAnalysis.NotAvailable)
        show(MyModelsUiState(loading = false, items = listOf(item)))
        compose.onNodeWithTag("my_models").performScrollToNode(hasText("Pause"))
        compose.onNodeWithText("244.1 KB of 976.6 KB (25%)").assertIsDisplayed()
        compose.onNodeWithText("Pause").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("pause:o/r/main/m.gguf", "cancel:o/r/main/m.gguf"), calls)
    }

    @Test
    fun failedDownloadsExplainAndRetryOnlyWhenPossible() {
        val retryable = MyModelItem("o/r/main/a.gguf", "o/r", "main", "a.gguf", DownloadStatus.Failed("The connection was lost.", true), 1, null, LocalAnalysis.NotAvailable)
        show(MyModelsUiState(loading = false, items = listOf(retryable)))
        compose.onNodeWithTag("my_models").performScrollToNode(hasText("Retry"))
        compose.onNodeWithText("The connection was lost.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf("resume:o/r/main/a.gguf"), calls)
    }

    @Test
    fun deleteAsksForConfirmationFirst() {
        show(MyModelsUiState(loading = false, items = listOf(completed("a-Q4_K_M.gguf", Verification.SHA256))))
        compose.onNodeWithTag("my_models").performScrollToNode(hasText("a-Q4_K_M.gguf", substring = true))
        compose.onNodeWithContentDescription("Delete a-Q4_K_M.gguf").performClick()
        assertTrue(calls.isEmpty()) // nothing deleted yet
        compose.onNodeWithText("Delete a-Q4_K_M.gguf?").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(listOf("delete:o/r/main/a-Q4_K_M.gguf"), calls)
    }

    @Test
    fun tappingAModelOpensItsRepository() {
        show(MyModelsUiState(loading = false, items = listOf(completed("a-Q4_K_M.gguf", Verification.SHA256))))
        compose.onNodeWithTag("my_models").performScrollToNode(hasText("a-Q4_K_M.gguf", substring = true))
        compose.onNodeWithText("o/r").performClick()
        assertEquals(listOf("open:o/r"), calls)
    }
}
