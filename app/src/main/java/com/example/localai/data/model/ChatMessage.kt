package com.example.localai.data.model

enum class ChatRole { USER, ASSISTANT }

/**
 * A chat message. [text] may contain fenced code blocks (``` ... ```); the UI renders them
 * as code. [attachments] holds the names of the documents attached to a user message.
 *
 * Provenance of assistant text is explicit: [isDemo] marks pre-written sample text, and
 * [generatedBy] names the on-device model that produced the text. Neither set means a user message.
 */
data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val attachments: List<String> = emptyList(),
    val isDemo: Boolean = false,
    val generatedBy: String? = null,
    /** Short status shown under a generated reply, e.g. "Stopped" or token speed. */
    val note: String? = null,
    /**
     * Extra text the model sees before [text] for this turn: attached documents and web results,
     * each in an UNTRUSTED block. Not shown as message text.
     */
    val promptContext: String? = null,
    /** Web pages the reply was given as reference; shown as links under it. */
    val sources: List<SourceLink> = emptyList()
)

/** A link shown under a reply. [url] is https and was checked by the search provider. */
data class SourceLink(val title: String, val url: String)
