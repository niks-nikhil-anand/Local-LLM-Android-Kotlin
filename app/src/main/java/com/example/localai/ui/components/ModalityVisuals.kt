package com.example.localai.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.localai.data.model.Modality

val Modality.icon: ImageVector
    get() = when (this) {
        Modality.TEXT -> Icons.Outlined.ChatBubbleOutline
        Modality.CODING -> Icons.Outlined.Code
        Modality.SPEECH -> Icons.Outlined.Mic
        Modality.IMAGE -> Icons.Outlined.Image
        Modality.VIDEO -> Icons.Outlined.Videocam
        Modality.EMBEDDINGS -> Icons.Outlined.Hub
    }

/** Decorative tonal tile showing a modality icon. */
@Composable
fun ModalityIconBadge(
    modality: Modality,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = modality.icon,
                contentDescription = null,
                modifier = Modifier.size(size / 2)
            )
        }
    }
}
