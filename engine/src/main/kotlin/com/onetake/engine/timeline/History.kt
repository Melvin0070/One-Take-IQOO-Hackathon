package com.onetake.engine.timeline

/** An immutable undo/redo history of timeline snapshots. */
class History(
    val current: Timeline,
    undoStack: List<Timeline> = emptyList(),
    redoStack: List<Timeline> = emptyList(),
) {
    val undoStack: List<Timeline> = java.util.Collections.unmodifiableList(undoStack.toList())
    val redoStack: List<Timeline> = java.util.Collections.unmodifiableList(redoStack.toList())

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun apply(next: Timeline): History = if (next == current) this else History(
        current = next,
        undoStack = undoStack + current,
        redoStack = emptyList(),
    )

    fun undo(): History = if (!canUndo) this else History(
        current = undoStack.last(),
        undoStack = undoStack.dropLast(1),
        redoStack = redoStack + current,
    )

    fun redo(): History = if (!canRedo) this else History(
        current = redoStack.last(),
        undoStack = undoStack + current,
        redoStack = redoStack.dropLast(1),
    )
}
