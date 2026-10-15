package com.example.localai.ui.screens.mymodels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.localai.data.catalog.DownloadStatus
import com.example.localai.data.catalog.RuntimeCompatibility
import com.example.localai.data.catalog.formatBytes
import com.example.localai.data.compat.Severity
import com.example.localai.data.download.Verification
import com.example.localai.data.inference.ModelLoadStatus
import com.example.localai.data.model.Modality
import com.example.localai.ui.components.AppTopBar
import com.example.localai.ui.components.BannerTone
import com.example.localai.ui.components.ConfirmationDialog
import com.example.localai.ui.components.EmptyState
import com.example.localai.ui.components.InfoBanner
import com.example.localai.ui.components.ModalityIconBadge
import com.example.localai.ui.components.ModelMetadataChip
import com.example.localai.ui.components.SectionHeader
import com.example.localai.ui.components.SkeletonCardList
import com.example.localai.ui.components.SkeletonGroup
import com.example.localai.ui.components.SkeletonInfoCard
import com.example.localai.ui.components.SkeletonLocalModelCard
import androidx.compose.material.icons.outlined.ErrorOutline
import com.example.localai.ui.components.StorageOverviewCard
import com.example.localai.ui.components.SurfaceCard
import com.example.localai.ui.screens.explore.VerdictChip
import com.example.localai.ui.theme.AppTheme

/** Callbacks of My Models, by download record id. */
data class MyModelsActions(
    val onOpenDetails: (repoId: String) -> Unit,
    val onPause: (String) -> Unit,
    val onResume: (String) -> Unit,
    val onCancel: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onRefresh: () -> Unit,
    val onDismissMessage: () -> Unit,
    val onExplore: () -> Unit,
    val onLoad: (String) -> Unit = {},
    val onUnload: () -> Unit = {},
    val onOpenChat: () -> Unit = {}
)

