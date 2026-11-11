package io.personal.filemanager.data.vault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

enum class VaultKind { PHOTO, VIDEO, LINK, NOTE }

data class VaultItem(
    val id: String,
    val kind: VaultKind,
    val title: String,
    val bytes: Long,
    val mime: String,
    val createdAt: Long,
)

/**
 * Local-only private vault. Both metadata and payload are authenticated AES-256-GCM ciphertext
 * inside noBackupFilesDir; no plaintext media is copied to public storage during import.
 * The Android Keystore holds an unexportable AES key; the application PIN gates UI access.
 * A four-digit PIN is a convenience lock, not a defence against a rooted/compromised device.
 */
class PrivateVault(private val context: Context) {
    private val prefs = context.getSharedPreferences("pfm_vault_auth_v1", Context.MODE_PRIVATE)
    private val dir = File(context.noBackupFilesDir, "private_vault_v1")
    private val index get() = File(dir, "index.pfm")
    private val keyAlias = "pfm_private_vault_aes_256_v1"

    init {
        // Stale plaintext previews are removed when the vault is next instantiated.
        File(context.cacheDir, "pfm_private_preview").listFiles()?.forEach { it.delete() }
    }

    fun isConfigured(): Boolean = prefs.contains("salt") && prefs.contains("pin_hash")

    @Synchronized
    fun setupPin(pin: String) {
        require(pin.matches(Regex("[0-9]{4}"))) { "PIN должен состоять из четырёх цифр" }
        if (isConfigured()) throw IllegalStateException("Сейф уже создан")
        if (dir.listFiles()?.isNotEmpty() == true) {
            throw IOException("Обнаружены существующие данные сейфа. Новый ключ создавать нельзя")
        }
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Не удалось создать закрытую папку")
        val salt = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val hash = derivePin(pin, salt)
        // The key is provisioned before committing the configured status.
        getOrCreateKey(allowCreate = true)
        writeIndex(emptyList())
        val committed = prefs.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            .putInt("tries", 0)
            .putLong("blocked_until", 0L)
            .commit()
        hash.fill(0)
        if (!committed) throw IOException("Не удалось сохранить параметры доступа")
    }

    @Synchronized
    fun checkPin(pin: String): Boolean {
        if (!isConfigured()) return false
        if (System.currentTimeMillis() < prefs.getLong("blocked_until", 0L)) {
            throw IllegalStateException("Слишком много попыток. Повторите позже")
        }
        val salt = Base64.decode(prefs.getString("salt", ""), Base64.DEFAULT)
        val expected = Base64.decode(prefs.getString("pin_hash", ""), Base64.DEFAULT)
        val candidate = derivePin(pin, salt)
        val ok = MessageDigest.isEqual(expected, candidate)
        candidate.fill(0)
        if (ok) {
            prefs.edit().putInt("tries", 0).putLong("blocked_until", 0L).apply()
            // Refuse to pretend an old encrypted vault can be unlocked after key loss.
            getOrCreateKey(allowCreate = false)
            return true
        }
        val tries = prefs.getInt("tries", 0) + 1
        val duration = if (tries >= 5) 60_000L else 0L
        prefs.edit()
            .putInt("tries", if (tries >= 5) 0 else tries)
            .putLong("blocked_until", if (duration > 0) System.currentTimeMillis() + duration else 0L)
            .apply()
        return false
    }

