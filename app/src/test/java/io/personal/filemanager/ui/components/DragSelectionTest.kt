package io.personal.filemanager.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class DragSelectionTest {
    private val paths = listOf("a", "b", "c", "d", "e")

    @Test
    fun selectsContiguousRangeFromAnchor() {
        assertEquals(
            setOf("b", "c", "d"),
            dragSelectionResult(paths, emptySet(), 1, 3, selecting = true),
        )
    }

    @Test
    fun shrinkingDragDropsItemsOutsideCurrentRange() {
        val base = setOf("e")
        val wide = dragSelectionResult(paths, base, 1, 4, selecting = true)
        assertEquals(setOf("b", "c", "d", "e"), wide)
        assertEquals(
            setOf("b", "c", "e"),
            dragSelectionResult(paths, base, 1, 2, selecting = true),
        )
    }

    @Test
    fun dragFromSelectedItemDeselectsRangeOnly() {
        val base = setOf("a", "b", "c", "d", "e")
        assertEquals(
            setOf("a", "e"),
            dragSelectionResult(paths, base, 1, 3, selecting = false),
        )
    }
}
