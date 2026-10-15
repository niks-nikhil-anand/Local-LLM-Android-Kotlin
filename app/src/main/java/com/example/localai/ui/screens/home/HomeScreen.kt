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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.localai.data.catalog.formatBytes
import com.example.localai.data.download.InventoryModel
import com.example.localai.data.model.Modality
import com.example.localai.ui.components.AppTopBar
import com.example.localai.ui.components.BannerTone
import com.example.localai.ui.components.DeviceOverviewCard
import com.example.localai.ui.components.EmptyState
import com.example.localai.ui.components.InfoBanner
import com.example.localai.ui.components.LocalAILogo
import com.example.localai.ui.components.ModalityIconBadge
import com.example.localai.ui.components.ModelMetadataChip
import com.example.localai.ui.components.SectionHeader
import com.example.localai.ui.components.SurfaceCard
import com.example.localai.ui.state.ChatModelState
import com.example.localai.ui.theme.AppTheme

/** Display text for Home, derived only from [HomeUiState]. Kept pure so it can be unit-tested. */
object HomeText {
    const val LOADING = "Loading…"
    const val UNAVAILABLE = "Unavailable"

    /** Null while [text] is still [LOADING]: the screen shows a placeholder instead. */
    fun shown(text: String): String? = text.takeUnless { it == LOADING }

    fun memory(state: HomeUiState): String {
        val d = state.device ?: return if (state.deviceMeasured) UNAVAILABLE else LOADING
        val free = d.availableRamBytes
        val total = d.totalRamBytes
        return when {
            free != null && total != null -> "${formatBytes(free)} free of ${formatBytes(total)}"
            total != null -> "${formatBytes(total)} total"
            else -> UNAVAILABLE
        }
    }

    /** Model storage is the measured size of usable model files; free space is measured separately. */
    fun storage(state: HomeUiState): String {
        val inv = state.inventory ?: return if (state.error != null) UNAVAILABLE else LOADING
        val models = "Models ${formatBytes(inv.modelBytes)}"
        val free = state.device?.availableStorageBytes ?: return models
        return "$models · ${formatBytes(free)} free"
    }

    fun models(state: HomeUiState): String {
        val inv = state.inventory ?: return if (state.error != null) UNAVAILABLE else LOADING
        val parts = mutableListOf(if (inv.modelCount == 0) "None" else inv.modelCount.toString())
        if (inv.activeDownloads > 0) parts += "${inv.activeDownloads} downloading"
        if (inv.damagedIds.isNotEmpty()) parts += "${inv.damagedIds.size} need attention"
        return parts.joinToString(" · ")
    }

    fun runtime(runtime: ChatModelState): String = when (runtime) {
        is ChatModelState.Ready -> "Loaded: ${runtime.fileName}"
        is ChatModelState.Loading -> "Loading ${runtime.fileName}…"
        is ChatModelState.LoadFailed -> "No model loaded (last load failed)"
        is ChatModelState.Unavailable -> "Engine unavailable"
        ChatModelState.NoModel -> "No model loaded"
    }

    fun badge(state: HomeUiState): String = when {
        state.device != null -> "Measured"
        state.deviceMeasured -> "Not measured"
        else -> LOADING
    }

    fun chatSubtitle(runtime: ChatModelState): String = when (runtime) {
        is ChatModelState.Ready -> "Chat with ${runtime.fileName}, on-device"
        is ChatModelState.Loading -> "A model is loading"
        else -> "Load a downloaded model first"
    }
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onExplore: () -> Unit,
    onMyModels: () -> Unit,
    onSettings: () -> Unit,
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val inventory = state.inventory

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
            modifier = Modifier.weight(1f).testTag("home"),
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
                val error = state.error
                if (error != null) {
                    InfoBanner(text = error, tone = BannerTone.ERROR)
                } else {
                    InfoBanner(
                        text = "Models are downloaded from Hugging Face and run on this device with llama.cpp. " +
                            "The figures below are measured on this device."
                    )
                }
            }
            item {
                DeviceOverviewCard(
                    memory = HomeText.shown(HomeText.memory(state)),
                    storage = HomeText.shown(HomeText.storage(state)),
                    models = HomeText.shown(HomeText.models(state)),
                    runtime = HomeText.runtime(state.runtime),
                    badge = HomeText.shown(HomeText.badge(state)),
                    modifier = Modifier.testTag("home_overview")
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Quick actions")
                    QuickActionRow(
                        icon = Icons.Outlined.Explore,
                        title = "Explore Models",
                        subtitle = "Browse GGUF models on Hugging Face",
                        onClick = onExplore
                    )
                    QuickActionRow(
                        icon = Icons.Outlined.Inventory2,
                        title = "Open My Models",
                        subtitle = "Load, unload and manage downloaded models",
                        onClick = onMyModels
                    )
                    QuickActionRow(
                        icon = Icons.Outlined.ChatBubbleOutline,
                        title = "Open Chat",
                        subtitle = HomeText.chatSubtitle(state.runtime),
                        onClick = onOpenChat
                    )
                }
            }
            if (inventory != null && inventory.modelCount == 0) {
                item {
                    SurfaceCard {
                        EmptyState(
                            icon = Icons.Outlined.Inventory2,
                            title = "No models downloaded",
                            message = "Find a GGUF model in Explore and download it to run it on this device.",
                            actionLabel = "Explore Models",
                            onAction = onExplore
                        )
                    }
                }
            }
            if (inventory != null && inventory.modelCount > 0) {
                item {
                    SectionHeader(title = "Your models", actionLabel = "See all", onAction = onMyModels)
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(inventory.models, key = { it.id }) { model ->
                            DownloadedModelCard(
                                model = model,
                                loaded = model.id == state.loadedRecordId,
                                onClick = onMyModels,
                                modifier = Modifier.width(300.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A downloaded model, styled like the catalogue cards. "Loaded" comes from the inference session. */
@Composable
private fun DownloadedModelCard(model: InventoryModel, loaded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier.testTag("home_model:${model.id}"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModalityIconBadge(Modality.TEXT)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = model.repoId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModelMetadataChip(formatBytes(model.sizeBytes))
                if (loaded) {
                    ModelMetadataChip(
                        text = "Loaded",
                        containerColor = AppTheme.extendedColors.successContainer,
                        contentColor = AppTheme.extendedColors.onSuccessContainer
                    )
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
