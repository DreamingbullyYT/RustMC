package com.movtery.zalithlauncher.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import com.movtery.zalithlauncher.R
import kotlin.math.roundToInt


@Composable fun MarqueeText(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium, textAlign: TextAlign = TextAlign.Start) { Text(text, modifier, maxLines=1, style=style, textAlign=textAlign) }
@Composable fun rememberDialogMaxHeight(): Dp = 520.dp
@Composable fun ImePanContainer(modifier: Modifier = Modifier, contentAlignment: Alignment = Alignment.Center, content: @Composable BoxWithConstraintsScope.() -> Unit) { BoxWithConstraints(modifier, contentAlignment=contentAlignment, content=content) }
@Composable fun Modifier.verticalScrollWithBar(state: androidx.compose.foundation.ScrollState) = verticalScroll(state)
@Composable fun Modifier.verticalScrollWithBar(state: androidx.compose.foundation.lazy.LazyListState) = this
@Composable fun Modifier.lazyScrollWithBar(state: androidx.compose.foundation.lazy.LazyListState) = this
@Composable fun Modifier.fadeEdge(state: androidx.compose.foundation.ScrollState, direction: EdgeDirection = EdgeDirection.Vertical) = this
@Composable fun Modifier.fadeEdge(state: androidx.compose.foundation.lazy.LazyListState, direction: EdgeDirection = EdgeDirection.Vertical) = this
enum class EdgeDirection { Horizontal, Vertical }

@Composable fun FloatingBall(position:Offset,onPositionChanged:(Offset)->Unit,modifier:Modifier=Modifier,onClick:()->Unit={},alpha:Float=1f,color:Color=Color.Black.copy(alpha=.35f),contentColor:Color=Color.White,shape:Shape=RoundedCornerShape(14.dp),content:@Composable ()->Unit) {
 var drag by remember { mutableStateOf(Offset.Zero) }
 var size by remember { mutableStateOf(IntSize.Zero) }
 BoxWithConstraints(Modifier.fillMaxSize()) {
  val maxX=(constraints.maxWidth-size.width).coerceAtLeast(0); val maxY=(constraints.maxHeight-size.height).coerceAtLeast(0)
  Surface(modifier=modifier.offset { IntOffset((position.x+drag.x).roundToInt().coerceIn(0,maxX),(position.y+drag.y).roundToInt().coerceIn(0,maxY)) }.alpha(alpha).pointerInput(position) {
   detectDragGestures(onDragEnd={onPositionChanged(Offset((position.x+drag.x).coerceIn(0f,maxX.toFloat()),(position.y+drag.y).coerceIn(0f,maxY.toFloat())));drag=Offset.Zero}) { change,amount -> change.consume();drag+=amount }
  },color=color,contentColor=contentColor,shape=shape,onClick=onClick) { Box(Modifier.onSizeChanged{size=it}){content()} }
 }
}
@Composable fun MenuBox(modifier:Modifier=Modifier,position:Offset,onPositionChanged:(Offset)->Unit,opened:Boolean,onClick:()->Unit) { FloatingBall(position,onPositionChanged,modifier,onClick=onClick){ Text(if(opened)"☰" else "≡",Modifier.padding(8.dp)) } }

