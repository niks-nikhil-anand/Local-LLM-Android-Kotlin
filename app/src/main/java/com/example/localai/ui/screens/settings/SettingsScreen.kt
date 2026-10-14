package com.example.localai.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.localai.data.mock.MockModelRepository
import com.example.localai.data.model.PreferredModelSize
import com.example.localai.ui.components.AppTopBar
import com.example.localai.ui.components.BannerTone
import com.example.localai.ui.components.ConfirmationDialog
import com.example.localai.ui.components.InfoBanner
import com.example.localai.ui.components.StorageOverviewCard
import com.example.localai.ui.components.SurfaceCard
import com.example.localai.ui.components.ThemeSelector
import com.example.localai.ui.state.LocalAISettingsActions
import com.example.localai.ui.state.LocalAIUiState

@Composable
fun SettingsScreen(
    state: LocalAIUiState,
    actions: LocalAISettingsActions,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        AppTopBar(title = "Settings", onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Section("Appearance") {
                SurfaceCard {
                    ThemeSelector(selected = state.themeMode, onSelect = actions.onThemeSelect)
                }
            }

            Section("Model preferences") {
                SurfaceCard {
                    DefaultModelRow(
                        selectedId = state.defaultModelId,
                        onSelect = actions.onDefaultModelSelect
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Preferred model size", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Used to suggest models. Stored locally in this demo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            PreferredModelSize.entries.forEachIndexed { index, size ->
                                SegmentedButton(
                                    selected = state.preferredSize == size,
                                    onClick = { actions.onPreferredSizeSelect(size) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index, PreferredModelSize.entries.size
                                    )
                                ) { Text(size.label) }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchRow(
                        title = "Automatic compatibility check",
                        subtitle = "Show a compatibility estimate for each model.",
                        checked = state.autoCompatibilityCheck,
                        onCheckedChange = actions.onAutoCompatibilityChange
                    )
                }
            }

            Section("Storage") {
                StorageOverviewCard(
                    modelsUsedMb = state.modelsUsedMb,
                    otherUsedMb = state.device.otherUsedStorageMb,
                    totalMb = state.device.totalStorageMb,
                    nearlyFull = state.isStorageNearlyFull
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Clear demo data") }
                    TextButton(
                        onClick = actions.onRestoreDemoData,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Restore sample data") }
                }
            }

            Section("Privacy") {
                SurfaceCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Product principles",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Principle("Local inference for supported models.")
                        Principle("Explicit disclosure before any optional cloud inference.")
                        Principle("You stay in control of the models you download.")
                    }
                }
                InfoBanner(
                    text = "These describe the intended product. This demo build does not run models, " +
                        "download files or make encryption or offline guarantees.",
                    tone = BannerTone.INFO
                )
            }

            Section("About") {
                SurfaceCard {
                    InfoRow("Application", "LocalAI")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoRow("Version", "1.0 (placeholder)")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Open-source model licenses", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Listed models are demonstration entries. Each model has its own license, " +
                                "such as Apache-2.0, MIT or a community license. Review it before use.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    LinkRow("Documentation") { actions.onShowMessage("Documentation link is not set up yet.") }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    LinkRow("Source code") { actions.onShowMessage("Source link is not set up yet.") }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (confirmClear) {
        ConfirmationDialog(
            title = "Clear demo data?",
            message = "This empties the simulated model list and all demo conversations. " +
                "No real files are affected.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = {
                actions.onClearDemoData()
                confirmClear = false
            },
            onDismiss = { confirmClear = false }
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() }
        )
        content()
    }
}

@Composable
private fun DefaultModelRow(selectedId: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = MockModelRepository.findModel(selectedId)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = expanded, onValueChange = { expanded = it }, role = Role.DropdownList)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Default model", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = selected?.name ?: "None",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MockModelRepository.chatModels.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.name) },
                    onClick = {
                        onSelect(model.id)
                        expanded = false
                    },
                    leadingIcon = {
                        if (model.id == selectedId) {
                            Icon(Icons.Filled.Check, contentDescription = "Selected")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Principle(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}
