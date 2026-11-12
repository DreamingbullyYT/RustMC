package dev.horizon.launcher.game

import androidx.compose.ui.graphics.Color
import com.movtery.layer_controller.data.ButtonPosition
import com.movtery.layer_controller.data.ButtonShape
import com.movtery.layer_controller.data.DefaultButtonStyleConfig
import com.movtery.layer_controller.data.DefaultJoystickStyleConfig
import com.movtery.layer_controller.data.DefaultSize
import com.movtery.layer_controller.data.JoystickData
import com.movtery.layer_controller.data.NormalData
import com.movtery.layer_controller.data.TextAlignment
import com.movtery.layer_controller.data.VisibilityType
import com.movtery.layer_controller.data.createNewButtonStyle
import com.movtery.layer_controller.data.createNewJoystickStyle
import com.movtery.layer_controller.data.lang.createTranslatable
import com.movtery.layer_controller.event.ClickEvent
import com.movtery.layer_controller.layout.ControlLayout
import com.movtery.layer_controller.layout.createNewLayer
import com.movtery.layer_controller.observable.ObservableControlLayer
import java.util.UUID

/** Horizon's default layout, using Zalith's native control-layout model. */
object TouchLayoutDefaults {
    fun create(): ControlLayout {
        val glass = createNewButtonStyle("Glass").copy(commonStyle = true).let { base ->
            val cfg = DefaultButtonStyleConfig.copy(
                backgroundColor = Color(0x40FFFFFF), pressedBackgroundColor = Color(0x80FFFFFF),
                contentColor = Color.White, pressedContentColor = Color.White,
                borderWidth = 2, pressedBorderWidth = 2,
                borderColor = Color(0xBFFFFFFF), pressedBorderColor = Color.White,
                borderRadius = ButtonShape(10f), pressedBorderRadius = ButtonShape(10f), alpha = 1f, pressedAlpha = 1f)
            base.copy(lightStyle = cfg, darkStyle = cfg)
        }
        val squareStick = createNewJoystickStyle("Glass square").copy(commonStyle = true).let { base ->
            val cfg = DefaultJoystickStyleConfig.copy(
                backgroundShape = 10, joystickShape = 10,
                backgroundColor = Color(0x4DFFFFFF), borderWidthRatio = 4, borderColor = Color(0xBFFFFFFF),
                joystickColor = Color(0x99FFFFFF), joystickSize = 0.4f)
            base.copy(lightStyle = cfg, darkStyle = cfg)
        }

        // Positions in LayerController are a fraction of the free space, so convert from "centre of the
        // button at (cx, cy) of the screen" assuming a ~20:9 landscape screen.
        fun pos(cx: Float, cy: Float, size: Int): ButtonPosition {
            val h = size / 10000f
            val w = h / 2.2f
            val x = ((cx - w / 2f) / (1f - w)).coerceIn(0f, 1f)
            val y = ((cy - h / 2f) / (1f - h)).coerceIn(0f, 1f)
            return ButtonPosition((x * 10000).toInt(), (y * 10000).toInt())
        }
        fun button(label: String, events: List<ClickEvent>, cx: Float, cy: Float, size: Int,
                   visibility: VisibilityType, toggle: Boolean = false) = NormalData(
            text = createTranslatable(label), uuid = UUID.randomUUID().toString(),
            position = pos(cx, cy, size),
            buttonSize = DefaultSize.copy(widthPercentage = size, heightPercentage = size),
            buttonStyle = glass.uuid, textAlignment = TextAlignment.Center, textBold = true,
            visibilityType = visibility, _clickEvents = events,
            isSwipple = false, isPenetrable = false, isToggleable = toggle
        )
        fun key(id: String) = listOf(ClickEvent(ClickEvent.Type.Key, id))

        // Hidden-at-start layer opened by the "…" button.
        val more = ObservableControlLayer(createNewLayer("More").copy(visibilityType = VisibilityType.IN_GAME))
        more.editorHide = true
        more.hide = true
        more.addNormalButton(button("Drop", key("GLFW_KEY_Q"), 0.36f, 0.60f, 900, VisibilityType.IN_GAME))
        more.addNormalButton(button("Swap", key("GLFW_KEY_F"), 0.42f, 0.60f, 900, VisibilityType.IN_GAME))
        more.addNormalButton(button("Pick", listOf(ClickEvent(ClickEvent.Type.Key, "GLFW_MOUSE_BUTTON_MIDDLE")), 0.48f, 0.60f, 900, VisibilityType.IN_GAME))
        more.addNormalButton(button("HUD", key("GLFW_KEY_F1"), 0.54f, 0.60f, 900, VisibilityType.IN_GAME))
        more.addNormalButton(button("Run", key("GLFW_KEY_LEFT_CONTROL"), 0.60f, 0.60f, 900, VisibilityType.IN_GAME, toggle = true))

        val gameplay = ObservableControlLayer(createNewLayer("Gameplay").copy(visibilityType = VisibilityType.IN_GAME))
        gameplay.apply {
            addJoystickButton(JoystickData(
                uuid = UUID.randomUUID().toString(), position = pos(0.12f, 0.70f, 2800),
                sizePercentage = 2800, visibilityType = VisibilityType.IN_GAME, joystickStyleId = squareStick.uuid
            ))
            addNormalButton(button("@sword", key("GLFW_MOUSE_BUTTON_LEFT"), 0.83f, 0.64f, 1400, VisibilityType.IN_GAME))
            addNormalButton(button("@use", key("GLFW_MOUSE_BUTTON_RIGHT"), 0.94f, 0.52f, 1300, VisibilityType.IN_GAME))
            addNormalButton(button("@jump", key("GLFW_KEY_SPACE"), 0.955f, 0.78f, 1400, VisibilityType.IN_GAME))
            addNormalButton(button("@sprint", key("GLFW_KEY_LEFT_CONTROL"), 0.74f, 0.77f, 1100, VisibilityType.IN_GAME, toggle = true))
            addNormalButton(button("@sneak", key("GLFW_KEY_LEFT_SHIFT"), 0.85f, 0.87f, 1100, VisibilityType.IN_GAME))
            addNormalButton(button("@dots", listOf(ClickEvent(ClickEvent.Type.SwitchLayer, more.uuid)), 0.75f, 0.92f, 800, VisibilityType.IN_GAME))
            // Icon row, top centre
            addNormalButton(button("@bag", key("GLFW_KEY_E"), 0.430f, 0.08f, 850, VisibilityType.IN_GAME))
            addNormalButton(button("@chat", key("GLFW_KEY_T"), 0.475f, 0.08f, 850, VisibilityType.IN_GAME))
            addNormalButton(button("@pause", key("GLFW_KEY_ESCAPE"), 0.520f, 0.08f, 850, VisibilityType.IN_GAME))
            addNormalButton(button("@camera", key("GLFW_KEY_F5"), 0.565f, 0.08f, 850, VisibilityType.IN_GAME))
            addNormalButton(button("@list", key("GLFW_KEY_TAB"), 0.610f, 0.08f, 850, VisibilityType.IN_GAME))
        }

        // Shown whenever a GUI has the cursor (inventory, pause screen, chat, crafting, ...).
        val menu = ObservableControlLayer(createNewLayer("Menu").copy(visibilityType = VisibilityType.IN_MENU))
        menu.apply {
            addNormalButton(button("Back", key("GLFW_KEY_ESCAPE"), 0.07f, 0.12f, 1100, VisibilityType.IN_MENU))
            addNormalButton(button("@bag", key("GLFW_KEY_E"), 0.07f, 0.30f, 1100, VisibilityType.IN_MENU))
            addNormalButton(button("Shift", key("GLFW_KEY_LEFT_SHIFT"), 0.07f, 0.48f, 1100, VisibilityType.IN_MENU, toggle = true))
            addNormalButton(button("Drop", key("GLFW_KEY_Q"), 0.07f, 0.66f, 1100, VisibilityType.IN_MENU))
            addNormalButton(button("Enter", key("GLFW_KEY_ENTER"), 0.93f, 0.30f, 1100, VisibilityType.IN_MENU))
        }

        return ControlLayout(
            info = ControlLayout.Info(createTranslatable("Horizon touch"), createTranslatable("Horizon"),
                createTranslatable("Default Horizon touch layout"), 3, "3"),
            layers = listOf(more.pack(), menu.pack(), gameplay.pack()),
            styles = listOf(glass), joystickStyles = listOf(squareStick),
            editorVersion = 12
        )
    }

}
