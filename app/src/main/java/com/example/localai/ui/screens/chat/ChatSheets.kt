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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.localai.data.attachment.TextDocuments

/**
 * What can be attached. Text files open the system file picker; the other types are listed with
 * the reason they are not supported, and are not clickable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentSheet(
    onPickTextFile: () -> Unit,
    onDismiss: () -> Unit,
    attachedCount: Int = 0
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
                text = "Files are read on this device and given to the loaded model as reference text. " +
                    "They are never uploaded. Up to ${TextDocuments.MAX_PENDING} files, " +
                    "${TextDocuments.formatBytes(TextDocuments.MAX_FILE_BYTES.toLong())} each; long files are cut to fit the model's context.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
            )
            val full = attachedCount >= TextDocuments.MAX_PENDING
            AttachmentOption(
                icon = Icons.Outlined.Description,
                title = "Text file",
                detail = if (full) "${TextDocuments.MAX_PENDING} files already attached" else ".txt, .md, .json or .csv",
                onClick = if (full) null else onPickTextFile
            )
            AttachmentOption(Icons.Outlined.PictureAsPdf, "PDF", "Not supported yet: no reliable on-device PDF text extraction in this build")
            AttachmentOption(Icons.Outlined.Image, "Image", "Not supported: the on-device engine in this app reads text only")
            AttachmentOption(Icons.Outlined.Videocam, "Video", "Not supported: the on-device engine in this app reads text only")
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun AttachmentOption(icon: ImageVector, title: String, detail: String, onClick: (() -> Unit)? = null) {
    val enabled = onClick != null
    val tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What the voice sheet asks of the user before or while listening. */
sealed interface VoiceSheetMode {
    data class Unavailable(val reason: String) : VoiceSheetMode
    data object PermissionDenied : VoiceSheetMode

    /** The default service may send audio off the device; the user must agree first. */
    data class Disclosure(val serviceLabel: String) : VoiceSheetMode
    data class Active(val state: VoiceState) : VoiceSheetMode
}

/**
 * Voice input. Listening shows the live transcript; Done puts it in the message box to edit and
 * send. Nothing is sent automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceInputSheet(
    mode: VoiceSheetMode,
    onStart: () -> Unit,
    onDone: () -> Unit,
    onAcceptDisclosure: () -> Unit,
    onUseSystemService: () -> Unit,
    onOpenAppSettings: () -> Unit,
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
            val listening = mode is VoiceSheetMode.Active && mode.state is VoiceState.Listening
            Surface(
                shape = CircleShape,
                color = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (listening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val off = mode is VoiceSheetMode.Unavailable || mode is VoiceSheetMode.PermissionDenied
                    Icon(if (off) Icons.Outlined.MicOff else Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(32.dp))
                }
            }
            when (mode) {
                is VoiceSheetMode.Unavailable -> {
                    SheetText("Voice input unavailable", mode.reason)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                VoiceSheetMode.PermissionDenied -> {
                    SheetText(
                        "Microphone permission needed",
                        "Voice input needs access to the microphone. You can allow it in the app's system settings."
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Button(onClick = onOpenAppSettings) { Text("Open settings") }
                    }
                }
                is VoiceSheetMode.Disclosure -> {
                    SheetText(
                        "Use ${mode.serviceLabel} for voice input?",
                        "On-device speech recognition is not available for your language here. ${mode.serviceLabel} would transcribe what you " +
                            "say and may send the audio to its provider's servers. LocalAI does not record or keep audio, " +
                            "and the transcript goes only into your message box."
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Button(onClick = onAcceptDisclosure) { Text("Continue") }
                    }
                }
                is VoiceSheetMode.Active -> when (val s = mode.state) {
                    VoiceState.Idle -> {
                        SheetText("Voice input", "Speak after tapping Start. The transcript is placed in the message box for you to edit.")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = onDismiss) { Text("Cancel") }
                            Button(onClick = onStart) { Text("Start") }
                        }
                    }
                    is VoiceState.Listening -> {
                        SheetText("Listening…", s.partial.ifBlank { "Start speaking." })
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = onDismiss) { Text("Cancel") }
                            Button(onClick = onDone) { Text("Done") }
                        }
                    }
                    is VoiceState.Transcribing -> {
                        SheetText("Transcribing…", s.partial.ifBlank { " " })
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                    is VoiceState.Failed -> {
                        SheetText("Voice input stopped", s.message)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = onDismiss) { Text("Close") }
                            if (s.canUseSystemService) {
                                OutlinedButton(onClick = onUseSystemService) { Text("Use device service") }
                            }
                            Button(onClick = onStart) { Text("Try again") }
                        }
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun SheetText(title: String, message: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

/** Shown before web search is first switched on: exactly what is sent, and to whom. */
@Composable
fun WebSearchConsentDialog(provider: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Language, contentDescription = null) },
        title = { Text("Turn on web search?") },
        text = {
            Text(
                "While web search is on, a search query made from each message you send (question words removed) " +
                    "is sent to $provider over the internet. Your earlier messages, attached files and replies are " +
                    "never sent. Up to 3 results are given to the on-device model as reference and shown as sources; " +
                    "they may be wrong or out of date."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
