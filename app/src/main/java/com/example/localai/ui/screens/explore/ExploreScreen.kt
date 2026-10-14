package com.example.localai.ui.screens.explore

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.localai.data.model.Modality
import com.example.localai.data.model.SortOption
import com.example.localai.ui.components.AppTopBar
import com.example.localai.ui.components.CategoryFilterRow
import com.example.localai.ui.components.EmptyState
import com.example.localai.ui.components.InfoBanner
import com.example.localai.ui.components.ModelCard
import com.example.localai.ui.components.SearchBar
import com.example.localai.ui.state.LocalAIUiState

@Composable
fun ExploreScreen(
    state: LocalAIUiState,
    onQueryChange: (String) -> Unit,
    onCategorySelect: (Modality?) -> Unit,
    onSortSelect: (SortOption) -> Unit,
    onModelClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val models = state.visibleModels

    Column(modifier = modifier.fillMaxSize()) {
        AppTopBar(title = "Explore")
        SearchBar(
            query = state.searchQuery,
            onQueryChange = onQueryChange,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        CategoryFilterRow(
            selected = state.selectedCategory,
            onSelect = onCategorySelect,
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
                text = if (models.size == 1) "1 model" else "${models.size} models",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SortMenu(selected = state.sortOption, onSelect = onSortSelect)
        }
        if (models.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = "No models found",
                message = "Nothing matches your search or filter. Try another name, provider or task.",
                actionLabel = "Clear search and filters",
                onAction = {
                    onQueryChange("")
                    onCategorySelect(null)
                },
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "note") {
                    InfoBanner(
                        text = "Categories are filters only. A runtime is not available for every type, " +
                            "and all entries are demonstrations."
                    )
                }
                items(models, key = { it.id }) { model ->
                    ModelCard(
                        model = model,
                        onClick = { onModelClick(model.id) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}

@Composable
private fun SortMenu(selected: SortOption, onSelect: (SortOption) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(
        onClick = { expanded = true },
        modifier = Modifier.heightIn(min = 48.dp)
    ) {
        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text = "Sort: ${selected.label}", modifier = Modifier.padding(start = 8.dp))
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOption.entries.forEach { option ->
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
