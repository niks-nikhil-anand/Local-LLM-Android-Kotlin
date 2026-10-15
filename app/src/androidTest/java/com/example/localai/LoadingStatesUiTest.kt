package com.example.localai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.localai.data.catalog.CatalogAccess
import com.example.localai.data.catalog.CatalogError
import com.example.localai.data.catalog.HfModelSummary
import com.example.localai.data.catalog.HfRepoId
import com.example.localai.data.compat.RuntimeProfile
import com.example.localai.data.model.ChatMessage
import com.example.localai.data.model.ChatRole
import com.example.localai.data.model.Conversation
import com.example.localai.ui.components.SkeletonCardList
import com.example.localai.ui.components.SkeletonModelCard
import com.example.localai.ui.screens.chat.ChatScreen
import com.example.localai.ui.screens.explore.ExploreActions
import com.example.localai.ui.screens.explore.ExploreScreen
import com.example.localai.ui.screens.explore.ExploreUiState
import com.example.localai.ui.screens.explore.Fetch
import com.example.localai.ui.screens.explore.HfModelDetailsScreen
import com.example.localai.ui.screens.explore.RepoDetailsActions
import com.example.localai.ui.screens.explore.RepoDetailsUiState
import com.example.localai.ui.screens.settings.LicenceText
import com.example.localai.ui.screens.settings.LicencesScreen
import com.example.localai.ui.state.ChatActions
import com.example.localai.ui.state.ChatModelState
import com.example.localai.ui.state.LoadPhase
import com.example.localai.ui.state.LocalAIUiState
import com.example.localai.ui.theme.LocalAITheme
import com.example.localai.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Content-shaped placeholders appear only while loading and are replaced by real content or errors. */
@RunWith(AndroidJUnit4::class)
class LoadingStatesUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    // ---- Explore ----

    private val exploreActions = ExploreActions(
        onQueryChange = {}, onCategorySelect = {}, onSortSelect = {}, onRefresh = {},
        onRetry = { calls += "retry" }, onLoadMore = {}, onClearFilters = {}, onModelClick = {}
    )

    private fun model(id: String) = HfModelSummary(
        repoId = HfRepoId(id), author = id.substringBefore('/'), downloads = 10, likes = 1,
        license = "mit", pipelineTag = "text-generation", tags = listOf("gguf"),
        lastModified = "2024-09-20T06:20:24.000Z", access = CatalogAccess.Public
    )

    @Test
    fun catalogueSkeletonIsReplacedByRealCards() {
        var state by mutableStateOf(ExploreUiState(isLoading = true))
        compose.setContent { LocalAITheme { ExploreScreen(state, exploreActions) } }
        compose.onNodeWithContentDescription("Loading models from Hugging Face").assertIsDisplayed()
        compose.onNodeWithText("No models found").assertDoesNotExist()

        state = ExploreUiState(results = listOf(model("org/real-model-GGUF")), resultsQuery = "")
        compose.onNodeWithText("real-model-GGUF").assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading models from Hugging Face").assertDoesNotExist()
    }

    @Test
    fun catalogueSkeletonEndsInARealEmptyStateOrAnError() {
        var state by mutableStateOf(ExploreUiState(isLoading = true))
        compose.setContent { LocalAITheme { ExploreScreen(state, exploreActions) } }
        state = ExploreUiState(results = emptyList(), resultsQuery = "zzz")
        compose.onNodeWithText("No models found").assertIsDisplayed()

        state = ExploreUiState(error = CatalogError.Offline)
        compose.onNodeWithText("Could not load models").assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading models from Hugging Face").assertDoesNotExist()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun aRunningSearchKeepsTheEarlierCardsWithTheRefreshBar() {
        compose.setContent {
            LocalAITheme { ExploreScreen(ExploreUiState(results = listOf(model("org/kept")), resultsQuery = "old", isRefreshing = true), exploreActions) }
        }
        compose.onNodeWithText("kept").assertIsDisplayed()
        compose.onNodeWithTag("explore_refreshing").assertIsDisplayed()
        compose.onNodeWithText("1 model for \"old\"").assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading models from Hugging Face").assertDoesNotExist()
    }

    // ---- Model details ----

    @Test
    fun detailsKeepTheRealNameWhilePlaceholdersStandInForUnresolvedFacts() {
        var state by mutableStateOf(RepoDetailsUiState(repoId = HfRepoId("org/repo-GGUF"), profile = RuntimeProfile.DEFAULT_ASSUMED))
        compose.setContent { LocalAITheme { HfModelDetailsScreen(state, RepoDetailsActions({}, {}, {}, {})) } }
        compose.onNodeWithText("org/repo-GGUF").assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading repository details").assertIsDisplayed()
        compose.onNodeWithText("Open model card on Hugging Face").assertIsDisplayed()

        state = state.copy(summary = Fetch.Ready(model("org/repo-GGUF")))
        compose.onNodeWithContentDescription("Loading repository details").assertDoesNotExist()
        compose.onNodeWithText("By org").assertIsDisplayed()
    }

    // ---- Licences ----

    @Test
    fun licencePlaceholderIsReplacedByTheRealText() {
        var state by mutableStateOf<LicenceText>(LicenceText.Loading)
        compose.setContent { LocalAITheme { LicencesScreen(state, onBack = {}) } }
        compose.onNodeWithContentDescription("Loading licence notices").assertIsDisplayed()
        state = LicenceText.Loaded("THIRD-PARTY NOTICES\n\nSome notice")
        compose.onNodeWithText("THIRD-PARTY NOTICES").assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading licence notices").assertDoesNotExist()
    }

    // ---- Chat ----

    private val chatActions = ChatActions(
        onSend = {}, onStop = { calls += "stop" }, onClear = {}, onNewChat = {}, onSelectConversation = {},
        onHistoryQueryChange = {}, onToggleWebSearch = {}, onAttachDocument = {}, onRemoveAttachment = {},
        onShowMessage = {}, onOpenLibrary = {}, onOpenSkills = {}, onOpenScheduledTasks = {}, onOpenSettings = {}
    )

    private fun chatState(reply: String, replying: Boolean, note: String? = null) = LocalAIUiState(
        conversations = listOf(
            Conversation(
                "c", "t", "m", listOf(
                    ChatMessage(1, ChatRole.USER, "Hello"),
                    ChatMessage(2, ChatRole.ASSISTANT, reply, generatedBy = "m.gguf", note = note)
                )
            )
        ),
        activeConversationId = "c",
        pendingReplies = if (replying) setOf("c") else emptySet(),
        chatModel = ChatModelState.Ready("m.gguf", 2048)
    )

    @Test
    fun theTypingIndicatorGivesWayToTheStreamedTokens() {
        var state by mutableStateOf(chatState("", replying = true, note = "Searching Wikipedia for “x”…"))
        compose.setContent { LocalAITheme { ChatScreen(state, chatActions) } }
        compose.onNodeWithTag("assistant_typing").assertIsDisplayed()
        compose.onNodeWithContentDescription("Assistant is replying. Searching Wikipedia for “x”…").assertIsDisplayed()
        // Stop stays available while waiting for the first token.
        compose.onNodeWithContentDescription("Stop generating").performClick()
        assertEquals(listOf("stop"), calls)

        state = chatState("Hi there", replying = true)
        compose.onNodeWithTag("assistant_typing").assertDoesNotExist()
        compose.onNodeWithText("Hi there").assertIsDisplayed()

        state = chatState("Hi there, friend.", replying = false)
        compose.onNodeWithContentDescription("Stop generating").assertDoesNotExist()
        compose.onNodeWithContentDescription("Send message").assertIsDisplayed()
    }

    @Test
    fun modelPreparationShowsTheRealPhaseWithoutAPercentage() {
        var state by mutableStateOf(LocalAIUiState(conversations = listOf(Conversation("c", "t", "m", emptyList())), activeConversationId = "c",
            chatModel = ChatModelState.Loading("m.gguf", LoadPhase.CHECKING)))
        compose.setContent { LocalAITheme { ChatScreen(state, chatActions) } }
        compose.onNodeWithTag("chat_model_preparing").assertIsDisplayed()
        compose.onNodeWithText("Checking the file and memory…").assertIsDisplayed()
        compose.onNodeWithText("%", substring = true).assertDoesNotExist()

        state = state.copy(chatModel = ChatModelState.Loading("m.gguf", LoadPhase.LOADING))
        compose.onNodeWithText("Loading into memory…").assertIsDisplayed()

        state = state.copy(chatModel = ChatModelState.Ready("m.gguf", 2048))
        compose.onNodeWithTag("chat_model_preparing").assertDoesNotExist()
        compose.onNodeWithText("Start a conversation").assertIsDisplayed()
    }

    // ---- Themes ----

    @Test
    fun skeletonsRenderInLightAndDarkThemes() {
        var mode by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent {
            LocalAITheme(themeMode = mode) { SkeletonCardList("Loading models", count = 2) { SkeletonModelCard() } }
        }
        compose.onNodeWithContentDescription("Loading models").assertIsDisplayed()
        mode = ThemeMode.DARK
        compose.onNodeWithContentDescription("Loading models").assertIsDisplayed()
    }
}
