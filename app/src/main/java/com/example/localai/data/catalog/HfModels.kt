package com.example.localai.data.catalog

import java.util.Locale

/** `owner/name` identifier of a Hugging Face repository. Legacy repos may have no owner. */
@JvmInline
value class HfRepoId(val value: String) {
    init {
        require(value.isNotBlank() && value.none { it.isWhitespace() } && value.count { it == '/' } <= 1) {
            "Invalid repository id: '$value'"
        }
    }

    val owner: String? get() = value.substringBefore('/', missingDelimiterValue = "").ifEmpty { null }
    val name: String get() = value.substringAfter('/')
    val webUrl: String get() = "https://huggingface.co/$value"
}

/**
 * Whether the Hub lets an anonymous client see the repository. This says nothing about whether
 * a file was downloaded or whether this app can run it.
 */
sealed interface CatalogAccess {
    /** Public: files can be fetched without a token. */
    data object Public : CatalogAccess

    /** Gated: the user must accept terms or request access on Hugging Face first. */
    data class Gated(val mode: String?) : CatalogAccess

    /** Marked private or disabled by the Hub. */
    data object Restricted : CatalogAccess
}

/** A repository as returned by the Hub listing/metadata endpoints. Fields the API omits stay null. */
data class HfModelSummary(
    val repoId: HfRepoId,
    val author: String?,
    val downloads: Long?,
    val likes: Int?,
    val license: String?,
    val pipelineTag: String?,
    val tags: List<String>,
    val lastModified: String?,
    val access: CatalogAccess
) {
    /** True when the tags advertise GGUF files. Says nothing about runtime support. */
    val advertisesGguf: Boolean get() = tags.any { it.equals("gguf", ignoreCase = true) }
}

/** One file from the repository tree at a given revision. */
data class HfRepoFile(
    val repoId: HfRepoId,
    val revision: String,
    val path: String,
    val sizeBytes: Long?,
    /** SHA-256 of the content, available for LFS-tracked files only. */
    val sha256: String?
) {
    val fileName: String get() = path.substringAfterLast('/')
    val extension: String get() = fileName.substringAfterLast('.', "").lowercase()

    /** Direct-download URL on the Hub for this file at [revision]. */
    val resolveUrl: String
        get() = "https://huggingface.co/${repoId.value}/resolve/$revision/$path"
}

/** Formats a byte count for display, e.g. `1.5 GB`. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}
