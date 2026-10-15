package com.example.localai.ui.screens.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.localai.data.catalog.CatalogAccess
import com.example.localai.data.catalog.HfModelSummary
import com.example.localai.data.catalog.DownloadStatus
import com.example.localai.data.compat.Verdict
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import com.example.localai.data.catalog.formatBytes
import com.example.localai.data.compat.Finding
import com.example.localai.data.compat.Severity
import com.example.localai.data.device.DeviceResourceSnapshot
import com.example.localai.data.gguf.GgufModelInfo
import com.example.localai.data.gguf.GgufParseResult
import com.example.localai.data.hf.HeaderInspection
import com.example.localai.ui.components.AppTopBar
import com.example.localai.ui.components.BannerTone
import com.example.localai.ui.components.EmptyState
import com.example.localai.ui.components.InfoBanner
import com.example.localai.ui.components.ModelMetadataChip
import com.example.localai.ui.components.SectionHeader
import com.example.localai.ui.components.SkeletonCardList
import com.example.localai.ui.components.SkeletonChip
import com.example.localai.ui.components.SkeletonGroup
import com.example.localai.ui.components.SkeletonText
import com.example.localai.ui.components.SkeletonVariantCard
import com.example.localai.ui.components.SurfaceCard
import com.example.localai.ui.theme.AppTheme

data class RepoDetailsActions(
    val onBack: () -> Unit,
    val onRetrySummary: () -> Unit,
    val onRetryFiles: () -> Unit,
    val onRecheckDevice: () -> Unit,
    val onInspectHeader: (variantId: String) -> Unit = {},
    val downloads: DownloadActions = DownloadActions()
)

/** Download operations by variant id. */
data class DownloadActions(
    val onDownload: (String) -> Unit = {},
    val onPause: (String) -> Unit = {},
    val onCancel: (String) -> Unit = {},
    val onRetry: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onDismissMessage: () -> Unit = {}
)

