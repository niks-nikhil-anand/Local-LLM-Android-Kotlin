package com.example.localai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.localai.data.catalog.CatalogAccess
import com.example.localai.data.catalog.CatalogError
import com.example.localai.data.catalog.HfModelSummary
import com.example.localai.data.catalog.HfRepoFile
import com.example.localai.data.catalog.HfRepoId
import com.example.localai.data.compat.CompatibilityEvaluator
import com.example.localai.data.compat.DeviceCapabilities
import com.example.localai.data.compat.RepoAnalyzer
import com.example.localai.data.compat.RuntimeProfile
import com.example.localai.ui.screens.explore.ExploreActions
import com.example.localai.ui.screens.explore.ExploreScreen
import com.example.localai.ui.screens.explore.ExploreUiState
import com.example.localai.ui.screens.explore.Fetch
import com.example.localai.ui.screens.explore.FilesState
import com.example.localai.ui.screens.explore.HfModelDetailsScreen
import com.example.localai.ui.screens.explore.RepoDetailsActions
import com.example.localai.ui.screens.explore.RepoDetailsUiState
import com.example.localai.ui.screens.explore.VariantAssessment
import com.example.localai.ui.theme.LocalAITheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explore UI states rendered from fixed state objects; no network involved. */
@RunWith(AndroidJUnit4::class)
class ExploreScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val actions = ExploreActions(
        onQueryChange = { calls += "query:$it" },
        onCategorySelect = { calls += "category:$it" },
        onSortSelect = { calls += "sort:$it" },
        onRefresh = { calls += "refresh" },
        onRetry = { calls += "retry" },
        onLoadMore = { calls += "more" },
        onClearFilters = { calls += "clear" },
        onModelClick = { calls += "open:$it" }
    )

    private fun model(id: String, access: CatalogAccess = CatalogAccess.Public) = HfModelSummary(
        repoId = HfRepoId(id), author = id.substringBefore('/'), downloads = 1_234_567, likes = 42,
        license = "apache-2.0", pipelineTag = "text-generation", tags = listOf("gguf"),
        lastModified = "2024-09-20T06:20:24.000Z", access = access
    )

    private fun show(state: ExploreUiState) = compose.setContent { LocalAITheme { ExploreScreen(state, actions) } }

    @Test
    fun loadingIndicatorWhileTheFirstPageLoads() {
        show(ExploreUiState(isLoading = true))
        compose.onNodeWithTag("explore_loading").assertIsDisplayed()
        compose.onNodeWithText("Searching…").assertIsDisplayed()
    }

    @Test
    fun resultsShowRealFieldsAndOpenDetails() {
        show(ExploreUiState(results = listOf(model("Qwen/Qwen2.5-0.5B-Instruct-GGUF"), model("meta-llama/Llama-3.2-1B", CatalogAccess.Gated("manual"))), resultsQuery = "qwen"))
        compose.onNodeWithText("Qwen2.5-0.5B-Instruct-GGUF").assertIsDisplayed()
        compose.onNodeWithText("2 models for \"qwen\"").assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithText("License: apache-2.0").fetchSemanticsNodes().size)
        compose.onNodeWithText("Gated").assertIsDisplayed()
        compose.onNodeWithText("Qwen2.5-0.5B-Instruct-GGUF").performClick()
        assertTrue("open:Qwen/Qwen2.5-0.5B-Instruct-GGUF" in calls)
    }

    @Test
    fun errorStateOffersRetry() {
        show(ExploreUiState(error = CatalogError.Offline))
        compose.onNodeWithText("Could not load models").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun rateLimitMessageMentionsTheWait() {
        show(ExploreUiState(error = CatalogError.RateLimited(90)))
        compose.onNodeWithText("Hugging Face is limiting requests right now. Try again in about 90 s.").assertIsDisplayed()
    }

    @Test
    fun emptyStateClearsFilters() {
        show(ExploreUiState(resultsQuery = "zzz"))
        compose.onNodeWithText("No models found").assertIsDisplayed()
        compose.onNodeWithText("Clear search and filters").performClick()
        assertEquals(listOf("clear"), calls)
    }

    @Test
    fun typingForwardsTheQuery() {
        show(ExploreUiState())
        compose.onNodeWithText("Search Hugging Face GGUF models").performTextInput("llama")
        assertTrue("query:llama" in calls)
    }

    @Test
    fun pageErrorKeepsResultsAndOffersRetry() {
        show(ExploreUiState(results = listOf(model("a/one")), pageError = CatalogError.Server(503)))
        compose.onNodeWithText("one").assertIsDisplayed()
        compose.onNodeWithTag("explore_results").performScrollToNode(hasText("Retry"))
        compose.onNodeWithText("Retry").performClick()
        assertTrue("retry" in calls)
    }

    @Test
    fun detailsShowVerdictAndPlainLanguageFindings() {
        val repo = HfRepoId("Qwen/Qwen2.5-0.5B-Instruct-GGUF")
        val analysis = RepoAnalyzer.analyze(listOf(HfRepoFile(repo, "main", "qwen2.5-0.5b-instruct-q4_k_m.gguf", 397_808_192, null)))
        val device = DeviceCapabilities(totalRamBytes = 8L shl 30, availableRamBytes = 4L shl 30, freeStorageBytes = 40L shl 30)
        val variants = analysis.variants.map { VariantAssessment(it, CompatibilityEvaluator.evaluate(it, null, device, RuntimeProfile.DEFAULT_ASSUMED)) }
        val state = RepoDetailsUiState(
            repoId = repo,
            summary = Fetch.Ready(model(repo.value)),
            files = FilesState.Ready(analysis, variants),
            profile = RuntimeProfile.DEFAULT_ASSUMED
        )
        compose.setContent {
            LocalAITheme { HfModelDetailsScreen(state, RepoDetailsActions({}, {}, {}, { calls += "recheck" })) }
        }
        compose.onNodeWithText("run on this device with llama.cpp (CPU)", substring = true).assertIsDisplayed()
        compose.onNodeWithText("No inference engine is installed", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("repo_details").performScrollToNode(hasText("Compatibility unknown"))
        compose.onNodeWithText("Compatibility unknown").assertIsDisplayed()
        compose.onNodeWithText("Quant (from name): Q4_K_M").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("The file header has not been read", substring = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("repo_details").performScrollToNode(hasText("Re-check device"))
        compose.onNodeWithText("Re-check device").performClick()
        assertTrue("recheck" in calls)
    }
}
