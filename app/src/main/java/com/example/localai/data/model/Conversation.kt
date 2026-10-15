package com.example.localai.data.model

/** A conversation shown in chat history. Kept in memory only. */
data class Conversation(
    val id: String,
    val title: String,
    val modelId: String,
    val messages: List<ChatMessage>
) {
    fun matches(query: String): Boolean =
        query.isBlank() ||
            title.contains(query, ignoreCase = true) ||
            messages.any { it.text.contains(query, ignoreCase = true) }

    companion object {
        const val NEW_CHAT_TITLE = "New chat"
    }
}
