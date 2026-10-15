package com.example.localai.ui.screens.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.localai.data.catalog.CatalogAccess
import com.example.localai.data.catalog.HfModelSummary
import com.example.localai.data.model.Modality
import com.example.localai.ui.components.ModalityIconBadge
import com.example.localai.ui.components.ModelMetadataChip
import com.example.localai.ui.theme.AppTheme

/**
 * Summary card for a real Hub repository, laid out like the existing ModelCard. Shows only what
 * the Hub returned; missing values are labelled unknown. Compatibility is not shown here because
 * it needs the file list, which is fetched only when the repository is opened.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HfModelCard(
    model: HfModelSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModalityIconBadge(modalityFor(model.pipelineTag))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.repoId.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = model.author ?: "Unknown publisher",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                AccessChip(model.access)
            }
            Spacer(Modifier.size(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ModelMetadataChip(model.pipelineTag ?: "Task unknown")
                if (model.advertisesGguf) ModelMetadataChip("GGUF")
                ModelMetadataChip(model.license?.let { "License: $it" } ?: "License unknown")
                formatCount(model.downloads)?.let { ModelMetadataChip(it, icon = Icons.Outlined.Download) }
                formatCount(model.likes?.toLong())?.let { ModelMetadataChip(it, icon = Icons.Outlined.FavoriteBorder) }
                formatHubDate(model.lastModified)?.let { ModelMetadataChip("Updated $it") }
            }
            Spacer(Modifier.size(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Compatibility: open to check files",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Details")
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AccessChip(access: CatalogAccess) {
    val extended = AppTheme.extendedColors
    when (access) {
        CatalogAccess.Public -> Unit
        is CatalogAccess.Gated -> ModelMetadataChip(
            text = "Gated",
            icon = Icons.Outlined.Lock,
            containerColor = extended.warningContainer,
            contentColor = extended.onWarningContainer
        )
        CatalogAccess.Restricted -> ModelMetadataChip(
            text = "Restricted",
            icon = Icons.Outlined.Lock,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

/** Icon only; derived from the Hub task. Unknown tasks fall back to the text icon. */
private fun modalityFor(pipelineTag: String?): Modality = when (pipelineTag) {
    "automatic-speech-recognition", "text-to-speech", "text-to-audio" -> Modality.SPEECH
    "text-to-image", "image-to-image", "image-text-to-text" -> Modality.IMAGE
    "text-to-video" -> Modality.VIDEO
    "feature-extraction", "sentence-similarity" -> Modality.EMBEDDINGS
    else -> Modality.TEXT
}
