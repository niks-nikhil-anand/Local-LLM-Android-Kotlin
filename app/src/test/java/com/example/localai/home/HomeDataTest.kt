package com.example.localai.home

import com.example.localai.data.download.DownloadController
import com.example.localai.data.download.InventorySummary
import com.example.localai.data.download.LocalModelInventory
import com.example.localai.data.inference.ModelSessionManager
import com.example.localai.data.local.LocalModelEntity
import com.example.localai.explore.FakeDeviceProvider
import com.example.localai.explore.FakeDownloads
import com.example.localai.explore.GB
import com.example.localai.gguf.GgufBuilder
import com.example.localai.inference.FakeEngine
import com.example.localai.ui.screens.home.HomeText
import com.example.localai.ui.screens.home.HomeUiState
import com.example.localai.ui.screens.home.HomeViewModel
import com.example.localai.ui.screens.mymodels.MyModelsViewModel
import com.example.localai.ui.state.ChatModelState
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class HomeDataTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val downloads = FakeDownloads()
    private val device = FakeDeviceProvider()
    private val engine = FakeEngine()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A completed record whose file exists with exactly [size] bytes (a real GGUF when [gguf]). */
    private fun completed(name: String, size: Int = 1000, gguf: Boolean = false): LocalModelEntity {
        val bytes = if (gguf) GgufBuilder.tinyLlama().file() else ByteArray(size)
        val file = temp.newFile(name).apply { writeBytes(bytes) }
        val row = row(name, "COMPLETED", bytes.size.toLong())
        downloads.files[row.id] = file
        return row
    }

    private fun row(name: String, state: String, bytes: Long, expected: Long? = bytes) = LocalModelEntity(
        id = "o/r/main/$name", repoId = "o/r", revision = "main", path = name, expectedSizeBytes = expected,
        expectedSha256 = null, downloadedBytes = bytes, state = state, failureReason = null, updatedAtMillis = 0
    )

    private fun summary(vararg rows: LocalModelEntity) = InventorySummary.of(rows.toList(), downloads::localFile)

    private fun session() = ModelSessionManager(engine, downloads, device, clock = { 0L }, ioDispatcher = dispatcher)

    private fun home(inventory: LocalModelInventory = LocalModelInventory(downloads, dispatcher), session: ModelSessionManager? = session()) =
        HomeViewModel(inventory, session, device)

    // ---- Counting rules ----

    @Test
    fun noDownloadedModelsCountsZero() {
        val s = summary()
        assertEquals(0, s.modelCount)
        assertEquals(0L, s.modelBytes)
    }

    @Test
    fun threeValidModelsCountThreeWithTheirMeasuredSizes() {
        val s = summary(completed("a.gguf", 100), completed("b.gguf", 200), completed("c.gguf", 300))
        assertEquals(3, s.modelCount)
        assertEquals(600L, s.modelBytes)
    }

    @Test
    fun catalogueOnlyAndIncompleteDownloadsAreNotUsableModels() {
        // A catalogue entry has no record at all; these records are unfinished.
        val s = summary(
            row("q.gguf", "QUEUED", 0),
            row("d.gguf", "DOWNLOADING", 500, expected = 1000),
            row("v.gguf", "VERIFYING", 1000),
            row("p.gguf", "PAUSED", 300, expected = 1000),
            row("f.gguf", "FAILED", 200, expected = 1000),
            row("x.gguf", "CANCELLED", 0, expected = 1000)
        )
        assertEquals(0, s.modelCount)
        assertEquals(0L, s.modelBytes)
        assertEquals(3, s.activeDownloads)
        assertEquals(500L + 1000 + 300 + 200, s.partialBytes) // reported separately, never as model storage
    }

    @Test
    fun missingOrResizedFilesAreNotCounted() {
        val missing = completed("m.gguf").also { downloads.files[it.id]!!.delete() }
        val resized = completed("r.gguf").also { downloads.files[it.id]!!.appendBytes(ByteArray(5)) }
        val good = completed("g.gguf", 700)
        val s = summary(missing, resized, good)
        assertEquals(1, s.modelCount)
        assertEquals(700L, s.modelBytes)
        assertEquals(setOf(missing.id, resized.id), s.damagedIds.toSet())
    }

    @Test
    fun theSameRecordIsNeverCountedTwice() {
        val a = completed("a.gguf")
        assertEquals(1, summary(a, a).modelCount)
    }

    // ---- Home view model ----

    @Test
    fun homeStartsLoadingWithoutAnyNumbers() = runTest(dispatcher) {
        val vm = home()
        val s = vm.state.value
        assertTrue(s.loading)
        assertEquals(HomeText.LOADING, HomeText.models(s))
        assertEquals(HomeText.LOADING, HomeText.storage(s))
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertEquals("None", HomeText.models(vm.state.value))
        assertEquals("Models 0 B · 40.0 GB free", HomeText.storage(vm.state.value))
    }

    @Test
    fun twoDownloadsShowTwoAndDeletingOneShowsOne() = runTest(dispatcher) {
        val a = completed("a.gguf", 2_000)
        val b = completed("b.gguf", 3_000)
        downloads.rows.value = listOf(a, b)
        val vm = home()
        advanceUntilIdle()
        assertEquals("2", HomeText.models(vm.state.value))
        assertEquals(5_000L, vm.state.value.inventory!!.modelBytes)

        // Delete b: the record goes and the file goes.
        downloads.files[b.id]!!.delete()
        downloads.rows.value = listOf(a)
        advanceUntilIdle()
        assertEquals("1", HomeText.models(vm.state.value))
        assertEquals(2_000L, vm.state.value.inventory!!.modelBytes)
        assertEquals(listOf("a.gguf"), vm.state.value.inventory!!.models.map { it.fileName })
    }

    @Test
    fun aCompletedDownloadAppearsWhenItFinishes() = runTest(dispatcher) {
        downloads.rows.value = listOf(row("n.gguf", "DOWNLOADING", 400, expected = 1_000))
        val vm = home()
        advanceUntilIdle()
        assertEquals("None · 1 downloading", HomeText.models(vm.state.value))

        val done = completed("n.gguf", 1_000)
        downloads.rows.value = listOf(done)
        advanceUntilIdle()
        assertEquals("1", HomeText.models(vm.state.value))
        assertEquals(1_000L, vm.state.value.inventory!!.modelBytes)
    }

    @Test
    fun aFileDeletedOutsideTheAppDisappearsOnRefresh() = runTest(dispatcher) {
        val a = completed("a.gguf", 2_000)
        downloads.rows.value = listOf(a)
        val vm = home()
        advanceUntilIdle()
        assertEquals("1", HomeText.models(vm.state.value))
        downloads.files[a.id]!!.delete() // records unchanged; only the filesystem changed
        vm.refresh()
        advanceUntilIdle()
        assertEquals("None · 1 need attention", HomeText.models(vm.state.value))
        assertEquals(0L, vm.state.value.inventory!!.modelBytes)
    }

    @Test
    fun downloadedModelsAreNotALoadedModel() = runTest(dispatcher) {
        val a = completed("a.gguf", gguf = true)
        downloads.rows.value = listOf(a)
        val s = session()
        val vm = home(session = s)
        advanceUntilIdle()
        assertEquals(ChatModelState.NoModel, vm.state.value.runtime)
        assertEquals("No model loaded", HomeText.runtime(vm.state.value.runtime))
        assertNull(vm.state.value.loadedRecordId)

        assertTrue(s.load(a))
        advanceUntilIdle()
        assertEquals("Loaded: a.gguf", HomeText.runtime(vm.state.value.runtime))
        assertEquals(a.id, vm.state.value.loadedRecordId)

        s.unload()
        advanceUntilIdle()
        assertEquals("No model loaded", HomeText.runtime(vm.state.value.runtime))
        assertNull(vm.state.value.loadedRecordId)
        assertEquals("1", HomeText.models(vm.state.value)) // still downloaded
    }

    @Test
    fun unreadableRecordsShowAnErrorNotZero() = runTest(dispatcher) {
        val broken = object : DownloadController by downloads {
            override fun observe(): Flow<List<LocalModelEntity>> = flow { throw IOException("database is locked") }
        }
        val vm = home(inventory = LocalModelInventory(broken, dispatcher))
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.error!!.contains("database is locked"))
        assertNull(s.inventory)
        assertEquals(HomeText.UNAVAILABLE, HomeText.models(s))
        assertEquals(HomeText.UNAVAILABLE, HomeText.storage(s))
    }

    @Test
    fun unmeasurableDeviceShowsUnavailableNotNumbers() = runTest(dispatcher) {
        device.fail = true
        val vm = home()
        advanceUntilIdle()
        assertNull(vm.state.value.device)
        assertEquals(HomeText.UNAVAILABLE, HomeText.memory(vm.state.value))
        assertEquals("Models 0 B", HomeText.storage(vm.state.value)) // free space unknown: omitted, not guessed
    }

    @Test
    fun noEngineMeansEngineUnavailable() = runTest(dispatcher) {
        val vm = home(session = null)
        advanceUntilIdle()
        assertEquals("Engine unavailable", HomeText.runtime(vm.state.value.runtime))
    }

    @Test
    fun storageIsFormattedInBytesMegabytesAndGigabytes() {
        fun storage(bytes: Long) = HomeText.storage(
            HomeUiState(inventory = InventorySummary(listOf(com.example.localai.data.download.InventoryModel("i", "o/r", "m.gguf", bytes)), emptyList(), 0, 0))
        )
        assertEquals("Models 512 B", storage(512))
        assertEquals("Models 468.6 MB", storage(491_400_032))
        assertEquals("Models 2.0 GB", storage(2 * GB))
    }

    @Test
    fun homeAndMyModelsAgreeOnCountAndStorage() = runTest(dispatcher) {
        val rows = listOf(
            completed("a.gguf", 1_500),
            completed("b.gguf", 2_500),
            row("c.gguf", "PAUSED", 700, expected = 9_000),
            completed("d.gguf", 100).also { downloads.files[it.id]!!.delete() }
        )
        downloads.rows.value = rows
        val homeVm = home()
        val myModels = MyModelsViewModel(downloads, device, clock = { 0L }, ioDispatcher = dispatcher)
        advanceUntilIdle()
        val inventory = homeVm.state.value.inventory!!
        assertEquals(2, inventory.modelCount)
        assertEquals(4_000L, inventory.modelBytes)
        assertEquals(inventory.modelBytes, myModels.state.value.completedBytes)
        assertEquals(inventory.partialBytes, myModels.state.value.partialBytes)
    }
}
