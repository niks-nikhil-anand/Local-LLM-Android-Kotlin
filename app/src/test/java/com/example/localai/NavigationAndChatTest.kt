package com.example.localai

import com.example.localai.data.mock.MockChatRepository
import com.example.localai.data.mock.MockWorkspaceRepository
import com.example.localai.ui.navigation.Routes
import com.example.localai.ui.navigation.TopLevelDestination
import com.example.localai.ui.state.LocalAIUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationAndChatTest {

    private fun state(query: String = "", activeId: String = "c-1") = LocalAIUiState(
        conversations = MockChatRepository.sampleConversations(),
        activeConversationId = activeId,
        historyQuery = query
    )

    @Test
    fun bottomNavigationHasExactlyFourDestinationsInOrder() {
        assertEquals(
            listOf(Routes.HOME, Routes.CHAT, Routes.EXPLORE, Routes.MY_MODELS),
            TopLevelDestination.entries.map { it.route }
        )
        assertEquals(
            listOf("Home", "Chat", "Explore", "My Models"),
            TopLevelDestination.entries.map { it.label }
        )
    }

    @Test
    fun secondaryRoutesAreNotBottomNavigationItems() {
        val tabRoutes = TopLevelDestination.entries.map { it.route }
        listOf(Routes.SETTINGS, Routes.LIBRARY, Routes.SKILLS, Routes.SCHEDULED_TASKS)
            .forEach { assertTrue(it !in tabRoutes) }
    }

    @Test
    fun activeConversationAndModelResolve() {
        val s = state()
        assertEquals("Getting started", s.activeConversation?.title)
        assertNotNull(s.activeModel)
        assertEquals("qwen-small-text", s.activeModel?.id)
    }

    @Test
    fun historySearchMatchesTitleAndMessageText() {
        assertEquals(4, state().filteredConversations.size)
        assertEquals(listOf("c-2"), state(query = "coroutines").filteredConversations.map { it.id })
        // Matches message text, not only the title.
        assertEquals(listOf("c-3"), state(query = "coastal").filteredConversations.map { it.id })
        assertTrue(state(query = "no-such-text").filteredConversations.isEmpty())
    }

    @Test
    fun workspaceSampleDataHasUniqueIds() {
        val ids = MockWorkspaceRepository.skills.map { it.id } +
            MockWorkspaceRepository.scheduledTasks.map { it.id } +
            MockWorkspaceRepository.libraryItems.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
