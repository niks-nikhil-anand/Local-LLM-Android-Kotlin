package com.example.localai.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.localai.data.mock.MockModelRepository
import com.example.localai.ui.components.AppTopBar
import com.example.localai.ui.components.DeviceOverviewCard
import com.example.localai.ui.components.EmptyState
import com.example.localai.ui.components.InfoBanner
import com.example.localai.ui.components.LocalAILogo
import com.example.localai.ui.components.ModelCard
import com.example.localai.ui.components.ModelMetadataChip
import com.example.localai.ui.components.SectionHeader
import com.example.localai.ui.components.SurfaceCard
import com.example.localai.ui.state.LocalAIUiState

private val featuredModelIds = listOf(
    "qwen-small-text", "gemma-compact-text", "whisper-tiny", "image-gen-placeholder"
)

@Composable
fun HomeScreen(
    state: LocalAIUiState,
    onExplore: () -> Unit,
    onMyModels: () -> Unit,
    onSettings: () -> Unit,
    onStartDemoChat: () -> Unit,
    onModelClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val featured = featuredModelIds.mapNotNull(MockModelRepository::findModel)

    Column(modifier = modifier.fillMaxSize()) {
        AppTopBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LocalAILogo(size = 30.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("LocalAI", style = MaterialTheme.typography.titleLarge)
                }
            },
            actions = {
                ModelMetadataChip(
                    text = "On-device",
                    icon = Icons.Outlined.PhoneAndroid,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
                IconButton(onClick = onSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Open settings")
                }
            }
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Your AI workspace",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Discover models and explore on-device AI.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Your AI. Your Device. Your Control.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            item {
                InfoBanner(
                    text = "Demo build: models and device figures are sample data. " +
                        "No real model is installed or running."
                )
            }
            item {
                DeviceOverviewCard(
                    device = state.device,
                    freeStorageMb = state.freeStorageMb,
                    installedCount = state.installedCount,
                    runtimeStatus = state.runtimeStatus
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Quick actions")
                    QuickActionRow(
                        icon = Icons.Outlined.Explore,
                        title = "Explore Models",
                        subtitle = "Browse the demo catalogue",
                        onClick = onExplore
                    )
                    QuickActionRow(
                        icon = Icons.Outlined.Inventory2,
                        title = "Open My Models",
                        subtitle = "Manage simulated installs",
                        onClick = onMyModels
                    )
                    QuickActionRow(
                        icon = Icons.Outlined.ChatBubbleOutline,
                        title = "Start a Demo Chat",
                        subtitle = "Pre-written replies, no model needed",
                        onClick = onStartDemoChat
                    )
                }
            }
            if (state.managedModels.isEmpty()) {
                item {
                    SurfaceCard {
                        EmptyState(
                            icon = Icons.Outlined.Inventory2,
                            title = "No models installed",
                            message = "Explore the catalogue to see what on-device AI could look like. " +
                                "Entries are demonstrations only.",
                            actionLabel = "Explore Models",
                            onAction = onExplore
                        )
                    }
                }
            }
            item {
                Column {
                    SectionHeader(
                        title = "Featured models",
                        actionLabel = "See all",
                        onAction = onExplore
                    )
                    Text(
                        text = "Demonstration entries, not verified installable models.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(featured, key = { it.id }) { model ->
                        ModelCard(
                            model = model,
                            onClick = { onModelClick(model.id) },
                            modifier = Modifier.width(300.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    SurfaceCard(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
