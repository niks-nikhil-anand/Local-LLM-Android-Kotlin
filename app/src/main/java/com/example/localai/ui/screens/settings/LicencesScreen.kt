package com.example.localai.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.localai.data.LicenceNotices
import com.example.localai.ui.components.AppTopBar
import com.example.localai.ui.components.EmptyState
import com.example.localai.ui.components.SkeletonGroup
import com.example.localai.ui.components.SkeletonText
import androidx.compose.foundation.layout.padding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loading → text or error. */
sealed interface LicenceText {
    data object Loading : LicenceText
    data class Loaded(val text: String) : LicenceText
    data class Failed(val message: String) : LicenceText
}

/** Splits the notices into paragraphs for lazy display; joining them with "\n\n" restores the text. */
fun licenceParagraphs(text: String): List<String> = text.split("\n\n")

/** Shows the third-party notices bundled in the APK, read from assets on a background thread. */
@Composable
fun LicencesRoute(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val state by produceState<LicenceText>(LicenceText.Loading) {
        value = withContext(Dispatchers.IO) {
            LicenceNotices.load(context).fold(
                onSuccess = { LicenceText.Loaded(it) },
                onFailure = { LicenceText.Failed(it.message ?: it.javaClass.simpleName) }
            )
        }
    }
    LicencesScreen(state, onBack)
}

@Composable
fun LicencesScreen(state: LicenceText, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(modifier = modifier.fillMaxSize()) {
        AppTopBar(title = "Open-source licences", onBack = onBack)
        when (state) {
            LicenceText.Loading -> SkeletonGroup(
                "Loading licence notices",
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("licences_loading")
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Paragraph-shaped bars only; no licence text is shown until the real file is read.
                    listOf(3, 2, 4, 2).forEach { lines ->
                        Column {
                            repeat(lines) { i ->
                                SkeletonText(MaterialTheme.typography.bodySmall, if (i == lines - 1) 180.dp else 300.dp)
                            }
                        }
                    }
                }
            }
            is LicenceText.Failed -> EmptyState(
                icon = Icons.Outlined.ErrorOutline,
                title = "Licence notices could not be loaded",
                message = "The bundled file ${LicenceNotices.ASSET_PATH} is missing or unreadable (${state.message}).",
                modifier = Modifier.weight(1f)
            )
            is LicenceText.Loaded -> SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("licences"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + bottomInset),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(licenceParagraphs(state.text)) { _, paragraph ->
                        Text(paragraph, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
