package io.personal.filemanager.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

sealed interface TextResult {
    data class Ok(
        val text: String,
        val charsetName: String,
        val truncated: Boolean,
        val totalBytes: Long,
    ) : TextResult

    data object Binary : TextResult
    data class Error(val message: String) : TextResult
}

/** Безопасное чтение текстовых файлов: ограничение размера, определение кодировки, защита от двоичных данных. */
object TextLoader {
    const val DEFAULT_LIMIT_BYTES = 1_000_000

    private val windows1251: Charset? = runCatching { Charset.forName("windows-1251") }.getOrNull()

    fun load(file: File, limitBytes: Int = DEFAULT_LIMIT_BYTES): TextResult {
        if (!file.isFile) return TextResult.Error("Файл не найден")
        if (!file.canRead()) return TextResult.Error("Нет доступа к файлу")
        val total = file.length()
        val bytes = try {
            file.inputStream().use { input ->
                val buffer = ByteArray(minOf(total, limitBytes.toLong()).toInt().coerceAtLeast(0))
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read == buffer.size) buffer else buffer.copyOf(read)
            }
        } catch (e: Exception) {
            return TextResult.Error("Не удалось прочитать файл: ${e.message ?: e.javaClass.simpleName}")
        }
        return decode(bytes, truncated = total > bytes.size, totalBytes = total)
    }

    fun decode(bytes: ByteArray, truncated: Boolean = false, totalBytes: Long = bytes.size.toLong()): TextResult {
        // BOM
        when {
            bytes.startsWith(0xEF, 0xBB, 0xBF) ->
                return TextResult.Ok(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), "UTF-8 (BOM)", truncated, totalBytes)
            bytes.startsWith(0xFF, 0xFE) ->
                return TextResult.Ok(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), "UTF-16LE", truncated, totalBytes)
            bytes.startsWith(0xFE, 0xFF) ->
                return TextResult.Ok(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), "UTF-16BE", truncated, totalBytes)
        }
        if (looksBinary(bytes)) return TextResult.Binary

        // Обрезанный по лимиту файл может кончаться посреди многобайтового символа UTF-8.
        val utf8Bytes = if (truncated) trimIncompleteUtf8(bytes) else bytes
        strictDecode(utf8Bytes, Charsets.UTF_8)?.let { return TextResult.Ok(it, "UTF-8", truncated, totalBytes) }
        windows1251?.let { cs ->
            strictDecode(bytes, cs)?.let { return TextResult.Ok(it, "Windows-1251", truncated, totalBytes) }
        }
        return TextResult.Ok(String(bytes, Charsets.ISO_8859_1), "ISO-8859-1", truncated, totalBytes)
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val sample = minOf(bytes.size, 8192)
        if (sample == 0) return false
        var control = 0
        for (i in 0 until sample) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return true
            if (b < 0x09 || (b in 0x0E..0x1F)) control++
        }
        return control * 10 > sample // >10% управляющих символов
    }

    private fun trimIncompleteUtf8(bytes: ByteArray): ByteArray {
        var end = bytes.size
        var back = 0
        while (end > 0 && back < 4) {
            val b = bytes[end - 1].toInt() and 0xFF
            if (b and 0xC0 == 0x80) { end--; back++; continue } // продолжение
            if (b and 0xC0 == 0xC0) {
                val need = when {
                    b and 0xF8 == 0xF0 -> 4
                    b and 0xF0 == 0xE0 -> 3
                    else -> 2
                }
                return if (back + 1 < need) bytes.copyOf(end - 1) else bytes
            }
            return bytes
        }
        return bytes.copyOf(end)
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { (this[it].toInt() and 0xFF) == prefix[it] }
}
