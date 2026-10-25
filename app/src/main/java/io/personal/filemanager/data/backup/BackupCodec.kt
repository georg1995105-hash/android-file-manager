package io.personal.filemanager.data.backup

import io.personal.filemanager.data.db.Favorite
import io.personal.filemanager.data.db.Link
import io.personal.filemanager.data.db.Note
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Настройки приложения в копии (значения DataStore как есть). */
@Serializable
data class BackupSettings(
    val uiConfigJson: String? = null,
    val recentSort: String? = null,
    val browserSort: String? = null,
    val browserDescending: Boolean? = null,
    val showHidden: Boolean? = null,
)

/** Собственные данные приложения. Обычные файлы пользователя в копию не входят (ТЗ §7). */
data class BackupPayload(
    val notes: List<Note>,
    val links: List<Link>,
    val favorites: List<Favorite>,
    val settings: BackupSettings,
)

@Serializable
data class FileDigest(val size: Long, val sha256: String)

@Serializable
data class BackupManifest(
    val format: String,
    val formatVersion: Int,
    val appVersion: String,
    val createdAt: Long,
    val encrypted: Boolean = false,
    val counts: Map<String, Int> = emptyMap(),
    val files: Map<String, FileDigest> = emptyMap(),
)

sealed interface DecodeResult {
    data class Ok(val payload: BackupPayload, val manifest: BackupManifest) : DecodeResult
    data object PasswordRequired : DecodeResult
    data object WrongPassword : DecodeResult
    data class Unsupported(val reason: String) : DecodeResult
    data class Invalid(val reason: String) : DecodeResult
}

/**
 * Переносимый формат резервной копии:
 * - ZIP: manifest.json (версия формата, контрольные суммы SHA-256 и размеры), notes.json, links.json,
 *   favorites.json, settings.json;
 * - при пароле весь ZIP шифруется AES-256-GCM, ключ — PBKDF2-HMAC-SHA256 из пароля со случайной солью;
 *   заголовок связан с шифротекстом как AAD. Пароль нигде не хранится.
 * Ключ подписи APK в копию не входит.
 */
object BackupCodec {
    const val FORMAT = "pfm-backup"
    const val VERSION = 1
    const val FILE_EXTENSION = "pfmbackup"
    const val MAX_BACKUP_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    private const val MAX_ITEMS = 200_000
    private const val MAX_TEXT = 5_000_000

    private val MAGIC = "PFMENC1\n".toByteArray(Charsets.US_ASCII)
    private const val DEFAULT_ITERATIONS = 310_000
    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 5_000_000

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private const val MANIFEST = "manifest.json"
    private const val NOTES = "notes.json"
    private const val LINKS = "links.json"
    private const val FAVORITES = "favorites.json"
    private const val SETTINGS = "settings.json"

    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size > MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun encode(
        payload: BackupPayload,
        appVersion: String,
        createdAt: Long,
        password: CharArray? = null,
        iterations: Int = DEFAULT_ITERATIONS,
    ): ByteArray {
        val files = linkedMapOf(
            NOTES to json.encodeToString(ListSerializer(Note.serializer()), payload.notes).toByteArray(),
            LINKS to json.encodeToString(ListSerializer(Link.serializer()), payload.links).toByteArray(),
            FAVORITES to json.encodeToString(ListSerializer(Favorite.serializer()), payload.favorites).toByteArray(),
            SETTINGS to json.encodeToString(BackupSettings.serializer(), payload.settings).toByteArray(),
        )
        val manifest = BackupManifest(
            format = FORMAT,
            formatVersion = VERSION,
            appVersion = appVersion,
            createdAt = createdAt,
            encrypted = password != null,
            counts = mapOf("notes" to payload.notes.size, "links" to payload.links.size, "favorites" to payload.favorites.size),
            files = files.mapValues { (_, b) -> FileDigest(b.size.toLong(), sha256(b)) },
        )
        val zipBytes = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zip ->
                fun put(name: String, data: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
                put(MANIFEST, json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
                files.forEach { (n, b) -> put(n, b) }
            }
            bos.toByteArray()
        }
        return if (password == null) zipBytes else encrypt(zipBytes, password, iterations)
    }

