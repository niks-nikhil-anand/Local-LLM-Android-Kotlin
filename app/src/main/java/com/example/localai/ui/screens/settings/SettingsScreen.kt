package com.example.localai.ui.screens.settings

import com.example.localai.ui.components.SkeletonGroup
import com.example.localai.ui.components.SkeletonText
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.localai.data.catalog.formatBytes
import com.example.localai.data.model.PreferredModelSize
import com.example.localai.ui.components.AppTopBar
import com.example.localai.ui.components.BannerTone
import com.example.localai.ui.components.ConfirmationDialog
import com.example.localai.ui.components.InfoBanner
import com.example.localai.ui.components.StorageOverviewCard
import com.example.localai.ui.components.SurfaceCard
import com.example.localai.ui.components.ThemeSelector
import com.example.localai.ui.state.ChatModelState
import com.example.localai.ui.theme.ThemeMode

/** Callbacks of the Settings screen. */
data class SettingsActions(
    val onThemeSelect: (ThemeMode) -> Unit,
    val onClearConversations: () -> Unit,
    val onOpenMyModels: () -> Unit,
    val onOpenLicences: () -> Unit
)

/** Display text for Settings, from [SettingsUiState] only. Pure, so it can be unit-tested. */
object SettingsText {
    const val LOADING = "Loading…"
    const val UNAVAILABLE = "Unavailable"

    fun loadedModel(state: SettingsUiState): String = when (val r = state.runtime) {
        is ChatModelState.Ready -> r.fileName
        is ChatModelState.Loading -> "Loading ${r.fileName}…"
        is ChatModelState.Unavailable -> "None (engine unavailable)"
        is ChatModelState.LoadFailed -> "None (last load failed)"
        ChatModelState.NoModel -> "None"
    }

    fun engine(state: SettingsUiState): String = when (val e = state.engine) {
        EngineAvailability.Checking -> LOADING
        EngineAvailability.Available -> "llama.cpp v0.6.0 (CPU) · available"
        is EngineAvailability.Unavailable -> "Unavailable: ${e.reason}"
    }

    fun downloadedModels(state: SettingsUiState): String {
        val inv = state.inventory ?: return if (state.inventoryError != null) UNAVAILABLE else LOADING
        val count = if (inv.modelCount == 1) "1 model" else "${inv.modelCount} models"
        return "$count · ${formatBytes(inv.modelBytes)}"
    }

    fun partialDownloads(state: SettingsUiState): String? {
        val inv = state.inventory ?: return null
        return if (inv.partialBytes > 0) "${formatBytes(inv.partialBytes)} in unfinished downloads" else null
    }

    fun ram(state: SettingsUiState): String {
        val d = state.device ?: return if (state.deviceMeasured) UNAVAILABLE else LOADING
        val total = d.totalRamBytes?.let { "${formatBytes(it)} total" }
        val available = d.availableRamBytes?.let { "${formatBytes(it)} available now" }
        return listOfNotNull(total, available).joinToString(" · ").ifEmpty { UNAVAILABLE }
    }

    fun version(state: SettingsUiState): String {
        val app = state.app ?: return UNAVAILABLE
        val name = app.versionName ?: return UNAVAILABLE
        return app.versionCode?.let { "$name ($it)" } ?: name
    }

    fun device(state: SettingsUiState): String {
        val app = state.app ?: return UNAVAILABLE
        return listOf(app.manufacturer, app.model).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { UNAVAILABLE }
    }

    fun android(state: SettingsUiState): String {
        val app = state.app ?: return UNAVAILABLE
        return "Android ${app.androidRelease} (API ${app.sdkInt})"
    }

