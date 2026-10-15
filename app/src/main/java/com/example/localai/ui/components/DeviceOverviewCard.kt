package com.example.localai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.localai.ui.theme.AppTheme

/**
 * Overview of this device and the app's models. The caller passes display text for each tile;
 * values come from measurements and real app state, say "Unavailable" rather than showing invented
 * numbers, and are null while still loading (a text-shaped placeholder is shown instead).
 */
@Composable
fun DeviceOverviewCard(
    memory: String?,
    storage: String?,
    models: String?,
    runtime: String?,
    badge: String?,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Device overview",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (badge != null) {
                    ModelMetadataChip(
                        text = badge,
                        containerColor = AppTheme.extendedColors.infoContainer,
                        contentColor = AppTheme.extendedColors.onInfoContainer
                    )
                } else {
                    SkeletonGroup("Measuring this device") { SkeletonChip(72.dp) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(icon = Icons.Outlined.Memory, label = "Memory", value = memory, modifier = Modifier.weight(1f))
                StatTile(icon = Icons.Outlined.Storage, label = "Storage", value = storage, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(icon = Icons.Outlined.Inventory2, label = "Downloaded models", value = models, modifier = Modifier.weight(1f))
                StatTile(icon = Icons.Outlined.PowerSettingsNew, label = "Runtime", value = runtime, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatTile(
    icon: ImageVector,
    label: String,
    value: String?,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                SkeletonGroup("$label loading") { SkeletonText(MaterialTheme.typography.titleSmall, 96.dp) }
            }
        }
    }
}
