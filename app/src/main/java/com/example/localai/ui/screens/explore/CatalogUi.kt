package com.example.localai.ui.screens.explore

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import com.example.localai.data.catalog.CatalogError
import com.example.localai.data.catalog.CatalogSort
import com.example.localai.data.compat.Verdict
import com.example.localai.ui.components.ModelMetadataChip
import com.example.localai.ui.theme.AppTheme

/** Plain-language explanation of a catalog failure. */
fun CatalogError.userMessage(): String = when (this) {
    CatalogError.Offline -> "Could not reach Hugging Face. Check your internet connection and try again."
    is CatalogError.RateLimited ->
        "Hugging Face is limiting requests right now" + (retryAfterSeconds?.let { ". Try again in about $it s." } ?: ". Try again shortly.")
    CatalogError.AccessRequired ->
        "This repository requires access on Hugging Face (gated or private), or it does not exist. This app does not sign in."
    CatalogError.NotFound -> "Not found on Hugging Face."
    is CatalogError.Server -> "Hugging Face returned an error (HTTP $httpCode). Try again later."
    is CatalogError.Malformed -> "Hugging Face sent a response this app could not read."
}

val CatalogSort.label: String
    get() = when (this) {
        CatalogSort.DOWNLOADS -> "Most downloads"
        CatalogSort.LIKES -> "Most likes"
        CatalogSort.RECENT -> "Recently updated"
    }

val Verdict.label: String
    get() = when (this) {
        Verdict.MEETS_REQUIREMENTS -> "Passes checks (unverified)"
        Verdict.INSUFFICIENT_RESOURCES -> "Not enough resources"
        Verdict.UNKNOWN -> "Compatibility unknown"
        Verdict.INCOMPATIBLE -> "Incompatible"
    }

/** Colored chip for a verdict. "Passes checks" stays explicitly unverified: no engine exists yet. */
@Composable
fun VerdictChip(verdict: Verdict, modifier: Modifier = Modifier) {
    val extended = AppTheme.extendedColors
    val colors = MaterialTheme.colorScheme
    val (icon, container, content) = when (verdict) {
        Verdict.MEETS_REQUIREMENTS -> Triple(Icons.Outlined.CheckCircle, extended.successContainer, extended.onSuccessContainer)
        Verdict.INSUFFICIENT_RESOURCES -> Triple(Icons.Outlined.Memory, extended.warningContainer, extended.onWarningContainer)
        Verdict.UNKNOWN -> Triple(Icons.AutoMirrored.Outlined.HelpOutline, extended.infoContainer, extended.onInfoContainer)
        Verdict.INCOMPATIBLE -> Triple(Icons.Outlined.Block, colors.errorContainer, colors.onErrorContainer)
    }
    ModelMetadataChip(text = verdict.label, icon = icon, containerColor = container, contentColor = content, modifier = modifier)
}

/** 1234567 -> "1.2M". Only formats real counts; null stays null. */
fun formatCount(value: Long?): String? = when {
    value == null -> null
    value >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

/** "2024-09-20T06:20:24.000Z" -> "2024-09-20"; anything unparseable is shown as given. */
fun formatHubDate(value: String?): String? = value?.takeIf { it.isNotBlank() }?.let {
    if (Regex("""^\d{4}-\d{2}-\d{2}""").containsMatchIn(it)) it.take(10) else it
}
