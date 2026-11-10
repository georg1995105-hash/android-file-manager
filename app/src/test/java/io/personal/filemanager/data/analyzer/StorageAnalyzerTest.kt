package io.personal.filemanager.data.analyzer

import io.personal.filemanager.core.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageAnalyzerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun detectsOnlyVerifiedDuplicates() {
        val root = folder.root
        File(root, "same1.txt").writeText("одинаковое содержимое")
        File(root, "same2.txt").writeText("одинаковое содержимое")
        File(root, "different.txt").writeText("совершенно другое")
        File(root, "track.mp3").writeBytes(ByteArray(2048))
        val report = StorageAnalyzer().scan(root)
        assertEquals(4, report.scannedFiles)
        assertFalse(report.incomplete)
        assertEquals(1, report.duplicates.size)
        assertEquals(2, report.duplicates.first().files.size)
        assertEquals("track.mp3", report.largeFiles.first().name)
        assertEquals(1, report.categories.first { it.kind == FileKind.AUDIO }.count)
    }

    @Test fun hasStrictFileCap() {
        repeat(5) { File(folder.root, "a$it.txt").writeText("x") }
        val report = StorageAnalyzer().scan(folder.root, maxFiles = 3)
        assertEquals(3, report.scannedFiles)
        assertTrue(report.incomplete)
    }

    @Test fun ignoresActualRecoverableTrashFolder() {
        val original = File(folder.root, "original.opus").apply { writeText("voice") }
        val trash = File(folder.root, io.personal.filemanager.data.ops.TrashStore.TRASH_DIR)
        val archived = File(trash, "1234/payload").apply { mkdirs() }
        File(archived, "old.opus").writeText("voice")
        val report = StorageAnalyzer().scan(folder.root)
        assertEquals(1, report.scannedFiles)
        assertTrue(report.duplicates.isEmpty())
        assertTrue(original.exists())
        assertTrue(File(archived, "old.opus").exists())
    }

    @Test fun withoutHashBudgetNoUnverifiedMatches() {
        File(folder.root, "a.json").writeText("{}")
        File(folder.root, "b.json").writeText("{}")
        val report = StorageAnalyzer().scan(folder.root, hashBudget = 0)
        assertTrue(report.duplicates.isEmpty())
        assertTrue(report.duplicatesIncomplete)
        assertTrue(File(folder.root, "a.json").exists())
    }
}