    private fun derivePin(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 240_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun getOrCreateKey(allowCreate: Boolean): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        if (!allowCreate) throw IllegalStateException("Аппаратный ключ сейфа утрачен: данные не расшифровать")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun writeEncrypted(source: java.io.InputStream, target: File) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(allowCreate = false))
        FileOutputStream(target).use { fileOut ->
            val out = DataOutputStream(fileOut)
            out.write(byteArrayOf(0x50, 0x46, 0x4D, 0x56)) // PFMV
            out.writeByte(1)
            out.writeByte(cipher.iv.size)
            out.write(cipher.iv)
            CipherOutputStream(out, cipher).use { encrypted ->
                source.copyTo(encrypted, 64 * 1024)
            }
            fileOut.fd.sync()
        }
    }

    private fun readEncrypted(source: File, destination: java.io.OutputStream) {
        FileInputStream(source).use { stream ->
            val data = DataInputStream(stream)
            val magic = ByteArray(4)
            data.readFully(magic)
            if (!magic.contentEquals(byteArrayOf(0x50, 0x46, 0x4D, 0x56))) {
                throw IOException("Неверный формат защищённого файла")
            }
            val version = data.readUnsignedByte()
            val ivLength = data.readUnsignedByte()
            if (version != 1 || ivLength != 12) throw IOException("Неподдерживаемая версия сейфа")
            val iv = ByteArray(ivLength)
            data.readFully(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(allowCreate = false), GCMParameterSpec(128, iv))
            CipherInputStream(data, cipher).use { decrypted ->
                decrypted.copyTo(destination, 64 * 1024)
            }
        }
    }

    private fun safeFile(id: String): File {
        UUID.fromString(id) // Never allow paths read from the index to escape the vault directory.
        return File(dir, "$"+"{id}.pfm")
    }

    @Synchronized
    fun list(): List<VaultItem> {
        if (!isConfigured()) return emptyList()
        if (!index.isFile) throw IOException("Индекс сейфа отсутствует")
        val out = ByteArrayOutputStream()
        readEncrypted(index, out)
        val json = JSONObject(out.toString(Charsets.UTF_8.name()))
        val array = json.getJSONArray("items")
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            VaultItem(
                id = item.getString("id"),
                kind = VaultKind.valueOf(item.getString("kind")),
                title = item.getString("title"),
                bytes = item.optLong("bytes"),
                mime = item.optString("mime"),
                createdAt = item.optLong("createdAt"),
            )
        }.sortedByDescending { it.createdAt }
    }

    private fun writeIndex(items: List<VaultItem>) {
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Не удалось создать закрытую папку")
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id); put("kind", item.kind.name); put("title", item.title)
                put("bytes", item.bytes); put("mime", item.mime); put("createdAt", item.createdAt)
            })
        }
        val temp = File(dir, "index.tmp")
        try {
            ByteArrayInputStream(JSONObject().put("items", array).toString().toByteArray(Charsets.UTF_8)).use {
                writeEncrypted(it, temp)
            }
            try {
                Files.move(temp.toPath(), index.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), index.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    @Synchronized
    fun saveText(kind: VaultKind, title: String, text: String, oldId: String? = null) {
        require(kind == VaultKind.LINK || kind == VaultKind.NOTE)
        require(title.isNotBlank()) { "Введите название" }
        require(text.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "Текст превышает 2 МБ" }
        val original = list()
        val id = oldId ?: UUID.randomUUID().toString()
        if (oldId != null && original.none { it.id == oldId }) throw IOException("Запись не найдена")
        val itemFile = safeFile(id)
        val temp = File(dir, "$"+"{id}.tmp")
        val oldCipher = if (itemFile.exists()) File(dir, "$"+"{id}.previous") else null
        try {
            ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)).use { writeEncrypted(it, temp) }
            if (oldCipher != null) Files.copy(itemFile.toPath(), oldCipher.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(temp.toPath(), itemFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val item = VaultItem(id, kind, title.trim().take(160), text.toByteArray(Charsets.UTF_8).size.toLong(),
                "text/plain", System.currentTimeMillis())
            try {
                writeIndex(original.filterNot { it.id == id } + item)
            } catch (e: Exception) {
                if (oldCipher != null) Files.move(oldCipher.toPath(), itemFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                else itemFile.delete()
                throw e
            }
        } finally {
            temp.delete()
            oldCipher?.delete()
        }
    }

    @Synchronized
    fun readText(item: VaultItem): String {
        require(item.kind == VaultKind.LINK || item.kind == VaultKind.NOTE)
        if (item.bytes > 2 * 1024 * 1024) throw IOException("Запись слишком большая")
        return ByteArrayOutputStream().also { readEncrypted(safeFile(item.id), it) }.toString(Charsets.UTF_8.name())
    }

    @Synchronized
    fun importMedia(uri: Uri, kind: VaultKind): VaultItem {
        require(kind == VaultKind.PHOTO || kind == VaultKind.VIDEO)
        val id = UUID.randomUUID().toString()
        var title = if (kind == VaultKind.PHOTO) "Фотография" else "Видео"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) title = it.getString(0)?.take(160) ?: title
        }
        val mime = context.contentResolver.getType(uri).orEmpty()
        val target = safeFile(id)
        val temp = File(dir, "$"+"{id}.tmp")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Не удалось прочитать исходный файл")
            input.use { writeEncrypted(it, temp) }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val item = VaultItem(id, kind, title, target.length(), mime, System.currentTimeMillis())
            try {
                writeIndex(list() + item)
            } catch (e: Exception) {
                target.delete()
                throw e
            }
            return item
        } finally {
            temp.delete()
        }
    }

    @Synchronized
    fun delete(item: VaultItem) {
        val old = list()
        if (old.none { it.id == item.id }) return
        // Index is updated first; a failed index write never loses the only copy of a file.
        writeIndex(old.filterNot { it.id == item.id })
        safeFile(item.id).delete()
    }

    fun export(item: VaultItem, uri: Uri) {
        val output = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Не удалось открыть место назначения")
        output.use { readEncrypted(safeFile(item.id), it) }
    }

    /** Temporary app-private preview, deleted by the UI on close and on next vault launch. */
    fun preview(item: VaultItem): File {
        require(item.kind == VaultKind.PHOTO || item.kind == VaultKind.VIDEO)
        val previewDir = File(context.cacheDir, "pfm_private_preview").apply { mkdirs() }
        val ext = when (item.mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            else -> if (item.kind == VaultKind.PHOTO) "jpg" else "mp4"
        }
        val temp = File(previewDir, "$"+"{item.id}.$"+"{ext}")
        try {
            FileOutputStream(temp).use { readEncrypted(safeFile(item.id), it) }
            return temp
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }
}
