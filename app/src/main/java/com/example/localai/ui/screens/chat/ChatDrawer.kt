package com.example.localai.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.localai.data.model.Conversation
import com.example.localai.ui.components.LocalAILogo
import com.example.localai.ui.components.SearchBar

/**
 * Secondary navigation for the chat tab: conversation history with search, plus links to
 * Library, Skills, Scheduled Tasks and Settings. These are not bottom-navigation items.
 */
@Composable
fun ChatDrawer(
    conversations: List<Conversation>,
    activeConversationId: String,
    historyQuery: String,
    onHistoryQueryChange: (String) -> Unit,
    onNewChat: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenScheduledTasks: () -> Unit,
    onOpenSettings: () -> Unit
) {
    ModalDrawerSheet {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LocalAILogo(size = 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("LocalAI", style = MaterialTheme.typography.titleLarge)
                }
            }
            item {
                Button(
                    onClick = onNewChat,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("New chat", modifier = Modifier.padding(start = 8.dp))
                }
            }
            item {
                SearchBar(
                    query = historyQuery,
                    onQueryChange = onHistoryQueryChange,
                    placeholder = "Search chat history"
                )
            }
            item {
                Text(
                    text = "History",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp)
                )
            }
            if (conversations.isEmpty()) {
                item {
                    Text(
                        text = "No conversations match your search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            } else {
                items(conversations, key = { it.id }) { conversation ->
                    NavigationDrawerItem(
                        selected = conversation.id == activeConversationId,
                        onClick = { onSelectConversation(conversation.id) },
                        label = {
                            Column {
                                Text(conversation.title, maxLines = 1)
                                Text(
                                    text = conversationSubtitle(conversation),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    )
                }
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { DrawerLink("Library", Icons.Outlined.CollectionsBookmark, onOpenLibrary) }
            item { DrawerLink("Skills", Icons.Outlined.AutoAwesome, onOpenSkills) }
            item { DrawerLink("Scheduled Tasks", Icons.Outlined.CalendarMonth, onOpenScheduledTasks) }
            item { DrawerLink("Settings", Icons.Outlined.Settings, onOpenSettings) }
        }
    }
}

@Composable
private fun DrawerLink(label: String, icon: ImageVector, onClick: () -> Unit) {
    NavigationDrawerItem(
        selected = false,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) }
    )
}

/** What produced a conversation's replies, from its messages (not from a model list). */
internal fun conversationSubtitle(conversation: Conversation): String {
    val replies = conversation.messages.filter { it.role == com.example.localai.data.model.ChatRole.ASSISTANT }
    return when {
        replies.isEmpty() -> "No replies yet"
        replies.any { it.isDemo } -> "Sample conversation (pre-written)"
        else -> replies.last().generatedBy?.let { "$it (on-device)" } ?: "On-device"
    }
}
