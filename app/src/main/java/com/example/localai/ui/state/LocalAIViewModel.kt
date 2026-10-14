package com.example.localai.ui.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.localai.data.mock.MockChatRepository
import com.example.localai.data.mock.MockModelRepository
import com.example.localai.data.model.Attachment
import com.example.localai.data.model.AttachmentKind
import com.example.localai.data.model.ChatMessage
import com.example.localai.data.model.ChatRole
import com.example.localai.data.model.Conversation
import com.example.localai.data.model.DemoScenario
import com.example.localai.data.model.InstallState
import com.example.localai.data.model.Modality
import com.example.localai.data.model.PreferredModelSize
import com.example.localai.data.model.SortOption
import com.example.localai.data.prefs.ThemePreferenceStore
import com.example.localai.ui.theme.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds all demo state. Downloads and replies are simulated with timers; no files,
 * network access or models are involved.
 */
class LocalAIViewModel(application: Application) : AndroidViewModel(application) {

    private val themeStore = ThemePreferenceStore(application)

    private val _uiState = MutableStateFlow(
        LocalAIUiState(
            installStates = MockModelRepository.initialInstallStates(DemoScenario.SAMPLE),
            conversations = MockChatRepository.sampleConversations(),
            activeConversationId = "c-1",
            enabledSkillIds = setOf("skill-summarize")
        )
    )
    val uiState: StateFlow<LocalAIUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** One-off feedback messages (shown as snackbars) for simulated actions. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val downloadJobs = mutableMapOf<String, Job>()
    private val replyJobs = mutableMapOf<String, Job>()
    private var nextMessageId = 1_000L
    private var nextConversationId = 5
    private var nextAttachmentId = 1L

    init {
        viewModelScope.launch {
            themeStore.themeMode.collect { mode -> _uiState.update { it.copy(themeMode = mode) } }
        }
        resumeInitialDownloads()
    }

    // ---- Appearance ----

    fun setThemeMode(mode: ThemeMode) {
        _uiState.update { it.copy(themeMode = mode) }
        viewModelScope.launch { themeStore.setThemeMode(mode) }
    }

    // ---- Explore ----

    fun setSearchQuery(query: String) = _uiState.update { it.copy(searchQuery = query) }

    fun setCategory(category: Modality?) = _uiState.update { it.copy(selectedCategory = category) }

    fun setSortOption(option: SortOption) = _uiState.update { it.copy(sortOption = option) }

    // ---- Simulated model management ----

    fun simulateDownload(modelId: String) {
        _uiState.update { it.copy(installStates = it.installStates + (modelId to InstallState.Downloading(0f))) }
        startDownloadTicker(modelId)
        emit("Simulated download started. Nothing is downloaded in this demo.")
    }

    fun pauseDownload(modelId: String) {
        val current = _uiState.value.installStateOf(modelId) as? InstallState.Downloading ?: return
        downloadJobs.remove(modelId)?.cancel()
        setInstallState(modelId, InstallState.Paused(current.progress))
        emit("Download paused (simulated).")
    }

    fun resumeDownload(modelId: String) {
        val progress = when (val s = _uiState.value.installStateOf(modelId)) {
            is InstallState.Paused -> s.progress
            is InstallState.Failed -> s.progress
            else -> return
        }
        setInstallState(modelId, InstallState.Downloading(progress))
        startDownloadTicker(modelId)
        emit("Download resumed (simulated).")
    }

    fun removeModel(modelId: String) {
        downloadJobs.remove(modelId)?.cancel()
        _uiState.update { it.copy(installStates = it.installStates - modelId) }
        emit("Removed from the demo list. No files were deleted.")
    }

    fun selectScenario(scenario: DemoScenario) {
        cancelAllDownloads()
        _uiState.update {
            it.copy(scenario = scenario, installStates = MockModelRepository.initialInstallStates(scenario))
        }
        resumeInitialDownloads()
    }

    /** Clears the simulated models and conversations. */
    fun clearDemoData() {
        cancelAllDownloads()
        replyJobs.values.forEach { it.cancel() }
        replyJobs.clear()
        val fresh = newEmptyConversation(_uiState.value.defaultModelId)
        _uiState.update {
            it.copy(
                scenario = DemoScenario.EMPTY,
                installStates = emptyMap(),
                conversations = listOf(fresh),
                activeConversationId = fresh.id,
                pendingReplies = emptySet(),
                pendingAttachments = emptyList(),
                historyQuery = ""
            )
        }
        emit("Demo data cleared.")
    }

    // ---- Chat ----

    /** Opens the chat for a model: continues its latest conversation or starts a new one. */
    fun openChatWith(modelId: String) {
        _uiState.update { state ->
            val existing = state.conversations.firstOrNull { it.modelId == modelId }
            when {
                existing != null -> state.copy(activeConversationId = existing.id)
                else -> {
                    val fresh = newEmptyConversation(modelId)
                    state.copy(conversations = listOf(fresh) + state.conversations, activeConversationId = fresh.id)
                }
            }
        }
    }

    fun newConversation() {
        _uiState.update { state ->
            val active = state.activeConversation
            if (active != null && active.messages.isEmpty()) {
                state
            } else {
                val fresh = newEmptyConversation(active?.modelId ?: state.defaultModelId)
                state.copy(
                    conversations = listOf(fresh) + state.conversations,
                    activeConversationId = fresh.id,
                    pendingAttachments = emptyList()
                )
            }
        }
    }

    fun selectConversation(conversationId: String) {
        _uiState.update { state ->
            if (state.conversations.any { it.id == conversationId }) {
                state.copy(activeConversationId = conversationId, pendingAttachments = emptyList())
            } else state
        }
    }

