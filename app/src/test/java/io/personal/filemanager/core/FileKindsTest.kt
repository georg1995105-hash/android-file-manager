package io.personal.filemanager.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileKindsTest {
    @Test
    fun practicalExtensions() {
        assertEquals(FileKind.TEXT, FileTypes.kindOf("README.md"))
        assertEquals(FileKind.TEXT, FileTypes.kindOf("config.YAML"))
        assertEquals(FileKind.TEXT, FileTypes.kindOf("data.json"))
        assertEquals(FileKind.TEXT, FileTypes.kindOf("app.log"))
        assertEquals(FileKind.PDF, FileTypes.kindOf("Отчёт_2024.pdf"))
        assertEquals(FileKind.ARCHIVE, FileTypes.kindOf("backup.7z"))
        assertEquals(FileKind.ARCHIVE, FileTypes.kindOf("backup.tar.zst"))
        assertEquals(FileKind.ARCHIVE, FileTypes.kindOf("movie.part1.rar"))
        assertEquals(FileKind.ARCHIVE, FileTypes.kindOf("big.7z.001"))
        assertEquals(FileKind.ARCHIVE, FileTypes.kindOf("big.z01"))
        assertEquals(FileKind.APK, FileTypes.kindOf("app-release.apk"))
        assertEquals(FileKind.APK, FileTypes.kindOf("bundle.apks"))
        assertEquals(FileKind.IMAGE, FileTypes.kindOf("IMG_20240314_182931.jpg"))
        assertEquals(FileKind.VIDEO, FileTypes.kindOf("Видео_отпуск.mp4"))
        assertEquals(FileKind.VIDEO, FileTypes.kindOf("clip.MKV"))
        assertEquals(FileKind.DOCUMENT, FileTypes.kindOf("table.xlsx"))
        assertEquals(FileKind.OTHER, FileTypes.kindOf("unknown.xyz"))
        assertEquals(FileKind.OTHER, FileTypes.kindOf("noextension"))
        assertEquals(FileKind.FOLDER, FileTypes.kindOf("folder.zip", isDirectory = true))
    }

    @Test
    fun extensionEdgeCases() {
        assertEquals("", FileTypes.extensionOf(".hidden"))
        assertEquals("", FileTypes.extensionOf("trailingdot."))
        assertEquals("gz", FileTypes.extensionOf("a.tar.gz"))
    }

    @Test
    fun documentCategoryIncludesTextFormats() {
        assertTrue("md" in FileTypes.documentExtensions)
        assertTrue("pdf" in FileTypes.documentExtensions)
    }
}
