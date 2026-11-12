package com.movtery.layer_controller.layout

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Vector icons for touch buttons. A button whose text is `@name` (e.g. `@sword`) draws the icon
 * instead of text, so icons follow the normal style editor (content colour, pressed colour...)
 * and can be typed into any button's text field.
 */
object Glyphs {
    val names = setOf("sword", "use", "jump", "sneak", "sprint", "dots", "chat", "pause", "bag", "camera", "list")

    fun isGlyph(text: String) = text.startsWith("@") && text.substring(1).trim().lowercase() in names
    fun nameOf(text: String) = text.substring(1).trim().lowercase()
}

@Composable
fun GlyphIcon(text: String, color: Color, modifier: Modifier = Modifier) {
    val name = Glyphs.nameOf(text)
    Canvas(modifier = modifier) {
        // soft dark shadow keeps white icons readable on bright terrain, like the Bedrock buttons
        drawGlyph(name, Color.Black.copy(alpha = 0.35f), Offset(size.minDimension * 0.025f, size.minDimension * 0.035f))
        drawGlyph(name, color, Offset.Zero)
    }
}

private fun DrawScope.drawGlyph(name: String, color: Color, shift: Offset) {
    val s = size.minDimension
    val w = s * 0.095f
    val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s + shift.x, y * s + shift.y)
    fun line(a: Offset, b: Offset, width: Float = w) =
        drawLine(color, a, b, strokeWidth = width, cap = StrokeCap.Round)
    fun poly(pts: List<Offset>) {
        val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y) }
        drawPath(path, color, style = stroke)
    }
    when (name) {
        "jump" -> { line(p(.5f, .86f), p(.5f, .16f)); poly(listOf(p(.18f, .46f), p(.5f, .14f), p(.82f, .46f))) }
        "sneak" -> { line(p(.5f, .14f), p(.5f, .84f)); poly(listOf(p(.18f, .54f), p(.5f, .86f), p(.82f, .54f))) }
        "sprint" -> {
            poly(listOf(p(.14f, .22f), p(.46f, .5f), p(.14f, .78f)))
            poly(listOf(p(.50f, .22f), p(.82f, .5f), p(.50f, .78f)))
        }
        "sword" -> {
            line(p(.38f, .62f), p(.84f, .16f), w * 1.5f)
            line(p(.26f, .50f), p(.50f, .74f), w * 1.2f)
            line(p(.40f, .60f), p(.20f, .80f), w * 1.1f)
        }
        "use" -> {
            drawCircle(color, radius = s * 0.30f, center = p(.5f, .5f), style = stroke)
            drawCircle(color, radius = s * 0.07f, center = p(.5f, .5f))
        }
        "dots" -> for (x in listOf(.2f, .5f, .8f)) drawCircle(color, radius = s * 0.075f, center = p(x, .5f))
        "pause" -> { line(p(.34f, .2f), p(.34f, .8f), w * 1.3f); line(p(.66f, .2f), p(.66f, .8f), w * 1.3f) }
        "list" -> for (y in listOf(.28f, .5f, .72f)) line(p(.2f, y), p(.8f, y))
        "chat" -> {
            drawRoundRect(color, topLeft = p(.14f, .2f), size = Size(s * .72f, s * .46f),
                cornerRadius = CornerRadius(s * .1f), style = stroke)
            poly(listOf(p(.32f, .66f), p(.28f, .84f), p(.5f, .66f)))
            for (x in listOf(.35f, .5f, .65f)) drawCircle(color, radius = s * 0.03f, center = p(x, .43f))
        }
        "bag" -> {
            drawRoundRect(color, topLeft = p(.24f, .3f), size = Size(s * .52f, s * .54f),
                cornerRadius = CornerRadius(s * .12f), style = stroke)
            poly(listOf(p(.38f, .3f), p(.38f, .18f), p(.62f, .18f), p(.62f, .3f)))
            line(p(.34f, .6f), p(.66f, .6f))
        }
        "camera" -> {
            drawRoundRect(color, topLeft = p(.14f, .3f), size = Size(s * .72f, s * .5f),
                cornerRadius = CornerRadius(s * .08f), style = stroke)
            poly(listOf(p(.36f, .3f), p(.42f, .2f), p(.58f, .2f), p(.64f, .3f)))
            drawCircle(color, radius = s * 0.12f, center = p(.5f, .55f), style = stroke)
        }
    }
}
