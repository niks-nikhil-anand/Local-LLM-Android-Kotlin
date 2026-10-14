package com.example.localai.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.localai.data.model.Compatibility
import com.example.localai.ui.theme.AppTheme

private data class BadgeStyle(val icon: ImageVector, val container: Color, val content: Color)

/** Compatibility status. Always pairs an icon and a label so color is never the only signal. */
@Composable
fun CompatibilityBadge(
    compatibility: Compatibility,
    modifier: Modifier = Modifier
) {
    val extended = AppTheme.extendedColors
    val colors = MaterialTheme.colorScheme
    val style = when (compatibility) {
        Compatibility.COMPATIBLE ->
            BadgeStyle(Icons.Outlined.CheckCircle, extended.successContainer, extended.onSuccessContainer)
        Compatibility.LIMITED ->
            BadgeStyle(Icons.Outlined.Warning, extended.warningContainer, extended.onWarningContainer)
        Compatibility.UNSUPPORTED ->
            BadgeStyle(Icons.Outlined.Block, colors.errorContainer, colors.onErrorContainer)
    }
    Surface(
        modifier = modifier.semantics { contentDescription = "Compatibility: ${compatibility.label}" },
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
            Text(text = compatibility.label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
