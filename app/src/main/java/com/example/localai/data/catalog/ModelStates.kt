package com.example.localai.data.catalog

/*
 * Three independent facts about a model file. They must never be derived from one another:
 *  - catalog availability: can the Hub serve it to this client?   (CatalogAccess)
 *  - download status:      is a complete, verified copy on disk?  (DownloadStatus)
 *  - runtime compatibility: can the installed engine load it?     (RuntimeCompatibility)
 * A .gguf extension proves none of them.
 */

/** Local copy state of one file. Only [Completed] means the file may be opened. */
sealed interface DownloadStatus {
    data object NotDownloaded : DownloadStatus
    data object Queued : DownloadStatus
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long?) : DownloadStatus
    data object Verifying : DownloadStatus
    data object Completed : DownloadStatus
    data class Failed(val reason: String, val retryable: Boolean) : DownloadStatus

    /** Stopped by the user; the verified-so-far partial file is kept for resuming. */
    data class Paused(val downloadedBytes: Long, val totalBytes: Long?) : DownloadStatus

    /** Stopped by the user; the partial file was deleted. */
    data object Cancelled : DownloadStatus
}

/** Why a file cannot be loaded by the current runtime. */
enum class IncompatibilityReason(val label: String) {
    UNSUPPORTED_FORMAT("Format is not supported by the current runtime"),
    MULTI_PART_UNSUPPORTED("Multi-part models are not supported yet"),
    AUXILIARY_FILE("Not a standalone model (projector, tokenizer or config)"),
    NO_RUNTIME("No inference engine is installed in this build")
}

/** Whether the current inference engine can load a file. */
sealed interface RuntimeCompatibility {
    /** Not assessed yet. Treated as "cannot run". */
    data object NotEvaluated : RuntimeCompatibility

    data class Incompatible(val reason: IncompatibilityReason) : RuntimeCompatibility

    /** Assessed against a real engine and a verified local file. */
    data class Loadable(val engineId: String) : RuntimeCompatibility
}

/** True only when the file is fully downloaded and the engine reported it loadable. */
fun canLoad(download: DownloadStatus, runtime: RuntimeCompatibility): Boolean =
    download == DownloadStatus.Completed && runtime is RuntimeCompatibility.Loadable
