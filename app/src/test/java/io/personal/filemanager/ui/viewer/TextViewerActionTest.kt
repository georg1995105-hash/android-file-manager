package io.personal.filemanager.ui.viewer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Общая логика вставки во всех поддерживаемых текстовых форматах. */
class TextViewerActionTest {
    @Test fun insertsMultilineAtCursorWithoutDiscardingExistingContent() {
        val original = TextFieldValue("Начало\nКонец", selection = TextRange(7))
        val result = insertAtSelection(original, "строка 1\nстрока 2\n")
        assertEquals("Начало\nстрока 1\nстрока 2\nКонец", result.text)
        assertEquals(25, result.selection.start)
        assertEquals(result.selection.start, result.selection.end)
    }

    @Test fun replacesOnlySelectedRange() {
        val original = TextFieldValue("aaa [old] zzz", selection = TextRange(4, 9))
        val result = insertAtSelection(original, "{\n  \"valid\": true\n}")
        assertEquals("aaa {\n  \"valid\": true\n} zzz", result.text)
        assertEquals(4 + "{\n  \"valid\": true\n}".length, result.selection.start)
    }

    @Test fun longPasteKeepsEveryLine() {
        val pasted = "строка\n".repeat(60_000)
        val result = insertAtSelection(TextFieldValue(""), pasted)
        assertEquals(pasted, result.text)
        assertEquals(pasted.length, result.selection.start)
        assertTrue(result.text.count { it == '\n' } == 60_000)
    }
}
