package com.example.localai.ui.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.localai.data.mock.MockChatRepository
import com.example.localai.data.mock.MockModelRepository
import com.example.localai.data.attachment.Attachment
import com.example.localai.data.attachment.ContentResolverDocumentReader
import com.example.localai.data.attachment.DocumentReadResult
import com.example.localai.data.attachment.DocumentReader
import com.example.localai.data.attachment.TextDocuments
import com.example.localai.data.websearch.WebSearchException
import com.example.localai.data.websearch.WebSearchLimits
import com.example.localai.data.websearch.WebSearchProvider
import com.example.localai.data.model.SourceLink
import com.example.localai.data.model.ChatMessage
import com.example.localai.data.model.ChatRole
import com.example.localai.data.model.Conversation
import com.example.localai.data.model.DemoScenario
import com.example.localai.data.model.InstallState
import com.example.localai.data.model.Modality
import com.example.localai.data.model.PreferredModelSize
import com.example.localai.data.model.SortOption
import com.example.localai.data.prefs.ThemePreferenceStore
import com.example.localai.data.CatalogServices
import com.example.localai.data.inference.ActiveModel
import com.example.localai.data.inference.EngineState
import com.example.localai.data.inference.GenerationEvent
import com.example.localai.data.inference.GenerationParams
import com.example.localai.data.inference.InferenceException
import com.example.localai.data.inference.ModelLoadStatus
import com.example.localai.data.inference.ModelSessionManager
import kotlinx.coroutines.flow.combine
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
 * App-wide UI state. Chat replies come from the shared on-device model session; the Library,
 * Skills and Scheduled-tasks screens and the legacy demo download list are still simulated.
 */
