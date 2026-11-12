package com.movtery.zalithlauncher.ui.control.mouse
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.PointerId
@Composable fun SwitchableMouseLayout(modifier:Modifier=Modifier,screenSize:IntSize,cursorMode:Int,isMoveOnlyPointer:(PointerId)->Boolean,onOccupiedPointer:(PointerId)->Unit,onReleasePointer:(PointerId)->Unit) { Box(modifier.fillMaxSize()) }
