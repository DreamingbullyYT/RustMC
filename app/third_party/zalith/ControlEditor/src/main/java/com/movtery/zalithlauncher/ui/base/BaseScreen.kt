package com.movtery.zalithlauncher.ui.base

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.movtery.zalithlauncher.ui.screens.TitledNavKey

@Composable fun BaseScreen(screenKey: TitledNavKey, currentKey: TitledNavKey?, content: @Composable () -> Unit) {
    if (screenKey == currentKey) Box(Modifier.fillMaxSize()) { content() }
}
