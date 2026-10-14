package com.example.localai.ui.screens.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.localai.data.model.Compatibility
import com.example.localai.data.model.InstallState
import com.example.localai.data.model.ModelInfo
import com.example.localai.data.model.formatSizeMb
import com.example.localai.ui.components.AppTopBar
import com.example.localai.ui.components.CompatibilityBadge
import com.example.localai.ui.components.EmptyState
import com.example.localai.ui.components.InfoBanner
import com.example.localai.ui.components.InstallStatusBadge
import com.example.localai.ui.components.ModalityIconBadge
import com.example.localai.ui.components.ModelMetadataChip
import com.example.localai.ui.components.SurfaceCard
import com.example.localai.ui.components.icon
import com.example.localai.ui.theme.AppTheme

private enum class PrimaryAction(val label: String) {
    PREVIEW("Preview Model"),
    OPEN_CHAT("Open Chat"),
    VIEW_CAPABILITIES("View Capabilities")
}

private fun primaryActionFor(model: ModelInfo, state: InstallState): PrimaryAction = when {
    !model.supportsChat || model.compatibility == Compatibility.UNSUPPORTED -> PrimaryAction.VIEW_CAPABILITIES
    state == InstallState.Installed -> PrimaryAction.OPEN_CHAT
    else -> PrimaryAction.PREVIEW
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelDetailsScreen(
    model: ModelInfo?,
    installState: InstallState?,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onSimulateDownload: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        AppTopBar(title = "Model details", onBack = onBack)

        if (model == null || installState == null) {
            EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = "Model not found",
                message = "This entry is not in the demo catalogue.",
                actionLabel = "Go back",
                onAction = onBack
            )
            return@Column
        }

        var showCapabilities by rememberSaveable { mutableStateOf(false) }
        val action = primaryActionFor(model, installState)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModalityIconBadge(model.modality, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "by ${model.provider}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompatibilityBadge(model.compatibility)
                if (installState != InstallState.NotInstalled) InstallStatusBadge(installState)
                if (model.isDemo) {
                    ModelMetadataChip(
                        text = "Demo entry",
                        containerColor = AppTheme.extendedColors.infoContainer,
                        contentColor = AppTheme.extendedColors.onInfoContainer
                    )
                }
            }
            if (model.isDemo) {
                InfoBanner(
                    text = "Demonstration entry. Details are illustrative, and nothing can be " +
                        "downloaded or run in this build."
                )
            }
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            DetailSection("Specifications") {
                SpecRow("Modality", model.modality.label)
                SpecRow("Task", model.task)
                SpecRow("Parameters", model.parameters)
                SpecRow("File size", formatSizeMb(model.sizeMb))
                SpecRow("Format", model.format)
                SpecRow("License", model.license)
                SpecRow("Supported runtime", model.runtime)
                SpecRow("Hardware fit", "${model.compatibility.label}. ${model.compatibility.description}", last = true)
            }

            DetailSection("Advantages") {
                BulletList(model.advantages, Icons.Filled.Check, AppTheme.extendedColors.success)
            }
            DetailSection("Known limitations") {
                BulletList(model.limitations, Icons.Outlined.Warning, AppTheme.extendedColors.warning)
            }
            DetailSection("Example tasks") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    model.exampleTasks.forEach { task ->
                        Text(
                            text = "• $task",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            when (action) {
                                PrimaryAction.VIEW_CAPABILITIES -> showCapabilities = true
                                PrimaryAction.PREVIEW, PrimaryAction.OPEN_CHAT -> onOpenChat(model.id)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                    ) { Text(action.label) }
                    if (installState == InstallState.NotInstalled &&
                        model.compatibility != Compatibility.UNSUPPORTED
                    ) {
                        OutlinedButton(
                            onClick = { onSimulateDownload(model.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) { Text("Simulate download (demo)") }
                    }
                }
            }
        }

        if (showCapabilities) {
            CapabilitiesDialog(model = model, onDismiss = { showCapabilities = false })
        }
    }
}

@Composable
private fun CapabilitiesDialog(model: ModelInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null) },
        title = { Text("Capabilities") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (model.compatibility == Compatibility.UNSUPPORTED) {
                        "${model.name} has no supported runtime yet, so it cannot be run on this device."
                    } else {
                        "${model.name} is a ${model.modality.label.lowercase()} model without a chat " +
                            "interface. Running it is not available in this build."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Intended tasks",
                    style = MaterialTheme.typography.titleSmall
                )
                model.exampleTasks.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() }
        )
        SurfaceCard { content() }
    }
}

@Composable
private fun SpecRow(label: String, value: String, last: Boolean = false) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(0.4f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(0.6f)
            )
        }
        if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun BulletList(items: List<String>, icon: ImageVector, tint: Color) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
