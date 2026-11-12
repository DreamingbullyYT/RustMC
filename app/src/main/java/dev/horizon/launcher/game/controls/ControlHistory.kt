package dev.horizon.launcher.game.controls

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.movtery.layer_controller.layout.ControlLayout

/**
 * Snapshot-based undo/redo for the layout editor (snapshots are packed, immutable layouts).
 * [version] is Compose state so Undo/Redo buttons enable and disable as the stacks change.
 */
class ControlHistory(private val limit: Int = 40) {
    private val undoStack = ArrayDeque<ControlLayout>()
    private val redoStack = ArrayDeque<ControlLayout>()

    var version by mutableIntStateOf(0)
        private set

    val canUndo: Boolean get() = version >= 0 && undoStack.isNotEmpty()
    val canRedo: Boolean get() = version >= 0 && redoStack.isNotEmpty()

    /** Call with the current state right BEFORE a change is made. */
    fun record(current: ControlLayout) {
        if (undoStack.lastOrNull() == current) return
        undoStack.addLast(current)
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        version++
    }

    /** Returns the layout to switch to, skipping snapshots identical to what is already shown. */
    fun undo(current: ControlLayout): ControlLayout? {
        var previous = undoStack.removeLastOrNull()
        while (previous != null && previous == current) previous = undoStack.removeLastOrNull()
        if (previous == null) { version++; return null }
        redoStack.addLast(current)
        version++
        return previous
    }

    fun redo(current: ControlLayout): ControlLayout? {
        var next = redoStack.removeLastOrNull()
        while (next != null && next == current) next = redoStack.removeLastOrNull()
        if (next == null) { version++; return null }
        undoStack.addLast(current)
        version++
        return next
    }

    fun clear() { undoStack.clear(); redoStack.clear(); version++ }
}
