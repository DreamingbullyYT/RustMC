package dev.horizon.launcher.game.zalith

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * The in-game menu of ZalithLauncher2 (GPL-3.0, see LICENSE-ZalithLauncher2-GPL-3.0.txt): the two cards
 * (tabs on the left, "Game menu" on the right), the rows (button / switch / slider / dropdown) and the
 * draggable floating ball, rewritten here without the launcher's settings, blur and resources.
 */

private val ZalithColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF8E7BFF),
    onPrimary = Color.White,
    surface = Color(0xFF17141F),
    onSurface = Color(0xFFEDEAF7),
    surfaceVariant = Color(0xFF2A2538),
    onSurfaceVariant = Color(0xFFCFC9EA),
    surfaceContainer = Color(0xFF1B1727),
    surfaceContainerHigh = Color(0xFF262136),
    surfaceContainerHighest = Color(0xFF302A44),
)

/** Dark Zalith-like colours for everything inside the in-game menu. */
@Composable
fun ZalithTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ZalithColors, content = content)
}

private val cardColor @Composable get() = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)
private val titleColor @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh
private val itemColor @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f)
private const val DisabledAlpha = 0.38f

enum class MenuState {
    NONE { override fun next() = SHOW },
    SHOW { override fun next() = HIDE },
    HIDE { override fun next() = SHOW };

    abstract fun next(): MenuState
}

/** Two cards over a dimmed screen: [leftMenuContent] on the left, [rightMenuContent] under [rightMenuTitle] on the right. */
@Composable
fun DualMenuSubscreen(
    state: MenuState,
    closeScreen: () -> Unit,
    shape: Shape = RoundedCornerShape(21.dp),
    backgroundColor: Color = Color.Black.copy(alpha = 0.25f),
    titleHeight: Dp = 48.dp,
    leftMenuContent: @Composable ColumnScope.() -> Unit = {},
    rightMenuTitle: (@Composable BoxScope.() -> Unit)? = null,
    rightMenuContent: @Composable ColumnScope.() -> Unit = {},
) {
    val visible = state == MenuState.SHOW
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(backgroundColor).clickable(
                    indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = closeScreen,
                )
            )
        }
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.42f).fillMaxHeight().padding(top = 12.dp, start = 12.dp, bottom = 12.dp)) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + slideInHorizontally(tween(260)) { -40 },
                exit = fadeOut() + slideOutHorizontally { -40 },
            ) {
                MenuCard(shape) { leftMenuContent() }
            }
        }
        Box(Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.34f).fillMaxHeight().padding(top = 12.dp, end = 12.dp, bottom = 12.dp)) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + slideInHorizontally(tween(260)) { 40 },
                exit = fadeOut() + slideOutHorizontally { 40 },
            ) {
                MenuCard(shape) {
                    rightMenuTitle?.let { MenuTitle(it, titleHeight) }
                    rightMenuContent()
                }
            }
        }
    }
}

@Composable
private fun MenuCard(shape: Shape, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = cardColor, contentColor = MaterialTheme.colorScheme.onSurface),
        elevation = CardDefaults.cardElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        content = content,
    )
}

@Composable
private fun MenuTitle(title: @Composable BoxScope.() -> Unit, height: Dp) {
    Surface(Modifier.height(height).fillMaxWidth(), color = titleColor, contentColor = MaterialTheme.colorScheme.onSurface) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = title)
    }
    HorizontalDivider(Modifier.fillMaxWidth())
}

/** The tab strip of the left card (icons only, like Zalith's). */
@Composable
fun MenuIconTabs(icons: List<ImageVector>, selected: Int, onSelect: (Int) -> Unit) {
    ScrollableTabRow(
        selectedTabIndex = selected,
        edgePadding = 0.dp,
        containerColor = titleColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        divider = { HorizontalDivider() },
    ) {
        icons.forEachIndexed { i, icon ->
            Tab(selected = i == selected, onClick = { onSelect(i) }, icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) })
        }
    }
}

/** A scrolling page of rows, the way each of Zalith's tabs is laid out. */
@Composable
fun MenuPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
fun MenuSectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 6.dp, top = 6.dp),
    )
}

@Composable
fun MenuHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarqueeText(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleSmall) {
    Text(text, modifier = modifier.basicMarquee(), style = style, maxLines = 1, overflow = TextOverflow.Clip)
}

@Composable
fun MenuButtonLayout(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = itemColor,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit = {},
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit,
) {
    val scale = remember { Animatable(0.95f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, tween(200)) }
    Surface(
        modifier = modifier.graphicsLayer(scaleX = scale.value, scaleY = scale.value),
        shape = shape, color = color, contentColor = contentColor, enabled = enabled, onClick = onClick,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = horizontalArrangement, verticalAlignment = verticalAlignment, content = content)
    }
}

@Composable
fun MenuTextButton(
    modifier: Modifier = Modifier,
    text: String,
    enabled: Boolean = true,
    appendLayout: (@Composable () -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    MenuButtonLayout(modifier.fillMaxWidth(), enabled = enabled, onClick = onClick, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        MarqueeText(text, Modifier.padding(14.dp).alpha(if (enabled) 1f else DisabledAlpha).weight(1f))
        appendLayout?.invoke()
    }
}

@Composable
fun MenuSwitchButton(modifier: Modifier = Modifier, text: String, switch: Boolean, enabled: Boolean = true, onSwitch: (Boolean) -> Unit) {
    MenuButtonLayout(modifier.fillMaxWidth(), enabled = enabled, onClick = { onSwitch(!switch) }) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MarqueeText(text, Modifier.padding(horizontal = 4.dp).weight(1f).alpha(if (enabled) 1f else DisabledAlpha))
            Switch(checked = switch, onCheckedChange = onSwitch, enabled = enabled)
        }
    }
}

