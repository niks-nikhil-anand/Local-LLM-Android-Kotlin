package com.example.localai.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.localai.data.model.formatSizeMb

/** Segmented usage bar: models (accent), other data (neutral) and free space. */
@Composable
fun StorageOverviewCard(
    modelsUsedMb: Long,
    otherUsedMb: Long,
    totalMb: Long,
    nearlyFull: Boolean,
    modifier: Modifier = Modifier
) {
    val freeMb = (totalMb - modelsUsedMb - otherUsedMb).coerceAtLeast(0)
    val modelsFraction by animateFloatAsState(modelsUsedMb.toFloat() / totalMb, label = "models")
    val otherFraction = otherUsedMb.toFloat() / totalMb
    val freeFraction = (1f - modelsFraction - otherFraction).coerceAtLeast(0.0001f)
    val summary = "Models ${formatSizeMb(modelsUsedMb)}, other data ${formatSizeMb(otherUsedMb)}, " +
        "free ${formatSizeMb(freeMb)} of ${formatSizeMb(totalMb)}. Demo data."

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Storage (demo data)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${formatSizeMb(freeMb)} free",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .semantics { contentDescription = summary }
            ) {
                if (modelsFraction > 0f) {
                    Box(
                        Modifier
                            .weight(modelsFraction.coerceAtLeast(0.0001f))
                            .height(10.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Box(
                    Modifier
                        .weight(otherFraction.coerceAtLeast(0.0001f))
                        .height(10.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )
                Box(Modifier.weight(freeFraction).height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem("Models ${formatSizeMb(modelsUsedMb)}", MaterialTheme.colorScheme.primary)
                LegendItem("Other ${formatSizeMb(otherUsedMb)}", MaterialTheme.colorScheme.outline)
            }
            if (nearlyFull) {
                InfoBanner(
                    text = "Storage nearly full. Remove a model or free space before adding more.",
                    tone = BannerTone.WARNING
                )
            }
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