@Composable
fun HfModelDetailsScreen(
    state: RepoDetailsUiState,
    actions: RepoDetailsActions,
    modifier: Modifier = Modifier
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(modifier = modifier.fillMaxSize()) {
        AppTopBar(title = state.repoId.name, subtitle = state.repoId.owner, onBack = actions.onBack)
        LazyColumn(
            modifier = Modifier.weight(1f).testTag("repo_details"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "summary") { SummarySection(state, actions) }
            item(key = "engine") {
                InfoBanner(
                    text = "Downloaded GGUF files are loaded in My Models and run on this device with llama.cpp (CPU). " +
                        "Use \"Check file header\" to read a file's first few MB and verify its architecture and context. " +
                        "\"Passes checks\" would mean no known problem, not that a model will run."
                )
            }
            item(key = "files_header") { SectionHeader("Model files") }
            filesSection(state, actions)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummarySection(state: RepoDetailsUiState, actions: RepoDetailsActions) {
    val uriHandler = LocalUriHandler.current
    SurfaceCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.repoId.value, style = MaterialTheme.typography.titleMedium)
            when (val summary = state.summary) {
                // Only the facts the Hub has not returned yet are placeholders; the name and link are real.
                Fetch.Loading -> SkeletonGroup("Loading repository details", Modifier.testTag("summary_loading")) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(88.dp, 96.dp, 120.dp, 104.dp, 112.dp).forEach { SkeletonChip(it) }
                        }
                        SkeletonText(MaterialTheme.typography.bodySmall, 260.dp)
                    }
                }
                is Fetch.Failed -> {
                    Text(
                        "Repository details unavailable: ${summary.error.userMessage()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = actions.onRetrySummary) { Text("Retry") }
                }
                is Fetch.Ready -> SummaryFacts(summary.value)
            }
            TextButton(onClick = { uriHandler.openUri(state.repoId.webUrl) }) {
                Text("Open model card on Hugging Face")
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryFacts(model: HfModelSummary) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AccessChip(model.access)
        ModelMetadataChip(model.author?.let { "By $it" } ?: "Publisher unknown")
        ModelMetadataChip(model.pipelineTag ?: "Task unknown")
        ModelMetadataChip(model.license?.let { "License: $it" } ?: "License unknown")
        ModelMetadataChip(formatCount(model.downloads)?.let { "$it downloads" } ?: "Downloads unknown")
        ModelMetadataChip(formatHubDate(model.lastModified)?.let { "Updated $it" } ?: "Last update unknown")
    }
    Text(
        "No description is available from the Hub listing; see the model card.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    when (model.access) {
        is CatalogAccess.Gated -> InfoBanner(
            "This repository is gated. Downloading its files requires accepting its terms on Hugging Face " +
                "with an account. This app does not sign in.",
            tone = BannerTone.WARNING
        )
        CatalogAccess.Restricted -> InfoBanner("This repository is private or disabled on Hugging Face.", tone = BannerTone.ERROR)
        CatalogAccess.Public -> Unit
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.filesSection(state: RepoDetailsUiState, actions: RepoDetailsActions) {
    when (val files = state.files) {
        FilesState.Loading -> item(key = "files_loading") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Reading the file list and checking this device…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SkeletonCardList("Loading model files", count = 2, modifier = Modifier.testTag("files_loading")) {
                    SkeletonVariantCard()
                }
            }
        }
        is FilesState.FetchFailed -> item(key = "files_failed") {
            EmptyState(
                icon = Icons.Outlined.ErrorOutline,
                title = "Could not read the file list",
                message = files.error.userMessage(),
                actionLabel = "Retry",
                onAction = actions.onRetryFiles
            )
        }
        is FilesState.AnalysisFailed -> item(key = "analysis_failed") {
            EmptyState(
                icon = Icons.Outlined.ErrorOutline,
                title = "Compatibility check failed",
                message = "The files could not be analysed (${files.message}). No compatibility result is shown.",
                actionLabel = "Retry",
                onAction = actions.onRetryFiles
            )
        }
        is FilesState.Ready -> {
            item(key = "device") { DeviceCard(state.device, state.isRecheckingDevice, actions.onRecheckDevice) }
            state.downloadMessage?.let { message ->
                item(key = "download_message") {
                    Column {
                        InfoBanner(message, tone = BannerTone.WARNING)
                        TextButton(onClick = actions.downloads.onDismissMessage) { Text("Dismiss") }
                    }
                }
            }
            if (files.variants.isEmpty()) {
                item(key = "no_gguf") {
                    EmptyState(
                        icon = Icons.Outlined.FolderOff,
                        title = "No GGUF model files",
                        message = if (files.analysis.otherFormatWeights.isNotEmpty()) {
                            "This repository has model weights only in formats the planned llama.cpp runtime cannot load."
                        } else {
                            "This repository contains no GGUF model weights."
                        }
                    )
                }
            }
            items(files.variants, key = { "variant:" + it.variant.id }) {
                VariantCard(
                    item = it,
                    download = state.downloads[it.variant.primaryFile.path] ?: DownloadStatus.NotDownloaded,
                    blockReason = downloadBlockReason(it, (state.summary as? Fetch.Ready)?.value?.access),
                    onInspectHeader = actions.onInspectHeader,
                    downloadActions = actions.downloads
                )
            }
            if (files.analysis.projectors.isNotEmpty()) {
                item(key = "projectors") {
                    FileListCard(
                        title = "Vision projectors (not standalone models)",
                        lines = files.analysis.projectors.map { it.path to it.sizeBytes }
                    )
                }
            }
            if (files.analysis.otherFormatWeights.isNotEmpty()) {
                item(key = "other_formats") {
                    FileListCard(
                        title = "Other formats (not supported by the planned runtime)",
                        lines = files.analysis.otherFormatWeights.map { "${it.file.path} (${it.format.ifEmpty { "split" }})" to it.file.sizeBytes }
                    )
                }
            }
            item(key = "ignored") {
                Text(
                    "${files.analysis.ignored.size} other file(s): documentation, tokenizer and configuration.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceResourceSnapshot?, rechecking: Boolean, onRecheck: () -> Unit) {
    fun bytes(v: Long?) = v?.let { formatBytes(it) } ?: "unknown"
    SurfaceCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("This device (measured)", style = MaterialTheme.typography.titleSmall)
            Text(
                "RAM: ${bytes(device?.totalRamBytes)} total, ${bytes(device?.availableRamBytes)} available now",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Free storage: ${bytes(device?.availableStorageBytes)}", style = MaterialTheme.typography.bodySmall)
            if (device?.isLowMemory == true) {
                Text("Android reports low memory right now.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text(
                "Available RAM changes constantly and is shared with other apps; results use estimates.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onRecheck, enabled = !rechecking) { Text("Re-check device") }
                if (rechecking) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.size(20.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VariantCard(
    item: VariantAssessment,
    download: DownloadStatus,
    blockReason: String?,
    onInspectHeader: (String) -> Unit,
    downloadActions: DownloadActions
) {
    val variant = item.variant
    val assessment = item.assessment
    SurfaceCard(modifier = Modifier.testTag("variant:${variant.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(variant.primaryFile.fileName, style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                VerdictChip(assessment.verdict)
                ModelMetadataChip(variant.quantLabel?.let { "Quant (from name): $it" } ?: "Quantization unknown")
                ModelMetadataChip(variant.totalSizeBytes?.let { formatBytes(it) } ?: "Size unknown")
                if (variant.isSharded) {
                    ModelMetadataChip(
                        variant.declaredShardCount?.let { "${variant.files.size} of $it parts" } ?: "${variant.files.size} parts (count unclear)"
                    )
                }
                if (variant.companionProjectors.isNotEmpty()) ModelMetadataChip("Has vision projector")
            }
            assessment.memory?.let { memory ->
                Text(
                    memory.totalBytes?.let { "Estimated memory: about ${formatBytes(it)} (estimate)" }
                        ?: "Estimated memory: at least ${formatBytes(memory.weightsBytes + memory.overheadBytes)} (weights only; estimate)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HeaderSection(item.header) { onInspectHeader(variant.id) }
            DownloadSection(variant.id, variant.totalSizeBytes, download, blockReason, downloadActions)
            assessment.findings.forEach { FindingRow(it) }
        }
    }
}

/** Header inspection status of one variant, with the metadata read from the file itself. */
@Composable
private fun HeaderSection(check: HeaderCheck?, onInspect: () -> Unit) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (check) {
        null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("File header not checked.", style = small, color = muted, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onInspect) { Text("Check file header") }
        }
        HeaderCheck.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp).testTag("header_running"))
            Spacer(Modifier.width(8.dp))
            Text("Reading the file header (up to 16 MB, never the whole model)…", style = small, color = muted)
        }
        is HeaderCheck.Done -> when (val inspection = check.inspection) {
            is HeaderInspection.FetchFailed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Header check failed: ${inspection.reason.description} (${inspection.detail}).",
                    style = small,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onInspect) { Text("Retry") }
            }
            is HeaderInspection.Inspected -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val read = "${formatBytes(inspection.bytesRead)} read" +
                    (inspection.fileSizeBytes?.let { " of ${formatBytes(it)}" } ?: "") +
                    if (!inspection.rangeSupported) "; server ignored partial download, stopped early" else ""
                when (val result = inspection.result) {
                    is GgufParseResult.Failure -> {
                        Text("Header inspected, but it could not be used: ${result.message} ($read).", style = small, color = muted)
                        TextButton(onClick = onInspect) { Text("Check again") }
                    }
                    is GgufParseResult.Success -> {
                        Text("Header inspected ($read).", style = small, color = muted)
                        check.info?.let { HeaderFacts(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderFacts(info: GgufModelInfo) {
    val unknown = "unknown"
    val lines = listOf(
        "Architecture" to (info.architecture ?: unknown),
        "Parameters" to (info.parameterCount?.let { formatParameters(it) } ?: unknown),
        "Quantization (file)" to (info.fileTypeLabel ?: info.dominantWeightType?.label ?: unknown),
        "Context length" to (info.contextLength?.let { "%,d tokens".format(it) } ?: unknown),
        "Layers / heads / KV heads" to listOf(info.blockCount, info.headCount, info.headCountKv).joinToString(" / ") { it?.toString() ?: "?" },
        "Tokenizer" to listOfNotNull(info.tokenizerModel, info.vocabularySize?.let { "%,d tokens".format(it) }).joinToString(", ").ifEmpty { unknown },
        "Chat template" to if (info.hasChatTemplate) "present" else "not in file",
        "GGUF version" to info.ggufVersion.toString()
    ) + listOfNotNull(info.splitCount?.let { "Split" to "part ${info.splitIndex ?: "?"} of $it" })
    lines.forEach { (label, value) ->
        Text("$label: $value", style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatParameters(count: Long): String = when {
    count >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.2fB (from tensor shapes)", count / 1e9)
    count >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM (from tensor shapes)", count / 1e6)
    else -> "$count (from tensor shapes)"
}

@Composable
private fun FindingRow(finding: Finding) {
    val extended = AppTheme.extendedColors
    val (icon, tint) = when (finding.severity) {
        Severity.BLOCKER -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
        Severity.RESOURCE -> Icons.Outlined.Memory to extended.warning
        Severity.UNCERTAIN -> Icons.AutoMirrored.Outlined.HelpOutline to MaterialTheme.colorScheme.onSurfaceVariant
        Severity.WARNING -> Icons.Outlined.WarningAmber to extended.warning
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = finding.severity.name.lowercase(), tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(finding.message, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FileListCard(title: String, lines: List<Pair<String, Long?>>) {
    SurfaceCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            lines.forEach { (name, size) ->
                Text("$name · ${size?.let { formatBytes(it) } ?: "size unknown"}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Why a variant cannot be downloaded here, or null when it can. Sharded sets and files the checks
 * found incompatible are not offered; gated repositories need an account this app does not use.
 */
fun downloadBlockReason(item: VariantAssessment, access: CatalogAccess?): String? = when {
    access is CatalogAccess.Gated -> "Download unavailable: this repository is gated on Hugging Face."
    access == CatalogAccess.Restricted -> "Download unavailable: this repository is private or disabled."
    item.variant.isSharded -> "Download unavailable: multi-part models are not supported yet."
    item.assessment.verdict == Verdict.INCOMPATIBLE -> "Download unavailable: this file failed the compatibility checks."
    else -> null
}

@Composable
private fun DownloadSection(
    variantId: String,
    sizeBytes: Long?,
    status: DownloadStatus,
    blockReason: String?,
    actions: DownloadActions
) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    fun progressText(done: Long, total: Long?): String =
        if (total != null && total > 0) "${formatBytes(done)} of ${formatBytes(total)} (${done * 100 / total}%)" else formatBytes(done)

    Column(Modifier.testTag("download:$variantId"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (status) {
            DownloadStatus.NotDownloaded, DownloadStatus.Cancelled -> {
                if (blockReason != null) {
                    Text(blockReason, style = small, color = muted)
                } else {
                    if (status == DownloadStatus.Cancelled) Text("Download cancelled.", style = small, color = muted)
                    Button(onClick = { actions.onDownload(variantId) }) {
                        Text("Download" + (sizeBytes?.let { " (${formatBytes(it)})" } ?: ""))
                    }
                }
            }
            DownloadStatus.Queued -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Waiting to download (needs a network connection)", style = small, color = muted, modifier = Modifier.weight(1f))
                TextButton(onClick = { actions.onCancel(variantId) }) { Text("Cancel") }
            }
            is DownloadStatus.Downloading -> {
                val total = status.totalBytes
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { (status.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloading ${progressText(status.downloadedBytes, total)}", style = small, modifier = Modifier.weight(1f))
                    TextButton(onClick = { actions.onPause(variantId) }) { Text("Pause") }
                    TextButton(onClick = { actions.onCancel(variantId) }) { Text("Cancel") }
                }
            }
            DownloadStatus.Verifying -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Verifying the downloaded file", style = small)
            }
            is DownloadStatus.Paused -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Paused at ${progressText(status.downloadedBytes, status.totalBytes)}", style = small, modifier = Modifier.weight(1f))
                TextButton(onClick = { actions.onRetry(variantId) }) { Text("Resume") }
                TextButton(onClick = { actions.onCancel(variantId) }) { Text("Cancel") }
            }
            DownloadStatus.Completed -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloaded and verified on this device.", style = small, modifier = Modifier.weight(1f))
                    TextButton(onClick = { actions.onDelete(variantId) }) { Text("Delete") }
                }
                Text("Load it in My Models to chat with it.", style = small, color = muted)
            }
            is DownloadStatus.Failed -> {
                Text("Download failed: ${status.reason}", style = small, color = MaterialTheme.colorScheme.error)
                Row {
                    if (status.retryable && blockReason == null) TextButton(onClick = { actions.onRetry(variantId) }) { Text("Retry") }
                    TextButton(onClick = { actions.onDelete(variantId) }) { Text("Remove") }
                }
            }
        }
    }
}
