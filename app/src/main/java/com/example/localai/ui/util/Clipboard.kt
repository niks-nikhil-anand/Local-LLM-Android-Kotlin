package com.example.localai.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Copies plain text to the system clipboard. */
fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
