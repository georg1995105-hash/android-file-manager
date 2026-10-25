package io.personal.filemanager.data.apps

import net.lingala.zip4j.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Вместо настоящих APK — файлы-образцы: проверяется копирование и структура, не установка. */
class ApkSetsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun fake(name: String, size: Int) = File(tmp.root, name).apply { writeBytes(ByteArray(size) { (it % 199).toByte() }) }

    @Test
    fun exportSingleApk() {
        val base = fake("base.apk", 300_000)
        val out = tmp.newFolder("out")
        val r = ApkSets.export(listOf(base), out, "Мой: app/1.0") as ExportResult.Ok
        assertEquals("Мой_ app_1.0.apk", r.file.name)
        assertTrue(base.readBytes().contentEquals(r.file.readBytes()))
        // Повторный экспорт не перезаписывает.
        val again = ApkSets.export(listOf(base), out, "Мой: app/1.0") as ExportResult.Ok
        assertEquals("Мой_ app_1.0 (1).apk", again.file.name)
    }

    @Test
    fun exportSplitSetIsVerifiedApks() {
        val parts = listOf(fake("base.apk", 200_000), fake("split_config.arm64_v8a.apk", 50_000), fake("split_config.ru.apk", 1_000))
        val out = tmp.newFolder("out")
        val r = ApkSets.export(parts, out, "app") as ExportResult.Ok
        assertEquals("app.apks", r.file.name)
        assertEquals(3, r.parts)
        val names = ZipFile(r.file).use { z -> z.fileHeaders.map { it.fileName } }
        assertEquals(listOf("base.apk", "split_config.arm64_v8a.apk", "split_config.ru.apk"), names)
        val check = ApkSets.inspect(r.file) as ApkSetCheck.Ok
        assertEquals("base.apk", check.info.baseEntry)
        assertEquals(251_000L, check.info.totalBytes)
        assertTrue(out.listFiles()!!.none { it.name.startsWith(".pfm-partial-") })
    }

    @Test
    fun cancelledExportLeavesNothing() {
        val out = tmp.newFolder("out")
        val r = ApkSets.export(listOf(fake("base.apk", 2_000_000)), out, "x", isCancelled = { true })
        assertEquals(ExportResult.Cancelled, r)
        assertTrue(out.listFiles()!!.isEmpty())
    }

    private fun zipOf(name: String, vararg entries: String): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { z ->
            entries.forEach { e -> z.putNextEntry(ZipEntry(e)); z.write(1); z.closeEntry() }
        }
        return f
    }

    @Test
    fun inspectRejectsBadSets() {
        assertTrue(ApkSets.inspect(zipOf("a.apks", "readme.txt")) is ApkSetCheck.Invalid)
        assertTrue(ApkSets.inspect(zipOf("b.apks", "../base.apk")) is ApkSetCheck.Invalid)
        assertTrue(ApkSets.inspect(File(tmp.root, "c.apks").apply { writeText("not zip") }) is ApkSetCheck.Invalid)
        val xapk = ApkSets.inspect(zipOf("d.xapk", "com.app.apk", "config.arm64_v8a.apk", "Android/obb/com.app/main.obb", "manifest.json"))
        assertTrue(xapk is ApkSetCheck.Ok)
        assertEquals("com.app.apk", (xapk as ApkSetCheck.Ok).info.baseEntry)
        assertTrue(xapk.info.hasObb)
        assertFalse(ApkSets.safeFileName("a/b").contains('/'))
    }
}
