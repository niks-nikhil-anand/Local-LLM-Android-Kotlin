package com.example.localai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared layout for secondary screens (no bottom navigation): top bar with back, an optional
 * "simulated" banner and a scrolling list that clears the system navigation bar.
 */
@Composable
fun SecondaryScreen(
    title: String,
    onBack: () -> Unit,
    banner: String,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(modifier = modifier.fillMaxSize()) {
        AppTopBar(title = title, onBack = onBack)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { InfoBanner(banner) }
            content()
        }
    }
}
