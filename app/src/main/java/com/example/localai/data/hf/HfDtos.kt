package com.example.localai.data.hf

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * Wire formats of the Hugging Face Hub API. Every field is optional: the Hub omits fields
 * depending on the endpoint, the expand parameters and the repository, so nothing here may
 * be required for parsing to succeed.
 */

/** Item of GET /api/models and body of GET /api/models/{id}. */
@Serializable
internal data class HfModelDto(
    val id: String? = null,
    val modelId: String? = null,
    val author: String? = null,
    val downloads: Long? = null,
    val likes: Int? = null,
    val tags: List<String>? = null,
    @SerialName("pipeline_tag") val pipelineTag: String? = null,
    val lastModified: String? = null,
    /** false, "auto" or "manual" (occasionally true). */
    val gated: JsonElement? = null,
    @SerialName("private") val isPrivate: Boolean? = null,
    val disabled: Boolean? = null,
    val cardData: HfCardDataDto? = null
)

@Serializable
internal data class HfCardDataDto(
    /** A string, or a list of strings in some model cards. */
    val license: JsonElement? = null
)

/** Item of GET /api/models/{id}/tree/{revision}. */
@Serializable
internal data class HfTreeEntryDto(
    val type: String? = null,
    val path: String? = null,
    val size: Long? = null,
    val lfs: HfLfsDto? = null
)

@Serializable
internal data class HfLfsDto(
    val oid: String? = null,
    val size: Long? = null
)
