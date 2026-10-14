package com.example.localai.ui.screens.chat

import androidx.activity.compose.BackHandler
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
import com.example.localai.data.mock.MockChatRepository
import com.example.localai.data.mock.MockModelRepository
import com.example.localai.ui.components.AppTopBar
import com.example.localai.ui.components.ChatComposer
import com.example.localai.ui.components.ChatMessageBubble
import com.example.localai.ui.components.ConfirmationDialog
import com.example.localai.ui.components.EmptyState
import com.example.localai.ui.components.InfoBanner
import com.example.localai.ui.state.ChatActions
import com.example.localai.ui.state.LocalAIUiState
import com.example.localai.ui.util.copyToClipboard
import kotlinx.coroutines.launch

/**
 * Primary chat destination. Everything here is simulated: replies are pre-written, and model,
 * attachment, voice and web-search controls are UI only.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    state: LocalAIUiState,
    actions: ChatActions,
    modifier: Modifier = Modifier
) {
    val conversation = state.activeConversation
    val model = state.activeModel

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val listState = rememberLazyListState()

    var draft by rememberSaveable { mutableStateOf("") }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var showAttachments by rememberSaveable { mutableStateOf(false) }
    var showVoice by rememberSaveable { mutableStateOf(false) }

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
                            .clickable(onClickLabel = "Choose model") { showModelPicker = true }
                            .padding(vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = model?.name ?: "Choose a model",
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
                                text = "Demo runtime",
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
                            text = { Text("Model details") },
                            leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                            enabled = model != null,
                            onClick = {
                                menuOpen = false
                                model?.let { actions.onOpenDetails(it.id) }
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

            if (history.isEmpty() && !isReplying) {
                Column(modifier = Modifier.weight(1f)) {
                    EmptyState(
                        icon = Icons.Outlined.ChatBubbleOutline,
                        title = "Start a demo conversation",
                        message = "Send a message to see a pre-written reply. No model is loaded or running.",
                        modifier = Modifier.padding(top = 32.dp)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "banner") {
                        InfoBanner("Demo chat: replies are pre-written. No model is loaded or running.")
                    }
                    items(history, key = { it.id }) { message ->
                        ChatMessageBubble(message = message, onCopy = copy)
                    }
                    if (isReplying) {
                        item(key = "typing") {
                            Text(
                                text = "Preparing demo response…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
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
                busy = isReplying,
                onAttach = { showAttachments = true },
                onVoice = { showVoice = true },
                topContent = {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = state.webSearchEnabled,
                            onClick = actions.onToggleWebSearch,
                            label = { Text("Web Search (simulated)") },
                            leadingIcon = {
                                Icon(Icons.Outlined.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                        state.pendingAttachments.forEach { attachment ->
                            InputChip(
                                selected = false,
                                onClick = { actions.onRemoveAttachment(attachment.id) },
                                label = { Text(attachment.name) },
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

    if (showModelPicker) {
        ModelPickerSheet(
            models = MockModelRepository.chatModels,
            selectedId = model?.id.orEmpty(),
            installStateOf = state::installStateOf,
            onSelect = { id ->
                actions.onSelectModel(id)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false }
        )
    }
    if (showAttachments) {
        AttachmentSheet(
            onPick = { kind ->
                actions.onAddAttachment(kind)
                showAttachments = false
            },
            onDismiss = { showAttachments = false }
        )
    }
    if (showVoice) {
        VoiceInputSheet(
            onUseSample = {
                draft = MockChatRepository.SAMPLE_TRANSCRIPT
                showVoice = false
            },
            onDismiss = { showVoice = false }
        )
    }
    if (confirmClear) {
        ConfirmationDialog(
            title = "Clear conversation?",
            message = "All messages in this demo chat will be removed.",
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
