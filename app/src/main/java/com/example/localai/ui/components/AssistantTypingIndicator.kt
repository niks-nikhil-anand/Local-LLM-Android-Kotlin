package com.example.localai.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp

/**
 * Shown in place of the assistant's reply until its first real token arrives: three pulsing dots
 * in an assistant-style bubble and the real [status] (e.g. "Searching Wikipedia…"). It contains no
 * placeholder text, and is removed as soon as streaming starts.
 */
@Composable
fun AssistantTypingIndicator(status: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .testTag("assistant_typing")
            .clearAndSetSemantics {
                contentDescription = "Assistant is replying. $status"
                liveRegion = LiveRegionMode.Polite
            }
    ) {
        Text(
            "Assistant",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val dots = typingDots()
                val color = MaterialTheme.colorScheme.onSurfaceVariant
                dots.forEach { alpha ->
                    Box(Modifier.size(8.dp).drawBehind { drawCircle(color.copy(alpha = alpha.value)) })
                }
            }
        }
        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

/** One alpha per dot, offset in time; static when animations are off. */
@Composable
private fun typingDots(): List<State<Float>> {
    if (!animationsEnabled()) return remember { List(3) { mutableFloatStateOf(0.6f) } }
    val transition = rememberInfiniteTransition(label = "typing")
    return List(3) { i ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse, StartOffset(i * 160)),
            label = "dot$i"
        )
    }
}
