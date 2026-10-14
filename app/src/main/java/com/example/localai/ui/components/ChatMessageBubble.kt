package com.example.localai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.localai.data.model.ChatMessage
import com.example.localai.data.model.ChatRole
import com.example.localai.ui.theme.CodeTextStyle

/** Part of a message: plain text or a fenced code block. */
sealed interface MessageSegment {
    data class Text(val text: String) : MessageSegment
    data class Code(val language: String, val code: String) : MessageSegment
}

/** Splits a message on ``` fences. An unclosed fence is treated as code. */
fun parseMessageSegments(raw: String): List<MessageSegment> {
    val parts = raw.split("```")
    val segments = mutableListOf<MessageSegment>()
    parts.forEachIndexed { index, part ->
        if (index % 2 == 0) {
            val text = part.trim()
            if (text.isNotEmpty()) segments += MessageSegment.Text(text)
        } else {
            val firstBreak = part.indexOf('\n')
            val language = if (firstBreak >= 0) part.substring(0, firstBreak).trim() else ""
            val code = (if (firstBreak >= 0) part.substring(firstBreak + 1) else part).trimEnd()
            if (code.isNotEmpty()) segments += MessageSegment.Code(language, code)
        }
    }
    return segments
}

@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == ChatRole.USER
    val roleLabel = if (isUser) "You" else "Assistant"
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val segments = parseMessageSegments(message.text)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.88f
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = false) { contentDescription = "$roleLabel message" },
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Text(
                text = if (isUser) roleLabel else "$roleLabel · Demo response",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
            Surface(
                shape = MaterialTheme.shapes.large,
                color = bubbleColor,
                contentColor = textColor,
                modifier = Modifier.widthIn(max = maxBubbleWidth)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    message.attachments.forEach { name ->
                        ModelMetadataChip(text = name, icon = Icons.Outlined.AttachFile)
                    }
                    segments.forEach { segment ->
                        when (segment) {
                            is MessageSegment.Text -> SelectionContainer {
                                Text(text = segment.text, style = MaterialTheme.typography.bodyLarge)
                            }
                            is MessageSegment.Code -> CodeBlock(segment, onCopy)
                        }
                    }
                }
            }
            IconButton(onClick = { onCopy(message.text) }) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy message",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(segment: MessageSegment.Code, onCopy: (String) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = segment.language.ifEmpty { "code" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = { onCopy(segment.code) }) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy code",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = segment.code,
                style = CodeTextStyle,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            )
        }
    }
}
