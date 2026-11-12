package dev.horizon.launcher.game.controls

/** One bindable output. [id] is exactly what Horizon's Rust input bridge understands. */
data class KeyOption(val id: String, val label: String, val group: String)

/**
 * Every key/mouse output the Android game bridge accepts (see `android_touch_output` in
 * horizon-client). Keeping this list in one place means the editor can never offer a binding
 * that the game would silently ignore.
 */
object KeyCatalog {
    const val MOUSE = "Mouse"
    const val MOVEMENT = "Movement"
    const val LETTERS = "Letters"
    const val NUMBERS = "Numbers"
    const val FUNCTION = "Function"
    const val SPECIAL = "Special"

    val all: List<KeyOption> = buildList {
        add(KeyOption("GLFW_MOUSE_BUTTON_LEFT", "Mouse left (attack / break)", MOUSE))
        add(KeyOption("GLFW_MOUSE_BUTTON_RIGHT", "Mouse right (use / place)", MOUSE))
        add(KeyOption("GLFW_MOUSE_BUTTON_MIDDLE", "Mouse middle (pick block)", MOUSE))

        add(KeyOption("GLFW_KEY_SPACE", "Space (jump)", MOVEMENT))
        add(KeyOption("GLFW_KEY_LEFT_SHIFT", "Shift (sneak)", MOVEMENT))
        add(KeyOption("GLFW_KEY_LEFT_CONTROL", "Ctrl (sprint)", MOVEMENT))
        add(KeyOption("GLFW_KEY_LEFT_ALT", "Alt", MOVEMENT))
        add(KeyOption("GLFW_KEY_UP", "Arrow up", MOVEMENT))
        add(KeyOption("GLFW_KEY_DOWN", "Arrow down", MOVEMENT))
        add(KeyOption("GLFW_KEY_LEFT", "Arrow left", MOVEMENT))
        add(KeyOption("GLFW_KEY_RIGHT", "Arrow right", MOVEMENT))

        for (c in 'A'..'Z') add(KeyOption("GLFW_KEY_$c", c.toString(), LETTERS))
        for (d in 0..9) add(KeyOption("GLFW_KEY_$d", d.toString(), NUMBERS))
        for (f in 1..12) add(KeyOption("GLFW_KEY_F$f", "F$f", FUNCTION))

        add(KeyOption("GLFW_KEY_ESCAPE", "Escape", SPECIAL))
        add(KeyOption("GLFW_KEY_TAB", "Tab", SPECIAL))
        add(KeyOption("GLFW_KEY_ENTER", "Enter", SPECIAL))
        add(KeyOption("GLFW_KEY_BACKSPACE", "Backspace", SPECIAL))
        add(KeyOption("GLFW_KEY_PAGE_UP", "Page up", SPECIAL))
        add(KeyOption("GLFW_KEY_PAGE_DOWN", "Page down", SPECIAL))
    }

    val groups: List<String> = all.map { it.group }.distinct()
    private val byId: Map<String, KeyOption> = all.associateBy { it.id }

    fun label(id: String): String = byId[id]?.label
        ?: id.removePrefix("GLFW_KEY_").removePrefix("GLFW_MOUSE_BUTTON_").lowercase().replaceFirstChar { it.uppercase() }

    fun inGroup(group: String): List<KeyOption> = all.filter { it.group == group }
}
