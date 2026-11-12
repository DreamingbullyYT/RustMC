package com.movtery.zalithlauncher.ui.screens
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
interface TitledNavKey
@Composable fun rememberSwapTween(): FiniteAnimationSpec<Float> = remember { tween<Float>(220) }
