package com.movtery.zalithlauncher.ui.components
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
enum class ButtonPosition { TopStart, TopEnd, Middle, BottomStart, BottomEnd, Top, Bottom, Single }
@Composable fun PositionButton(modifier:Modifier=Modifier,position:ButtonPosition=ButtonPosition.Single,onClick:()->Unit,content:@Composable RowScope.()->Unit) { Button(modifier=modifier,onClick=onClick,content=content) }
@Composable fun PositionFilledTonalButton(modifier:Modifier=Modifier,position:ButtonPosition=ButtonPosition.Single,onClick:()->Unit,content:@Composable RowScope.()->Unit) { FilledTonalButton(modifier=modifier,onClick=onClick,content=content) }
