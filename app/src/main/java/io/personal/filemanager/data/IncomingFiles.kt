package io.personal.filemanager.data

import android.content.Context
import android.content.Intent
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

sealed interface IncomingResult {
    data class Imported(val file: File) : IncomingResult
    data class Error(val message: String) : IncomingResult
}

/**
 * Android «Открыть с помощью»: принимаем content://, копируем с лимитом 128 МБ.
 * Исходный документ другого приложения никогда не изменяется.
 */
object IncomingFiles {
    private const val MAX_SIZE = 128L * 1024 * 1024

    fun import(context: Context, intent: Intent): IncomingResult {
        val uri = intent.data ?: return IncomingResult.Error("Источник не передал файл")
        if (intent.action != Intent.ACTION_VIEW || uri.scheme != "content") {
            return IncomingResult.Error("Поддерживается открытие файлов Android через content://")
        }
        val resolver = context.contentResolver
        var rawName: String? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !c.isNull(sizeIndex) && c.getLong(sizeIndex) > MAX_SIZE)
                        return IncomingResult.Error("Файл слишком большой (лимит 128 МБ)")
                    val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) rawName = c.getString(nameIndex)
                }
            }
        } catch (_: Exception) {
            // Некоторые провайдеры не предоставляют метаданные: ограничение чтения действует ниже.
        }
        val extension = when (intent.type) {
            "application/json" -> ".json"
            "application/xml", "text/xml" -> ".xml"
            "application/pdf" -> ".pdf"
            "application/zip", "application/x-zip-compressed" -> ".zip"
            "application/yaml" -> ".yaml"
            else -> ".txt"
        }
        val proposed = rawName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f/\\\\]"), "_").trim().take(120)
        val name = proposed.takeIf { FileSystemRepository.validateName(it) == null }
            ?: "received$extension"
        val root = File(context.cacheDir, "imported-files")
        if (!root.isDirectory && !root.mkdirs())
            return IncomingResult.Error("Недоступен кэш приложения")
        val targetDir = File(root, "incoming-${System.nanoTime()}")
        if (!targetDir.mkdirs())
            return IncomingResult.Error("Не удалось создать временную папку")
        val output = File(targetDir, name)
        return try {
            (resolver.openInputStream(uri) ?: throw IOException("Источник недоступен")).use { input ->
                FileOutputStream(output).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_SIZE) throw IOException("Размер больше 128 МБ")
                        out.write(buffer, 0, n)
                    }
                    out.fd.sync()
                }
            }
            IncomingResult.Imported(output)
        } catch (e: Exception) {
            targetDir.deleteRecursively()
            IncomingResult.Error(e.message ?: "Не удалось прочитать файл")
        }
    }
}