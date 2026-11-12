package io.personal.filemanager.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonPrettyPrinterTest {
    @Test fun pastedCompactJsonGetsReadableIndentation() {
        val json = """{"server":{"port":443,"enabled":true},"list":[1,2]}"""
        val formatted = JsonPrettyPrinter.format(json).getOrThrow()
        assertTrue(formatted.contains("\n"))
        assertTrue(formatted.contains("  \"server\""))
        assertTrue(formatted.contains("    \"port\": 443"))
        assertTrue(formatted.contains("    \"enabled\": true"))
        assertEquals(json.replace(" ", ""), formatted.replace(Regex("\\s+(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)"), ""))
    }

    @Test fun invalidJsonIsNotSilentlyRewritten() {
        assertTrue(JsonPrettyPrinter.format("""{"broken": }""").isFailure)
        assertTrue(JsonPrettyPrinter.format("""{"ok": 1}""").isSuccess)
    }

    @Test fun syntaxHighlightDoesNotAlterPastedCharacters() {
        val input = androidx.compose.ui.text.AnnotatedString("""{"text":"Привет","number":12}""")
        val transformed = SyntaxHighlight("json").filter(input)
        assertEquals(input.text, transformed.text.text)
        assertEquals(8, transformed.offsetMapping.originalToTransformed(8))
        assertTrue(transformed.text.spanStyles.isNotEmpty())
    }
}