class LocalAIViewModel @JvmOverloads constructor(
    application: Application,
    /** Shared on-device model session; null only when none can be created. */
    private val modelSession: ModelSessionManager? = runCatching { CatalogServices.modelSession(application) }.getOrNull(),
    /** Reads documents the user picks; they never leave the device. */
    private val documentReader: DocumentReader = ContentResolverDocumentReader(application),
    /** Optional web search; used only for messages sent while the user has it switched on. */
    private val webSearch: WebSearchProvider? = runCatching { CatalogServices.webSearch }.getOrNull()
) : AndroidViewModel(application) {

    private val themeStore = ThemePreferenceStore(application)

    private val _uiState = MutableStateFlow(
        LocalAIUiState(
            installStates = MockModelRepository.initialInstallStates(DemoScenario.SAMPLE),
            // Sample history stays, explicitly marked as pre-written so it never reads as model output.
            conversations = MockChatRepository.sampleConversations().map { c ->
                c.copy(messages = c.messages.map { m -> if (m.role == ChatRole.ASSISTANT) m.copy(isDemo = true) else m })
            },
            activeConversationId = "c-1",
            enabledSkillIds = setOf("skill-summarize"),
            webSearchProvider = webSearch?.name
        )
    )
    val uiState: StateFlow<LocalAIUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** One-off feedback messages (shown as snackbars) for simulated actions. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val downloadJobs = mutableMapOf<String, Job>()
    private val replyJobs = mutableMapOf<String, Job>()

    /** Conversations whose reply is still waiting for web results (Stop cancels the search). */
    private val searching = mutableSetOf<String>()
    private var nextMessageId = 1_000L
    private var nextConversationId = 5
    private var nextAttachmentId = 1L

    init {
        viewModelScope.launch {
            themeStore.themeMode.collect { mode -> _uiState.update { it.copy(themeMode = mode) } }
        }
        resumeInitialDownloads()
        modelSession?.let { session ->
            viewModelScope.launch {
                combine(session.engineState, session.active, session.loadStatus) { engine, active, status ->
                    modelRuntimeState(engine, active, status)
                }.collect { model -> _uiState.update { it.copy(chatModel = model) } }
            }
        }
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

    /** Clears every conversation (they are kept in memory only); downloaded models are untouched. */
    fun clearConversations() {
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
        emit("Conversations cleared.")
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

    fun setHistoryQuery(query: String) = _uiState.update { it.copy(historyQuery = query) }

    /**
     * Switches web search on or off. The first time it is switched on, the user is asked to
     * confirm what is sent and to whom; nothing is sent before that.
     */
    fun toggleWebSearch() {
        val state = _uiState.value
        when {
            state.webSearchProvider == null -> emit("Web search is not available in this build.")
            state.webSearchEnabled -> _uiState.update { it.copy(webSearchEnabled = false) }
            state.webSearchConsented -> _uiState.update { it.copy(webSearchEnabled = true) }
            else -> _uiState.update { it.copy(webSearchConsentPending = true) }
        }
    }

    fun confirmWebSearch() = _uiState.update {
        it.copy(webSearchConsented = true, webSearchEnabled = true, webSearchConsentPending = false)
    }

    fun dismissWebSearchConsent() = _uiState.update { it.copy(webSearchConsentPending = false) }

    /** Reads a document picked with the system file picker and adds it to the next message. */
    fun attachDocument(uri: String) {
        if (_uiState.value.pendingAttachments.size >= TextDocuments.MAX_PENDING) {
            emit("You can attach up to ${TextDocuments.MAX_PENDING} files to one message.")
            return
        }
        _uiState.update { it.copy(readingAttachment = true) }
        viewModelScope.launch {
            try {
                when (val result = documentReader.read(uri)) {
                    is DocumentReadResult.Read -> _uiState.update {
                        it.copy(pendingAttachments = it.pendingAttachments + Attachment(nextAttachmentId++, result.name, result.sizeBytes, result.text))
                    }
                    is DocumentReadResult.Rejected -> emit(result.reason)
                }
            } finally {
                _uiState.update { it.copy(readingAttachment = false) }
            }
        }
    }

    fun removeAttachment(attachmentId: Long) {
        _uiState.update { state ->
            state.copy(pendingAttachments = state.pendingAttachments.filterNot { it.id == attachmentId })
        }
    }

    /**
     * Sends [text] to the loaded on-device model and streams the reply into a new assistant
     * message. Attached documents are added to this turn as untrusted reference text, within the
     * model's context. With web search on, only a query derived from [text] is sent to the search
     * provider first. Without a loaded model nothing is generated (and no canned text is shown).
     */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        val state = _uiState.value
        val conversationId = state.activeConversationId
        if (trimmed.isEmpty() || conversationId in state.pendingReplies || state.readingAttachment) return
        val session = modelSession
        val model = state.chatModel as? ChatModelState.Ready
        if (session == null || model == null) {
            _uiState.update { it.copy(chatError = "Load a model in My Models before chatting.") }
            return
        }
        val provider = webSearch?.takeIf { state.webSearchEnabled }
        val budget = OnDeviceChat.referenceBudgetChars(model.contextLength)
        val documents = OnDeviceChat.documentContext(
            state.pendingAttachments,
            if (provider != null) (budget - OnDeviceChat.WEB_RESULTS_CHARS).coerceAtLeast(0) else budget
        )
        val userId = nextMessageId++
        val userMessage = ChatMessage(
            id = userId,
            role = ChatRole.USER,
            text = trimmed,
            attachments = state.pendingAttachments.map { it.name },
            promptContext = documents.text,
            note = documents.notes.joinToString("\n").ifEmpty { null }
        )
        val replyId = nextMessageId++
        val query = provider?.let { WebSearchLimits.queryFrom(trimmed) }
        val reply = ChatMessage(
            replyId, ChatRole.ASSISTANT, "", generatedBy = model.fileName,
            note = query?.let { "Searching ${provider.name} for “$it”…" }
        )
        _uiState.update {
            it.copy(pendingAttachments = emptyList(), pendingReplies = it.pendingReplies + conversationId, chatError = null)
        }
        updateConversation(conversationId) { conversation ->
            conversation.copy(
                title = if (conversation.title == Conversation.NEW_CHAT_TITLE) trimmed.take(TITLE_LENGTH) else conversation.title,
                messages = conversation.messages + userMessage + reply
            )
        }
        replyJobs[conversationId]?.cancel()
        replyJobs[conversationId] = viewModelScope.launch {
            try {
                val webNote = if (provider != null && query != null) {
                    searching += conversationId
                    try {
                        searchTheWeb(provider, query, conversationId, userId, replyId, documents.text)
                    } finally {
                        searching -= conversationId
                    }
                } else null
                val history = OnDeviceChat.promptHistory(
                    _uiState.value.conversations.firstOrNull { it.id == conversationId }?.messages.orEmpty(),
                    excludingId = replyId
                )
                OnDeviceChat.generateWithTrimming(history, { session.generate(it, GenerationParams()) }) { event ->
                    when (event) {
                        is GenerationEvent.Token -> updateMessage(conversationId, replyId) { it.copy(text = it.text + event.text) }
                        is GenerationEvent.Finished -> updateMessage(conversationId, replyId) {
                            it.copy(note = listOfNotNull(webNote, OnDeviceChat.finishedNote(event)).joinToString(" · "))
                        }
                    }
                }
            } catch (e: InferenceException) {
                _uiState.update { it.copy(chatError = e.message ?: e.kind.description) }
                updateMessage(conversationId, replyId) { it.copy(note = "Generation failed") }
            } finally {
                // An empty reply (failure or immediate stop) is removed instead of left blank.
                updateConversation(conversationId) { c ->
                    c.copy(messages = c.messages.filterNot { it.id == replyId && it.text.isBlank() })
                }
                _uiState.update { it.copy(pendingReplies = it.pendingReplies - conversationId) }
            }
        }
    }

    /**
     * Runs the search and attaches the results to the user's turn (as untrusted text) and to the
     * reply (as source links). A failed search is reported and the model answers without results.
     */
    private suspend fun searchTheWeb(
        provider: WebSearchProvider,
        query: String,
        conversationId: String,
        userId: Long,
        replyId: Long,
        documents: String?
    ): String {
        val results = try {
            provider.search(query)
        } catch (e: WebSearchException) {
            updateMessage(conversationId, replyId) { it.copy(note = null) }
            return "Web search failed (${e.message}); answered without web results"
        }
        updateMessage(conversationId, replyId) {
            it.copy(note = null, sources = results.map { r -> SourceLink(r.title, r.url) })
        }
        if (results.isEmpty()) return "No ${provider.name} results for “$query”"
        val block = OnDeviceChat.webResultsBlock(query, provider.name, results)
        updateMessage(conversationId, userId) { it.copy(promptContext = listOfNotNull(documents, block).joinToString("\n\n")) }
        return "Searched ${provider.name} for “$query”"
    }

    /** Stops the reply being generated; the text produced so far is kept. */
    fun stopGeneration() {
        val conversationId = _uiState.value.activeConversationId
        if (conversationId in searching) replyJobs[conversationId]?.cancel() else modelSession?.stop()
    }

    fun dismissChatError() = _uiState.update { it.copy(chatError = null) }

    private fun updateMessage(conversationId: String, messageId: Long, change: (ChatMessage) -> ChatMessage) =
        updateConversation(conversationId) { c -> c.copy(messages = c.messages.map { if (it.id == messageId) change(it) else it }) }

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
        const val DOWNLOAD_TICK_MS = 500L
        const val DOWNLOAD_STEP = 0.03f
        const val TITLE_LENGTH = 32
    }
}