    fun selectChatModel(modelId: String) {
        updateActiveConversation { it.copy(modelId = modelId) }
    }

    fun setHistoryQuery(query: String) = _uiState.update { it.copy(historyQuery = query) }

    fun toggleWebSearch() = _uiState.update { it.copy(webSearchEnabled = !it.webSearchEnabled) }

    fun addAttachment(kind: AttachmentKind) {
        val attachment = Attachment(nextAttachmentId++, kind, kind.sampleName)
        _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + attachment) }
        emit("${kind.label} attachment added (simulated sample, no file was read).")
    }

    fun removeAttachment(attachmentId: Long) {
        _uiState.update { state ->
            state.copy(pendingAttachments = state.pendingAttachments.filterNot { it.id == attachmentId })
        }
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        val state = _uiState.value
        val conversationId = state.activeConversationId
        if (trimmed.isEmpty() || conversationId in state.pendingReplies) return
        val userMessage = ChatMessage(
            id = nextMessageId++,
            role = ChatRole.USER,
            text = trimmed,
            attachments = state.pendingAttachments.map { it.name }
        )
        val webSearch = state.webSearchEnabled
        _uiState.update {
            it.copy(pendingAttachments = emptyList(), pendingReplies = it.pendingReplies + conversationId)
        }
        updateConversation(conversationId) { conversation ->
            conversation.copy(
                title = if (conversation.title == Conversation.NEW_CHAT_TITLE) trimmed.take(TITLE_LENGTH) else conversation.title,
                messages = conversation.messages + userMessage
            )
        }
        replyJobs[conversationId]?.cancel()
        replyJobs[conversationId] = viewModelScope.launch {
            delay(REPLY_DELAY_MS)
            updateConversation(conversationId) { conversation ->
                val replyCount = conversation.messages.count { it.role == ChatRole.ASSISTANT }
                var replyText = MockChatRepository.replyFor(trimmed, replyCount)
                if (webSearch) replyText += "\n\n${MockChatRepository.WEB_SEARCH_NOTE}"
                conversation.copy(
                    messages = conversation.messages +
                        ChatMessage(nextMessageId++, ChatRole.ASSISTANT, replyText)
                )
            }
            _uiState.update { it.copy(pendingReplies = it.pendingReplies - conversationId) }
        }
    }

    /** Clears the messages of the active conversation. */
    fun clearChat() {
        val conversationId = _uiState.value.activeConversationId
        replyJobs.remove(conversationId)?.cancel()
        _uiState.update { it.copy(pendingReplies = it.pendingReplies - conversationId, pendingAttachments = emptyList()) }
        updateConversation(conversationId) { it.copy(messages = emptyList()) }
    }

    // ---- Skills and scheduled tasks (local toggles only) ----

    fun toggleSkill(skillId: String) = _uiState.update {
        it.copy(enabledSkillIds = if (skillId in it.enabledSkillIds) it.enabledSkillIds - skillId else it.enabledSkillIds + skillId)
    }

    fun toggleTask(taskId: String) = _uiState.update {
        it.copy(enabledTaskIds = if (taskId in it.enabledTaskIds) it.enabledTaskIds - taskId else it.enabledTaskIds + taskId)
    }

    // ---- Settings ----

    fun setDefaultModel(modelId: String) = _uiState.update { it.copy(defaultModelId = modelId) }

    fun setPreferredSize(size: PreferredModelSize) = _uiState.update { it.copy(preferredSize = size) }

    fun setAutoCompatibilityCheck(enabled: Boolean) =
        _uiState.update { it.copy(autoCompatibilityCheck = enabled) }

    /** Shows a transient message, e.g. for an action that is not available in the demo. */
    fun showMessage(message: String) = emit(message)

    // ---- Internals ----

    private fun emit(message: String) {
        _messages.tryEmit(message)
    }

    private fun newEmptyConversation(modelId: String) =
        Conversation("c-${nextConversationId++}", Conversation.NEW_CHAT_TITLE, modelId, emptyList())

    private fun updateConversation(conversationId: String, transform: (Conversation) -> Conversation) {
        _uiState.update { state ->
            state.copy(conversations = state.conversations.map { if (it.id == conversationId) transform(it) else it })
        }
    }

    private fun updateActiveConversation(transform: (Conversation) -> Conversation) =
        updateConversation(_uiState.value.activeConversationId, transform)

    private fun setInstallState(modelId: String, state: InstallState) {
        _uiState.update { it.copy(installStates = it.installStates + (modelId to state)) }
    }

    private fun cancelAllDownloads() {
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
    }

    private fun resumeInitialDownloads() {
        _uiState.value.installStates.forEach { (id, state) ->
            if (state is InstallState.Downloading) startDownloadTicker(id)
        }
    }

    private fun startDownloadTicker(modelId: String) {
        downloadJobs[modelId]?.cancel()
        downloadJobs[modelId] = viewModelScope.launch {
            while (true) {
                delay(DOWNLOAD_TICK_MS)
                val current = _uiState.value.installStateOf(modelId) as? InstallState.Downloading ?: return@launch
                val next = current.progress + DOWNLOAD_STEP
                if (next >= 1f) {
                    setInstallState(modelId, InstallState.Installed)
                    downloadJobs.remove(modelId)
                    return@launch
                }
                setInstallState(modelId, InstallState.Downloading(next))
            }
        }
    }

    private companion object {
        const val REPLY_DELAY_MS = 600L
        const val DOWNLOAD_TICK_MS = 500L
        const val DOWNLOAD_STEP = 0.03f
        const val TITLE_LENGTH = 32
    }
}
