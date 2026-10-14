package com.example.localai.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 4dp-grid spacing tokens, available as [LocalSpacing]. */
@Immutable
data class Spacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,
    /** Horizontal gutter for screen content. */
    val screenHorizontal: Dp = 16.dp,
    /** Minimum interactive size. */
    val minTouchTarget: Dp = 48.dp
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }
