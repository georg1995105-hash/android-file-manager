package io.personal.filemanager.data

import io.personal.filemanager.data.settings.BrowserPrefs
import io.personal.filemanager.data.settings.BrowserSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Только автоматически созданные тестовые файлы (ТЗ §8): никаких личных данных. */
class FileSystemRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val repo = FileSystemRepository()
    private lateinit var root: File

    @Before
    fun fixtures() {
        root = tmp.newFolder("storage")
        File(root, "Пустая папка").mkdir()
        File(root, "Документы").mkdir()
        File(root, "Документы/заметка.md").writeText("# Заголовок\nТекст по-русски")
        File(root, "Документы/одинаковое.txt").writeText("в папке")
        File(root, "одинаковое.txt").writeText("в корне — то же имя")
        File(root, "Отчёт_2024.pdf").writeBytes(ByteArray(2048))
        File(root, "archive.zip").writeBytes(ByteArray(10))
        File(root, "app-release.apk").writeBytes(ByteArray(300))
        File(root, ".hidden").writeText("secret-looking but test")
        File(root, "Б-файл.json").writeText("{}")
        File(root, "а-файл.yaml").writeText("a: 1")
        File(root, "Б-файл.json").setLastModified(1_000_000L)
        File(root, "а-файл.yaml").setLastModified(2_000_000L)
    }

    private fun names(prefs: BrowserPrefs = BrowserPrefs()): List<String> =
        (repo.list(root.path, prefs) as FolderResult.Ok).entries.map { it.name }

    @Test
    fun foldersFirstThenRussianAlphabetical() {
        val n = names()
        assertEquals(listOf("Документы", "Пустая папка"), n.take(2))
        assertTrue(n.indexOf("а-файл.yaml") < n.indexOf("Б-файл.json"))
        assertFalse(".hidden" in n)
    }

    @Test
    fun hiddenFilesShownOnRequest() {
        assertTrue(".hidden" in names(BrowserPrefs(showHidden = true)))
    }

    @Test
    fun sortBySizeDescending() {
        val files = names(BrowserPrefs(sort = BrowserSort.SIZE, descending = true)).drop(2)
        assertEquals("Отчёт_2024.pdf", files.first())
    }

    @Test
    fun sortByDate() {
        val n = names(BrowserPrefs(sort = BrowserSort.DATE))
        assertTrue(n.indexOf("Б-файл.json") < n.indexOf("а-файл.yaml"))
    }

    @Test
    fun emptyFolderAndChildCount() {
        val ok = repo.list(File(root, "Пустая папка").path, BrowserPrefs()) as FolderResult.Ok
        assertTrue(ok.entries.isEmpty())
        val docs = (repo.list(root.path, BrowserPrefs()) as FolderResult.Ok).entries.first { it.name == "Документы" }
        assertEquals(2, docs.childCount)
    }

    @Test
    fun missingOrNotADirectory() {
        assertEquals(FolderResult.NotFound, repo.list(File(root, "нет такой").path, BrowserPrefs()))
        assertEquals(FolderResult.NotFound, repo.list(File(root, "archive.zip").path, BrowserPrefs()))
    }

    @Test
    fun unreadableFolderReportsAccessDenied() {
        // Под root права не действуют (локальная песочница) — проверяется в CI.
        Assume.assumeFalse(System.getProperty("user.name") == "root")
        val locked = File(root, "закрыто").apply { mkdir() }
        File(locked, "file.txt").writeText("x")
        locked.setReadable(false, false)
        try {
            assertEquals(FolderResult.AccessDenied, repo.list(locked.path, BrowserPrefs()))
        } finally {
            locked.setReadable(true, false)
        }
    }

    @Test
    fun androidDataIsReportedAsRestricted() {
        assertTrue(FileSystemRepository.isRestrictedByAndroid(File("/storage/emulated/0/Android/data")))
        assertTrue(FileSystemRepository.isRestrictedByAndroid(File("/storage/emulated/0/Android/obb/com.game")))
        assertTrue(FileSystemRepository.isRestrictedByAndroid(File("/storage/1234-ABCD/Android/data/")))
        assertFalse(FileSystemRepository.isRestrictedByAndroid(File("/storage/emulated/0/Android/media")))
        assertFalse(FileSystemRepository.isRestrictedByAndroid(File("/storage/emulated/0/Download/Android/data")))
    }

    @Test
    fun createFolderValidatesAndNeverOverwrites() {
        assertTrue(repo.createFolder(root.path, "  Новая папка ") is CreateFolderResult.Created)
        assertTrue(File(root, "Новая папка").isDirectory)
        assertEquals(CreateFolderResult.AlreadyExists, repo.createFolder(root.path, "Новая папка"))
        assertEquals(CreateFolderResult.AlreadyExists, repo.createFolder(root.path, "одинаковое.txt"))
        assertEquals("в корне — то же имя", File(root, "одинаковое.txt").readText())
        assertTrue(repo.createFolder(root.path, "a/b") is CreateFolderResult.Invalid)
        assertTrue(repo.createFolder(root.path, "..") is CreateFolderResult.Invalid)
        assertTrue(repo.createFolder(root.path, "   ") is CreateFolderResult.Invalid)
        assertEquals(CreateFolderResult.Failed, repo.createFolder(File(root, "нет").path, "x"))
    }

    @Test
    fun hiddenSegments() {
        assertTrue(FileSystemRepository.hasHiddenSegment("/storage/emulated/0/DCIM/.thumbnails/1.jpg"))
        assertFalse(FileSystemRepository.hasHiddenSegment("/storage/emulated/0/Download/a.b.c.txt"))
    }
}
