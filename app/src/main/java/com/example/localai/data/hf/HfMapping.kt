package com.example.localai.data.hf

import com.example.localai.data.catalog.CatalogAccess
import com.example.localai.data.catalog.HfModelSummary
import com.example.localai.data.catalog.HfRepoFile
import com.example.localai.data.catalog.HfRepoId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private const val LICENSE_TAG_PREFIX = "license:"

/** Maps a model DTO, or returns null when it has no usable repository id. */
internal fun HfModelDto.toSummary(): HfModelSummary? {
    val rawId = (id ?: modelId)?.trim().orEmpty()
    val repoId = runCatching { HfRepoId(rawId) }.getOrNull() ?: return null
    val tagList = tags.orEmpty()
    return HfModelSummary(
        repoId = repoId,
        author = author?.takeIf { it.isNotBlank() } ?: repoId.owner,
        downloads = downloads,
        likes = likes,
        license = cardData?.license.firstString()
            ?: tagList.firstOrNull { it.startsWith(LICENSE_TAG_PREFIX) }
                ?.removePrefix(LICENSE_TAG_PREFIX)
                ?.takeIf { it.isNotBlank() },
        pipelineTag = pipelineTag?.takeIf { it.isNotBlank() },
        tags = tagList,
        lastModified = lastModified,
        access = toAccess()
    )
}

private fun HfModelDto.toAccess(): CatalogAccess = when {
    isPrivate == true || disabled == true -> CatalogAccess.Restricted
    else -> when (val g = gated) {
        null, JsonNull -> CatalogAccess.Public
        is JsonPrimitive -> when {
            g.booleanOrNull == false -> CatalogAccess.Public
            g.booleanOrNull == true -> CatalogAccess.Gated(mode = null)
            else -> CatalogAccess.Gated(mode = g.contentOrNull)
        }
        // An unexpected shape is treated as gated: never claim public access we cannot confirm.
        else -> CatalogAccess.Gated(mode = null)
    }
}

private fun JsonElement?.firstString(): String? = when (this) {
    is JsonArray -> firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    is JsonPrimitive -> takeIf { isString }?.content
    else -> null
}?.takeIf { it.isNotBlank() }

/**
 * Maps a tree entry to a file, skipping directories and entries without a safe relative path.
 * For LFS files the size and SHA-256 come from the LFS pointer's target, not the pointer file.
 */
internal fun HfTreeEntryDto.toFile(repoId: HfRepoId, revision: String): HfRepoFile? {
    if (type != "file") return null
    val filePath = path?.takeIf { it.isNotBlank() } ?: return null
    if (filePath.startsWith("/") || filePath.split('/').any { it == ".." || it.isEmpty() }) return null
    return HfRepoFile(
        repoId = repoId,
        revision = revision,
        path = filePath,
        sizeBytes = lfs?.size ?: size,
        sha256 = lfs?.oid?.takeIf { it.isNotBlank() }
    )
}
