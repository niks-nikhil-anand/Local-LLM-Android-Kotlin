package com.example.localai.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.localai.data.model.AttachmentKind
import com.example.localai.data.model.InstallState
import com.example.localai.data.model.ModelInfo
import com.example.localai.data.model.formatSizeMb
import com.example.localai.ui.components.InstallStatusBadge
import com.example.localai.ui.components.ModalityIconBadge

/** Lets the user choose which (demo) model the active conversation uses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    models: List<ModelInfo>,
    selectedId: String,
    installStateOf: (String) -> InstallState,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                text = "Choose model",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Text(
                text = "Demo models only. None are loaded; replies are pre-written.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
            )
            LazyColumn {
                items(models, key = { it.id }) { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .selectable(
                                selected = model.id == selectedId,
                                onClick = { onSelect(model.id) },
                                role = Role.RadioButton
                            )
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ModalityIconBadge(model.modality, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(model.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "${model.provider} · ${formatSizeMb(model.sizeMb)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            InstallStatusBadge(installStateOf(model.id))
                        }
                        RadioButton(selected = model.id == selectedId, onClick = null)
                    }
                }
            }
        }
    }
}

private fun AttachmentKind.icon(): ImageVector = when (this) {
    AttachmentKind.IMAGE -> Icons.Outlined.Image
    AttachmentKind.VIDEO -> Icons.Outlined.Videocam
    AttachmentKind.FILE -> Icons.Outlined.AttachFile
}

/** Attachment options. Picking one adds a labelled sample; no file is opened or read. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentSheet(
    onPick: (AttachmentKind) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                text = "Add to message",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Text(
                text = "Simulated: adds a sample attachment and does not access your files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
            )
            AttachmentKind.entries.forEach { kind ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable { onPick(kind) }
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(kind.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text(kind.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

/** Simulated voice input. The microphone is never used. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceInputSheet(
    onUseSample: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(32.dp))
                }
            }
            Text("Voice input (demo)", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "The microphone is not used in this build. Insert a sample transcript to see how " +
                    "voice input would fill the message box.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(onClick = onUseSample, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Use sample transcript")
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}