    fun abi(state: SettingsUiState): String = state.app?.appAbi ?: UNAVAILABLE
}

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    state: SettingsUiState,
    actions: SettingsActions,
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
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("settings"),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Section("Appearance") {
                SurfaceCard {
                    ThemeSelector(selected = themeMode, onSelect = actions.onThemeSelect)
                }
            }

            Section("Model preferences") {
                SurfaceCard {
                    NavigationRow(
                        title = "Loaded model",
                        subtitle = SettingsText.loadedModel(state) + " · load or switch in My Models",
                        onClick = actions.onOpenMyModels
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Preferred model size", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Not available yet: model suggestions are not implemented, so this has no effect.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            PreferredModelSize.entries.forEachIndexed { index, size ->
                                SegmentedButton(
                                    selected = false,
                                    onClick = {},
                                    enabled = false,
                                    shape = SegmentedButtonDefaults.itemShape(index, PreferredModelSize.entries.size)
                                ) { Text(size.label) }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchRow(
                        title = "Automatic compatibility check",
                        subtitle = "Always on: every model's files are checked against this device before download and load.",
                        checked = true,
                        enabled = false,
                        onCheckedChange = {}
                    )
                }
            }

            Section("Storage") {
                val device = state.device
                val inventory = state.inventory
                val total = device?.totalStorageBytes
                val free = device?.availableStorageBytes
                if (inventory != null && total != null && free != null) {
                    val mb = 1024L * 1024
                    val models = inventory.modelBytes + inventory.partialBytes
                    StorageOverviewCard(
                        modelsUsedMb = models / mb,
                        otherUsedMb = ((total - free - models) / mb).coerceAtLeast(0),
                        totalMb = total / mb,
                        nearlyFull = free.toDouble() / total < 0.08,
                        title = "Storage (measured)"
                    )
                }
                SurfaceCard {
                    InfoRow("Downloaded models", SettingsText.downloadedModels(state))
                    SettingsText.partialDownloads(state)?.let {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        InfoRow("Unfinished downloads", it)
                    }
                }
                state.inventoryError?.let { InfoBanner(it, tone = BannerTone.ERROR) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Clear conversations") }
                    TextButton(onClick = actions.onOpenMyModels, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Manage models")
                    }
                }
            }

            Section("Privacy") {
                SurfaceCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("How this app uses your data", style = MaterialTheme.typography.titleSmall)
                        Principle("Chat runs on this device with llama.cpp. Prompts and replies are not sent to any server.")
                        Principle("The network is used only to browse Hugging Face and download models from it.")
                        Principle("Conversations are kept in memory and are cleared when the app is closed.")
                        Principle("Downloaded models stay in the app's private storage and are excluded from backups.")
                        Principle("The app contains no analytics or telemetry.")
                    }
                }
                InfoBanner(
                    text = "Browsing the catalogue and downloading need an internet connection. Chatting with a " +
                        "downloaded, loaded model does not.",
                    tone = BannerTone.INFO
                )
            }

            Section("About") {
                SurfaceCard {
                    InfoRow("Application", state.app?.appName ?: SettingsText.UNAVAILABLE)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoRow("Version", SettingsText.version(state))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoRow("Inference engine", SettingsText.engine(state))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoRow("Device", SettingsText.device(state))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoRow("System", SettingsText.android(state))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoRow("App ABI", SettingsText.abi(state))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoRow("Memory (RAM)", SettingsText.ram(state))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Model licences", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Each model has its own licence, shown on its Hugging Face page. Review it before use.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationRow(
                        title = "Open-source licences",
                        subtitle = "Notices for llama.cpp and the libraries this app includes",
                        onClick = actions.onOpenLicences
                    )
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (confirmClear) {
        ConfirmationDialog(
            title = "Clear conversations?",
            message = "This removes every conversation, including the sample ones. Downloaded models are not affected.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = {
                actions.onClearConversations()
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
private fun NavigationRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = false, onValueChange = { onClick() }, role = Role.Button)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch, enabled = enabled)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
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
        Spacer(Modifier.width(12.dp))
        if (value == SettingsText.LOADING) {
            SkeletonGroup("$label loading") { SkeletonText(MaterialTheme.typography.bodyMedium, 120.dp) }
        } else {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }
    }
}
