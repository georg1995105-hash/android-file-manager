package io.personal.filemanager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TextLoaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun utf8Cyrillic() {
        val f = tmp.newFile("заметка.md").apply { writeText("# Привет\nмир", Charsets.UTF_8) }
        val r = TextLoader.load(f) as TextResult.Ok
        assertEquals("# Привет\nмир", r.text)
        assertEquals("UTF-8", r.charsetName)
        assertFalse(r.truncated)
    }

    @Test
    fun windows1251Fallback() {
        val f = tmp.newFile("old.txt").apply { writeBytes("Привет, мир".toByteArray(charset("windows-1251"))) }
        val r = TextLoader.load(f) as TextResult.Ok
        assertEquals("Привет, мир", r.text)
        assertEquals("Windows-1251", r.charsetName)
    }

    @Test
    fun bomVariants() {
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "да".toByteArray()
        assertEquals("да", (TextLoader.decode(utf8) as TextResult.Ok).text)
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "да".toByteArray(Charsets.UTF_16LE)
        assertEquals("да", (TextLoader.decode(utf16) as TextResult.Ok).text)
    }

    @Test
    fun binaryDetected() {
        val f = tmp.newFile("x.bin").apply { writeBytes(byteArrayOf(1, 2, 0, 4, 5)) }
        assertEquals(TextResult.Binary, TextLoader.load(f))
    }

    @Test
    fun truncatesLargeFilesWithoutBreakingUtf8() {
        val text = "я".repeat(1000) // 2000 байт
        val f = tmp.newFile("big.log").apply { writeText(text) }
        val r = TextLoader.load(f, limitBytes = 1001) as TextResult.Ok // граница посреди символа
        assertTrue(r.truncated)
        assertEquals(2000L, r.totalBytes)
        assertEquals("UTF-8", r.charsetName)
        assertEquals(500, r.text.length)
    }

    @Test
    fun defaultViewerLoadsTextLargerThanOldOneMegabyteLimit() {
        val file = tmp.newFile("long.md")
        val text = "Строка документа\\n".repeat(80_000)
        file.writeText(text, Charsets.UTF_8)
        val r = TextLoader.load(file) as TextResult.Ok
        assertFalse(r.truncated)
        assertEquals(text, r.text)
        assertTrue(TextFileEditor.snapshot(file, r.charsetName) != null)
    }

    @Test
    fun emptyAndMissing() {
        val empty = tmp.newFile("empty.txt")
        assertEquals("", (TextLoader.load(empty) as TextResult.Ok).text)
        assertTrue(TextLoader.load(File(tmp.root, "none.txt")) is TextResult.Error)
    }
}
