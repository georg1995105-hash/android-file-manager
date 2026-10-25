package io.personal.filemanager.data

import io.personal.filemanager.data.ops.ConflictPolicy
import io.personal.filemanager.data.ops.FileOperations
import io.personal.filemanager.data.ops.RenameResult
import io.personal.filemanager.data.ops.TrashStore
import io.personal.filemanager.data.ops.splitName
import io.personal.filemanager.data.ops.uniqueTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Опасные операции — только на автоматически созданных образцах (ТЗ §8). */
class FileOperationsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var src: File
    private lateinit var dst: File
    private lateinit var trashRoot: File
    private lateinit var trash: TrashStore
    private lateinit var ops: FileOperations

    @Before
    fun setUp() {
        src = tmp.newFolder("src")
        dst = tmp.newFolder("dst")
        trashRoot = File(tmp.root, TrashStore.TRASH_DIR)
        trash = TrashStore { trashRoot }
        ops = FileOperations(trash) { null }
        File(src, "заметка.md").writeText("# привет")
        File(src, "big.bin").writeBytes(ByteArray(3_000_000) { (it % 251).toByte() })
        File(src, "папка/вложенная").mkdirs()
        File(src, "папка/вложенная/a.txt").writeText("a")
        File(src, "папка/b.txt").writeText("b")
    }

    private fun f(dir: File, path: String) = File(dir, path)

    @Test
    fun copyFilesAndFoldersVerbatim() {
        val report = ops.copy(listOf(f(src, "заметка.md"), f(src, "big.bin"), f(src, "папка")), dst, ConflictPolicy.SKIP)
        assertTrue(report.failed.toString(), report.ok)
        assertEquals("# привет", f(dst, "заметка.md").readText())
        assertTrue(f(src, "big.bin").readBytes().contentEquals(f(dst, "big.bin").readBytes()))
        assertEquals("a", f(dst, "папка/вложенная/a.txt").readText())
        assertTrue(f(src, "заметка.md").exists()) // оригинал на месте
        assertTrue(dst.walkTopDown().none { it.name.startsWith(FileOperations.PARTIAL_PREFIX) })
    }

    @Test
    fun conflictSkipRenameReplace() {
        f(dst, "заметка.md").writeText("старая")
        val one = listOf(f(src, "заметка.md"))
        assertEquals(listOf("заметка.md"), ops.conflicts(one, dst))

        ops.copy(one, dst, ConflictPolicy.SKIP).also { assertEquals(1, it.skipped.size) }
        assertEquals("старая", f(dst, "заметка.md").readText())

        ops.copy(one, dst, ConflictPolicy.RENAME)
        assertEquals("# привет", f(dst, "заметка (1).md").readText())
        assertEquals("старая", f(dst, "заметка.md").readText())

        ops.copy(one, dst, ConflictPolicy.REPLACE)
        assertEquals("# привет", f(dst, "заметка.md").readText())
        // Заменённый файл не потерян — он в корзине.
        val trashed = trash.list(listOf(trashRoot))
        assertEquals(1, trashed.size)
        assertEquals("старая", trashed.first().payload.readText())
    }

    @Test
    fun cancelLeavesSourceAndNoPartialFiles() {
        var calls = 0
        val report = ops.copy(listOf(f(src, "big.bin")), dst, ConflictPolicy.SKIP, isCancelled = { ++calls > 3 })
        assertTrue(report.cancelled)
        assertFalse(f(dst, "big.bin").exists())
        assertTrue(dst.listFiles()!!.isEmpty())
        assertEquals(3_000_000L, f(src, "big.bin").length())
    }

    @Test
    fun moveDeletesOriginalOnlyAfterSuccess() {
        val report = ops.move(listOf(f(src, "папка"), f(src, "заметка.md")), dst, ConflictPolicy.SKIP)
        assertTrue(report.ok)
        assertFalse(f(src, "папка").exists())
        assertFalse(f(src, "заметка.md").exists())
        assertEquals("b", f(dst, "папка/b.txt").readText())
    }

    @Test
    fun moveAcrossVolumesUsesVerifiedCopy() {
        val crossOps = FileOperations(trash) { file -> if (file.absolutePath.startsWith(dst.absolutePath)) "B" else "A" }
        val report = crossOps.move(listOf(f(src, "big.bin")), dst, ConflictPolicy.SKIP)
        assertTrue(report.ok)
        assertFalse(f(src, "big.bin").exists())
        assertEquals(3_000_000L, f(dst, "big.bin").length())
    }

    @Test
    fun cannotCopyFolderIntoItself() {
        val report = ops.copy(listOf(f(src, "папка")), f(src, "папка/вложенная"), ConflictPolicy.SKIP)
        assertFalse(report.ok)
        assertTrue(f(src, "папка/b.txt").exists())
    }

    @Test
    fun missingDestinationFailsWithoutTouchingSource() {
        val report = ops.move(listOf(f(src, "заметка.md")), f(tmp.root, "нет"), ConflictPolicy.SKIP)
        assertFalse(report.ok)
        assertTrue(f(src, "заметка.md").exists())
    }

    @Test
    fun insufficientSpaceIsDetectedBeforeStart() {
        // Источник «больше» свободного места: проверяем через огромный разреженный файл.
        val huge = File(src, "huge.img")
        java.io.RandomAccessFile(huge, "rw").use { it.setLength(dst.usableSpace + 10_000_000_000L) }
        val report = ops.copy(listOf(huge), dst, ConflictPolicy.SKIP)
        assertFalse(report.ok)
        assertTrue(report.failed.first().second.startsWith("Недостаточно места"))
        assertFalse(f(dst, "huge.img").exists())
    }

    @Test
    fun renameRules() {
        val file = f(src, "заметка.md")
        assertTrue(ops.rename(file, "a/b") is RenameResult.Error)
        assertTrue(ops.rename(file, "big.bin") is RenameResult.Error)
        val ok = ops.rename(file, " Новое имя.md ") as RenameResult.Ok
        assertEquals("Новое имя.md", ok.file.name)
        assertEquals("# привет", ok.file.readText())
    }

    @Test
    fun trashAndRestore() {
        val file = f(src, "заметка.md")
        val report = ops.moveToTrash(listOf(file, f(src, "папка")))
        assertTrue(report.ok)
        assertFalse(file.exists())
        assertTrue(File(trashRoot, ".nomedia").exists())
        val items = trash.list(listOf(trashRoot))
        assertEquals(2, items.size)

        // Если на старом месте уже появился файл с тем же именем — восстановление не перезаписывает.
        file.writeText("новый")
        val restoredNote = trash.restore(items.first { it.meta.name == "заметка.md" }).getOrThrow()
        assertEquals("заметка (1).md", restoredNote.name)
        assertEquals("новый", file.readText())

        val restoredDir = trash.restore(items.first { it.meta.name == "папка" }).getOrThrow()
        assertEquals("a", File(restoredDir, "вложенная/a.txt").readText())
        assertTrue(trash.list(listOf(trashRoot)).isEmpty())
    }

    @Test
    fun purgeRemovesOnlyTrashItem() {
        ops.moveToTrash(listOf(f(src, "заметка.md")))
        val item = trash.list(listOf(trashRoot)).single()
        assertTrue(trash.purge(item))
        assertTrue(f(src, "big.bin").exists())
        assertTrue(trash.list(listOf(trashRoot)).isEmpty())
    }

    @Test
    fun uniqueNames() {
        File(dst, "a.txt").writeText("")
        File(dst, "a (1).txt").writeText("")
        assertEquals("a (2).txt", uniqueTarget(dst, "a.txt").name)
        assertEquals("backup" to ".tar.gz", splitName("backup.tar.gz"))
        assertEquals(".hidden" to "", splitName(".hidden"))
        assertEquals("Папка (1)", uniqueTarget(dst.also { File(it, "Папка").mkdir() }, "Папка").name)
    }

    @Test
    fun volumeRoots() {
        assertEquals("/storage/emulated/0", FileOperations.defaultVolumeRoot(File("/storage/emulated/0/Download/x")))
        assertEquals("/storage/1234-ABCD", FileOperations.defaultVolumeRoot(File("/storage/1234-ABCD/DCIM")))
        assertTrue(FileOperations.isInside(File("/a/b/c"), File("/a/b")))
        assertFalse(FileOperations.isInside(File("/a/bc"), File("/a/b")))
    }
}
