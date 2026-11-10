package io.personal.filemanager.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TextFileEditorTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun savesUtf8AndPreservesContent() {
        val file = folder.newFile("config.json")
        file.writeText("{}")
        val before = requireNotNull(TextFileEditor.snapshot(file, "UTF-8"))
        val saved = TextFileEditor.save(file, "{\"name\":\"тест\"}", before)
        assertTrue(saved is TextFileEditor.Result.Saved)
        assertEquals("{\"name\":\"тест\"}", file.readText())
    }

    @Test fun externalChangesAreNotOverwritten() {
        val file = folder.newFile("config.json")
        file.writeText("{\"a\":1}")
        val before = requireNotNull(TextFileEditor.snapshot(file, "UTF-8"))
        file.writeText("{\"a\":2}")
        assertEquals(TextFileEditor.Result.ChangedExternally, TextFileEditor.save(file, "{\"a\":3}", before))
        assertEquals("{\"a\":2}", file.readText())
    }

    @Test fun utf16BomIsPreserved() {
        val file = folder.newFile("notes.txt")
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "one".toByteArray(Charsets.UTF_16LE))
        val before = requireNotNull(TextFileEditor.snapshot(file, "UTF-16LE"))
        assertTrue(TextFileEditor.save(file, "два", before) is TextFileEditor.Result.Saved)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), file.readBytes().copyOfRange(0, 2))
        assertEquals("два", file.readBytes().copyOfRange(2, file.length().toInt()).toString(Charsets.UTF_16LE))
    }

    @Test fun unsupportedEncodingDoesNotCorruptOriginal() {
        val file = folder.newFile("old.txt")
        file.writeText("abc")
        val before = requireNotNull(TextFileEditor.snapshot(file, "ISO-8859-1"))
        assertTrue(TextFileEditor.save(file, "Привет", before) is TextFileEditor.Result.Failure)
        assertEquals("abc", file.readText())
    }

    @Test fun largeFilesStayReadOnly() {
        val file = folder.newFile("huge.txt")
        file.writeBytes(ByteArray(TextFileEditor.MAX_EDIT_BYTES + 1))
        assertEquals(null, TextFileEditor.snapshot(file, "UTF-8"))
    }
}
