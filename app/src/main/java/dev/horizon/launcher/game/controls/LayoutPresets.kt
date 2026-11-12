package dev.horizon.launcher.game.controls

import com.movtery.layer_controller.data.ButtonPosition
import com.movtery.layer_controller.layout.ControlLayer
import com.movtery.layer_controller.layout.ControlLayout
import dev.horizon.launcher.game.TouchLayoutDefaults

/**
 * Built-in layouts. Every preset is the default layout with a small transform applied,
 * so they stay consistent with it and need no extra JSON files.
 */
object LayoutPresets {
    class Preset(val id: String, val label: String, val build: () -> ControlLayout)

    val all: List<Preset> = listOf(
        Preset("horizon", "Horizon (default)") { TouchLayoutDefaults.create() },
        Preset("left", "Left-handed") { mirror(TouchLayoutDefaults.create()) },
        Preset("compact", "Compact") { scale(TouchLayoutDefaults.create(), 0.8f) },
        Preset("big", "Big thumbs") { scale(TouchLayoutDefaults.create(), 1.25f) },
        Preset("minimal", "Minimal") { minimal(TouchLayoutDefaults.create()) },
    )

    fun labels(): List<String> = all.map { it.label }

    fun byLabel(label: String): ControlLayout? = all.firstOrNull { it.label == label }?.build?.invoke()

    private fun ControlLayout.mapLayers(f: (ControlLayer) -> ControlLayer) = copy(layers = layers.map(f))

    /** Mirrors every button across the vertical centre line (positions are fractions of free space). */
    private fun mirror(l: ControlLayout) = l.mapLayers { layer ->
        layer.copy(
            normalButtons = layer.normalButtons.map { it.copy(position = it.position.flip()) },
            joystickButtons = layer.joystickButtons.map { it.copy(position = it.position.flip()) },
        )
    }

    private fun ButtonPosition.flip() = ButtonPosition(10000 - x, y)

    private fun scaled(v: Int, f: Float) = (v * f).toInt().coerceIn(400, 4000)

    private fun scale(l: ControlLayout, f: Float) = l.mapLayers { layer ->
        layer.copy(
            normalButtons = layer.normalButtons.map {
                it.copy(buttonSize = it.buttonSize.copy(
                    widthPercentage = scaled(it.buttonSize.widthPercentage, f),
                    heightPercentage = scaled(it.buttonSize.heightPercentage, f)))
            },
            joystickButtons = layer.joystickButtons.map { it.copy(sizePercentage = scaled(it.sizePercentage, f)) },
        )
    }

    /** Drops the top icon row (inventory, chat, pause, camera, list): menus stay reachable via the "…" layer. */
    private fun minimal(l: ControlLayout) = l.mapLayers { layer ->
        layer.copy(normalButtons = layer.normalButtons.filter { it.position.y > 1500 })
    }
}
