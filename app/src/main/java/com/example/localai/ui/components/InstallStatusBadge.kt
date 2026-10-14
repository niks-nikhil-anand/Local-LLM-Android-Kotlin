package com.example.localai.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.localai.data.model.InstallState
import com.example.localai.ui.theme.AppTheme

private data class StatusStyle(
    val label: String,
    val icon: ImageVector,
    val container: Color,
    val content: Color
)

/** Simulated install status with icon and text. Never implies a real install. */
@Composable
fun InstallStatusBadge(
    state: InstallState,
    modifier: Modifier = Modifier
) {
    val extended = AppTheme.extendedColors
    val colors = MaterialTheme.colorScheme
    val style = when (state) {
        InstallState.Installed -> StatusStyle(
            "Installed (demo)", Icons.Outlined.CheckCircle, extended.successContainer, extended.onSuccessContainer
        )
        is InstallState.Downloading -> StatusStyle(
            "Downloading ${(state.progress * 100).toInt()}% (simulated)", Icons.Outlined.Download,
            extended.infoContainer, extended.onInfoContainer
        )
        is InstallState.Paused -> StatusStyle(
            "Paused ${(state.progress * 100).toInt()}%", Icons.Filled.Pause,
            extended.warningContainer, extended.onWarningContainer
        )
        is InstallState.Failed -> StatusStyle(
            "Failed (simulated)", Icons.Outlined.ErrorOutline, colors.errorContainer, colors.onErrorContainer
        )
        InstallState.NotInstalled -> StatusStyle(
            "Not installed", Icons.Outlined.Download, colors.surfaceContainer, colors.onSurfaceVariant
        )
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = style.container,
        contentColor = style.content
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = style.icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(text = style.label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
