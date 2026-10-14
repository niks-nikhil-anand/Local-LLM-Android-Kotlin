package com.example.localai.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.localai.ui.theme.AppTheme

enum class BannerTone { INFO, WARNING, ERROR }

/** Inline notice for demo disclosures and warnings. Icon plus text, never color alone. */
@Composable
fun InfoBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.INFO
) {
    val extended = AppTheme.extendedColors
    val colors = MaterialTheme.colorScheme
    val (container, content, icon) = when (tone) {
        BannerTone.INFO -> Triple(extended.infoContainer, extended.onInfoContainer, Icons.Outlined.Info)
        BannerTone.WARNING -> Triple(extended.warningContainer, extended.onWarningContainer, Icons.Outlined.Warning)
        BannerTone.ERROR -> Triple(colors.errorContainer, colors.onErrorContainer, Icons.Outlined.ErrorOutline)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