@Composable fun DualMenuSubscreen(state:MenuState,closeScreen:()->Unit,leftMenuTitle:(@Composable BoxScope.()->Unit)?=null,leftMenuContent:@Composable ColumnScope.()->Unit={},rightMenuTitle:(@Composable BoxScope.()->Unit)?=null,rightMenuContent:@Composable ColumnScope.()->Unit={}) {
 AnimatedVisibility(state==MenuState.SHOW,enter=fadeIn(),exit=fadeOut()) {
  Box(Modifier.fillMaxSize().clickable(onClick=closeScreen)) {
   Row(Modifier.fillMaxSize().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
    Card(Modifier.weight(1.2f).fillMaxHeight(),shape=MaterialTheme.shapes.large) { Column(Modifier.fillMaxSize()) { leftMenuTitle?.let{Box(Modifier.fillMaxWidth(),content=it)};leftMenuContent() } }
    Card(Modifier.weight(.8f).fillMaxHeight(),shape=MaterialTheme.shapes.large) { Column(Modifier.fillMaxSize()) { rightMenuTitle?.let{Box(Modifier.fillMaxWidth(),content=it)};rightMenuContent() } }
   }
  }
 }
}
@Composable fun MenuTextButton(modifier:Modifier=Modifier,text:String,enabled:Boolean=true,onClick:()->Unit,color:Color=MaterialTheme.colorScheme.surfaceVariant,contentColor:Color=MaterialTheme.colorScheme.onSurface) { Surface(modifier = modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,color=color,contentColor=contentColor,enabled=enabled,onClick=onClick){Text(text,Modifier.padding(14.dp),maxLines=1)} }
@Composable fun MenuSwitchButton(modifier:Modifier=Modifier,text:String,switch:Boolean,enabled:Boolean=true,onSwitch:(Boolean)->Unit,color:Color=MaterialTheme.colorScheme.surfaceVariant,contentColor:Color=MaterialTheme.colorScheme.onSurface) { Surface(modifier = modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,color=color,contentColor=contentColor,enabled=enabled,onClick={onSwitch(!switch)}) { Row(Modifier.padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Text(text,Modifier.weight(1f));Switch(switch,onSwitch,enabled=enabled)} } }
@Composable fun <E> MenuListLayout(modifier:Modifier=Modifier,title:String,items:List<E>,currentItem:E,onItemChange:(E)->Unit,getItemText:@Composable (E)->String,color:Color=MaterialTheme.colorScheme.surfaceVariant,contentColor:Color=MaterialTheme.colorScheme.onSurface,enabled:Boolean=true) { var expanded by remember{mutableStateOf(false)};Surface(modifier = modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,color=color,contentColor=contentColor,enabled=enabled,onClick={expanded=!expanded}){Column{Row(Modifier.fillMaxWidth().clickable(enabled=enabled){expanded=!expanded}.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title);Text(getItemText(currentItem),style=MaterialTheme.typography.labelSmall)};Text(if(expanded)"⌃" else "⌄")};if(expanded)items.forEach{DropdownMenuItem(text={Text(getItemText(it))},onClick={onItemChange(it);expanded=false})}}} }
@Composable fun ScalingActionButton(modifier:Modifier=Modifier,onClick:()->Unit,content:@Composable RowScope.()->Unit) { Button(modifier=modifier,onClick=onClick,content=content) }

