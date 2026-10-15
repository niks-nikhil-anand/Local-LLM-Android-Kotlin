package com.example.localai.ui.state

import androidx.compose.runtime.Immutable
import com.example.localai.data.mock.MockModelRepository
import com.example.localai.data.attachment.Attachment
import com.example.localai.data.model.Conversation
import com.example.localai.data.model.DemoScenario
import com.example.localai.data.model.InstallState
import com.example.localai.data.model.Modality
import com.example.localai.data.model.ModelInfo
import com.example.localai.data.inference.ActiveModel
import com.example.localai.data.inference.EngineState
import com.example.localai.data.inference.ModelLoadStatus
import com.example.localai.data.model.SortOption
import com.example.localai.ui.theme.ThemeMode

/** What Chat can do right now, derived from the shared on-device model session. */
sealed interface ChatModelState {
    /** The native engine is not present on this device. */
    data class Unavailable(val reason: String) : ChatModelState
    data object NoModel : ChatModelState
    data class Loading(val fileName: String, val phase: LoadPhase = LoadPhase.LOADING) : ChatModelState
    data class Ready(val fileName: String, val contextLength: Int) : ChatModelState
    data class LoadFailed(val fileName: String, val message: String) : ChatModelState
}

/** The real steps of preparing a model, as reported by the session manager. */
enum class LoadPhase {
    /** Validating the file and checking it fits this device's memory. */
    CHECKING,

    /** The native engine is reading the model into memory. No percentage is available. */
    LOADING
}

/**
 * The runtime state shown by Chat and Home, derived only from the shared inference session: a
 * model is "loaded" when the engine has it, never because its file exists.
 */
fun modelRuntimeState(
    engine: EngineState,
    active: ActiveModel?,
    status: Map<String, ModelLoadStatus>
): ChatModelState {
    if (engine is EngineState.Unavailable) return ChatModelState.Unavailable(engine.reason)
    if (active != null) return ChatModelState.Ready(active.fileName, active.loaded.contextLength)
    status.entries.firstOrNull { it.value == ModelLoadStatus.Checking || it.value == ModelLoadStatus.Loading }?.let {
        val phase = if (it.value == ModelLoadStatus.Checking) LoadPhase.CHECKING else LoadPhase.LOADING
        return ChatModelState.Loading(it.key.substringAfterLast('/'), phase)
    }
    status.entries.firstOrNull { it.value is ModelLoadStatus.Failed }
        ?.let { return ChatModelState.LoadFailed(it.key.substringAfterLast('/'), (it.value as ModelLoadStatus.Failed).message) }
    return ChatModelState.NoModel
}

/** A model that appears in My Models together with its simulated state. */
data class ManagedModel(val model: ModelInfo, val state: InstallState)

/** Single source of truth for all demo UI state. Nothing here reflects real downloads. */
@Immutable
data class LocalAIUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val searchQuery: String = "",
    val selectedCategory: Modality? = null,
    val sortOption: SortOption = SortOption.RECOMMENDED,
    val scenario: DemoScenario = DemoScenario.SAMPLE,
    val installStates: Map<String, InstallState> = emptyMap(),
    // Chat (kept in memory only)
    val conversations: List<Conversation> = emptyList(),
    val activeConversationId: String = "",
    val historyQuery: String = "",
    val pendingReplies: Set<String> = emptySet(),
    /** Who web searches go to; null when this build has no search provider. */
    val webSearchProvider: String? = null,
    /** Off by default; on only after the user confirmed what is sent ([webSearchConsented]). */
    val webSearchEnabled: Boolean = false,
    val webSearchConsented: Boolean = false,
    /** The consent dialog is showing. */
    val webSearchConsentPending: Boolean = false,
    /** Documents read on-device, waiting to be sent with the next message. */
    val pendingAttachments: List<Attachment> = emptyList(),
    val readingAttachment: Boolean = false,
    val enabledSkillIds: Set<String> = emptySet(),
    val enabledTaskIds: Set<String> = emptySet(),
    // Real on-device chat
    val chatModel: ChatModelState = ChatModelState.NoModel,
    /** Last chat failure (load or generation), shown until dismissed or the next send. */
    val chatError: String? = null,
    // Settings
    val defaultModelId: String = "qwen-small-text"
) {
    fun installStateOf(modelId: String): InstallState =
        installStates[modelId] ?: InstallState.NotInstalled

    /** Catalogue after applying search, category filter and sort. */
    val visibleModels: List<ModelInfo>
        get() {
            val query = searchQuery.trim()
            val filtered = MockModelRepository.models.filter { model ->
                (selectedCategory == null || model.modality == selectedCategory) &&
                    (query.isEmpty() ||
                        model.name.contains(query, ignoreCase = true) ||
                        model.provider.contains(query, ignoreCase = true) ||
                        model.task.contains(query, ignoreCase = true) ||
                        model.modality.label.contains(query, ignoreCase = true))
            }
            return when (sortOption) {
                SortOption.RECOMMENDED -> filtered.sortedBy { it.recommendedRank }
                SortOption.SMALLEST -> filtered.sortedBy { it.sizeMb }
                SortOption.LARGEST -> filtered.sortedByDescending { it.sizeMb }
                SortOption.NAME -> filtered.sortedBy { it.name.lowercase() }
            }
        }

    /** Entries shown in My Models, in the order they were added. */
    val managedModels: List<ManagedModel>
        get() = installStates.mapNotNull { (id, state) ->
            if (state == InstallState.NotInstalled) null
            else MockModelRepository.findModel(id)?.let { ManagedModel(it, state) }
        }

    // ---- Chat ----

    val activeConversation: Conversation?
        get() = conversations.firstOrNull { it.id == activeConversationId }

    /** History after applying the drawer search. */
    val filteredConversations: List<Conversation>
        get() = conversations.filter { it.matches(historyQuery.trim()) }
}

/** Callbacks used by the Chat screen, grouped to keep the screen signature short. */
data class ChatActions(
    val onSend: (String) -> Unit,
    val onStop: () -> Unit = {},
    val onOpenMyModels: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onClear: () -> Unit,
    val onNewChat: () -> Unit,
    val onSelectConversation: (String) -> Unit,
    val onHistoryQueryChange: (String) -> Unit,
    val onToggleWebSearch: () -> Unit,
    val onConfirmWebSearch: () -> Unit = {},
    val onDismissWebSearchConsent: () -> Unit = {},
    /** A document URI returned by the system file picker. */
    val onAttachDocument: (String) -> Unit,
    val onRemoveAttachment: (Long) -> Unit,
    val onShowMessage: (String) -> Unit,
    val onOpenLibrary: () -> Unit,
    val onOpenSkills: () -> Unit,
    val onOpenScheduledTasks: () -> Unit,
    val onOpenSettings: () -> Unit
)
