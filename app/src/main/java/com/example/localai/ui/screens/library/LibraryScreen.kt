package com.example.localai.ui.screens.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.localai.data.mock.MockWorkspaceRepository
import com.example.localai.ui.components.SecondaryScreen
import com.example.localai.ui.components.SurfaceCard

@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    SecondaryScreen(
        title = "Library",
        onBack = onBack,
        banner = "Simulated: these are sample items. Nothing is saved or synced.",
        modifier = modifier
    ) {
        items(MockWorkspaceRepository.libraryItems, key = { it.id }) { item ->
            SurfaceCard(onClick = { onShowMessage("Opening items is not available in this demo.") }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (item.kind) {
                            "Prompt" -> Icons.Outlined.ChatBubbleOutline
                            "Note" -> Icons.AutoMirrored.Outlined.StickyNote2
                            else -> Icons.AutoMirrored.Outlined.Article
                        },
                        contentDescription = item.kind,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = item.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
