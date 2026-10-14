package com.example.localai.ui.state

import androidx.compose.runtime.Immutable
import com.example.localai.data.mock.MockModelRepository
import com.example.localai.data.model.Attachment
import com.example.localai.data.model.AttachmentKind
import com.example.localai.data.model.Conversation
import com.example.localai.data.model.DemoScenario
import com.example.localai.data.model.DeviceInfo
import com.example.localai.data.model.InstallState
import com.example.localai.data.model.Modality
import com.example.localai.data.model.ModelInfo
import com.example.localai.data.model.PreferredModelSize
import com.example.localai.data.model.RuntimeStatus
import com.example.localai.data.model.SortOption
import com.example.localai.ui.theme.ThemeMode

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
    // Chat (all simulated, kept in memory only)
    val conversations: List<Conversation> = emptyList(),
    val activeConversationId: String = "",
    val historyQuery: String = "",
    val pendingReplies: Set<String> = emptySet(),
    val webSearchEnabled: Boolean = false,
    val pendingAttachments: List<Attachment> = emptyList(),
    val enabledSkillIds: Set<String> = emptySet(),
    val enabledTaskIds: Set<String> = emptySet(),
    // Settings
    val defaultModelId: String = "qwen-small-text",
    val preferredSize: PreferredModelSize = PreferredModelSize.SMALL,
    val autoCompatibilityCheck: Boolean = true
) {
    val runtimeStatus: RuntimeStatus = RuntimeStatus.IDLE

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

    val installedCount: Int
        get() = installStates.values.count { it == InstallState.Installed }

    /** Space attributed to models: full size when installed, partial while in progress. */
    val modelsUsedMb: Long
        get() = installStates.entries.sumOf { (id, state) ->
            val size = MockModelRepository.findModel(id)?.sizeMb ?: 0
            when (state) {
                InstallState.Installed -> size.toLong()
                is InstallState.Downloading -> (size * state.progress).toLong()
                is InstallState.Paused -> (size * state.progress).toLong()
                is InstallState.Failed -> (size * state.progress).toLong()
                InstallState.NotInstalled -> 0L
            }
        }

    val device: DeviceInfo
        get() = MockModelRepository.deviceInfo.let { base ->
            if (scenario == DemoScenario.STORAGE_FULL) {
                base.copy(otherUsedStorageMb = MockModelRepository.FULL_STORAGE_OTHER_USED_MB)
            } else base
        }

    val freeStorageMb: Long get() = device.freeStorageMb(modelsUsedMb)
    val isStorageNearlyFull: Boolean get() = device.isStorageNearlyFull(modelsUsedMb)

    // ---- Chat ----

    val activeConversation: Conversation?
        get() = conversations.firstOrNull { it.id == activeConversationId }

    val activeModel: ModelInfo?
        get() = activeConversation?.let { MockModelRepository.findModel(it.modelId) }

    /** History after applying the drawer search. */
    val filteredConversations: List<Conversation>
        get() = conversations.filter { it.matches(historyQuery.trim()) }
}

/** Callbacks used by the Settings screen, grouped to keep the screen signature short. */
data class LocalAISettingsActions(
    val onThemeSelect: (ThemeMode) -> Unit,
    val onDefaultModelSelect: (String) -> Unit,
    val onPreferredSizeSelect: (PreferredModelSize) -> Unit,
    val onAutoCompatibilityChange: (Boolean) -> Unit,
    val onClearDemoData: () -> Unit,
    val onRestoreDemoData: () -> Unit,
    val onShowMessage: (String) -> Unit
)

/** Callbacks used by the Chat screen, grouped to keep the screen signature short. */
data class ChatActions(
    val onSend: (String) -> Unit,
    val onClear: () -> Unit,
    val onNewChat: () -> Unit,
    val onSelectConversation: (String) -> Unit,
    val onHistoryQueryChange: (String) -> Unit,
    val onSelectModel: (String) -> Unit,
    val onToggleWebSearch: () -> Unit,
    val onAddAttachment: (AttachmentKind) -> Unit,
    val onRemoveAttachment: (Long) -> Unit,
    val onOpenDetails: (String) -> Unit,
    val onShowMessage: (String) -> Unit,
    val onOpenLibrary: () -> Unit,
    val onOpenSkills: () -> Unit,
    val onOpenScheduledTasks: () -> Unit,
    val onOpenSettings: () -> Unit
)
