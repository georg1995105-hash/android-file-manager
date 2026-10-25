package io.personal.filemanager.data.archive

import com.github.junrar.Archive
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedRarEncryptedException
import com.github.junrar.exception.UnsupportedRarVersionException
import com.github.junrar.exception.WrongPasswordException
import io.personal.filemanager.data.ops.FileOperations
import io.personal.filemanager.data.ops.uniqueTarget
import net.lingala.zip4j.exception.ZipException
import org.apache.commons.compress.MemoryLimitException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.StandardOpenOption
import kotlin.random.Random
import net.lingala.zip4j.ZipFile as Zip4jFile
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile

/**
 * Чтение и безопасная распаковка архивов. Все данные архива считаются недоверенными:
 * пути нормализуются, ссылки и спецфайлы не создаются, объём ограничен, распаковка идёт
 * во временную папку и переименовывается в итоговую только после успеха.
 */
class ArchiveEngine(private val memoryLimitKiB: Int = 512 * 1024) {

    /** Посетитель элементов: stream == null — элемент без данных (папка) или только листинг. */
    private fun interface Visitor {
        fun visit(item: ArchiveItem, open: (() -> InputStream)?)
    }

    fun list(file: File, password: CharArray? = null): ListResult {
        val primary = ArchiveFormats.primaryVolume(file)
        val format = ArchiveFormats.detect(primary.name) ?: return ListResult.Unsupported("Формат не распознан")
        val items = mutableListOf<ArchiveItem>()
        return try {
            val encrypted = walk(primary, format, password, readData = false) { item, _ -> items += item }
            ListResult.Ok(items, encrypted, noteFor(format))
        } catch (e: Throwable) {
            classify(e, password)
        }
    }

    private fun noteFor(format: ArchiveFormat): String? = when (format) {
        ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_ZST, ArchiveFormat.TAR_BZ2 ->
            "Сжатый TAR читается целиком: просмотр больших архивов может занять время."
        ArchiveFormat.GZ, ArchiveFormat.XZ, ArchiveFormat.ZST, ArchiveFormat.BZ2 ->
            "Это один сжатый файл; размер станет известен после распаковки."
        else -> null
    }

    private fun classify(e: Throwable, password: CharArray?): ListResult = when {
        e is PasswordRequiredException -> ListResult.PasswordRequired
        e is PasswordNeeded -> ListResult.PasswordRequired
        e is WrongPasswordException -> ListResult.WrongPassword
        e is ZipException && e.type == ZipException.Type.WRONG_PASSWORD -> ListResult.WrongPassword
        e is UnsupportedRarVersionException ->
            ListResult.Unsupported("RAR5 встроенным движком не читается (поддерживается RAR до версии 4). Откройте архив во внешнем приложении.")
        e is UnsupportedRarEncryptedException ->
            ListResult.Unsupported("Этот вид шифрования RAR не поддерживается встроенным движком.")
        e is MemoryLimitException -> ListResult.Unsupported("Архиву нужно слишком много памяти для распаковки на телефоне.")
        e is ZipException && e.type == ZipException.Type.UNKNOWN_COMPRESSION_METHOD ->
            ListResult.Unsupported("Метод сжатия в этом ZIP/ZIPX не поддерживается.")
        password != null && e is IOException -> ListResult.WrongPassword
        else -> ListResult.Failed("Архив повреждён или не читается: ${e.message ?: e.javaClass.simpleName}")
    }

    private class PasswordNeeded : Exception()

    /**
     * Обходит элементы архива. Возвращает признак шифрования.
     * [readData] = false — только заголовки (для форматов без оглавления данные всё равно читаются потоком).
     */
    private fun walk(file: File, format: ArchiveFormat, password: CharArray?, readData: Boolean, visitor: Visitor): Boolean =
        when (format) {
            ArchiveFormat.ZIP -> walkZip(file, password, readData, visitor)
            ArchiveFormat.SEVEN_Z -> walk7z(file, password, readData, visitor)
            ArchiveFormat.RAR -> walkRar(file, password, readData, visitor)
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_ZST, ArchiveFormat.TAR_BZ2 ->
                walkTar(file, format, visitor).let { false }
            ArchiveFormat.GZ, ArchiveFormat.XZ, ArchiveFormat.ZST, ArchiveFormat.BZ2 ->
                walkSingle(file, format, readData, visitor).let { false }
        }

    // ---------- ZIP (zip4j: шифрование ZipCrypto/AES, многотомные .z01; запасной путь — Commons Compress) ----------

    private fun openZip4j(file: File, password: CharArray?, charset: Charset): Zip4jFile =
        Zip4jFile(file, password).apply { setCharset(charset) }