@Composable fun SimpleAlertDialog(title:String,text:String,confirmText:String=stringResource(R.string.generic_confirm),dismissText:String=stringResource(R.string.generic_cancel),dismissByDialog:Boolean=true,onConfirm:()->Unit,onDismiss:()->Unit) { AlertDialog(onDismissRequest={if(dismissByDialog)onDismiss()},title={Text(title)},text={Text(text)},confirmButton={TextButton(onClick=onConfirm){Text(confirmText)}},dismissButton={TextButton(onClick=onDismiss){Text(dismissText)}}) }
@Composable fun SimpleAlertDialog(title:String,text:String,confirmText:String=stringResource(R.string.generic_confirm),dismissByDialog:Boolean=true,onDismiss:()->Unit) { AlertDialog(onDismissRequest={if(dismissByDialog)onDismiss()},title={Text(title)},text={Text(text)},confirmButton={TextButton(onClick=onDismiss){Text(confirmText)}}) }
@Composable fun SimpleAlertDialog(title:String,text:@Composable ()->Unit={},confirmText:String=stringResource(R.string.generic_confirm),dismissText:String=stringResource(R.string.generic_cancel),onConfirm:()->Unit,onCancel:()->Unit,onDismissRequest:()->Unit={}) { AlertDialog(onDismissRequest=onDismissRequest,title={Text(title)},text=text,confirmButton={TextButton(onClick=onConfirm){Text(confirmText)}},dismissButton={TextButton(onClick=onCancel){Text(dismissText)}}) }
@Composable fun SimpleAlertDialog(title:String,text:@Composable ()->Unit={},confirmText:String=stringResource(R.string.generic_confirm),onDismiss:()->Unit) { AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text=text,confirmButton={TextButton(onClick=onDismiss){Text(confirmText)}}) }
@Composable fun SimpleEditDialog(title:String,value:String,onValueChange:(String)->Unit,label:(@Composable ()->Unit)?=null,isError:Boolean=false,supportingText:(@Composable ()->Unit)?=null,singleLine:Boolean=false,maxLines:Int=3,keyboardOptions:KeyboardOptions=KeyboardOptions.Default,extraBody:(@Composable ()->Unit)?=null,extraContent:(@Composable ()->Unit)?=null,onDismissRequest:()->Unit={},onCancel:()->Unit=onDismissRequest,onConfirm:()->Unit={}) { AlertDialog(onDismissRequest=onDismissRequest,title={Text(title)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(value,onValueChange,label=label,isError=isError,supportingText=supportingText,singleLine=singleLine,maxLines=maxLines,keyboardOptions=keyboardOptions);extraBody?.invoke();extraContent?.invoke()}},confirmButton={TextButton(onClick=onConfirm){Text(stringResource(R.string.generic_confirm))}},dismissButton={TextButton(onClick=onCancel){Text(stringResource(R.string.generic_cancel))}}) }
@Composable fun SimpleEditDialog(title:String,value:String,onValueChange:(String)->Unit,label:(@Composable ()->Unit)?=null,isError:Boolean=false,supportingText:(@Composable ()->Unit)?=null,singleLine:Boolean=false,maxLines:Int=3,keyboardOptions:KeyboardOptions=KeyboardOptions.Default,extraBody:(@Composable ()->Unit)?=null,extraContent:(@Composable ()->Unit)?=null,onConfirm:()->Unit={}) { SimpleEditDialog(title,value,onValueChange,label,isError,supportingText,singleLine,maxLines,keyboardOptions,extraBody,extraContent,{}, {},onConfirm) }
@Composable fun ProgressDialog(title:String,text:String="") { AlertDialog(onDismissRequest={},title={Text(title)},text={Text(text)},confirmButton={}) }
@Composable fun OwnOutlinedTextField(value:String,onValueChange:(String)->Unit,modifier:Modifier=Modifier,label:(@Composable ()->Unit)?=null,singleLine:Boolean=false,isError:Boolean=false,supportingText:(@Composable ()->Unit)?=null,keyboardOptions:KeyboardOptions=KeyboardOptions.Default,enabled:Boolean=true,shape:Shape=MaterialTheme.shapes.large) { OutlinedTextField(value,onValueChange,modifier=modifier,label=label,singleLine=singleLine,isError=isError,supportingText=supportingText,keyboardOptions=keyboardOptions,enabled=enabled,shape=shape) }
@Composable fun SingleLineTextCheck(value:String,onValueChange:(String)->Unit,modifier:Modifier=Modifier,label:(@Composable ()->Unit)?=null,isError:Boolean=false) { OutlinedTextField(value,onValueChange,modifier=modifier,label=label,singleLine=true,isError=isError) }
@Composable fun SingleLineTextCheck(text:String,onSingleLined:(String)->Unit,modifier:Modifier=Modifier) { OutlinedTextField(value=text,onValueChange=onSingleLined,modifier=modifier,singleLine=true) }
@Composable fun DefaultSwitch(checked:Boolean,onCheckedChange:(Boolean)->Unit,enabled:Boolean=true) { Switch(checked,onCheckedChange,enabled=enabled) }
@Composable fun CheckChip(selected:Boolean,onClick:()->Unit,label:@Composable ()->Unit) { FilterChip(selected,onClick,label=label) }
@Composable fun WarningCard(modifier:Modifier=Modifier,title:String,icon:@Composable (Modifier)->Unit,text:@Composable ()->Unit) { Card(modifier) { Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){icon(Modifier.size(24.dp));Spacer(Modifier.width(8.dp));Column{Text(title,style=MaterialTheme.typography.titleSmall);text()} } } }
@Composable fun SimpleTextSlider(value:Float,onValueChange:(Float)->Unit,valueRange:ClosedFloatingPointRange<Float>,modifier:Modifier=Modifier,enabled:Boolean=true,shorter:Boolean=false,decimalFormat:String="#0.00",suffix:String?=null,onValueChangeFinished:(()->Unit)?=null,onTextClick:(()->Unit)?=null,fineTuningControl:Boolean=true,fineTuningStep:Float=0.5f,textAlign:TextAlign=TextAlign.Start) { Slider(value.coerceIn(valueRange),onValueChange,valueRange=valueRange,enabled=enabled,onValueChangeFinished=onValueChangeFinished,modifier=modifier) }
@Composable fun SliderValueEditDialog(onDismissRequest:()->Unit,title:String,valueRange:ClosedFloatingPointRange<Float>,value:Float,onValueChange:(Float)->Unit,onValueChangeFinished:()->Unit={}) { var v by remember { mutableFloatStateOf(value) }; AlertDialog(onDismissRequest=onDismissRequest,title={Text(title)},text={Slider(v,{v=it},valueRange=valueRange)},confirmButton={TextButton(onClick={onValueChange(v);onValueChangeFinished();onDismissRequest()}){Text(stringResource(R.string.generic_confirm))}}) }
@Composable fun LittleTextLabel(text:String) { Text(text,style=MaterialTheme.typography.labelSmall) }
