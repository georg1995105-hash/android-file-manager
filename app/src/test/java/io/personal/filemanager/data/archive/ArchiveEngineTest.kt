package io.personal.filemanager.data.archive

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Все архивы создаются в тесте; личные файлы не используются (ТЗ §8). */
class ArchiveEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = ArchiveEngine()
    private val creator = ArchiveCreator()
    private lateinit var src: File
    private lateinit var out: File

    @Before
    fun setUp() {
        src = tmp.newFolder("исходные")
        out = tmp.newFolder("out")
        File(src, "заметка.md").writeText("# Привет")
        File(src, "папка/вложенная").mkdirs()
        File(src, "папка/вложенная/данные.json").writeText("""{"a":1}""")
        File(src, "big.bin").writeBytes(ByteArray(400_000) { (it * 31 % 256).toByte() })
    }

    private fun sources() = listOf(File(src, "заметка.md"), File(src, "папка"), File(src, "big.bin"))

    private fun assertSameTree(root: File) {
        assertEquals("# Привет", File(root, "заметка.md").readText())
        assertEquals("""{"a":1}""", File(root, "папка/вложенная/данные.json").readText())
        assertTrue(File(src, "big.bin").readBytes().contentEquals(File(root, "big.bin").readBytes()))
    }

    private fun noPartials(dir: File) = dir.walkTopDown().none { it.name.startsWith(".pfm-partial-") }

    @Test
    fun zipRoundTrip() {
        val r = creator.create(sources(), out, "архив", ArchiveFormat.ZIP, null) as CreateResult.Ok
        val list = engine.list(r.file) as ListResult.Ok
        assertTrue(list.items.any { it.path == "папка/вложенная/данные.json" })
        val x = engine.extract(r.file, out)
        assertTrue(x.error, x.ok)
        assertEquals("архив", x.outputDir!!.name)
        assertSameTree(x.outputDir!!)
        assertTrue(noPartials(out))
    }

    @Test
    fun zipWithPassword() {
        val r = creator.create(sources(), out, "secret", ArchiveFormat.ZIP, "пароль123".toCharArray()) as CreateResult.Ok
        // Оглавление видно без пароля, данные — нет.
        val list = engine.list(r.file) as ListResult.Ok
        assertTrue(list.encrypted)
        assertTrue(engine.extract(r.file, out).passwordRequired)
        assertTrue(engine.extract(r.file, out, "неверный".toCharArray()).wrongPassword)
        assertTrue(noPartials(out))
        val ok = engine.extract(r.file, out, "пароль123".toCharArray())
        assertTrue(ok.error, ok.ok)
        assertSameTree(ok.outputDir!!)
    }

    @Test
    fun sevenZRoundTripWithPasswordAndVolumes() {
        val r = creator.create(sources(), out, "data", ArchiveFormat.SEVEN_Z, "pw".toCharArray()) as CreateResult.Ok
        val listed = engine.list(r.file)
        assertTrue(listed.toString(), listed is ListResult.Ok || listed == ListResult.PasswordRequired)
        assertTrue(engine.extract(r.file, out).passwordRequired)
        val x = engine.extract(r.file, out, "pw".toCharArray())
        assertTrue(x.error, x.ok)
        assertSameTree(x.outputDir!!)
        assertFalse(engine.extract(r.file, out, "bad".toCharArray()).ok)

        // Разрезаем на тома .7z.001/.002/.003 и открываем со второго тома.
        val plain = creator.create(sources(), out, "multi", ArchiveFormat.SEVEN_Z, null) as CreateResult.Ok
        val bytes = plain.file.readBytes()
        val chunk = bytes.size / 3 + 1
        val vols = (0 until 3).map { i ->
            File(out, "multi.7z.00${i + 1}").apply { writeBytes(bytes.copyOfRange(i * chunk, minOf(bytes.size, (i + 1) * chunk))) }
        }
        assertEquals(vols.first(), ArchiveFormats.primaryVolume(vols[1]))
        val mx = engine.extract(vols[1], out)
        assertTrue(mx.error, mx.ok)
        assertEquals("multi", mx.outputDir!!.name)
        assertSameTree(mx.outputDir!!)
    }

    private fun writeTar(target: File, wrap: (OutputStream) -> OutputStream) {
        target.outputStream().use { raw ->
            TarArchiveOutputStream(wrap(raw)).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                src.walkTopDown().filter { it != src }.forEach { f ->
                    val e = TarArchiveEntry(f, f.relativeTo(src).invariantSeparatorsPath + if (f.isDirectory) "/" else "")
                    tar.putArchiveEntry(e)
                    if (f.isFile) f.inputStream().use { it.copyTo(tar) }
                    tar.closeArchiveEntry()
                }
                // Символьная ссылка наружу — должна быть пропущена.
                val link = TarArchiveEntry("evil-link", TarArchiveEntry.LF_SYMLINK).apply { linkName = "/etc/passwd" }
                tar.putArchiveEntry(link)
                tar.closeArchiveEntry()
            }
        }
    }

    @Test
    fun tarGzAndXz() {
        val gz = File(out, "a.tar.gz").also { f -> writeTar(f) { GzipCompressorOutputStream(it) } }
        val xz = File(out, "b.tar.xz").also { f -> writeTar(f) { XZCompressorOutputStream(it) } }
        for (f in listOf(gz, xz)) {
            val x = engine.extract(f, out)
            assertTrue(x.error, x.ok)
            assertSameTree(x.outputDir!!)
            assertFalse(File(x.outputDir, "evil-link").exists())
            assertTrue(x.skipped.any { it.first == "evil-link" })
        }
    }

    @Test
    fun tarZst() {
        // Нативная библиотека zstd есть не во всех средах модульных тестов.
        Assume.assumeTrue(runCatching { com.github.luben.zstd.Zstd.defaultCompressionLevel() }.isSuccess)
        val zst = File(out, "c.tar.zst").also { f -> writeTar(f) { ZstdCompressorOutputStream(it) } }
        val x = engine.extract(zst, out)
        assertTrue(x.error, x.ok)
        assertSameTree(x.outputDir!!)
    }

    @Test
    fun singleGz() {
        val gz = File(out, "заметка.md.gz")
        GzipCompressorOutputStream(gz.outputStream()).use { it.write("# Привет".toByteArray()) }
        val list = engine.list(gz) as ListResult.Ok
        assertEquals("заметка.md", list.items.single().path)
        val x = engine.extract(gz, out)
        assertTrue(x.error, x.ok)
        assertEquals("# Привет", File(x.outputDir, "заметка.md").readText())
    }

    @Test
    fun zipSlipAndAbsolutePathsAreRejected() {
        val evil = File(out, "evil.zip")
        ZipOutputStream(evil.outputStream()).use { z ->
            for (name in listOf("../../escape.txt", "/abs.txt", "ok/good.txt", "C:/win.txt", "a/../../b.txt")) {
                z.putNextEntry(ZipEntry(name))
                z.write("x".toByteArray())
                z.closeEntry()
            }
        }
        val x = engine.extract(evil, out)
        assertTrue(x.error, x.ok)
        assertEquals("x", File(x.outputDir, "ok/good.txt").readText())
        assertEquals(1, x.files)
        assertEquals(4, x.skipped.size)
        assertFalse(File(tmp.root, "escape.txt").exists())
        assertFalse(File(out, "escape.txt").exists())
    }

    @Test
    fun bombIsStoppedAndNothingLeftBehind() {
        val bomb = File(out, "bomb.zip")
        ZipOutputStream(bomb.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("zeros.bin"))
            val chunk = ByteArray(1_000_000)
            repeat(50) { z.write(chunk) } // 50 МБ нулей сжимаются до ~50 КБ
            z.closeEntry()
        }
        val x = engine.extract(bomb, out, limits = ExtractLimits(maxTotalBytes = 10_000_000))
        assertFalse(x.ok)
        assertNotNull(x.error)
        assertFalse(File(out, "bomb").exists())
        assertTrue(noPartials(out))

        val ratio = engine.extract(bomb, out, limits = ExtractLimits(maxTotalBytes = Long.MAX_VALUE, maxRatio = 100, ratioCheckAfterBytes = 1_000_000))
        assertFalse(ratio.ok)
        assertTrue(noPartials(out))
    }

    @Test
    fun selectiveExtraction() {
        val r = creator.create(sources(), out, "sel", ArchiveFormat.ZIP, null) as CreateResult.Ok
        val x = engine.extract(r.file, out, selected = setOf("папка"))
        assertTrue(x.error, x.ok)
        assertTrue(File(x.outputDir, "папка/вложенная/данные.json").exists())
        assertFalse(File(x.outputDir, "big.bin").exists())
    }

    @Test
    fun cancelLeavesNothing() {
        val r = creator.create(sources(), out, "c", ArchiveFormat.ZIP, null) as CreateResult.Ok
        var n = 0
        val x = engine.extract(r.file, out, isCancelled = { ++n > 2 })
        assertTrue(x.cancelled)
        assertTrue(noPartials(out))
        assertFalse(File(out, "c").exists())
    }

    @Test
    fun corruptedAndUnknown() {
        val bad = File(out, "broken.zip").apply { writeBytes(ByteArray(100) { 7 }) }
        assertTrue(engine.list(bad) is ListResult.Failed || engine.list(bad) is ListResult.Unsupported)
        val badExtract = engine.extract(bad, out)
        assertFalse(badExtract.ok)
        assertTrue(noPartials(out))
        assertTrue(engine.list(File(out, "x.unknown").apply { writeText("x") }) is ListResult.Unsupported)
        val bad7z = File(out, "broken.7z").apply { writeBytes(ByteArray(100) { 1 }) }
        assertTrue(engine.list(bad7z) is ListResult.Failed)
    }

    @Test
    fun formatHelpers() {
        assertEquals(ArchiveFormat.TAR_GZ, ArchiveFormats.detect("x.tgz"))
        assertEquals(ArchiveFormat.SEVEN_Z, ArchiveFormats.detect("x.7z.003"))
        assertEquals(ArchiveFormat.RAR, ArchiveFormats.detect("movie.part2.rar"))
        assertNull(ArchiveFormats.detect("photo.jpg"))
        assertEquals("photos", ArchiveFormats.baseName(File("photos.tar.gz")))
        assertEquals("big", ArchiveFormats.baseName(File("big.7z.001")))
        assertNull(ArchiveFormats.safeRelativePath("../a"))
        assertNull(ArchiveFormats.safeRelativePath("\\\\server\\a"))
        assertEquals("a/b", ArchiveFormats.safeRelativePath("./a//b/"))
    }
}