    fun decode(bytes: ByteArray, password: CharArray? = null): DecodeResult {
        if (bytes.size > MAX_BACKUP_BYTES) return DecodeResult.Invalid("Файл слишком большой для резервной копии приложения")
        val zipBytes = if (isEncrypted(bytes)) {
            if (password == null) return DecodeResult.PasswordRequired
            decrypt(bytes, password) ?: return DecodeResult.WrongPassword
        } else {
            bytes
        }
        val entries = try {
            readZip(zipBytes)
        } catch (e: Exception) {
            return DecodeResult.Invalid("Файл повреждён или это не резервная копия: ${e.message ?: e.javaClass.simpleName}")
        }
        val manifestBytes = entries[MANIFEST] ?: return DecodeResult.Invalid("Нет manifest.json — это не резервная копия приложения")
        val manifest = try {
            json.decodeFromString(BackupManifest.serializer(), manifestBytes.decodeToString())
        } catch (e: Exception) {
            return DecodeResult.Invalid("Повреждён manifest.json")
        }
        if (manifest.format != FORMAT) return DecodeResult.Invalid("Неизвестный формат «${manifest.format}»")
        if (manifest.formatVersion > VERSION) {
            return DecodeResult.Unsupported("Копия создана более новой версией приложения (формат ${manifest.formatVersion}). Обновите приложение.")
        }
        if (manifest.formatVersion < 1) return DecodeResult.Unsupported("Неподдерживаемая версия формата ${manifest.formatVersion}")
        for ((name, digest) in manifest.files) {
            val data = entries[name] ?: return DecodeResult.Invalid("В копии не хватает $name")
            if (data.size.toLong() != digest.size || sha256(data) != digest.sha256) {
                return DecodeResult.Invalid("Контрольная сумма $name не совпадает — копия повреждена")
            }
        }
        return try {
            val payload = migrate(manifest.formatVersion, entries)
            validate(payload)?.let { return DecodeResult.Invalid(it) }
            DecodeResult.Ok(payload, manifest)
        } catch (e: Exception) {
            DecodeResult.Invalid("Не удалось прочитать данные копии: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Чтение данных с учётом версии формата (место для будущих миграций). */
    private fun migrate(version: Int, entries: Map<String, ByteArray>): BackupPayload {
        check(version == 1)
        fun text(name: String) = entries[name]?.decodeToString()
        return BackupPayload(
            notes = text(NOTES)?.let { json.decodeFromString(ListSerializer(Note.serializer()), it) }.orEmpty(),
            links = text(LINKS)?.let { json.decodeFromString(ListSerializer(Link.serializer()), it) }.orEmpty(),
            favorites = text(FAVORITES)?.let { json.decodeFromString(ListSerializer(Favorite.serializer()), it) }.orEmpty(),
            settings = text(SETTINGS)?.let { json.decodeFromString(BackupSettings.serializer(), it) } ?: BackupSettings(),
        )
    }

    private fun validate(p: BackupPayload): String? = when {
        p.notes.size + p.links.size + p.favorites.size > MAX_ITEMS -> "Слишком много записей"
        p.notes.any { it.body.length > MAX_TEXT || it.title.length > 10_000 } -> "Слишком длинная заметка"
        p.links.any { it.url.length > 10_000 || it.title.length > 10_000 || it.note.length > MAX_TEXT } -> "Некорректная ссылка"
        p.favorites.any { !it.path.startsWith("/") || it.path.contains('\u0000') } -> "Некорректный путь в избранном"
        else -> null
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                if (result.size > 64) error("слишком много файлов")
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = zip.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ENTRY_BYTES) error("элемент ${e.name} слишком большой")
                    out.write(buf, 0, n)
                }
                result[e.name] = out.toByteArray()
            }
        }
        return result
    }

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(key, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun encrypt(plain: ByteArray, password: CharArray, iterations: Int): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val header = ByteBuffer.allocate(MAGIC.size + 4 + salt.size + iv.size)
            .put(MAGIC).putInt(iterations).put(salt).put(iv).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    /** null — неверный пароль или изменённые данные (GCM проверяет целостность). */
    private fun decrypt(data: ByteArray, password: CharArray): ByteArray? {
        val headerSize = MAGIC.size + 4 + 16 + 12
        if (data.size < headerSize + 16) return null
        val buf = ByteBuffer.wrap(data)
        buf.position(MAGIC.size)
        val iterations = buf.int
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return null
        val salt = ByteArray(16).also { buf.get(it) }
        val iv = ByteArray(12).also { buf.get(it) }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(128, iv))
            cipher.updateAAD(data, 0, headerSize)
            cipher.doFinal(data, headerSize, data.size - headerSize)
        } catch (e: AEADBadTagException) {
            null
        } catch (e: java.security.GeneralSecurityException) {
            null
        }
    }
}
