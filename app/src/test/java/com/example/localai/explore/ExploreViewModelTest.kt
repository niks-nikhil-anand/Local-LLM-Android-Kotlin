package com.example.localai.explore

import com.example.localai.data.catalog.CatalogError
import com.example.localai.data.catalog.CatalogSort
import com.example.localai.data.model.Modality
import com.example.localai.ui.screens.explore.ExploreViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val catalog = FakeCatalog()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = ExploreViewModel(catalog, debounceMillis = 400)

    private fun ExploreViewModel.ids() = state.value.results.map { it.repoId.value }

    @Test
    fun initialLoadShowsLoadingThenRealResults() = runTest(dispatcher) {
        catalog.onSearch = { _, _ -> page("a/one", "b/two") }
        val vm = vm()
        runCurrent()
        assertEquals(listOf("a/one", "b/two"), vm.ids())
        assertFalse(vm.state.value.isLoading)
        assertEquals("", vm.state.value.resultsQuery)
        val (query, cursor) = catalog.searches.single()
        assertNull(cursor)
        assertTrue(query.ggufOnly)
        assertNull(query.pipelineTag) // "All" category: no task filter
        assertEquals(CatalogSort.DOWNLOADS, query.sort)
        assertEquals("", query.search)
    }

    @Test
    fun initialStateIsLoadingBeforeTheResponse() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        catalog.onSearch = { _, _ -> gate.await(); page("a/one") }
        val vm = vm()
        runCurrent()
        assertTrue(vm.state.value.isLoading)
        gate.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun typingIsDebouncedIntoOneRequest() = runTest(dispatcher) {
        val vm = vm()
        runCurrent()
        catalog.searches.clear()
        listOf("q", "qw", "qwe", "qwen").forEach {
            vm.onQueryChange(it)
            advanceTimeBy(100)
        }
        assertTrue(catalog.searches.isEmpty())
        advanceTimeBy(401)
        assertEquals(listOf("qwen"), catalog.searches.map { it.first.search })
    }

    @Test
    fun searchTextIsTrimmedAndLabelsTheResults() = runTest(dispatcher) {
        catalog.onSearch = { q, _ -> page("x/${q.search.ifEmpty { "all" }}") }
        val vm = vm()
        vm.onQueryChange("  llama ")
        advanceUntilIdle()
        assertEquals("llama", catalog.searches.last().first.search)
        assertEquals("llama", vm.state.value.resultsQuery)
        assertEquals(listOf("x/llama"), vm.ids())
    }

    @Test
    fun aSlowerOlderSearchNeverReplacesNewerResults() = runTest(dispatcher) {
        val slowGate = CompletableDeferred<Unit>()
        var slowCancelled = false
        catalog.onSearch = { q, _ ->
            when (q.search) {
                "slow" -> try {
                    slowGate.await(); page("old/slow")
                } finally {
                    slowCancelled = !slowGate.isCompleted
                }
                else -> page("new/${q.search.ifEmpty { "all" }}")
            }
        }
        val vm = vm()
        advanceUntilIdle()
        vm.onQueryChange("slow")
        advanceTimeBy(401)
        runCurrent()
        assertTrue(vm.state.value.isRefreshing)
        vm.onQueryChange("fast")
        advanceUntilIdle()
        slowGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("new/fast"), vm.ids())
        assertEquals("fast", vm.state.value.resultsQuery)
        assertTrue("obsolete request should be cancelled", slowCancelled)
    }

    @Test
    fun earlierResultsStayLabelledWithTheirOwnQueryWhileANewSearchRuns() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        catalog.onSearch = { q, _ -> if (q.search == "b") gate.await(); page("r/${q.search.ifEmpty { "all" }}") }
        val vm = vm()
        advanceUntilIdle()
        vm.onQueryChange("b")
        advanceTimeBy(401)
        runCurrent()
        // No flash to an empty list, and the old results are never presented as results for "b".
        assertEquals(listOf("r/all"), vm.ids())
        assertEquals("", vm.state.value.resultsQuery)
        assertTrue(vm.state.value.isRefreshing)
        assertFalse(vm.state.value.isLoading)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("r/b"), vm.ids())
    }

    @Test
    fun emptyResults() = runTest(dispatcher) {
        catalog.onSearch = { _, _ -> page() }
        val vm = vm()
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.results.isEmpty())
        assertNull(s.error)
        assertFalse(s.canLoadMore)
        assertFalse(s.isLoading)
    }

    @Test
    fun repositoryFailureThenRetry() = runTest(dispatcher) {
        catalog.onSearch = { _, _ -> failure(CatalogError.Offline) }
        val vm = vm()
        advanceUntilIdle()
        assertEquals(CatalogError.Offline, vm.state.value.error)
        assertTrue(vm.state.value.results.isEmpty())

        catalog.onSearch = { _, _ -> page("a/b") }
        vm.retry()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals(listOf("a/b"), vm.ids())
    }

    @Test
    fun rateLimitIsSurfacedWithItsDelay() = runTest(dispatcher) {
        catalog.onSearch = { _, _ -> failure(CatalogError.RateLimited(120)) }
        val vm = vm()
        advanceUntilIdle()
        assertEquals(CatalogError.RateLimited(120), vm.state.value.error)
    }

    @Test
    fun paginationAppendsAndDeduplicates() = runTest(dispatcher) {
        catalog.onSearch = { _, cursor ->
            when (cursor) {
                null -> page("a/1", "a/2", next = "c1")
                "c1" -> page("a/2", "a/3", next = null)
                else -> error("unexpected cursor $cursor")
            }
        }
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.state.value.canLoadMore)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("a/1", "a/2", "a/3"), vm.ids())
        assertFalse(vm.state.value.canLoadMore)
        vm.loadMore() // past the end: no request
        advanceUntilIdle()
        assertEquals(listOf<String?>(null, "c1"), catalog.searches.map { it.second })
    }

    @Test
    fun repeatedLoadMoreIssuesOneRequest() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        catalog.onSearch = { _, cursor -> if (cursor == null) page("a/1", next = "c1") else { gate.await(); page("a/2") } }
        val vm = vm()
        advanceUntilIdle()
        repeat(5) { vm.loadMore() }
        runCurrent()
        assertTrue(vm.state.value.isLoadingMore)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, catalog.searches.count { it.second == "c1" })
        assertEquals(listOf("a/1", "a/2"), vm.ids())
    }

    @Test
    fun aFailedPageKeepsLoadedResultsAndCanBeRetried() = runTest(dispatcher) {
        var failPage = true
        catalog.onSearch = { _, cursor ->
            when {
                cursor == null -> page("a/1", next = "c1")
                failPage -> failure(CatalogError.Server(503))
                else -> page("a/2")
            }
        }
        val vm = vm()
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(CatalogError.Server(503), vm.state.value.pageError)
        assertEquals(listOf("a/1"), vm.ids())
        assertNull(vm.state.value.error)

        failPage = false
        vm.retry()
        advanceUntilIdle()
        assertNull(vm.state.value.pageError)
        assertEquals(listOf("a/1", "a/2"), vm.ids())
    }

    @Test
    fun aNewSearchDiscardsAnInFlightPage() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        catalog.onSearch = { q, cursor ->
            if (cursor != null) { gate.await(); page("old/page2") } else page("${q.search.ifEmpty { "all" }}/1", next = "c1")
        }
        val vm = vm()
        advanceUntilIdle()
        vm.loadMore()
        runCurrent()
        vm.onSortSelect(CatalogSort.LIKES)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("all/1"), vm.ids())
        assertFalse(vm.state.value.isLoadingMore)
        assertEquals(CatalogSort.LIKES, catalog.searches.last().first.sort)
        assertNull(catalog.searches.last().second) // pagination restarted
    }

    @Test
    fun refreshKeepsResultsAndIgnoresRepeatedTaps() = runTest(dispatcher) {
        catalog.onSearch = { _, _ -> page("a/1") }
        val vm = vm()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        catalog.onSearch = { _, _ -> gate.await(); page("a/1", "a/2") }
        vm.refresh()
        runCurrent()
        assertTrue(vm.state.value.isRefreshing)
        assertEquals(listOf("a/1"), vm.ids()) // still shown while refreshing
        vm.refresh()
        vm.refresh()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, catalog.searches.size)
        assertEquals(listOf("a/1", "a/2"), vm.ids())
    }

    @Test
    fun aFailedRefreshKeepsEarlierResults() = runTest(dispatcher) {
        catalog.onSearch = { _, _ -> page("a/1") }
        val vm = vm()
        advanceUntilIdle()
        catalog.onSearch = { _, _ -> failure(CatalogError.Offline) }
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf("a/1"), vm.ids())
        assertEquals(CatalogError.Offline, vm.state.value.error)
        assertFalse(vm.state.value.isRefreshing)
    }

    @Test
    fun categoriesMapToRealHubTasksOnly() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onCategorySelect(Modality.TEXT)
        advanceUntilIdle()
        assertEquals("text-generation", catalog.searches.last().first.pipelineTag)

        val before = catalog.searches.size
        vm.onCategorySelect(Modality.CODING)
        advanceUntilIdle()
        assertEquals(before, catalog.searches.size) // no invented filter, no request
        assertTrue(vm.state.value.categoryUnsupported)
        assertTrue(vm.state.value.results.isEmpty())

        vm.clearSearchAndFilters()
        advanceUntilIdle()
        assertFalse(vm.state.value.categoryUnsupported)
        assertNull(vm.state.value.category)
        assertNull(catalog.searches.last().first.pipelineTag)
    }

    @Test
    fun sameSelectionsDoNotRequestAgain() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onSortSelect(CatalogSort.DOWNLOADS)
        vm.onCategorySelect(null)
        vm.onQueryChange("")
        advanceUntilIdle()
        assertEquals(1, catalog.searches.size)
    }

    @Test
    fun missingOptionalMetadataPassesThroughAsUnknown() = runTest(dispatcher) {
        catalog.onSearch = { _, _ -> page("solo") }
        val vm = vm()
        advanceUntilIdle()
        val item = vm.state.value.results.single()
        assertNull(item.author)
        assertNull(item.license)
        assertNull(item.downloads)
        assertNull(item.lastModified)
    }

    @Test
    fun pipelineMapping() {
        assertNull(ExploreViewModel.pipelineTagFor(null))
        assertNull(ExploreViewModel.pipelineTagFor(Modality.CODING))
        assertEquals("feature-extraction", ExploreViewModel.pipelineTagFor(Modality.EMBEDDINGS))
        assertEquals("automatic-speech-recognition", ExploreViewModel.pipelineTagFor(Modality.SPEECH))
    }
}
