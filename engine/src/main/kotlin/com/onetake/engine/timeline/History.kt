package com.onetake.engine.timeline

/**
 * An immutable undo/redo history of timeline snapshots.
 *
 * Each stack is represented bottom-to-top, so its last entry is the next
 * snapshot restored by undo or redo.
 */
class History(
    val current: Timeline,
    undoStack: List<Timeline> = emptyList(),
    redoStack: List<Timeline> = emptyList(),
) {
    /** An immutable bottom-to-top snapshot stack available for undo. */
    val undoStack: List<Timeline> = immutableCopy(undoStack)

    /** An immutable bottom-to-top snapshot stack available for redo. */
    val redoStack: List<Timeline> = immutableCopy(redoStack)

    /** Whether [undo] can move to an earlier snapshot. */
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    /** Whether [redo] can move to a later snapshot. */
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Applies [next] and records the current snapshot for undo.
     *
     * Applying an equal timeline is a no-op and preserves both stacks.  Any
     * real edit starts a new branch and therefore clears the redo stack.
     */
    fun apply(next: Timeline): History = if (next == current) this else History(
        current = next,
        undoStack = undoStack + current,
        redoStack = emptyList(),
    )

    /** Undoes the newest edit, or returns this history when none is available. */
    fun undo(): History = if (!canUndo) this else History(
        current = undoStack.last(),
        undoStack = undoStack.dropLast(1),
        redoStack = redoStack + current,
    )

    /** Redoes the newest undone edit, or returns this history when none is available. */
    fun redo(): History = if (!canRedo) this else History(
        current = redoStack.last(),
        undoStack = undoStack + current,
        redoStack = redoStack.dropLast(1),
    )

    override fun equals(other: Any?): Boolean = other is History &&
        current == other.current &&
        undoStack == other.undoStack &&
        redoStack == other.redoStack

    override fun hashCode(): Int = 31 * (31 * current.hashCode() + undoStack.hashCode()) + redoStack.hashCode()

    override fun toString(): String =
        "History(current=$current, undoStack=$undoStack, redoStack=$redoStack)"

    companion object {
        private fun <T> immutableCopy(values: List<T>): List<T> =
            java.util.Collections.unmodifiableList(values.toList())
    }
}
