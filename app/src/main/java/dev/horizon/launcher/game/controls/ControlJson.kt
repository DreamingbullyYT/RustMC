package dev.horizon.launcher.game.controls

import com.movtery.layer_controller.layout.ControlLayout
import com.movtery.layer_controller.layout.loadLayoutFromString
import com.movtery.layer_controller.utils.ColorSerializer
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

/**
 * Json configured like LayerController's own (colours are @Contextual). The old save path used a
 * plain `Json {}` which throws as soon as a layout contains a custom button/joystick style, so
 * every save in the app now goes through here.
 */
object ControlJson {
    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        serializersModule = SerializersModule { contextual(Color::class, ColorSerializer) }
    }

    fun encode(layout: ControlLayout): String = json.encodeToString(ControlLayout.serializer(), layout)

    /** Uses LayerController's loader so version checks and legacy migration still apply. */
    fun decode(text: String): ControlLayout = loadLayoutFromString(text)
}
