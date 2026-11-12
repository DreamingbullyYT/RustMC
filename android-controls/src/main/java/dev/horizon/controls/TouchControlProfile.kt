package dev.horizon.controls

data class TouchControlProfile(
    val id: String = "default",
    val name: String = "Default",
    val layout: String = "JOYSTICK_FIXED",
    val sensitivityX: Float = 1.0f,
    val sensitivityY: Float = 1.0f,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    val gyroscope: Boolean = false,
    val autoSprint: Boolean = false,
    val opacity: Float = 0.82f,
    val buttonScale: Float = 1.0f
) {
    companion object {
        val layouts = listOf(
            "JOYSTICK_FIXED",
            "JOYSTICK_DYNAMIC",
            "DPAD",
            "BEDROCK",
            "VIRTUAL_POINTER"
        )
    }
}
