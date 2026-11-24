package io.personal.filemanager.ui.viewer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class TextEditingActionsTest {
    @Test fun pasteAtCursorKeepsMultilineAndCursor() {
        val input = TextFieldValue("до после", selection = TextRange(3))
        val inserted = insertAtSelection(input, "строка 1\nстрока 2\n")
        assertEquals("до строка 1\nстрока 2\nпосле", inserted.text)
        assertEquals(21, inserted.selection.start)
    }

    @Test fun pasteReplacesOnlySelectedRange() {
        val input = TextFieldValue("prefix 123 suffix", selection = TextRange(7, 10))
        val inserted = insertAtSelection(input, "много\nстрок")
        assertEquals("prefix много\nстрок suffix", inserted.text)
        assertEquals(18, inserted.selection.start)
        assertEquals(inserted.selection.start, inserted.selection.end)
    }

    @Test fun pasteWorksForLongTextIndependentlyOfFileExtension() {
        val longText = "документ\n".repeat(60_000)
        for (extension in listOf("txt", "md", "json", "xml", "yaml", "conf", "log")) {
            val value = insertAtSelection(TextFieldValue("", selection = TextRange(0)), longText)
            assertEquals(extension, longText, value.text)
        }
    }
}
