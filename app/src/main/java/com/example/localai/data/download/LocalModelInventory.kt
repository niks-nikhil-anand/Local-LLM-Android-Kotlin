package com.example.localai.data.download

import com.example.localai.data.local.LocalDownloadState
import com.example.localai.data.local.LocalModelEntity
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/** A downloaded model whose file exists and has its verified size: the only kind that counts. */
data class InventoryModel(
    val id: String,
    val repoId: String,
    val path: String,
    /** Measured size of the file on disk. */
    val sizeBytes: Long
) {
    val fileName: String get() = path.substringAfterLast('/')
}

/** What is actually on disk, derived from the download records and the files themselves. */
data class InventorySummary(
    /** Usable models: completed records whose file exists with the verified size. */
    val models: List<InventoryModel>,
    /** Completed records whose file is missing or has the wrong size; not counted, not usable. */
    val damagedIds: List<String>,
    /** Records currently queued, downloading or verifying. */
    val activeDownloads: Int,
    /** Bytes of unfinished downloads on disk (kept apart from model storage). */
    val partialBytes: Long
) {
    val modelCount: Int get() = models.size

    /** Storage used by usable models: the sum of their actual file sizes. */
    val modelBytes: Long get() = models.sumOf { it.sizeBytes }

    companion object {
        val EMPTY = InventorySummary(emptyList(), emptyList(), 0, 0)

        /**
         * The validation rule shared by Home and My Models. A record is a usable model only when it is
         * COMPLETED and its file exists with the verified size (the listed size, or the size recorded
         * when verification finished). Catalogue entries without a record never appear here.
         */
        fun of(rows: List<LocalModelEntity>, fileOf: (LocalModelEntity) -> File): InventorySummary {
            val models = mutableListOf<InventoryModel>()
            val damaged = mutableListOf<String>()
            var active = 0
            var partial = 0L
            for (row in rows.distinctBy { it.id }) {
                when (row.state) {
                    LocalDownloadState.COMPLETED.name -> {
                        val file = fileOf(row)
                        val expected = row.expectedSizeBytes ?: row.downloadedBytes
                        val actual = if (file.isFile) file.length() else -1L
                        if (actual == expected && actual > 0) {
                            models += InventoryModel(row.id, row.repoId, row.path, actual)
                        } else {
                            damaged += row.id
                        }
                    }
                    LocalDownloadState.QUEUED.name, LocalDownloadState.DOWNLOADING.name, LocalDownloadState.VERIFYING.name -> {
                        active++
                        partial += row.downloadedBytes
                    }
                    LocalDownloadState.PAUSED.name, LocalDownloadState.FAILED.name -> partial += row.downloadedBytes
                }
            }
            return InventorySummary(models.sortedBy { it.fileName.lowercase() }, damaged, active, partial)
        }
    }
}

/**
 * Observable inventory over the existing download records. File checks run off the main thread and
 * are repeated when the records change or [refresh] is called (e.g. when a screen resumes, to pick
 * up files removed outside the app). There is no polling.
 */
class LocalModelInventory(
    private val downloads: DownloadController,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val refreshes = MutableStateFlow(0)

    val summary: Flow<InventorySummary> =
        combine(downloads.observe(), refreshes) { rows, _ -> InventorySummary.of(rows, downloads::localFile) }
            .flowOn(ioDispatcher)
            .distinctUntilChanged()

    fun refresh() {
        refreshes.value++
    }
}
