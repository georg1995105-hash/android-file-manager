package io.personal.filemanager.data.archive

import io.personal.filemanager.data.ops.FileOperations
import io.personal.filemanager.data.ops.OpProgress
import io.personal.filemanager.data.ops.OperationCancelledException
import io.personal.filemanager.data.ops.uniqueTarget
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.random.Random

sealed interface CreateResult {
    data class Ok(val file: File, val files: Int) : CreateResult
    data class Failed(val reason: String) : CreateResult
    data object Cancelled : CreateResult
}

/**
 * Создание ZIP (Deflate; пароль — AES-256) и 7z (LZMA2; пароль — AES-256 из Commons Compress).
 * Архив пишется во временный файл и появляется под итоговым именем только после успешного завершения.
 */
class ArchiveCreator {

    private data class Source(val file: File, val name: String)

    private fun collect(sources: List<File>): List<Source> = sources.flatMap { root ->
        if (root.isDirectory) {
            root.walkTopDown()
                .filter { !Files.isSymbolicLink(it.toPath()) }
                .map { Source(it, root.name + "/" + it.relativeTo(root).invariantSeparatorsPath) }
                .map { if (it.file == root) Source(root, root.name) else it }
                .toList()
        } else {
            listOf(Source(root, root.name))
        }
    }

    fun create(
        sources: List<File>,
        destDir: File,
        baseName: String,
        format: ArchiveFormat,
        password: CharArray?,
        onProgress: (OpProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): CreateResult {
        require(format.canCreate) { "Создание ${format.title} не поддерживается" }
        if (sources.isEmpty()) return CreateResult.Failed("Нечего архивировать")
        if (!destDir.isDirectory || !destDir.canWrite()) return CreateResult.Failed("Нет доступа к папке назначения")
        val ext = if (format == ArchiveFormat.ZIP) ".zip" else ".7z"
        val target = File(destDir, baseName + ext).let { if (it.exists()) uniqueTarget(destDir, it.name) else it }
        val items = collect(sources)
        val total = items.filter { it.file.isFile }.sumOf { it.file.length() }
        if (total > destDir.usableSpace) return CreateResult.Failed("Недостаточно места для архива")
        val tmp = File(destDir, "${FileOperations.PARTIAL_PREFIX}${target.name}-${Random.nextInt(0, Int.MAX_VALUE).toString(36)}")
        var done = 0L
        var count = 0
        val buffer = ByteArray(256 * 1024)
        fun progress(name: String) = onProgress(OpProgress(total, done, items.size, count, name))
        try {
            when (format) {
                ArchiveFormat.ZIP -> {
                    tmp.outputStream().buffered(256 * 1024).use { raw ->
                        ZipOutputStream(raw, password?.takeIf { it.isNotEmpty() }).use { zip ->
                            for (s in items) {
                                if (isCancelled()) throw OperationCancelledException()
                                val params = ZipParameters().apply {
                                    fileNameInZip = if (s.file.isDirectory) s.name + "/" else s.name
                                    compressionMethod = if (s.file.isDirectory) CompressionMethod.STORE else CompressionMethod.DEFLATE
                                    lastModifiedFileTime = s.file.lastModified()
                                    if (password != null && password.isNotEmpty() && !s.file.isDirectory) {
                                        isEncryptFiles = true
                                        encryptionMethod = EncryptionMethod.AES
                                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                                    }
                                    if (s.file.isDirectory) entrySize = 0
                                }
                                zip.putNextEntry(params)
                                if (s.file.isFile) {
                                    s.file.inputStream().use { input ->
                                        while (true) {
                                            if (isCancelled()) throw OperationCancelledException()
                                            val n = input.read(buffer)
                                            if (n < 0) break
                                            zip.write(buffer, 0, n)
                                            done += n
                                        }
                                    }
                                }
                                zip.closeEntry()
                                count++
                                progress(s.name)
                            }
                        }
                    }
                }
                ArchiveFormat.SEVEN_Z -> {
                    val out = if (password == null || password.isEmpty()) SevenZOutputFile(tmp) else SevenZOutputFile(tmp, password)
                    out.use { z ->
                        for (s in items) {
                            if (isCancelled()) throw OperationCancelledException()
                            z.putArchiveEntry(z.createArchiveEntry(s.file, s.name))
                            if (s.file.isFile) {
                                s.file.inputStream().use { input ->
                                    while (true) {
                                        if (isCancelled()) throw OperationCancelledException()
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        z.write(buffer, 0, n)
                                        done += n
                                    }
                                }
                            }
                            z.closeArchiveEntry()
                            count++
                            progress(s.name)
                        }
                        z.finish()
                    }
                }
                else -> error("unreachable")
            }
            if (target.exists()) throw IOException("«${target.name}» появился во время создания архива")
            if (!tmp.renameTo(target)) throw IOException("Не удалось сохранить архив")
            return CreateResult.Ok(target, items.count { it.file.isFile })
        } catch (e: OperationCancelledException) {
            tmp.delete()
            return CreateResult.Cancelled
        } catch (e: Throwable) {
            tmp.delete()
            return CreateResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        fun suggestedName(sources: List<File>): String =
            if (sources.size == 1) sources.first().let { if (it.isDirectory) it.name else it.nameWithoutExtension } else "Архив"
    }
}