/** A small "✕" for [MenuTextButton.appendLayout]: clears whatever the row holds. */
@Composable
fun MenuClearButton(onClick: () -> Unit) {
    Text("✕", Modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), style = MaterialTheme.typography.titleSmall)
}

/** A titled slider with its value on the right. [onChange] fires while dragging. */
@Composable
fun MenuSliderLayout(
    modifier: Modifier = Modifier,
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    steps: Int = 0,
    format: (Float) -> String = { "%.2f".format(it) },
    onChange: (Float) -> Unit,
) {
    MenuButtonLayout(modifier.fillMaxWidth(), enabled = enabled) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Row(Modifier.padding(top = 8.dp).fillMaxWidth().alpha(if (enabled) 1f else DisabledAlpha), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MarqueeText(title, Modifier.weight(1f))
                Text(format(value), style = MaterialTheme.typography.titleSmall)
            }
            Slider(value = value.coerceIn(valueRange), onValueChange = onChange, valueRange = valueRange, steps = steps, enabled = enabled, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A row that opens a list of [items] and shows the chosen one. */
@Composable
fun <E> MenuListLayout(
    modifier: Modifier = Modifier,
    title: String,
    items: List<E>,
    currentItem: E,
    getItemText: (E) -> String,
    maxListHeight: Dp = 220.dp,
    onItemChange: (E) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    MenuButtonLayout(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MarqueeText(title)
                    Text(getItemText(currentItem), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val rotation by animateFloatAsState(if (expanded) -180f else 0f, tween(200), label = "arrow")
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.padding(end = 4.dp).size(28.dp).rotate(rotation))
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(200)),
                exit = shrinkVertically(tween(200)) + fadeOut(),
            ) {
                Column(Modifier.fillMaxWidth().heightIn(max = maxListHeight).verticalScroll(rememberScrollState()).padding(4.dp)) {
                    items.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().padding(2.dp).clip(MaterialTheme.shapes.medium)
                                .clickable { if (item != currentItem) onItemChange(item); expanded = false },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = item == currentItem, onClick = { if (item != currentItem) onItemChange(item); expanded = false })
                            MarqueeText(getItemText(item), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Zalith's draggable ball: a small translucent button the player can park anywhere; a tap opens the menu.
 * [position] is in pixels (Offset.Zero until first placed, then the top centre).
 */
@Composable
fun FloatingBall(
    position: Offset,
    onPositionChanged: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    onSavePos: () -> Unit = {},
    onClick: () -> Unit = {},
    alpha: Float = 1f,
    color: Color = Color.Black.copy(alpha = 0.35f),
    contentColor: Color = Color.White.copy(alpha = 0.95f),
    shape: Shape = RoundedCornerShape(14.dp),
    content: @Composable () -> Unit,
) {
    var ballSize by remember { mutableStateOf(IntSize.Zero) }
    val currentPosition by rememberUpdatedState(position)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnSavePos by rememberUpdatedState(onSavePos)
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    var placed by remember { mutableStateOf(position != Offset.Zero) }

    BoxWithConstraints(Modifier.alpha(alpha).fillMaxSize()) {
        val parentWidth by rememberUpdatedState(constraints.maxWidth)
        val parentHeight by rememberUpdatedState(constraints.maxHeight)
        Surface(
            modifier = Modifier
                .onSizeChanged { size ->
                    ballSize = size
                    if (!placed && size.width > 0) {
                        placed = true
                        onPositionChanged(Offset(((parentWidth - size.width) / 2f).coerceAtLeast(0f), 0f))
                    }
                }
                .offset {
                    val p = if (dragging) dragPosition else currentPosition
                    IntOffset(
                        p.x.roundToInt().coerceIn(0, (parentWidth - ballSize.width).coerceAtLeast(0)),
                        p.y.roundToInt().coerceIn(0, (parentHeight - ballSize.height).coerceAtLeast(0)),
                    )
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        var moved = false
                        val maxX = (parentWidth - ballSize.width).toFloat().coerceAtLeast(0f)
                        val maxY = (parentHeight - ballSize.height).toFloat().coerceAtLeast(0f)
                        val origin = Offset(currentPosition.x.coerceIn(0f, maxX), currentPosition.y.coerceIn(0f, maxY))
                        val completed = drag(down.id) { change ->
                            val delta = change.positionChange()
                            if (!moved && (change.position - start).getDistance() > viewConfiguration.touchSlop) {
                                moved = true; dragging = true; dragPosition = origin
                            }
                            if (moved) {
                                dragPosition = Offset((dragPosition.x + delta.x).coerceIn(0f, maxX), (dragPosition.y + delta.y).coerceIn(0f, maxY))
                            }
                            change.consume()
                        }
                        val upConsumed = if (completed) {
                            awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id }?.isConsumed ?: true
                        } else true
                        if (moved) {
                            onPositionChanged(dragPosition)
                            if (!upConsumed) currentOnSavePos()
                            dragging = false
                        } else if (!upConsumed) currentOnClick()
                    }
                }
                .then(modifier),
            color = color, contentColor = contentColor, shape = shape,
        ) { content() }
    }
}
