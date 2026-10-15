package com.example.localai.explore

import com.example.localai.data.catalog.CatalogAccess
import com.example.localai.data.catalog.CatalogError
import com.example.localai.data.catalog.CatalogQuery
import com.example.localai.data.catalog.CatalogResult
import com.example.localai.data.catalog.HfModelSummary
import com.example.localai.data.catalog.HfRepoFile
import com.example.localai.data.catalog.HfRepoId
import com.example.localai.data.catalog.ModelCatalogRepository
import com.example.localai.data.catalog.Page
import com.example.localai.data.device.DeviceResourceProvider
import com.example.localai.data.device.DeviceResourceSnapshot
import com.example.localai.data.device.RawDeviceReadings

internal fun summary(id: String, access: CatalogAccess = CatalogAccess.Public) = HfModelSummary(
    repoId = HfRepoId(id),
    author = null,
    downloads = null,
    likes = null,
    license = null,
    pipelineTag = null,
    tags = emptyList(),
    lastModified = null,
    access = access
)

internal fun page(vararg ids: String, next: String? = null): CatalogResult<Page<HfModelSummary>> =
    CatalogResult.Success(Page(ids.map { summary(it) }, next))

internal fun failure(error: CatalogError) = CatalogResult.Failure(error)

/** Scriptable in-memory catalog that records every call. */
internal class FakeCatalog : ModelCatalogRepository {
    val searches = mutableListOf<Pair<CatalogQuery, String?>>()
    val detailCalls = mutableListOf<HfRepoId>()
    val fileCalls = mutableListOf<HfRepoId>()

    var onSearch: suspend (CatalogQuery, String?) -> CatalogResult<Page<HfModelSummary>> = { _, _ -> page() }
    var onDetails: suspend (HfRepoId) -> CatalogResult<HfModelSummary> = { CatalogResult.Success(summary(it.value)) }
    var onFiles: suspend (HfRepoId) -> CatalogResult<List<HfRepoFile>> = { CatalogResult.Success(emptyList()) }

    override suspend fun search(query: CatalogQuery, cursor: String?): CatalogResult<Page<HfModelSummary>> {
        searches += query to cursor
        return onSearch(query, cursor)
    }

    override suspend fun modelDetails(repoId: HfRepoId): CatalogResult<HfModelSummary> {
        detailCalls += repoId
        return onDetails(repoId)
    }

    override suspend fun listFiles(repoId: HfRepoId, revision: String): CatalogResult<List<HfRepoFile>> {
        fileCalls += repoId
        return onFiles(repoId)
    }
}

internal const val GB = 1024L * 1024 * 1024

internal val normalDevice = RawDeviceReadings(
    totalRamBytes = 8 * GB,
    availableRamBytes = 4 * GB,
    lowMemoryThresholdBytes = 512L * 1024 * 1024,
    isLowMemory = false,
    isLowRamDevice = false,
    totalStorageBytes = 128 * GB,
    availableStorageBytes = 40 * GB
)

/** Device provider returning scripted readings; counts measurements. */
internal class FakeDeviceProvider(var readings: RawDeviceReadings = normalDevice, private val clock: () -> Long = { 0L }) :
    DeviceResourceProvider {
    var snapshots = 0
    var refreshes = 0
    var fail = false

    override suspend fun snapshot(maxAgeMillis: Long): DeviceResourceSnapshot {
        snapshots++
        if (fail) throw IllegalStateException("boom")
        return DeviceResourceSnapshot.from(readings, clock())
    }

    override suspend fun refresh(): DeviceResourceSnapshot {
        refreshes++
        if (fail) throw IllegalStateException("boom")
        return DeviceResourceSnapshot.from(readings, clock())
    }
}

/** In-memory download controller recording calls; rows are pushed by tests. */
internal class FakeDownloads : com.example.localai.data.download.DownloadController {
    val rows = kotlinx.coroutines.flow.MutableStateFlow<List<com.example.localai.data.local.LocalModelEntity>>(emptyList())
    val calls = mutableListOf<String>()
    var enqueueResult: com.example.localai.data.download.EnqueueResult = com.example.localai.data.download.EnqueueResult.Started
    override val liveProgress = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>>(emptyMap())
    override fun observe() = rows
    override suspend fun enqueue(file: HfRepoFile): com.example.localai.data.download.EnqueueResult {
        calls += "enqueue:${file.path}"; return enqueueResult
    }
    override suspend fun pause(id: String) { calls += "pause:$id" }
    override suspend fun cancel(id: String) { calls += "cancel:$id" }
    override suspend fun retry(id: String): com.example.localai.data.download.EnqueueResult { calls += "retry:$id"; return enqueueResult }
    override suspend fun delete(id: String) { calls += "delete:$id" }
    val files = mutableMapOf<String, java.io.File>()
    override fun localFile(row: com.example.localai.data.local.LocalModelEntity): java.io.File =
        files[row.id] ?: java.io.File("does-not-exist-${row.id.hashCode()}.gguf")
    var verifyCalls = 0
    override suspend fun verifyCompletedFiles(): Int { verifyCalls++; return 0 }
}
