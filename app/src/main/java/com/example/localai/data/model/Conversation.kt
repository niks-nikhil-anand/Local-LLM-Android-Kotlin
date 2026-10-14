package com.example.localai.data.model

/** A (simulated) conversation shown in chat history. Kept in memory only. */
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

enum class AttachmentKind(val label: String, val sampleName: String) {
    IMAGE("Image", "sample_image.jpg"),
    VIDEO("Video", "sample_clip.mp4"),
    FILE("File", "sample_document.pdf")
}

/** A simulated attachment waiting to be sent. */
data class Attachment(val id: Long, val kind: AttachmentKind, val name: String)
