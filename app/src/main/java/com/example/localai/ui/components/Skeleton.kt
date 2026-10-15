package com.example.localai.ui.components

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Content-shaped loading placeholders. Every placeholder in a group shares one pulse, which is read
 * only while drawing (no recomposition per frame). The pulse stops when the system's animator
 * duration scale is 0 (reduced motion / animations off). A group is one accessibility node that
 * says what is loading; the shapes inside it are never announced.
 */

private const val PULSE_MILLIS = 900
private const val MIN_ALPHA = 0.45f
private const val MAX_ALPHA = 0.9f

/** The pulse shared by placeholders in a [SkeletonGroup]. */
private val LocalSkeletonPulse = compositionLocalOf<State<Float>?> { null }

@Composable
internal fun animationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f
    }
}

@Composable
private fun rememberPulse(): State<Float> {
    if (!animationsEnabled()) return remember { mutableFloatStateOf((MIN_ALPHA + MAX_ALPHA) / 2) }
    return rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = MIN_ALPHA,
        targetValue = MAX_ALPHA,
        animationSpec = infiniteRepeatable(tween(PULSE_MILLIS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "skeletonPulse"
    )
}

/**
 * Wraps placeholders: one pulse for all of them, and one accessibility node reading
 * [description] (e.g. "Loading models") instead of the decorative shapes.
 */
@Composable
fun SkeletonGroup(
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val pulse = rememberPulse()
    Box(modifier.clearAndSetSemantics { contentDescription = description }) {
        CompositionLocalProvider(LocalSkeletonPulse provides pulse) { content() }
    }
}

/** A placeholder block in the theme's surface-variant colour. */
@Composable
fun SkeletonBox(modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.small) {
    val pulse = LocalSkeletonPulse.current ?: rememberPulse()
    val color = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier.drawBehind {
            drawOutline(shape.createOutline(size, layoutDirection, this), color = color.copy(alpha = color.alpha * pulse.value))
        }
    )
}

/**
 * One line of text that has not loaded: as tall as a line in [style] (so nothing moves when the
 * real text arrives), with a bar about the height of its glyphs.
 */
@Composable
fun SkeletonText(style: TextStyle, width: Dp, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val lineHeight = with(density) { (if (style.lineHeight.isSp) style.lineHeight else style.fontSize * 1.4f).toDp() }
    val barHeight = with(density) { style.fontSize.toDp() } * 0.8f
    Box(modifier.height(lineHeight), contentAlignment = Alignment.CenterStart) {
        SkeletonBox(Modifier.width(width).height(barHeight), RoundedCornerShape(4.dp))
    }
}

@Composable
fun SkeletonCircle(size: Dp, modifier: Modifier = Modifier) =
    SkeletonBox(modifier.size(size), RoundedCornerShape(50))

/** Same footprint as [ModalityIconBadge]. */
@Composable
fun SkeletonIconBadge(modifier: Modifier = Modifier, size: Dp = 40.dp) =
    SkeletonBox(modifier.size(size), RoundedCornerShape(12.dp))

/** Same height as a [ModelMetadataChip] (label line plus 4 dp padding above and below). */
@Composable
fun SkeletonChip(width: Dp, modifier: Modifier = Modifier) {
    val label = MaterialTheme.typography.labelMedium
    val height = with(LocalDensity.current) { label.lineHeight.toDp() } + 8.dp
    SkeletonBox(modifier.width(width).height(height), MaterialTheme.shapes.small)
}

/** The header row shared by model cards: icon badge, a title line and a subtitle line. */
@Composable
fun SkeletonCardHeader(titleWidth: Dp = 160.dp, subtitleWidth: Dp = 96.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SkeletonIconBadge()
        Spacer(Modifier.width(12.dp))
        Column {
            SkeletonText(MaterialTheme.typography.titleMedium, titleWidth)
            SkeletonText(MaterialTheme.typography.bodySmall, subtitleWidth)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkeletonChips(widths: List<Dp>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        widths.forEach { SkeletonChip(it) }
    }
}

/** Placeholder with the layout of a catalogue model card (Explore). */
@Composable
fun SkeletonModelCard(modifier: Modifier = Modifier) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            SkeletonCardHeader()
            Spacer(Modifier.size(12.dp))
            SkeletonChips(listOf(96.dp, 48.dp, 112.dp, 64.dp))
            Spacer(Modifier.size(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonText(MaterialTheme.typography.bodySmall, 180.dp)
                SkeletonText(MaterialTheme.typography.labelLarge, 64.dp)
            }
        }
    }
}

/** Placeholder with the layout of a downloaded-model card (My Models). */
@Composable
fun SkeletonLocalModelCard(modifier: Modifier = Modifier) {
    SurfaceCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonIconBadge()
                Spacer(Modifier.width(12.dp))
                Column {
                    SkeletonText(MaterialTheme.typography.titleMedium, 150.dp)
                    SkeletonText(MaterialTheme.typography.bodySmall, 200.dp)
                    SkeletonText(MaterialTheme.typography.bodySmall, 120.dp)
                }
            }
            SkeletonChips(listOf(88.dp, 120.dp))
            SkeletonText(MaterialTheme.typography.bodySmall, 240.dp)
            SkeletonBox(Modifier.width(96.dp).height(40.dp), RoundedCornerShape(20.dp))
        }
    }
}

/** Placeholder with the layout of a model-file (variant) card on the details screen. */
@Composable
fun SkeletonVariantCard(modifier: Modifier = Modifier) {
    SurfaceCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonText(MaterialTheme.typography.titleSmall, 220.dp)
            SkeletonChips(listOf(110.dp, 120.dp, 64.dp))
            SkeletonText(MaterialTheme.typography.bodySmall, 250.dp)
            SkeletonBox(Modifier.width(150.dp).height(40.dp), RoundedCornerShape(20.dp))
        }
    }
}

/** Placeholder for a titled card of a few text lines (e.g. storage or device summaries). */
@Composable
fun SkeletonInfoCard(lines: Int = 2, modifier: Modifier = Modifier) {
    SurfaceCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SkeletonText(MaterialTheme.typography.titleMedium, 140.dp)
            SkeletonBox(Modifier.fillMaxWidth().height(12.dp), RoundedCornerShape(6.dp))
            repeat(lines - 1) { SkeletonText(MaterialTheme.typography.bodySmall, 200.dp) }
        }
    }
}

/** A list of [count] card placeholders as one "loading" node. */
@Composable
fun SkeletonCardList(
    description: String,
    count: Int,
    modifier: Modifier = Modifier,
    card: @Composable () -> Unit
) {
    SkeletonGroup(description, modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(count) { card() } }
    }
}
