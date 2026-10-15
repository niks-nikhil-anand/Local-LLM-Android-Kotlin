package com.example.localai

import com.example.localai.data.mock.MockChatRepository
import com.example.localai.data.mock.MockModelRepository
import com.example.localai.data.model.DemoScenario
import com.example.localai.data.model.InstallState
import com.example.localai.data.model.Modality
import com.example.localai.data.model.SortOption
import com.example.localai.ui.components.MessageSegment
import com.example.localai.ui.components.parseMessageSegments
import com.example.localai.ui.state.LocalAIUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoLogicTest {

    private fun state(
        query: String = "",
        category: Modality? = null,
        sort: SortOption = SortOption.RECOMMENDED,
        scenario: DemoScenario = DemoScenario.SAMPLE
    ) = LocalAIUiState(
        searchQuery = query,
        selectedCategory = category,
        sortOption = sort,
        scenario = scenario,
        installStates = MockModelRepository.initialInstallStates(scenario)
    )

    @Test
    fun searchMatchesNameProviderAndTask() {
        assertTrue(state(query = "whisper").visibleModels.all { it.name.contains("Whisper") })
        assertTrue(state(query = "google").visibleModels.any { it.provider == "Google" })
        assertTrue(state(query = "speech recognition").visibleModels.isNotEmpty())
        assertTrue(state(query = "zzzz-no-match").visibleModels.isEmpty())
    }

    @Test
    fun categoryFilterOnlyReturnsThatModality() {
        val result = state(category = Modality.SPEECH).visibleModels
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.modality == Modality.SPEECH })
    }

    @Test
    fun sortingOrdersBySizeAndName() {
        val smallest = state(sort = SortOption.SMALLEST).visibleModels.map { it.sizeMb }
        assertEquals(smallest.sorted(), smallest)
        val largest = state(sort = SortOption.LARGEST).visibleModels.map { it.sizeMb }
        assertEquals(largest.sortedDescending(), largest)
        val names = state(sort = SortOption.NAME).visibleModels.map { it.name.lowercase() }
        assertEquals(names.sorted(), names)
    }

    @Test
    fun modelIdsAreUniqueAndFindable() {
        val ids = MockModelRepository.models.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { MockModelRepository.findModel(it) != null })
    }

    @Test
    fun scenariosProduceExpectedStorageStates() {
        val empty = state(scenario = DemoScenario.EMPTY)
        assertTrue(empty.managedModels.isEmpty())
    }

    @Test
    fun sampleScenarioCoversEveryInstallState() {
        val states = state().installStates.values
        assertTrue(states.any { it == InstallState.Installed })
        assertTrue(states.any { it is InstallState.Downloading })
        assertTrue(states.any { it is InstallState.Paused })
        assertTrue(states.any { it is InstallState.Failed })
    }

    @Test
    fun chatRepliesAreDeterministic() {
        assertEquals(
            MockChatRepository.replyFor("hello", 0),
            MockChatRepository.replyFor("hello", 0)
        )
        assertTrue(MockChatRepository.replyFor("write a kotlin function", 0).contains("```"))
    }

    @Test
    fun codeFencesAreParsedIntoSegments() {
        val segments = parseMessageSegments("Intro\n\n```kotlin\nval x = 1\n```\n\nOutro")
        assertEquals(3, segments.size)
        assertEquals(MessageSegment.Code("kotlin", "val x = 1"), segments[1])
        assertEquals(listOf(MessageSegment.Text("plain")), parseMessageSegments("plain"))
    }
}
