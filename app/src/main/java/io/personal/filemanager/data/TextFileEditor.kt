package io.personal.filemanager.data

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Редактор ограничен небольшими файлами: большие остаются в потоковом просмотрщике. */
object TextFileEditor {
    const val MAX_EDIT_BYTES = 4 * 1024 * 1024

    data class Snapshot(val sha256: String, val charsetName: String)

    sealed interface Result {
        data class Saved(val snapshot: Snapshot) : Result
        data object ChangedExternally : Result
        data class Failure(val message: String) : Result
    }

    /**
     * Проверка вставляемого текста с учётом исходной кодировки и BOM.
     * В отличие от проверки числа символов, учитывает фактический размер сохранения.
     */
    fun validationError(text: String, charsetName: String): String? = try {
        if (text.length > MAX_EDIT_BYTES || encode(text, charsetName).size > MAX_EDIT_BYTES)
            "Текст превышает лимит редактора (4 МБ)."
        else null
    } catch (_: CharacterCodingException) {
        "Текст содержит символы, не поддерживаемые кодировкой файла."
    } catch (_: Exception) {
        "Неизвестная кодировка файла."
    }

    fun snapshot(file: File, charsetName: String): Snapshot? {
        if (!file.isFile || !file.canRead() || file.length() > MAX_EDIT_BYTES) return null
        return try {
            Snapshot(hash(file), charsetName)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Запись в соседний временный файл, fsync и замена оригинала.
     * SHA-256 защищает от незаметной перезаписи файла, изменённого другим приложением.
     * Кодировка и BOM сохраняются. Ошибки кодирования не заменяются вопросительными знаками.
     */
    fun save(file: File, contents: String, expected: Snapshot): Result {
        if (!file.isFile || !file.canWrite()) return Result.Failure("Нет доступа к записи файла")
        if (file.length() > MAX_EDIT_BYTES) return Result.Failure("Файл слишком большой для редактора")
        val actualHash = try { hash(file) } catch (_: Exception) {
            return Result.Failure("Не удалось проверить исходный файл")
        }
        if (actualHash != expected.sha256) return Result.ChangedExternally

        val bytes = try {
            encode(contents, expected.charsetName)
        } catch (_: CharacterCodingException) {
            return Result.Failure("Текст содержит символы, не поддерживаемые исходной кодировкой. Сохранение отменено.")
        } catch (_: Exception) {
            return Result.Failure("Неизвестная кодировка файла")
        }
        if (bytes.size > MAX_EDIT_BYTES) return Result.Failure("Превышен лимит редактора (4 МБ)")

        val parent = file.parentFile ?: return Result.Failure("Не найдена папка файла")
        var temp: File? = null
        return try {
            temp = File.createTempFile(".myfiles-edit-", ".tmp", parent)
            FileOutputStream(temp).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            // Повторная проверка непосредственно перед заменой.
            if (hash(file) != expected.sha256) return Result.ChangedExternally
            try {
                Files.move(temp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            Result.Saved(Snapshot(hash(file), expected.charsetName))
        } catch (_: Exception) {
            Result.Failure("Не удалось сохранить файл. Исходный файл не должен быть изменён.")
        } finally {
            temp?.delete()
        }
    }

    private fun encode(text: String, charsetName: String): ByteArray {
        val (charset, prefix) = when (charsetName) {
            "UTF-8 (BOM)" -> Charsets.UTF_8 to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            "UTF-16LE" -> Charsets.UTF_16LE to byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            "UTF-16BE" -> Charsets.UTF_16BE to byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            "Windows-1251" -> Charset.forName("windows-1251") to byteArrayOf()
            "ISO-8859-1" -> Charsets.ISO_8859_1 to byteArrayOf()
            else -> Charsets.UTF_8 to byteArrayOf()
        }
        val buffer = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(text))
        val encoded = ByteArray(prefix.size + buffer.remaining())
        prefix.copyInto(encoded)
        buffer.get(encoded, prefix.size, buffer.remaining())
        return encoded
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val bytes = ByteArray(8192)
            while (true) {
                val read = stream.read(bytes)
                if (read < 0) break
                digest.update(bytes, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
