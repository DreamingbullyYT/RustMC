package com.movtery.zalithlauncher.ui.screens.content.elements

import androidx.compose.runtime.Composable
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.movtery.zalithlauncher.ui.screens.TitledNavKey

data class CategoryItem(val key: TitledNavKey, val icon: @Composable () -> Unit, val textRes: Int)
@Composable fun CategoryIcon(iconRes: Int, descriptionRes: Int) { Icon(painterResource(iconRes), contentDescription = stringResource(descriptionRes)) }
const val DisabledAlpha = 0.38f