    private fun walkZip(file: File, password: CharArray?, readData: Boolean, visitor: Visitor): Boolean {
        // Имена без флага UTF-8 часто в CP866 (архивы из русской Windows).
        var zip = openZip4j(file, password, Charsets.UTF_8)
        if (zip.fileHeaders.any { !it.isFileNameUTF8Encoded && it.fileName.contains('�') }) {
            zip.close()
            zip = openZip4j(file, password, Charset.forName("CP866"))
        }
        zip.use { z ->
            if (!z.isValidZipFile) throw IOException("Это не ZIP-архив")
            val encrypted = z.isEncrypted
            // Оглавление ZIP читается без пароля; пароль нужен только для данных.
            if (encrypted && password == null && readData) throw PasswordNeeded()
            try {
                for (h in z.fileHeaders) {
                    val item = ArchiveItem(
                        path = h.fileName.trimEnd('/'),
                        isDirectory = h.isDirectory,
                        size = h.uncompressedSize,
                        packedSize = h.compressedSize,
                        modified = h.lastModifiedTimeEpoch,
                        encrypted = h.isEncrypted,
                    )
                    visitor.visit(item, if (readData && !h.isDirectory) ({ z.getInputStream(h) }) else null)
                }
            } catch (e: ZipException) {
                if (e.type == ZipException.Type.UNKNOWN_COMPRESSION_METHOD && !encrypted) {
                    // Deflate64, BZIP2, Implode и др. (часть ZIPX) — через Commons Compress.
                    walkZipCommons(file, readData, visitor)
                    return false
                }
                throw e
            }
            return encrypted
        }
    }

    private fun walkZipCommons(file: File, readData: Boolean, visitor: Visitor) {
        CommonsZipFile.builder().setFile(file).get().use { z ->
            for (e in z.entries.toList()) {
                if (!z.canReadEntryData(e)) throw ZipException("Метод сжатия не поддерживается", ZipException.Type.UNKNOWN_COMPRESSION_METHOD)
                val item = ArchiveItem(e.name.trimEnd('/'), e.isDirectory, e.size, e.compressedSize, e.time, false)
                visitor.visit(item, if (readData && !e.isDirectory) ({ z.getInputStream(e) }) else null)
            }
        }
    }

    // ---------- 7z (Commons Compress: AES-пароль, тома .001/.002…) ----------

