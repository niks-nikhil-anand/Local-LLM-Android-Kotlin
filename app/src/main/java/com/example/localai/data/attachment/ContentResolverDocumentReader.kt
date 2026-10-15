package com.example.localai.data.attachment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a document picked with the Storage Access Framework. Only the one granted URI is read,
 * once, on an IO thread; no persistent permission is kept and nothing is copied or uploaded.
 */
class ContentResolverDocumentReader(context: Context) : DocumentReader {

    private val resolver = context.applicationContext.contentResolver

    override suspend fun read(uri: String): DocumentReadResult = withContext(Dispatchers.IO) {
        try {
            val parsed = Uri.parse(uri)
            var name = parsed.lastPathSegment?.substringAfterLast('/') ?: "file"
            var size = -1L
            resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
            TextDocuments.rejectionForName(name)?.let { return@withContext DocumentReadResult.Rejected(it) }
            if (size >= 0) TextDocuments.rejectionForSize(name, size)?.let { return@withContext DocumentReadResult.Rejected(it) }
            val stream = resolver.openInputStream(parsed)
                ?: return@withContext DocumentReadResult.Rejected("“${TextDocuments.displayName(name)}” could not be opened.")
            // Read at most one byte past the limit, so an unknown or wrong reported size cannot exhaust memory.
            val bytes = stream.use { it.readNBytesCompat(TextDocuments.MAX_FILE_BYTES + 1) }
            TextDocuments.rejectionForSize(name, bytes.size.toLong())?.let { return@withContext DocumentReadResult.Rejected(it) }
            TextDocuments.decode(name, bytes)
        } catch (e: IOException) {
            DocumentReadResult.Rejected("The file could not be read (${e.javaClass.simpleName}).")
        } catch (e: SecurityException) {
            DocumentReadResult.Rejected("Access to the file was denied.")
        } catch (e: IllegalArgumentException) {
            DocumentReadResult.Rejected("The file could not be opened.")
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
