package com.example.localai.data.attachment

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** A text document the user picked; its whole text was read on this device and is held in memory only. */
data class Attachment(val id: Long, val name: String, val sizeBytes: Long, val text: String)

/** Outcome of reading a picked document. */
sealed interface DocumentReadResult {
    data class Read(val name: String, val sizeBytes: Long, val text: String) : DocumentReadResult
    data class Rejected(val reason: String) : DocumentReadResult
}

/** Reads a document the user picked with the system file picker. */
fun interface DocumentReader {
    suspend fun read(uri: String): DocumentReadResult
}

/** Which documents chat accepts and how their bytes become text. Independent of Android. */
object TextDocuments {

    val SUPPORTED_EXTENSIONS = setOf("txt", "md", "markdown", "json", "csv")

    /** Types offered by the system picker; the extension and content are still checked after picking. */
    val PICKER_MIME_TYPES = arrayOf(
        "text/plain", "text/markdown", "text/x-markdown", "text/csv", "text/comma-separated-values",
        "application/json", "application/csv"
    )

    const val MAX_FILE_BYTES = 512 * 1024
    const val MAX_PENDING = 3

    const val PDF_UNSUPPORTED = "PDF is not supported yet: this build has no reliable on-device PDF text extraction."

    /** Null when [name] is a supported document, otherwise why it is not. */
    fun rejectionForName(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            extension in SUPPORTED_EXTENSIONS -> null
            extension == "pdf" -> PDF_UNSUPPORTED
            else -> "“${displayName(name)}” is not a supported file. Attach .txt, .md, .json or .csv text files."
        }
    }

    fun rejectionForSize(name: String, sizeBytes: Long): String? =
        if (sizeBytes > MAX_FILE_BYTES) {
            "“${displayName(name)}” is too large (${formatBytes(sizeBytes)}). The limit is ${formatBytes(MAX_FILE_BYTES.toLong())}."
        } else null

    /**
     * Decodes strict UTF-8 (an optional BOM is dropped, line endings are normalised). Bytes that are
     * not valid UTF-8, or NUL characters, mean the file is not plain text and it is rejected.
     */
    fun decode(name: String, bytes: ByteArray): DocumentReadResult {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            return DocumentReadResult.Rejected("“${displayName(name)}” is not UTF-8 text and cannot be read.")
        }
        if ('\u0000' in text) return DocumentReadResult.Rejected("“${displayName(name)}” looks like a binary file, not text.")
        val normalised = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        if (normalised.isBlank()) return DocumentReadResult.Rejected("“${displayName(name)}” is empty.")
        return DocumentReadResult.Read(name, bytes.size.toLong(), normalised)
    }

    /** A name safe to show and to quote in a prompt: one line, no quotes, bounded length. */
    fun displayName(name: String): String =
        name.replace(Regex("[\\p{Cntrl}\"“”]"), " ").trim().ifEmpty { "file" }.take(80)

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