    private fun walk7z(file: File, password: CharArray?, readData: Boolean, visitor: Visitor): Boolean {
        val volumes = ArchiveFormats.sevenZVolumes(file)
        val channel = if (volumes.size > 1) {
            MultiReadOnlySeekableByteChannel.forFiles(*volumes.toTypedArray())
        } else {
            FileChannel.open(file.toPath(), StandardOpenOption.READ)
        }
        val builder = SevenZFile.builder().setSeekableByteChannel(channel).setMaxMemoryLimitKiB(memoryLimitKiB)
        if (password != null) builder.setPassword(password)
        builder.get().use { z ->
            var encrypted = false
            if (!readData) {
                for (e in z.entries) {
                    if (e.isAntiItem) continue
                    visitor.visit(item7z(e), null)
                }
                return password != null
            }
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isAntiItem) continue
                encrypted = encrypted || password != null
                val stream = object : InputStream() {
                    override fun read(): Int = z.read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int = z.read(b, off, len)
                }
                visitor.visit(item7z(e), if (!e.isDirectory && e.hasStream()) ({ stream }) else null)
            }
            return encrypted
        }
    }

    private fun item7z(e: org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry) = ArchiveItem(
        path = e.name.orEmpty().trimEnd('/'),
        isDirectory = e.isDirectory,
        size = e.size,
        packedSize = -1,
        modified = if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0L,
        encrypted = false,
    )

    // ---------- RAR (junrar: только чтение RAR 1.5–4.x) ----------

    private fun walkRar(file: File, password: CharArray?, readData: Boolean, visitor: Visitor): Boolean {
        val archive = if (password != null) Archive(file, String(password)) else Archive(file)
        archive.use { a ->
            val headersEncrypted = runCatching { a.isEncrypted }.getOrDefault(false)
            val protected = headersEncrypted || runCatching { a.isPasswordProtected }.getOrDefault(false)
            if (password == null && (headersEncrypted || (protected && readData))) throw PasswordNeeded()
            for (h in a.fileHeaders) {
                val item = ArchiveItem(
                    path = h.fileName.replace('\\', '/').trimEnd('/'),
                    isDirectory = h.isDirectory,
                    size = h.fullUnpackSize,
                    packedSize = h.fullPackSize,
                    modified = h.mTime?.time ?: 0L,
                    encrypted = h.isEncrypted,
                )
                val open: (() -> InputStream)? = if (readData && !h.isDirectory) {
                    { RarEntryStream(a, h) }
                } else {
                    null
                }
                visitor.visit(item, open)
            }
            return protected
        }
    }

    /** junrar пишет в OutputStream; здесь — через промежуточный буфер в памяти по частям нельзя, поэтому через файл. */
    private class RarEntryStream(private val a: Archive, private val h: com.github.junrar.rarfile.FileHeader) : InputStream() {
        private val tmp: File = File.createTempFile("pfm-rar", ".tmp")
        private val delegate: InputStream

        init {
            try {
                tmp.outputStream().use { a.extractFile(h, it) }
            } catch (e: RarException) {
                tmp.delete()
                throw IOException(e.message, e)
            }
            delegate = tmp.inputStream()
        }

        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun close() {
            delegate.close()
            tmp.delete()
        }
    }

    // ---------- TAR и одиночные сжатые файлы ----------

    private fun decompressor(format: ArchiveFormat, raw: InputStream): InputStream = when (format) {
        ArchiveFormat.TAR_GZ, ArchiveFormat.GZ -> GzipCompressorInputStream(raw, true)
        ArchiveFormat.TAR_XZ, ArchiveFormat.XZ -> XZCompressorInputStream(raw, true)
        ArchiveFormat.TAR_ZST, ArchiveFormat.ZST -> ZstdCompressorInputStream(raw)
        ArchiveFormat.TAR_BZ2, ArchiveFormat.BZ2 -> BZip2CompressorInputStream(raw, true)
        else -> raw
    }

    private fun walkTar(file: File, format: ArchiveFormat, visitor: Visitor) {
        BufferedInputStream(file.inputStream(), 256 * 1024).use { raw ->
            TarArchiveInputStream(decompressor(format, raw)).use { tar ->
                while (true) {
                    val e = tar.nextEntry ?: break
                    val special = e.isSymbolicLink || e.isLink || e.isCharacterDevice || e.isBlockDevice || e.isFIFO
                    val item = ArchiveItem(e.name.trimEnd('/'), e.isDirectory, if (special) 0 else e.size, -1, e.modTime?.time ?: 0L, false)
                    if (special) {
                        visitor.visit(item.copy(path = SPECIAL_PREFIX + item.path), null)
                        continue
                    }
                    val stream = object : InputStream() {
                        override fun read(): Int = tar.read()
                        override fun read(b: ByteArray, off: Int, len: Int): Int = tar.read(b, off, len)
                        override fun close() = Unit // поток архива закрывается в конце обхода
                    }
                    visitor.visit(item, if (e.isFile) ({ stream }) else null)
                }
            }
        }
    }

    private fun walkSingle(file: File, format: ArchiveFormat, readData: Boolean, visitor: Visitor) {
        val name = ArchiveFormats.baseName(file)
        val item = ArchiveItem(name, false, -1, file.length(), file.lastModified(), false)
        if (!readData) {
            // Проверяем заголовок, чтобы повреждённый файл не выглядел исправным.
            BufferedInputStream(file.inputStream()).use { raw -> decompressor(format, raw).use { it.read() } }
            visitor.visit(item, null)
            return
        }
        BufferedInputStream(file.inputStream(), 256 * 1024).use { raw ->
            val stream = decompressor(format, raw)
            visitor.visit(item, { stream })
        }
    }

    // ---------- Распаковка ----------

    /**
     * Распаковывает весь архив или [selected] (пути; папка включает вложенное) в новую папку
     * внутри [destParent]. Существующие файлы не перезаписываются.
     */
    fun extract(
        file: File,
        destParent: File,
        password: CharArray? = null,
        selected: Set<String>? = null,
        limits: ExtractLimits = ExtractLimits(maxTotalBytes = destParent.usableSpace - SPACE_RESERVE),
        folderName: String? = null,
        onProgress: (bytes: Long, current: String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): ExtractResult {
        val primary = ArchiveFormats.primaryVolume(file)
        val format = ArchiveFormats.detect(primary.name) ?: return ExtractResult(error = "Формат не распознан")
        if (!destParent.isDirectory || !destParent.canWrite()) return ExtractResult(error = "Нет доступа к папке назначения")
        val archiveBytes = archiveSize(primary, format)
        val wantedName = folderName ?: ArchiveFormats.baseName(primary)
        val finalDir = File(destParent, wantedName).let { if (it.exists()) uniqueTarget(destParent, wantedName) else it }
        val work = File(destParent, "${FileOperations.PARTIAL_PREFIX}${finalDir.name}-${Random.nextInt(0, Int.MAX_VALUE).toString(36)}")
        if (!work.mkdirs()) return ExtractResult(error = "Не удалось создать папку для распаковки")

        var files = 0
        var bytes = 0L
        var entries = 0
        val skipped = mutableListOf<Pair<String, String>>()
        val buffer = ByteArray(256 * 1024)

        fun wanted(path: String): Boolean =
            selected == null || selected.any { s -> path == s || path.startsWith("$s/") }

        try {
            walk(primary, format, password, readData = true) { item, open ->
                if (isCancelled()) throw ArchiveAbort(CANCELLED)
                if (++entries > limits.maxEntries) throw ArchiveAbort("Слишком много элементов в архиве (> ${limits.maxEntries})")
                if (item.path.startsWith(SPECIAL_PREFIX)) {
                    skipped += item.path.removePrefix(SPECIAL_PREFIX) to "ссылки и специальные файлы не создаются"
                    return@walk
                }
                if (!wanted(item.path)) return@walk
                val rel = ArchiveFormats.safeRelativePath(item.path)
                if (rel == null) {
                    skipped += item.path to "небезопасный путь"
                    return@walk
                }
                var target = File(work, rel)
                if (!ArchiveFormats.isInside(target, work)) {
                    skipped += item.path to "путь выходит за папку распаковки"
                    return@walk
                }
                if (item.isDirectory) {
                    target.mkdirs()
                    return@walk
                }
                val parent = target.parentFile!!
                if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Не удалось создать папку ${parent.name}")
                if (target.exists()) target = uniqueTarget(parent, target.name) // повтор имени в архиве
                val input = open?.invoke() ?: return@walk
                onProgress(bytes, item.name)
                input.use { inp ->
                    target.outputStream().use { out -> bytes = copyLimited(inp, out, buffer, bytes, limits, archiveBytes, isCancelled, onProgress, item.name) }
                }
                if (item.modified > 0) target.setLastModified(item.modified)
                files++
            }
        } catch (e: ArchiveAbort) {
            work.deleteRecursively()
            return if (e.message == CANCELLED) ExtractResult(cancelled = true) else ExtractResult(error = e.message)
        } catch (e: Throwable) {
            work.deleteRecursively()
            return when (val r = classify(e, password)) {
                ListResult.PasswordRequired -> ExtractResult(passwordRequired = true)
                ListResult.WrongPassword -> ExtractResult(wrongPassword = true)
                is ListResult.Unsupported -> ExtractResult(error = r.reason)
                is ListResult.Failed -> ExtractResult(error = r.reason)
                is ListResult.Ok -> ExtractResult(error = "Ошибка распаковки")
            }
        }
        if (files == 0 && work.listFiles().isNullOrEmpty()) {
            work.deleteRecursively()
            return ExtractResult(error = if (skipped.isNotEmpty()) "Все элементы пропущены: ${skipped.first().second}" else "Нечего распаковывать", skipped = skipped)
        }
        if (!work.renameTo(finalDir)) {
            work.deleteRecursively()
            return ExtractResult(error = "Не удалось завершить распаковку")
        }
        return ExtractResult(outputDir = finalDir, files = files, bytes = bytes, skipped = skipped)
    }

    private fun copyLimited(
        input: InputStream,
        out: OutputStream,
        buffer: ByteArray,
        startTotal: Long,
        limits: ExtractLimits,
        archiveBytes: Long,
        isCancelled: () -> Boolean,
        onProgress: (Long, String) -> Unit,
        name: String,
    ): Long {
        var total = startTotal
        var lastReport = 0L
        while (true) {
            if (isCancelled()) throw ArchiveAbort(CANCELLED)
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limits.maxTotalBytes) {
                throw ArchiveAbort("Распаковка остановлена: не хватает места или превышен лимит (защита от «архивных бомб»)")
            }
            if (total > limits.ratioCheckAfterBytes && archiveBytes > 0 && total / archiveBytes > limits.maxRatio) {
                throw ArchiveAbort("Распаковка остановлена: подозрительно высокая степень сжатия (возможна «архивная бомба»)")
            }
            out.write(buffer, 0, n)
            if (total - lastReport > 1_000_000) {
                lastReport = total
                onProgress(total, name)
            }
        }
        return total
    }

    private fun archiveSize(primary: File, format: ArchiveFormat): Long = when (format) {
        ArchiveFormat.SEVEN_Z -> ArchiveFormats.sevenZVolumes(primary).sumOf { it.length() }
        else -> primary.length()
    }

    companion object {
        private const val CANCELLED = "cancelled"
        private const val SPECIAL_PREFIX = "\u0000special:"
        private const val SPACE_RESERVE = 20_000_000L
    }
}
