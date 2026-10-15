package com.example.localai.data.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.localai.data.CatalogServices
import java.util.concurrent.TimeUnit

/**
 * Schedules each download as unique WorkManager work named after its record, with
 * [ExistingWorkPolicy.KEEP], so the same file can never download twice at once. Work survives
 * process death and reboots; it needs a network connection and backs off exponentially.
 *
 * Not a foreground service: Android stops background work after about ten minutes and on some
 * system events. The download then resumes from its durable bytes on the next run, so large
 * files finish over several runs rather than in one.
 */
class WorkManagerDownloadScheduler(context: Context) : DownloadScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun enqueue(id: String) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(id: String) {
        workManager.cancelUniqueWork(workName(id))
    }

    companion object {
        const val TAG = "model-download"
        fun workName(id: String) = "model-download:" + ModelDownloadManager.fileNameFor(id)
    }
}

/** Runs one download attempt; the outcome and progress are persisted by [ModelDownloadManager]. */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val manager = CatalogServices.downloads(applicationContext)
        val result = manager.runDownload(id) { bytes, total ->
            setProgress(workDataOf(KEY_BYTES to bytes, KEY_TOTAL to (total ?: -1L)))
        }
        return when (result) {
            RunResult.DONE, RunResult.STOPPED -> Result.success()
            RunResult.RETRY -> Result.retry()
            RunResult.FAILED -> Result.failure()
        }
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
    }
}
