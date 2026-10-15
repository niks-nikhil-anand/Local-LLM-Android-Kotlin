package com.example.localai.ui.screens.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.localai.data.catalog.CatalogError
import com.example.localai.data.catalog.CatalogQuery
import com.example.localai.data.catalog.CatalogResult
import com.example.localai.data.catalog.CatalogSort
import com.example.localai.data.catalog.HfModelSummary
import com.example.localai.data.catalog.ModelCatalogRepository
import com.example.localai.data.model.Modality
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State of the real Hugging Face catalog shown in Explore. */
data class ExploreUiState(
    /** Text in the search field; may be ahead of [resultsQuery] while the debounce runs. */
    val query: String = "",
    val category: Modality? = null,
    val sort: CatalogSort = CatalogSort.DOWNLOADS,
    val results: List<HfModelSummary> = emptyList(),
    /** The trimmed search the current [results] belong to; null before the first result. */
    val resultsQuery: String? = null,
    /** First page in flight with nothing to show yet. */
    val isLoading: Boolean = false,
    /** First page in flight while the previous results stay visible (refresh). */
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** The first page failed. Shown full-screen when [results] is empty, as a banner otherwise. */
    val error: CatalogError? = null,
    /** A later page failed; loaded results are kept. */
    val pageError: CatalogError? = null,
    val canLoadMore: Boolean = false,
    /** The selected category has no Hugging Face task to filter on. */
    val categoryUnsupported: Boolean = false
)

/**
 * Searches the Hub through [ModelCatalogRepository]. Typing is debounced; every new search
 * cancels the previous one and a generation counter discards any late response, so results
 * always belong to the latest query. Created once per Explore destination, so rotation and tab
 * switches do not repeat requests.
 */
class ExploreViewModel(
    private val catalog: ModelCatalogRepository,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS
) : ViewModel() {

    private val _state = MutableStateFlow(ExploreUiState())
    val state: StateFlow<ExploreUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var pageJob: Job? = null
    private var generation = 0
    private var nextCursor: String? = null
    private var activeQuery: CatalogQuery? = null

    init {
        search(keepResults = false)
    }

    fun onQueryChange(text: String) {
        if (text == _state.value.query) return
        _state.update { it.copy(query = text) }
        // Earlier results stay visible (still labelled with their own query) until the new ones
        // arrive, so typing does not flash the list away.
        search(keepResults = _state.value.results.isNotEmpty(), debounce = true)
    }

    fun onCategorySelect(category: Modality?) {
        if (category == _state.value.category) return
        _state.update { it.copy(category = category) }
        search(keepResults = false)
    }

    fun onSortSelect(sort: CatalogSort) {
        if (sort == _state.value.sort) return
        _state.update { it.copy(sort = sort) }
        search(keepResults = false)
    }

    /** Re-runs the current search keeping the visible results. Ignored while a search runs. */
    fun refresh() {
        val s = _state.value
        if (s.isLoading || s.isRefreshing) return
        search(keepResults = s.results.isNotEmpty())
    }

    fun retry() {
        val s = _state.value
        if (s.pageError != null) loadMore() else search(keepResults = s.results.isNotEmpty())
    }

    fun clearSearchAndFilters() {
        _state.update { it.copy(query = "", category = null) }
        search(keepResults = false)
    }

    /** Requests the next page. Safe to call repeatedly: one request at a time, none past the end. */
    fun loadMore() {
        val s = _state.value
        val cursor = nextCursor
        val query = activeQuery
        if (cursor == null || query == null || s.isLoadingMore || s.isLoading || s.isRefreshing) return
        val gen = generation
        _state.update { it.copy(isLoadingMore = true, pageError = null) }
        pageJob = viewModelScope.launch {
            val result = catalog.search(query, cursor)
            if (gen != generation) return@launch
            when (result) {
                is CatalogResult.Success -> {
                    nextCursor = result.value.nextCursor
                    _state.update { st ->
                        val known = st.results.mapTo(HashSet()) { it.repoId }
                        st.copy(
                            results = st.results + result.value.items.filter { known.add(it.repoId) },
                            isLoadingMore = false,
                            canLoadMore = nextCursor != null
                        )
                    }
                }
                is CatalogResult.Failure -> _state.update { it.copy(isLoadingMore = false, pageError = result.error) }
            }
        }
    }

    private fun search(keepResults: Boolean, debounce: Boolean = false) {
        searchJob?.cancel()
        pageJob?.cancel()
        val gen = ++generation
        searchJob = viewModelScope.launch {
            if (debounce) delay(debounceMillis)
            val s = _state.value
            val pipelineTag = pipelineTagFor(s.category)
            if (s.category != null && pipelineTag == null) {
                activeQuery = null
                nextCursor = null
                _state.update {
                    it.copy(
                        results = emptyList(), resultsQuery = s.query.trim(), isLoading = false, isRefreshing = false,
                        isLoadingMore = false, error = null, pageError = null, canLoadMore = false, categoryUnsupported = true
                    )
                }
                return@launch
            }
            val query = CatalogQuery(search = s.query.trim(), pipelineTag = pipelineTag, ggufOnly = true, sort = s.sort)
            _state.update {
                if (keepResults) {
                    it.copy(isRefreshing = true, isLoadingMore = false, error = null, pageError = null, categoryUnsupported = false)
                } else {
                    it.copy(
                        results = emptyList(), resultsQuery = null, isLoading = true, isRefreshing = false,
                        isLoadingMore = false, error = null, pageError = null, canLoadMore = false, categoryUnsupported = false
                    )
                }
            }
            val result = catalog.search(query)
            if (gen != generation) return@launch
            when (result) {
                is CatalogResult.Success -> {
                    activeQuery = query
                    nextCursor = result.value.nextCursor
                    _state.update {
                        it.copy(
                            results = result.value.items.distinctBy { item -> item.repoId },
                            resultsQuery = query.search,
                            isLoading = false,
                            isRefreshing = false,
                            canLoadMore = nextCursor != null
                        )
                    }
                }
                is CatalogResult.Failure -> _state.update {
                    // A failed refresh keeps the old results and their cursor.
                    it.copy(isLoading = false, isRefreshing = false, error = result.error)
                }
            }
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 400L

        /**
         * Hub task (pipeline_tag) for each Explore category. Coding has no task of its own on the
         * Hub, so it is reported as unsupported instead of being approximated.
         */
        fun pipelineTagFor(category: Modality?): String? = when (category) {
            null, Modality.CODING -> null
            Modality.TEXT -> "text-generation"
            Modality.SPEECH -> "automatic-speech-recognition"
            Modality.IMAGE -> "text-to-image"
            Modality.VIDEO -> "text-to-video"
            Modality.EMBEDDINGS -> "feature-extraction"
        }
    }
}
