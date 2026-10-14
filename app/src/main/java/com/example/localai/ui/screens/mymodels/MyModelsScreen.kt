package com.example.localai.ui.screens.mymodels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.localai.data.model.DemoScenario
import com.example.localai.data.model.InstallState
import com.example.localai.data.model.ModelInfo
import com.example.localai.data.model.formatSizeMb
import com.example.localai.ui.components.AppTopBar
import com.example.localai.ui.components.ConfirmationDialog
import com.example.localai.ui.components.EmptyState
import com.example.localai.ui.components.InfoBanner
import com.example.localai.ui.components.InstallStatusBadge
import com.example.localai.ui.components.ModalityIconBadge
import com.example.localai.ui.components.SectionHeader
import com.example.localai.ui.components.StorageOverviewCard
import com.example.localai.ui.components.SurfaceCard
import com.example.localai.ui.state.LocalAIUiState
import com.example.localai.ui.state.ManagedModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyModelsScreen(
    state: LocalAIUiState,
    onModelClick: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRemove: (String) -> Unit,
    onSelectScenario: (DemoScenario) -> Unit,
    onExplore: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingRemoval by remember { mutableStateOf<ModelInfo?>(null) }
    val managed = state.managedModels

    Column(modifier = modifier.fillMaxSize()) {
        AppTopBar(title = "My Models")
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                InfoBanner(
                    text = "Everything here is simulated. No files are downloaded, stored or removed."
                )
            }
            item {
                StorageOverviewCard(
                    modelsUsedMb = state.modelsUsedMb,
                    otherUsedMb = state.device.otherUsedStorageMb,
                    totalMb = state.device.totalStorageMb,
                    nearlyFull = state.isStorageNearlyFull
                )
            }
            item {
                Column {
                    Text(
                        text = "Demo state",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DemoScenario.entries.forEach { scenario ->
                            FilterChip(
                                selected = state.scenario == scenario,
                                onClick = { onSelectScenario(scenario) },
                                label = { Text(scenario.label) }
                            )
                        }
                    }
                }
            }
            item { SectionHeader("Your models") }
            if (managed.isEmpty()) {
                item {
                    SurfaceCard {
                        EmptyState(
                            icon = Icons.Outlined.Inventory2,
                            title = "No models installed",
                            message = "Models you add will be listed here with their simulated status.",
                            actionLabel = "Explore Models",
                            onAction = onExplore
                        )
                    }
                }
            } else {
                items(managed, key = { it.model.id }) { item ->
                    ManagedModelCard(
                        item = item,
                        onClick = { onModelClick(item.model.id) },
                        onOpenChat = { onOpenChat(item.model.id) },
                        onPause = { onPause(item.model.id) },
                        onResume = { onResume(item.model.id) },
                        onRemove = { pendingRemoval = item.model },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }

    pendingRemoval?.let { model ->
        ConfirmationDialog(
            title = "Remove ${model.name}?",
            message = "This removes the entry from the demo list. No real files are affected.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = {
                onRemove(model.id)
                pendingRemoval = null
            },
            onDismiss = { pendingRemoval = null }
        )
    }
}

@Composable
private fun ManagedModelCard(
    item: ManagedModel,
    onClick: () -> Unit,
    onOpenChat: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val model = item.model
    val progress = when (val s = item.state) {
        is InstallState.Downloading -> s.progress
        is InstallState.Paused -> s.progress
        is InstallState.Failed -> s.progress
        else -> null
    }
    SurfaceCard(onClick = onClick, modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModalityIconBadge(model.modality)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${model.modality.label} · ${formatSizeMb(model.sizeMb)} · ${model.runtime}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            InstallStatusBadge(item.state)
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (item.state is InstallState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (item.state) {
                    InstallState.Installed -> if (model.supportsChat) {
                        Button(onClick = onOpenChat, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open") }
                    } else {
                        OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("View Capabilities")
                        }
                    }
                    is InstallState.Downloading -> OutlinedButton(
                        onClick = onPause,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Pause", modifier = Modifier.padding(start = 8.dp))
                    }
                    is InstallState.Paused -> Button(onClick = onResume, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Resume", modifier = Modifier.padding(start = 8.dp))
                    }
                    is InstallState.Failed -> Button(onClick = onResume, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Retry", modifier = Modifier.padding(start = 8.dp))
                    }
                    InstallState.NotInstalled -> Unit
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Remove ${model.name}",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
