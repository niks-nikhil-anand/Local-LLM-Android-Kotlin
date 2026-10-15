package com.example.localai.ui.screens.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.AssistChip
import com.example.localai.data.attachment.TextDocuments
import com.example.localai.data.model.ChatMessage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.fillMaxWidth
import com.example.localai.ui.components.AssistantTypingIndicator
import androidx.compose.ui.platform.testTag
import com.example.localai.ui.state.LoadPhase
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.localai.ui.components.AppTopBar
import com.example.localai.ui.components.ChatComposer
import com.example.localai.ui.components.ChatMessageBubble
import com.example.localai.ui.components.ConfirmationDialog
import com.example.localai.ui.components.EmptyState
import com.example.localai.ui.components.InfoBanner
import com.example.localai.ui.state.ChatActions
import com.example.localai.ui.state.LocalAIUiState
import com.example.localai.ui.state.ChatModelState
import com.example.localai.ui.components.BannerTone
import androidx.compose.material3.TextButton
import com.example.localai.ui.util.copyToClipboard
import kotlinx.coroutines.launch

/**
 * Primary chat destination. Replies come from the on-device model loaded in My Models (llama.cpp);
 * without one, nothing is generated. Sample conversations keep their pre-written replies, labelled
 * as such. Text files are read on-device; voice input uses the platform speech recognizer and read
 * aloud uses offline text-to-speech voices; web search is opt-in and sends only a query.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    state: LocalAIUiState,
    actions: ChatActions,
    modifier: Modifier = Modifier
) {
    val conversation = state.activeConversation
    val chatModel = state.chatModel
    val modelReady = chatModel is ChatModelState.Ready

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val listState = rememberLazyListState()

    var draft by rememberSaveable { mutableStateOf("") }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var showAttachments by rememberSaveable { mutableStateOf(false) }

    // ---- Attachments: the system picker grants access to the one chosen document ----
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { actions.onAttachDocument(it.toString()) }
    }

    // ---- Voice input ----
    val speechInput = remember { SpeechInput(context) }
    var showVoice by remember { mutableStateOf(false) }
    var voiceAvailability by remember { mutableStateOf<VoiceAvailability?>(null) }
    var micDenied by remember { mutableStateOf(false) }
    /** The user agreed to the default (possibly online) speech service during this session. */
    var systemVoiceAccepted by rememberSaveable { mutableStateOf(false) }
    // Read when called (not at composition), so callbacks see the latest availability.
    fun usesSystemService() = voiceAvailability is VoiceAvailability.SystemService

    fun startListening() {
        speechInput.start(onDevice = !usesSystemService()) { transcript ->
            draft = mergeTranscript(draft, transcript)
            showVoice = false
        }
    }

    fun closeVoice() {
        speechInput.cancel()
        showVoice = false
    }

    val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micDenied = !granted
        showVoice = true
        if (granted && (!usesSystemService() || systemVoiceAccepted)) startListening()
    }

    fun openVoice() {
        val availability = voiceAvailability(context)
        voiceAvailability = availability
        micDenied = false
        when {
            availability is VoiceAvailability.Unavailable -> showVoice = true
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                requestMic.launch(Manifest.permission.RECORD_AUDIO)
            else -> {
                showVoice = true
                if (!usesSystemService() || systemVoiceAccepted) startListening()
            }
        }
    }

    // ---- Read aloud ----
    val readAloud = remember { ReadAloud(context) }
    val speak: (ChatMessage) -> Unit = { message ->
        when (val status = readAloud.status) {
            ReadAloudStatus.Ready ->
                if (readAloud.speakingId == message.id) readAloud.stop() else readAloud.speak(message.id, message.text)
            ReadAloudStatus.Initializing -> actions.onShowMessage("Read aloud is starting. Try again in a moment.")
            is ReadAloudStatus.Unavailable -> actions.onShowMessage("Read aloud unavailable: ${status.reason}")
        }
    }

    DisposableEffect(speechInput, readAloud) {
        onDispose {
            speechInput.cancel()
            readAloud.shutdown()
        }
    }
    // Leaving the app stops listening and speaking.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (showVoice) closeVoice()
        readAloud.stop()
    }

    val history = conversation?.messages.orEmpty()
    val isReplying = conversation?.id in state.pendingReplies

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    // Keep the newest message (or the typing indicator) in view.
    val lastIndex = history.size + (if (isReplying) 1 else 0)
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(conversation?.id, history.size, isReplying, imeVisible) {
        if (history.isNotEmpty() || isReplying) listState.animateScrollToItem(lastIndex)
    }

    fun closeDrawerThen(block: () -> Unit) {
        scope.launch { drawerState.close() }
        block()
    }

    val copy: (String) -> Unit = { text ->
        copyToClipboard(context, "LocalAI message", text)
        actions.onShowMessage("Copied to clipboard")
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            ChatDrawer(
                conversations = state.filteredConversations,
                activeConversationId = state.activeConversationId,
                historyQuery = state.historyQuery,
                onHistoryQueryChange = actions.onHistoryQueryChange,
                onNewChat = { closeDrawerThen(actions.onNewChat) },
                onSelectConversation = { id -> closeDrawerThen { actions.onSelectConversation(id) } },
                onOpenLibrary = { closeDrawerThen(actions.onOpenLibrary) },
                onOpenSkills = { closeDrawerThen(actions.onOpenSkills) },
                onOpenScheduledTasks = { closeDrawerThen(actions.onOpenScheduledTasks) },
                onOpenSettings = { closeDrawerThen(actions.onOpenSettings) }
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(
                title = {
                    Column(
                        modifier = Modifier
                            .clickable(onClickLabel = "Load or switch model") { actions.onOpenMyModels() }
                            .padding(vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when (chatModel) {
                                    is ChatModelState.Ready -> chatModel.fileName
                                    is ChatModelState.Loading -> chatModel.fileName
                                    else -> "No model loaded"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .size(14.dp)
                            )
                            Text(
                                text = when (chatModel) {
                                    is ChatModelState.Ready -> "On-device · llama.cpp · ${chatModel.contextLength} ctx"
                                    is ChatModelState.Loading -> chatModel.phase.label()
                                    is ChatModelState.LoadFailed -> "Load failed"
                                    is ChatModelState.Unavailable -> "Engine unavailable on this device"
                                    ChatModelState.NoModel -> "Tap to load a model"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Open conversations and tools")
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("New chat") },
                            leadingIcon = { Icon(Icons.Outlined.AddComment, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                actions.onNewChat()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Manage models") },
                            leadingIcon = { Icon(Icons.Outlined.Memory, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                actions.onOpenMyModels()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear conversation") },
                            leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                            enabled = history.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                confirmClear = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                actions.onOpenSettings()
                            }
                        )
                    }
                }
            )

            if (chatModel is ChatModelState.Loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("chat_model_preparing"))
            }

            state.chatError?.let { error ->
                Column(Modifier.padding(horizontal = 16.dp)) {
                    InfoBanner(error, tone = BannerTone.ERROR)
                    TextButton(onClick = actions.onDismissError) { Text("Dismiss") }
                }
            }

            if (history.isEmpty() && !isReplying) {
                Column(modifier = Modifier.weight(1f)) {
                    when (chatModel) {
                        is ChatModelState.Ready -> EmptyState(
                            icon = Icons.Outlined.ChatBubbleOutline,
                            title = "Start a conversation",
                            message = "Replies are generated on this device by ${chatModel.fileName}. " +
                                (if (state.webSearchEnabled) "Web search is on: a query from each message is sent to ${state.webSearchProvider}. "
                                else "No internet is used. ") +
                                "Small models make mistakes.",
                            modifier = Modifier.padding(top = 32.dp)
                        )
                        is ChatModelState.Loading -> EmptyState(
                            icon = Icons.Outlined.ChatBubbleOutline,
                            title = "Preparing ${chatModel.fileName}",
                            message = chatModel.phase.label() + " You can type your message meanwhile.",
                            modifier = Modifier.padding(top = 32.dp)
                        )
                        is ChatModelState.Unavailable -> EmptyState(
                            icon = Icons.Outlined.ChatBubbleOutline,
                            title = "On-device engine unavailable",
                            message = "This device's processor type is not supported by the bundled engine.",
                            modifier = Modifier.padding(top = 32.dp)
                        )
                        else -> EmptyState(
                            icon = Icons.Outlined.ChatBubbleOutline,
                            title = "No model loaded",
                            message = (if (chatModel is ChatModelState.LoadFailed) "Loading ${chatModel.fileName} failed. " else "") +
                                "Download a model in Explore, then load it in My Models to chat on-device.",
                            actionLabel = "Open My Models",
                            onAction = actions.onOpenMyModels,
                            modifier = Modifier.padding(top = 32.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "banner") {
                        if (history.any { it.isDemo }) {
                            InfoBanner("Demo conversation: these replies were pre-written, not generated by a model.")
                        } else if (!modelReady) {
                            InfoBanner("No model is loaded. Load one in My Models to continue this conversation.")
                        }
                    }
                    items(history, key = { it.id }) { message ->
                        val generating = isReplying && message.id == history.lastOrNull()?.id
                        if (generating && message.role == com.example.localai.data.model.ChatRole.ASSISTANT && message.text.isEmpty()) {
                            // Nothing generated yet: a typing indicator, never an empty or placeholder bubble.
                            AssistantTypingIndicator(status = message.note ?: "Reading the conversation…")
                            return@items
                        }
                        ChatMessageBubble(
                            message = message,
                            onCopy = copy,
                            onReadAloud = if (message.role == com.example.localai.data.model.ChatRole.ASSISTANT && !generating &&
                                message.text.isNotBlank()
                            ) {
                                { speak(message) }
                            } else null,
                            readingAloud = readAloud.speakingId == message.id
                        )
                    }
                }
            }

            ChatComposer(
                value = draft,
                onValueChange = { draft = it },
                onSend = {
                    actions.onSend(draft)
                    draft = ""
                },
                busy = isReplying || !modelReady || state.readingAttachment,
                onStop = if (isReplying) actions.onStop else null,
                placeholder = when (chatModel) {
                    is ChatModelState.Ready -> "Message (on-device)"
                    is ChatModelState.Loading -> "Type while the model loads"
                    else -> "Load a model to chat"
                },
                onAttach = { showAttachments = true },
                onVoice = { openVoice() },
                topContent = {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (state.webSearchProvider != null) {
                            FilterChip(
                                selected = state.webSearchEnabled,
                                onClick = actions.onToggleWebSearch,
                                label = { Text(if (state.webSearchEnabled) "Web search on · ${state.webSearchProvider}" else "Web search") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            )
                        }
                        if (state.readingAttachment) {
                            AssistChip(onClick = {}, enabled = false, label = { Text("Reading file…") })
                        }
                        state.pendingAttachments.forEach { attachment ->
                            InputChip(
                                selected = false,
                                onClick = { actions.onRemoveAttachment(attachment.id) },
                                label = { Text("${TextDocuments.displayName(attachment.name)} · ${TextDocuments.formatBytes(attachment.sizeBytes)}", maxLines = 1) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                                trailingIcon = {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove ${attachment.name}",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        }
                    }
                },
                modifier = Modifier.imePadding()
            )
        }
    }

    if (showAttachments) {
        AttachmentSheet(
            onPickTextFile = {
                showAttachments = false
                pickDocument.launch(TextDocuments.PICKER_MIME_TYPES)
            },
            onDismiss = { showAttachments = false },
            attachedCount = state.pendingAttachments.size
        )
    }
    if (showVoice) {
        val availability = voiceAvailability
        VoiceInputSheet(
            mode = when {
                availability is VoiceAvailability.Unavailable -> VoiceSheetMode.Unavailable(availability.reason)
                micDenied -> VoiceSheetMode.PermissionDenied
                availability is VoiceAvailability.SystemService && !systemVoiceAccepted -> VoiceSheetMode.Disclosure(availability.label)
                else -> VoiceSheetMode.Active(speechInput.state)
            },
            onStart = { startListening() },
            onDone = { speechInput.finish() },
            onAcceptDisclosure = {
                systemVoiceAccepted = true
                startListening()
            },
            onUseSystemService = {
                val fallback = systemVoiceFallback(context)
                if (fallback == null) {
                    actions.onShowMessage("No other speech recognition service is installed.")
                } else {
                    speechInput.cancel()
                    voiceAvailability = fallback
                    if (systemVoiceAccepted) startListening()
                }
            },
            onOpenAppSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                closeVoice()
            },
            onDismiss = { closeVoice() }
        )
    }
    state.webSearchProvider?.takeIf { state.webSearchConsentPending }?.let { provider ->
        WebSearchConsentDialog(provider, onConfirm = actions.onConfirmWebSearch, onDismiss = actions.onDismissWebSearchConsent)
    }
    if (confirmClear) {
        ConfirmationDialog(
            title = "Clear conversation?",
            message = "All messages in this conversation will be removed.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = {
                actions.onClear()
                confirmClear = false
            },
            onDismiss = { confirmClear = false }
        )
    }
}

private fun LoadPhase.label(): String = when (this) {
    LoadPhase.CHECKING -> "Checking the file and memory…"
    LoadPhase.LOADING -> "Loading into memory…"
}
