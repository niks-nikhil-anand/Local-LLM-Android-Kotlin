package com.example.localai.ui.screens.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.localai.data.catalog.CatalogSort
import com.example.localai.data.model.Modality
import com.example.localai.ui.components.AppTopBar
import com.example.localai.ui.components.BannerTone
import com.example.localai.ui.components.CategoryFilterRow
import com.example.localai.ui.components.EmptyState
import com.example.localai.ui.components.InfoBanner
import com.example.localai.ui.components.SkeletonCardList
import com.example.localai.ui.components.SkeletonModelCard
import com.example.localai.ui.components.SearchBar

/** Callbacks of the Explore screen, grouped to keep the signature short. */
data class ExploreActions(
    val onQueryChange: (String) -> Unit,
    val onCategorySelect: (Modality?) -> Unit,
    val onSortSelect: (CatalogSort) -> Unit,
    val onRefresh: () -> Unit,
    val onRetry: () -> Unit,
    val onLoadMore: () -> Unit,
    val onClearFilters: () -> Unit,
    val onModelClick: (String) -> Unit
)

@Composable
fun ExploreScreen(
    state: ExploreUiState,
    actions: ExploreActions,
    modifier: Modifier = Modifier
) {
    val models = state.results

    Column(modifier = modifier.fillMaxSize()) {
        AppTopBar(
            title = "Explore",
            actions = {
                IconButton(onClick = actions.onRefresh, enabled = !state.isLoading && !state.isRefreshing) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh results")
                }
            }
        )
        SearchBar(
            query = state.query,
            onQueryChange = actions.onQueryChange,
            placeholder = "Search Hugging Face GGUF models",
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        CategoryFilterRow(
            selected = state.category,
            onSelect = actions.onCategorySelect,
            modifier = Modifier.padding(top = 8.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = resultsLabel(state),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            SortMenu(selected = state.sort, onSelect = actions.onSortSelect)
        }
        if (state.isRefreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("explore_refreshing"))
        }

        when {
            state.isLoading -> SkeletonCardList(
                description = "Loading models from Hugging Face",
                count = 3,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp)
                    .testTag("explore_loading")
            ) { SkeletonModelCard() }
            state.categoryUnsupported -> EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = "No Hugging Face category",
                message = "Hugging Face has no separate category for this. Choose All and search for a name instead, for example \"coder\".",
                actionLabel = "Clear search and filters",
                onAction = actions.onClearFilters,
                modifier = Modifier.weight(1f)
            )
            models.isEmpty() && state.error != null -> EmptyState(
                icon = Icons.Outlined.CloudOff,
                title = "Could not load models",
                message = state.error.userMessage(),
                actionLabel = "Retry",
                onAction = actions.onRetry,
                modifier = Modifier.weight(1f)
            )
            models.isEmpty() -> EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = "No models found",
                message = "No GGUF repositories on Hugging Face match this search and filter.",
                actionLabel = "Clear search and filters",
                onAction = actions.onClearFilters,
                modifier = Modifier.weight(1f)
            )
            else -> ResultsList(state, actions, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ResultsList(state: ExploreUiState, actions: ExploreActions, modifier: Modifier) {
    val listState = rememberLazyListState()
    // Ask for the next page when the last few cards come into view; the ViewModel ignores repeats.
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, state.canLoadMore, state.pageError) {
        if (nearEnd && state.canLoadMore && state.pageError == null) actions.onLoadMore()
    }

    LazyColumn(
        state = listState,
        modifier = modifier.testTag("explore_results"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "note") {
            InfoBanner(
                text = "Live results from Hugging Face. Open a model to check its files against this device. " +
                    "Downloaded models run on this device with llama.cpp once loaded in My Models."
            )
        }
        if (state.error != null) {
            item(key = "refresh_error") {
                Column {
                    InfoBanner(text = "Refresh failed: ${state.error.userMessage()} Showing earlier results.", tone = BannerTone.WARNING)
                    TextButton(onClick = actions.onRetry) { Text("Retry") }
                }
            }
        }
        items(state.results, key = { it.repoId.value }) { model ->
            HfModelCard(
                model = model,
                onClick = { actions.onModelClick(model.repoId.value) },
                modifier = Modifier.animateItem()
            )
        }
        item(key = "footer") {
            PageFooter(state, actions.onRetry)
        }
    }
}

@Composable
private fun PageFooter(state: ExploreUiState, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        when {
            state.isLoadingMore -> CircularProgressIndicator(modifier = Modifier.size(28.dp).testTag("explore_loading_more"))
            state.pageError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Could not load more: ${state.pageError.userMessage()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            !state.canLoadMore -> Text(
                text = "End of results",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun resultsLabel(state: ExploreUiState): String {
    val count = state.results.size
    val shown = when {
        state.isLoading -> "Searching…"
        count == 1 -> "1 model"
        else -> "$count models" + if (state.canLoadMore) "+" else ""
    }
    val q = state.resultsQuery
    return if (!state.isLoading && !q.isNullOrEmpty()) "$shown for \"$q\"" else shown
}

@Composable
private fun SortMenu(selected: CatalogSort, onSelect: (CatalogSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(
        onClick = { expanded = true },
        modifier = Modifier.heightIn(min = 48.dp)
    ) {
        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text = "Sort: ${selected.label}", modifier = Modifier.padding(start = 8.dp))
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CatalogSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    leadingIcon = {
                        if (option == selected) {
                            Icon(Icons.Filled.Check, contentDescription = "Selected")
                        }
                    }
                )
            }
        }
    }
}
