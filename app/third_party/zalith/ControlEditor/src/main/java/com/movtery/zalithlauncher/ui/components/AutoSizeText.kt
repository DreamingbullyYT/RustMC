package com.movtery.zalithlauncher.ui.components
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
@Composable fun AutoSizeText(text:String,modifier:Modifier=Modifier,style:TextStyle=TextStyle.Default,maxLines:Int=1) { Text(text,modifier=modifier,style=style,maxLines=maxLines) }
