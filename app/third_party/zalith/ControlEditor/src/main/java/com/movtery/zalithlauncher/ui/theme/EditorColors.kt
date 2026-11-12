package com.movtery.zalithlauncher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable fun cardColor(influencedByBackground: Boolean = false) = MaterialTheme.colorScheme.surface
@Composable fun onCardColor() = MaterialTheme.colorScheme.onSurface
@Composable fun itemColor(influencedByBackground: Boolean = false) = MaterialTheme.colorScheme.surfaceVariant
@Composable fun onItemColor() = MaterialTheme.colorScheme.onSurfaceVariant
@Composable fun cardTitleColor() = MaterialTheme.colorScheme.surfaceContainerHigh
@Composable fun backgroundColor() = MaterialTheme.colorScheme.background
@Composable fun onBackgroundColor() = MaterialTheme.colorScheme.onBackground
