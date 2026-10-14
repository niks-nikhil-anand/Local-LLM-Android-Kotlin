package com.example.localai.data.model

enum class ChatRole { USER, ASSISTANT }

/**
 * A chat message. [text] may contain fenced code blocks (``` ... ```); the UI renders them
 * as code. Assistant messages are always simulated. [attachments] holds the names of
 * simulated attachments; no real files are involved.
 */
data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val attachments: List<String> = emptyList()
)
