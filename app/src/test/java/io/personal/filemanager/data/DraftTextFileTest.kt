package io.personal.filemanager.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saving new text documents is an explicit operation, never a side effect of opening an editor. */
class DraftTextFileTest {
    @Test fun cancellingCreationLeavesNoEmptyFile() {
        val dir = Files.createTempDirectory("myfiles-draft").toFile()
        try {
            val target = File(dir, "config.json")
            val result = FileSystemRepository().prepareFile(dir.path, target.name)
            assertTrue(result is CreateFolderResult.Created)
            // Simulated cancellation: a draft exists only in ViewModel memory.
            assertFalse(target.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun firstSaveCreatesFileWithExactUtf8Text() {
        val dir = Files.createTempDirectory("myfiles-draft").toFile()
        try {
            val file = File(dir, "config.json")
            val data = """{"message":"Привет","items":[1,2,3]}""" + "\n"
            assertTrue(TextFileEditor.create(file, data) is TextFileEditor.Result.Saved)
            assertEquals(data, file.readText(Charsets.UTF_8))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun createDoesNotOverwriteExistingFile() {
        val dir = Files.createTempDirectory("myfiles-draft").toFile()
        try {
            val file = File(dir, "README.md")
            file.writeText("original")
            assertTrue(TextFileEditor.create(file, "other") is TextFileEditor.Result.ChangedExternally)
            assertEquals("original", file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun userCanExplicitlySaveEmptyFile() {
        val dir = Files.createTempDirectory("myfiles-draft").toFile()
        try {
            val file = File(dir, "empty.txt")
            assertTrue(TextFileEditor.create(file, "") is TextFileEditor.Result.Saved)
            assertTrue(file.isFile)
            assertEquals(0L, file.length())
        } finally {
            dir.deleteRecursively()
        }
    }
}
