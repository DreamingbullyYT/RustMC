package dev.horizon.launcher.game

/** JNI bridge to Horizon's NativeActivity game loop. */
object GameInput {
    init { System.loadLibrary("horizon_android_game") }

    external fun nativeSendControl(key: String, pressed: Boolean)
    external fun nativeLook(dx: Float, dy: Float)
    external fun nativeLookActive(active: Boolean)
    external fun nativeCursorGrabbing(): Boolean
    external fun nativeGameFps(): Int
    /** [left, top, slotWidth, height] of the hotbar in physical pixels; zeros until the first frame. */
    external fun nativeHotbarRect(): FloatArray
    external fun nativeSetImeAllowed(allowed: Boolean)
    external fun nativeMenuTouch(id: Long, x: Float, y: Float, phase: Int)
}
