package com.movtery.zalithlauncher.utils.animation
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
@androidx.compose.runtime.Composable
fun <T> getAnimateTween(): FiniteAnimationSpec<T> = tween(220)
