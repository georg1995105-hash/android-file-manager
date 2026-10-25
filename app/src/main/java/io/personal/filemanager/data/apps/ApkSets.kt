package io.personal.filemanager.data.apps

import io.personal.filemanager.data.archive.ArchiveFormats
import io.personal.filemanager.data.ops.FileOperations
import io.personal.filemanager.data.ops.OperationCancelledException
import io.personal.filemanager.data.ops.uniqueTarget
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import java.io.File
import java.io.IOException
import kotlin.random.Random

sealed interface ExportResult {
    data class Ok(val file: File, val parts: Int, val bytes: Long) : ExportResult
    data class Failed(val reason: String) : ExportResult
    data object Cancelled : ExportResult
}

/** Проверенная структура набора APK (.apks/.xapk/.apkm без шифрования). */
data class ApkSetInfo(val apkEntries: List<String>, val baseEntry: String, val totalBytes: Long, val hasObb: Boolean)

sealed interface ApkSetCheck {
    data class Ok(val info: ApkSetInfo) : ApkSetCheck
    data class Invalid(val reason: String) : ApkSetCheck
}

/**
 * Экспорт установочных файлов установленного приложения и проверка наборов split APK.
 * Экспорт копирует подписанные APK без изменений: это НЕ резервная копия данных приложения.
 */
object ApkSets {
    const val MAX_SET_BYTES = 4_000_000_000L
    private const val MAX_ENTRIES = 200

    fun safeFileName(raw: String): String =
        raw.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().ifEmpty { "app" }.take(120)

    /**
     * Одиночный APK копируется как `name.apk`; split-приложение — как `name.apks`
     * (ZIP без сжатия: base.apk + split_*.apk). Результат проверяется перед публикацией.
     */
    fun export(
        apkPaths: List<File>,
        destDir: File,
        baseName: String,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): ExportResult {
        if (apkPaths.isEmpty()) return ExportResult.Failed("Нет файлов APK")
        if (apkPaths.any { !it.isFile || !it.canRead() }) return ExportResult.Failed("Нет доступа к файлам APK приложения")
        if (!destDir.isDirectory && !destDir.mkdirs()) return ExportResult.Failed("Не удалось создать папку экспорта")
        val total = apkPaths.sumOf { it.length() }
        if (total > destDir.usableSpace - 10_000_000) return ExportResult.Failed("Недостаточно места")
        val name = safeFileName(baseName) + if (apkPaths.size == 1) ".apk" else ".apks"
        val target = File(destDir, name).let { if (it.exists()) uniqueTarget(destDir, name) else it }
        val tmp = File(destDir, "${FileOperations.PARTIAL_PREFIX}${target.name}-${Random.nextInt(0, Int.MAX_VALUE).toString(36)}")
        val buffer = ByteArray(256 * 1024)
        var done = 0L
        try {
            if (apkPaths.size == 1) {
                apkPaths.first().inputStream().use { input ->
                    tmp.outputStream().use { out ->
                        while (true) {
                            if (isCancelled()) throw OperationCancelledException()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                        out.fd.sync()
                    }
                }
                if (tmp.length() != apkPaths.first().length()) throw IOException("Размер копии не совпал")
            } else {
                val names = entryNames(apkPaths)
                tmp.outputStream().buffered(256 * 1024).use { raw ->
                    ZipOutputStream(raw).use { zip ->
                        apkPaths.forEachIndexed { i, apk ->
                            val params = ZipParameters().apply {
                                fileNameInZip = names[i]
                                compressionMethod = CompressionMethod.STORE
                                entrySize = apk.length()
                                lastModifiedFileTime = apk.lastModified()
                            }
                            zip.putNextEntry(params)
                            apk.inputStream().use { input ->
                                while (true) {
                                    if (isCancelled()) throw OperationCancelledException()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    zip.write(buffer, 0, n)
                                    done += n
                                    onProgress(done, total)
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                }
                // Проверка: все части на месте и совпадают по размеру.
                val headers = ZipFile(tmp).use { it.fileHeaders.associate { h -> h.fileName to h.uncompressedSize } }
                apkPaths.forEachIndexed { i, apk ->
                    if (headers[names[i]] != apk.length()) throw IOException("Проверка набора не прошла: ${names[i]}")
                }
            }
            if (target.exists()) throw IOException("«${target.name}» появился во время экспорта")
            if (!tmp.renameTo(target)) throw IOException("Не удалось сохранить файл")
            return ExportResult.Ok(target, apkPaths.size, total)
        } catch (e: OperationCancelledException) {
            tmp.delete()
            return ExportResult.Cancelled
        } catch (e: Exception) {
            tmp.delete()
            return ExportResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** base.apk первым, остальные — под своими именами (split_config.*.apk), без повторов. */
    fun entryNames(apkPaths: List<File>): List<String> {
        val used = mutableSetOf<String>()
        return apkPaths.mapIndexed { i, f ->
            var n = if (i == 0) "base.apk" else f.name.takeIf { it.endsWith(".apk") } ?: "split_$i.apk"
            if (n == "base.apk" && i != 0) n = "split_$i.apk"
            while (!used.add(n)) n = "split_${i}_${used.size}.apk"
            n
        }
    }

    /**
     * Проверка структуры набора до установки: только безопасные имена, есть базовый APK,
     * разумный размер. Подпись и совместимость проверяет сам Android при установке.
     */
    fun inspect(file: File): ApkSetCheck = try {
        ZipFile(file).use { zip ->
            if (!zip.isValidZipFile) return ApkSetCheck.Invalid("Файл не является ZIP-набором APK")
            if (zip.isEncrypted) return ApkSetCheck.Invalid("Зашифрованные наборы (например, .apkm) не поддерживаются")
            val headers = zip.fileHeaders
            if (headers.size > MAX_ENTRIES) return ApkSetCheck.Invalid("Слишком много файлов в наборе")
            val apks = headers.filter { !it.isDirectory && it.fileName.lowercase().endsWith(".apk") }
            if (apks.isEmpty()) return ApkSetCheck.Invalid("В наборе нет файлов APK")
            if (apks.any { ArchiveFormats.safeRelativePath(it.fileName) == null }) {
                return ApkSetCheck.Invalid("В наборе есть небезопасные пути")
            }
            val total = apks.sumOf { it.uncompressedSize }
            if (total > MAX_SET_BYTES) return ApkSetCheck.Invalid("Набор слишком большой")
            val names = apks.map { it.fileName }
            val base = names.firstOrNull { it.substringAfterLast('/').equals("base.apk", ignoreCase = true) }
                ?: names.firstOrNull { !it.substringAfterLast('/').startsWith("split_") && !it.substringAfterLast('/').startsWith("config.") }
                ?: return ApkSetCheck.Invalid("Не найден базовый APK")
            val hasObb = headers.any { it.fileName.lowercase().endsWith(".obb") }
            ApkSetCheck.Ok(ApkSetInfo(names, base, total, hasObb))
        }
    } catch (e: Exception) {
        ApkSetCheck.Invalid("Не удалось прочитать набор: ${e.message ?: e.javaClass.simpleName}")
    }
}
