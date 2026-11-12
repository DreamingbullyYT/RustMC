package dev.ferrite.core

enum class WidgetKind { JOYSTICK, BUTTON, LOOK_AREA, HOTBAR }

/** Position and size are fractions of the screen (0..1), so layouts survive any resolution. */
data class Widget(val id: String, val kind: WidgetKind, val x: Double, val y: Double, val size: Double, val action: String = "", val opacity: Double = 0.6) {
    fun toMap(): Map<String, Any?> = linkedMapOf("id" to id, "kind" to kind.name.lowercase(), "x" to x, "y" to y, "size" to size, "action" to action, "opacity" to opacity)
}

data class TouchLayout(val name: String, val widgets: List<Widget>) {
    fun toJson(): String = Json.write(linkedMapOf("format" to 1, "name" to name, "widgets" to widgets.map { it.toMap() }), pretty = true)

    /** Moves one widget, keeping it fully on screen. */
    fun move(id: String, x: Double, y: Double) = copy(widgets = widgets.map {
        if (it.id != id) it else it.copy(x = x.coerceIn(it.size / 2, 1 - it.size / 2), y = y.coerceIn(it.size / 2, 1 - it.size / 2))
    })

    /** Ids of widgets whose squares overlap (centre x/y, side `size`, in screen fractions). */
    fun overlaps(): List<Pair<String, String>> = buildList {
        for (a in widgets.indices) for (b in a + 1 until widgets.size) {
            val p = widgets[a]; val q = widgets[b]; val r = (p.size + q.size) / 2
            if (Math.abs(p.x - q.x) < r && Math.abs(p.y - q.y) < r && p.kind != WidgetKind.LOOK_AREA && q.kind != WidgetKind.LOOK_AREA) add(p.id to q.id)
        }
    }

    companion object {
        fun fromJson(text: String): TouchLayout {
            val o = Json.parse(text).obj()
            require(o["format"].int() == 1) { "unsupported layout format" }
            val ws = o["widgets"].arr().map { w ->
                val m = w.obj()
                val kind = WidgetKind.entries.firstOrNull { it.name.equals(m["kind"].str(), true) } ?: error("bad widget kind")
                Widget(m["id"].str(), kind, m["x"].num().coerceIn(0.0, 1.0), m["y"].num().coerceIn(0.0, 1.0), m["size"].num(0.1).coerceIn(0.03, 1.0), m["action"].str(), m["opacity"].num(0.6).coerceIn(0.0, 1.0))
            }
            require(ws.map { it.id }.toSet().size == ws.size) { "duplicate widget ids" }
            return TouchLayout(o["name"].str("imported"), ws)
        }

        /** Bedrock-style: left stick, right look area, jump and sneak buttons, hotbar along the bottom. */
        val MCPE = TouchLayout("mcpe", listOf(
            Widget("look", WidgetKind.LOOK_AREA, 0.75, 0.5, 0.5, "look", 0.0),
            Widget("move", WidgetKind.JOYSTICK, 0.14, 0.72, 0.2, "move"),
            Widget("jump", WidgetKind.BUTTON, 0.88, 0.7, 0.12, "jump"),
            Widget("sneak", WidgetKind.BUTTON, 0.74, 0.82, 0.09, "sneak"),
            Widget("hotbar", WidgetKind.HOTBAR, 0.5, 0.94, 0.1, "hotbar"),
        ))
        /** Java-style: more buttons, joystick lower left. */
        val JAVA = TouchLayout("java", MCPE.widgets + listOf(
            Widget("inventory", WidgetKind.BUTTON, 0.94, 0.08, 0.08, "inventory"),
            Widget("drop", WidgetKind.BUTTON, 0.84, 0.08, 0.08, "drop"),
        ))
        val PRESETS = listOf(MCPE, JAVA)
    }
}
