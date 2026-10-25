package io.personal.filemanager.data.backup

import io.personal.filemanager.data.db.Favorite
import io.personal.filemanager.data.db.Link
import io.personal.filemanager.data.db.LinkRules
import io.personal.filemanager.data.db.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupCodecTest {
    private val payload = BackupPayload(
        notes = listOf(Note(1, "Список", "молоко\nхлеб", 10, 20, pinned = true), Note(2, "", "Без заголовка\nтекст", 30, 40)),
        links = listOf(Link(1, "Документация", "https://developer.android.com", "заметка", 1, 2)),
        favorites = listOf(Favorite(1, "/storage/emulated/0/Download", "Download", true, 5)),
        settings = BackupSettings(uiConfigJson = """{"version":1,"tiles":[],"tabs":[]}""", recentSort = "ADDED", showHidden = true),
    )

    @Test
    fun plainRoundTrip() {
        val bytes = BackupCodec.encode(payload, "0.5.0", 123L)
        assertFalse(BackupCodec.isEncrypted(bytes))
        val r = BackupCodec.decode(bytes) as DecodeResult.Ok
        assertEquals(payload, r.payload)
        assertEquals(mapOf("notes" to 2, "links" to 1, "favorites" to 1), r.manifest.counts)
        assertEquals(123L, r.manifest.createdAt)
    }

    @Test
    fun encryptedRoundTripAndWrongPassword() {
        val bytes = BackupCodec.encode(payload, "0.5.0", 1L, "секрет".toCharArray(), iterations = 100_000)
        assertTrue(BackupCodec.isEncrypted(bytes))
        // Открытого текста заметок в файле нет.
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("manifest"))
        assertEquals(DecodeResult.PasswordRequired, BackupCodec.decode(bytes))
        assertEquals(DecodeResult.WrongPassword, BackupCodec.decode(bytes, "неверно".toCharArray()))
        val ok = BackupCodec.decode(bytes, "секрет".toCharArray()) as DecodeResult.Ok
        assertEquals(payload, ok.payload)
        // Изменённый байт шифротекста отвергается (GCM).
        val tampered = bytes.copyOf().also { it[it.size - 20] = (it[it.size - 20] + 1).toByte() }
        assertEquals(DecodeResult.WrongPassword, BackupCodec.decode(tampered, "секрет".toCharArray()))
    }

    @Test
    fun corruptedOrForeignFilesAreRejected() {
        assertTrue(BackupCodec.decode("not a zip".toByteArray()) is DecodeResult.Invalid)
        val foreign = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry("x.txt")); z.write(1); z.closeEntry() }
        }.toByteArray()
        assertTrue(BackupCodec.decode(foreign) is DecodeResult.Invalid)
        // Подменённые данные при верном manifest — контрольная сумма не сойдётся.
        val good = BackupCodec.encode(payload, "0.5.0", 1L)
        // Подменяем notes.json, оставляя прежний manifest.
        val swapped = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                java.util.zip.ZipInputStream(good.inputStream()).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        val data = zin.readBytes()
                        z.putNextEntry(ZipEntry(e.name))
                        z.write(if (e.name == "notes.json") "[]".toByteArray() else data)
                        z.closeEntry()
                    }
                }
            }
        }.toByteArray()
        val r = BackupCodec.decode(swapped)
        assertTrue(r.toString(), r is DecodeResult.Invalid)
    }

    @Test
    fun newerFormatIsUnsupported() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write("""{"format":"pfm-backup","formatVersion":99,"appVersion":"9","createdAt":1}""".toByteArray())
            z.closeEntry()
        }
        assertTrue(BackupCodec.decode(bos.toByteArray()) is DecodeResult.Unsupported)
    }

    @Test
    fun invalidFavoritePathRejected() {
        val bad = payload.copy(favorites = listOf(Favorite(1, "relative/path", "x", false, 1)))
        assertTrue(BackupCodec.decode(BackupCodec.encode(bad, "1", 1)) is DecodeResult.Invalid)
    }

    @Test
    fun linkRules() {
        assertEquals("https://example.com", LinkRules.normalize(" example.com "))
        assertEquals("http://a.b/c?d", LinkRules.normalize("http://a.b/c?d"))
        assertNull(LinkRules.normalize("javascript:alert(1)"))
        assertNull(LinkRules.normalize("file:///etc/passwd"))
        assertNull(LinkRules.normalize("not a url"))
        assertNull(LinkRules.normalize("https://"))
        assertEquals("Без заголовка", Note(0, "", "\n  Без заголовка\nтекст", 0, 0).displayTitle)
    }
}