@Composable
fun MyModelsScreen(
    state: MyModelsUiState,
    actions: MyModelsActions,
    modifier: Modifier = Modifier
) {
    var pendingDelete by remember { mutableStateOf<MyModelItem?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        AppTopBar(
            title = "My Models",
            actions = {
                IconButton(onClick = actions.onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Re-check files and device")
                }
            }
        )
        LazyColumn(
            modifier = Modifier.weight(1f).testTag("my_models"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "note") {
                InfoBanner(
                    text = if (state.engineAvailable) {
                        "Models downloaded to this device from Hugging Face. Load one to chat with it entirely on-device " +
                            "(llama.cpp, CPU). A model counts as working only after it has actually loaded."
                    } else {
                        "Models downloaded to this device from Hugging Face. The on-device engine is not available on " +
                            "this device, so models cannot be loaded."
                    }
                )
            }
            state.message?.let { message ->
                item(key = "message") {
                    Column {
                        InfoBanner(message, tone = BannerTone.WARNING)
                        TextButton(onClick = actions.onDismissMessage) { Text("Dismiss") }
                    }
                }
            }
            item(key = "storage") {
                // Storage is measured together with the first read of the records.
                if (state.loading) {
                    SkeletonGroup("Measuring storage") { SkeletonInfoCard(lines = 3) }
                } else {
                    StorageSection(state)
                }
            }
            item(key = "header") { SectionHeader("Your models") }
            when {
                state.loading -> item(key = "loading") {
                    SkeletonCardList("Loading downloaded models", count = 2, modifier = Modifier.testTag("my_models_loading")) {
                        SkeletonLocalModelCard()
                    }
                }
                state.error != null -> item(key = "error") {
                    SurfaceCard {
                        EmptyState(
                            icon = Icons.Outlined.ErrorOutline,
                            title = "Downloaded models could not be read",
                            message = state.error,
                            actionLabel = "Retry",
                            onAction = actions.onRefresh
                        )
                    }
                }
                state.items.isEmpty() -> item(key = "empty") {
                    SurfaceCard {
                        EmptyState(
                            icon = Icons.Outlined.Inventory2,
                            title = "No models downloaded",
                            message = "Find a GGUF model in Explore and download it to see it here.",
                            actionLabel = "Explore Models",
                            onAction = actions.onExplore
                        )
                    }
                }
                else -> items(state.items, key = { it.id }) { item ->
                    LocalModelCard(
                        item = item,
                        actions = actions,
                        onDelete = { pendingDelete = item },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }

    pendingDelete?.let { item ->
        val downloaded = item.status == DownloadStatus.Completed
        ConfirmationDialog(
            title = "Delete ${item.fileName}?",
            message = if (downloaded) {
                "This deletes the downloaded file" + (item.sizeBytes?.let { " (${formatBytes(it)})" } ?: "") +
                    " from this device. You can download it again from Explore."
            } else {
                "This stops the download if it is running and deletes any partially downloaded data."
            },
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                actions.onDelete(item.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun StorageSection(state: MyModelsUiState) {
    val device = state.device
    val total = device?.totalStorageBytes
    val free = device?.availableStorageBytes
    val models = state.completedBytes + state.partialBytes
    if (total != null && free != null) {
        val mb = 1024L * 1024
        StorageOverviewCard(
            modelsUsedMb = models / mb,
            otherUsedMb = ((total - free - models) / mb).coerceAtLeast(0),
            totalMb = total / mb,
            nearlyFull = free.toDouble() / total < 0.08,
            title = "Storage (measured)"
        )
    } else {
        SurfaceCard {
            Text(
                "Models use ${formatBytes(models)}. Device storage could not be measured.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalModelCard(
    item: MyModelItem,
    actions: MyModelsActions,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SurfaceCard(onClick = { actions.onOpenDetails(item.repoId) }, modifier = modifier.testTag("local_model:${item.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModalityIconBadge(Modality.TEXT)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.repoName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        listOfNotNull(item.fileName, item.sizeBytes?.let { formatBytes(it) } ?: "size unknown").joinToString(" · "),
                        style = small,
                        color = muted
                    )
                    Text(item.repoId, style = small, color = muted)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(item.status)
                when (item.verification) {
                    Verification.SHA256 -> ModelMetadataChip("SHA-256 verified", icon = Icons.Outlined.Verified)
                    Verification.SIZE_ONLY -> ModelMetadataChip("Size verified only (no checksum)")
                    null -> Unit
                }
                (item.analysis as? LocalAnalysis.Ready)?.let { VerdictChip(it.assessment.verdict) }
            }
            ProgressOrReason(item)
            AnalysisSection(item.analysis, showEngineVerdict = item.load == null)
            if (item.status == DownloadStatus.Completed) LoadSection(item, actions)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                when (val status = item.status) {
                    is DownloadStatus.Downloading, DownloadStatus.Queued -> {
                        OutlinedButton(onClick = { actions.onPause(item.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Pause", modifier = Modifier.padding(start = 8.dp))
                        }
                        TextButton(onClick = { actions.onCancel(item.id) }) {
                            Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Cancel", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    is DownloadStatus.Paused -> Button(onClick = { actions.onResume(item.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Resume", modifier = Modifier.padding(start = 8.dp))
                    }
                    is DownloadStatus.Failed -> if (status.retryable) {
                        Button(onClick = { actions.onResume(item.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Retry", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    DownloadStatus.Cancelled -> Button(onClick = { actions.onResume(item.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Download again")
                    }
                    DownloadStatus.Completed, DownloadStatus.Verifying, DownloadStatus.NotDownloaded -> Unit
                }
                Spacer(Modifier.weight(1f))
                if (item.status != DownloadStatus.Verifying) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete ${item.fileName}", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: DownloadStatus) {
    val extended = AppTheme.extendedColors
    val colors = MaterialTheme.colorScheme
    val (label, container, content) = when (status) {
        DownloadStatus.Completed -> Triple("Downloaded", extended.successContainer, extended.onSuccessContainer)
        is DownloadStatus.Downloading -> Triple("Downloading", extended.infoContainer, extended.onInfoContainer)
        DownloadStatus.Queued -> Triple("Waiting", extended.infoContainer, extended.onInfoContainer)
        DownloadStatus.Verifying -> Triple("Verifying", extended.infoContainer, extended.onInfoContainer)
        is DownloadStatus.Paused -> Triple("Paused", extended.warningContainer, extended.onWarningContainer)
        is DownloadStatus.Failed -> Triple("Failed", colors.errorContainer, colors.onErrorContainer)
        DownloadStatus.Cancelled -> Triple("Cancelled", colors.surfaceContainerHigh, colors.onSurfaceVariant)
        DownloadStatus.NotDownloaded -> Triple("Not downloaded", colors.surfaceContainerHigh, colors.onSurfaceVariant)
    }
    ModelMetadataChip(text = label, containerColor = container, contentColor = content)
}

@Composable
private fun ProgressOrReason(item: MyModelItem) {
    val small = MaterialTheme.typography.bodySmall
    fun progressText(done: Long, total: Long?) =
        if (total != null && total > 0) "${formatBytes(done)} of ${formatBytes(total)} (${done * 100 / total}%)" else formatBytes(done)
    when (val status = item.status) {
        is DownloadStatus.Downloading -> {
            val total = status.totalBytes
            if (total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { (status.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Text(progressText(status.downloadedBytes, total), style = small)
        }
        is DownloadStatus.Paused -> {
            val total = status.totalBytes
            if (total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { (status.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
            Text("Paused at ${progressText(status.downloadedBytes, total)}", style = small)
        }
        DownloadStatus.Queued -> Text("Waiting for a network connection or a free download slot.", style = small)
        DownloadStatus.Verifying -> {
            // Checksum progress is not reported, so the bar is indeterminate.
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Checking the downloaded file…", style = small)
        }
        is DownloadStatus.Failed -> Text(status.reason, style = small, color = MaterialTheme.colorScheme.error)
        DownloadStatus.Cancelled -> Text("Download cancelled; partial data was deleted.", style = small)
        DownloadStatus.Completed, DownloadStatus.NotDownloaded -> Unit
    }
}

/** Load state and actions for a downloaded model. The engine's own result is the only proof it works. */
@Composable
private fun LoadSection(item: MyModelItem, actions: MyModelsActions) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.testTag("load:${item.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (val load = item.load) {
            ModelLoadStatus.Checking, ModelLoadStatus.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (load == ModelLoadStatus.Checking) "Checking the file and device…" else "Loading into memory…",
                    style = small
                )
            }
            is ModelLoadStatus.Loaded -> {
                ModelMetadataChip(
                    text = "Loaded",
                    containerColor = AppTheme.extendedColors.successContainer,
                    contentColor = AppTheme.extendedColors.onSuccessContainer
                )
                Text(
                    "Loaded by llama.cpp in ${load.model.loadMillis} ms · context ${load.model.contextLength} tokens. " +
                        "Very long conversations or prompts may still fail.",
                    style = small,
                    color = muted
                )
                load.notes.take(2).forEach { Text("• $it", style = small, color = muted) }
                Row {
                    Button(onClick = actions.onOpenChat, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open chat") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = actions.onUnload, modifier = Modifier.heightIn(min = 48.dp)) { Text("Unload") }
                }
            }
            is ModelLoadStatus.Failed -> {
                Text(
                    (if (load.rejected) "Not loaded: " else "Load failed: ") + load.message,
                    style = small,
                    color = MaterialTheme.colorScheme.error
                )
                Text("The file was kept.", style = small, color = muted)
                OutlinedButton(onClick = { actions.onLoad(item.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
            }
            null -> OutlinedButton(onClick = { actions.onLoad(item.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Load") }
        }
    }
}

/** Compatibility from the file's own header, and loadability, for completed files only. */
@Composable
private fun AnalysisSection(analysis: LocalAnalysis, showEngineVerdict: Boolean = true) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (analysis) {
        LocalAnalysis.NotAvailable -> Unit
        LocalAnalysis.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Reading the model's header…", style = small, color = muted)
        }
        is LocalAnalysis.Failed -> Text("Could not analyse the file: ${analysis.message}", style = small, color = MaterialTheme.colorScheme.error)
        is LocalAnalysis.Ready -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            analysis.info?.let { info ->
                Text(
                    listOfNotNull(
                        info.architecture?.let { "Architecture $it" },
                        info.fileTypeLabel?.let { "quantization $it" },
                        info.contextLength?.let { "context %,d".format(it) }
                    ).joinToString(" · ").ifEmpty { "Header read; no architecture details stated." },
                    style = small,
                    color = muted
                )
            }
            analysis.assessment.findings
                .filter { it.severity != Severity.WARNING }
                .take(2)
                .forEach { Text("• ${it.message}", style = small, color = muted) }
            if (showEngineVerdict) Text(
                when (val runtime = analysis.runtime) {
                    is RuntimeCompatibility.Loadable -> "Loadable by ${runtime.engineId}."
                    is RuntimeCompatibility.Incompatible -> "Not loadable: ${runtime.reason.label.replaceFirstChar { it.lowercase() }}."
                    RuntimeCompatibility.NotEvaluated -> "Not loaded yet: whether it works is known only after loading."
                },
                style = small,
                color = if (analysis.runtime is RuntimeCompatibility.Loadable) MaterialTheme.colorScheme.onSurface else muted
            )
        }
    }
}
