package io.personal.filemanager.data.ops

import io.personal.filemanager.data.FileSystemRepository
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.random.Random

/** Что делать, если в папке назначения уже есть объект с тем же именем. */
enum class ConflictPolicy { SKIP, RENAME, REPLACE }

class OperationCancelledException : Exception("Операция отменена")

data class OpProgress(
    val totalBytes: Long,
    val doneBytes: Long,
    val totalItems: Int,
    val doneItems: Int,
    val current: String,
) {
    val fraction: Float get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

data class OpReport(
    val done: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val failed: List<Pair<String, String>> = emptyList(),
    val cancelled: Boolean = false,
    /** Новые пути (для обновления индекса Android). */
    val created: List<String> = emptyList(),
    /** Пути, которых больше нет (для обновления индекса Android). */
    val removed: List<String> = emptyList(),
    /** Пояснение для пользователя (куда распаковано и т. п.). */
    val note: String? = null,
) {
    val ok: Boolean get() = failed.isEmpty() && !cancelled
}

sealed interface RenameResult {
    data class Ok(val file: File) : RenameResult
    data class Error(val message: String) : RenameResult
}

/** «имя (1).ext», «имя (2).ext»…; для «.tar.gz» и подобных номер ставится до двойного расширения. */
fun uniqueTarget(dir: File, name: String): File {
    val (base, ext) = splitName(name)
    var i = 1
    while (true) {
        val candidate = File(dir, "$base ($i)$ext")
        if (!candidate.exists()) return candidate
        i++
    }
}

private val doubleExtensions = listOf(".tar.gz", ".tar.xz", ".tar.zst", ".tar.bz2", ".tar.lz4")

internal fun splitName(name: String): Pair<String, String> {
    doubleExtensions.firstOrNull { name.lowercase().endsWith(it) && name.length > it.length }?.let {
        return name.dropLast(it.length) to name.takeLast(it.length)
    }
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot)
}

/**
 * Безопасные операции с файлами (ТЗ §3, §8):
 * - копирование потоком во временный файл `.pfm-partial-*`, проверка размера, затем rename в итоговое имя;
 * - существующие объекты не перезаписываются без явного REPLACE, а при REPLACE старый уходит в корзину;
 * - перенос: сначала rename (тот же носитель), иначе копия → проверка → только потом удаление оригинала;
 * - при ошибке или отмене оригинал не трогается, недописанная копия удаляется.
 */
class FileOperations(
    private val trash: TrashStore?,
    /** Корень носителя для пути; null — неизвестен (тогда носители считаются одинаковыми). */
    private val volumeRootOf: (File) -> String? = ::defaultVolumeRoot,
) {
    private class Ctx(
        val totalBytes: Long,
        val totalItems: Int,
        val onProgress: (OpProgress) -> Unit,
        val isCancelled: () -> Boolean,
    ) {
        var doneBytes = 0L
        var doneItems = 0
        var current = ""
        private var lastReport = 0L

        fun advance(bytes: Long) {
            doneBytes += bytes
            val now = System.nanoTime()
            if (now - lastReport > 100_000_000L) {
                lastReport = now
                report()
            }
        }

        fun report() = onProgress(OpProgress(totalBytes, doneBytes, totalItems, doneItems, current))

        fun checkCancel() {
            if (isCancelled()) throw OperationCancelledException()
        }
    }

    /** Имена, которые уже существуют в папке назначения. */
    fun conflicts(sources: List<File>, destDir: File): List<String> =
        sources.map { it.name }.filter { File(destDir, it).exists() }

    fun copy(
        sources: List<File>,
        destDir: File,
        policy: ConflictPolicy,
        onProgress: (OpProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): OpReport = transfer(sources, destDir, policy, move = false, onProgress, isCancelled)

    fun move(
        sources: List<File>,
        destDir: File,
        policy: ConflictPolicy,
        onProgress: (OpProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): OpReport = transfer(sources, destDir, policy, move = true, onProgress, isCancelled)

    private fun transfer(
        sources: List<File>,
        destDir: File,
        policy: ConflictPolicy,
        move: Boolean,
        onProgress: (OpProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): OpReport {
        if (!destDir.isDirectory) return OpReport(failed = listOf(destDir.path to "Папка назначения не найдена"))
        if (!destDir.canWrite()) return OpReport(failed = listOf(destDir.path to "Нет права записи в папку назначения"))

        // Проверки до начала: источник существует, нельзя копировать папку в саму себя.
        val failed = mutableListOf<Pair<String, String>>()
        val valid = sources.filter { src ->
            when {
                !src.exists() -> { failed += src.path to "Источник не найден"; false }
                src.isDirectory && isInside(destDir, src) -> { failed += src.path to "Нельзя поместить папку внутрь самой себя"; false }
                move && src.parentFile?.absolutePath == destDir.absolutePath -> { failed += src.path to "Файл уже в этой папке"; false }
                else -> true
            }
        }
        val sameVolume = valid.all { sameVolume(it, destDir) }
        val needsBytes = if (move && sameVolume) 0L else valid.sumOf { TrashStore.treeSize(it) }
        if (needsBytes > 0 && needsBytes > destDir.usableSpace - SPACE_RESERVE) {
            return OpReport(
                failed = failed + valid.map {
                    it.path to "Недостаточно места: нужно ${needsBytes / 1_000_000} МБ, свободно ${destDir.usableSpace / 1_000_000} МБ"
                },
            )
        }

        val ctx = Ctx(needsBytes, valid.size, onProgress, isCancelled)
        val done = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val created = mutableListOf<String>()
        val removed = mutableListOf<String>()
        var cancelled = false

        for (src in valid) {
            if (isCancelled()) {
                cancelled = true
                break
            }
            ctx.current = src.name
            ctx.report()
            var target = File(destDir, src.name)
            if (target.exists()) {
                when (policy) {
                    ConflictPolicy.SKIP -> {
                        skipped += src.path
                        continue
                    }
                    ConflictPolicy.RENAME -> target = uniqueTarget(destDir, src.name)
                    ConflictPolicy.REPLACE -> {
                        if (target.isDirectory && src.isDirectory && !move) {
                            // Слияние папок: внутри заменяются только совпадающие файлы.
                        } else {
                            val moved = trash?.moveToTrash(target)
                            if (moved == null || moved.isFailure) {
                                failed += src.path to "Не удалось убрать существующий «${target.name}» в корзину — замена отменена"
                                continue
                            }
                            removed += target.absolutePath
                        }
                    }
                }
            }
            try {
                if (move && sameVolume && !target.exists() && src.renameTo(target)) {
                    created += target.absolutePath
                    removed += src.absolutePath
                } else {
                    copyTree(src, target, ctx, policy, created)
                    if (move) {
                        // Оригинал удаляется только после полной и проверенной копии.
                        if (!src.deleteRecursively()) {
                            failed += src.path to "Копия создана, но оригинал удалить не удалось"
                            ctx.doneItems++
                            continue
                        }
                        removed += src.absolutePath
                    }
                }
                done += src.path
            } catch (e: OperationCancelledException) {
                cancelled = true
                break
            } catch (e: Exception) {
                failed += src.path to (e.message ?: e.javaClass.simpleName)
            }
            ctx.doneItems++
        }
        ctx.report()
        return OpReport(done, skipped, failed, cancelled, created, removed)
    }

    private fun copyTree(src: File, target: File, ctx: Ctx, policy: ConflictPolicy, created: MutableList<String>) {
        ctx.checkCancel()
        if (Files.isSymbolicLink(src.toPath())) return // ссылки не копируются и не обходятся
        if (src.isDirectory) {
            val createdHere = !target.exists()
            if (createdHere && !target.mkdir()) throw IOException("Не удалось создать папку «${target.name}»")
            try {
                for (child in src.listFiles().orEmpty()) {
                    val childTarget = File(target, child.name)
                    if (childTarget.exists() && !createdHere) {
                        // Слияние в существующую папку (REPLACE): старый файл — в корзину.
                        if (childTarget.isDirectory && child.isDirectory) {
                            copyTree(child, childTarget, ctx, policy, created)
                            continue
                        }
                        val moved = trash?.moveToTrash(childTarget)
                        if (moved == null || moved.isFailure) throw IOException("Не удалось заменить «${childTarget.name}»")
                    }
                    copyTree(child, childTarget, ctx, policy, created)
                }
            } catch (e: Exception) {
                if (createdHere) target.deleteRecursively()
                throw e
            }
            target.setLastModified(src.lastModified())
            if (createdHere) created += target.absolutePath
        } else {
            copyFile(src, target, ctx)
            created += target.absolutePath
        }
    }

    private fun copyFile(src: File, target: File, ctx: Ctx) {
        val dir = target.parentFile ?: throw IOException("Нет папки назначения")
        val tmp = File(dir, "$PARTIAL_PREFIX${target.name}-${Random.nextInt(0, Int.MAX_VALUE).toString(36)}")
        try {
            src.inputStream().use { input ->
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        ctx.checkCancel()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        ctx.advance(n.toLong())
                    }
                    out.fd.sync()
                }
            }
            if (tmp.length() != src.length()) throw IOException("Размер копии «${src.name}» не совпал с оригиналом")
            tmp.setLastModified(src.lastModified())
            if (target.exists()) throw IOException("«${target.name}» появился во время копирования — не перезаписан")
            if (!tmp.renameTo(target)) throw IOException("Не удалось завершить запись «${target.name}»")
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    fun rename(file: File, rawName: String): RenameResult {
        val name = rawName.trim()
        FileSystemRepository.validateName(name)?.let { return RenameResult.Error(it) }
        if (!file.exists()) return RenameResult.Error("Файл не найден")
        if (name == file.name) return RenameResult.Ok(file)
        val target = File(file.parentFile, name)
        // На файловых системах без учёта регистра «a» и «A» — один файл: разрешаем смену регистра.
        if (target.exists() && !target.absolutePath.equals(file.absolutePath, ignoreCase = true)) {
            return RenameResult.Error("Объект с таким именем уже существует")
        }
        return if (file.renameTo(target)) RenameResult.Ok(target) else RenameResult.Error("Не удалось переименовать")
    }

    /** Удаление в корзину; при недоступности корзины объект остаётся на месте. */
    fun moveToTrash(files: List<File>): OpReport {
        val done = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        for (f in files) {
            val result = trash?.moveToTrash(f) ?: Result.failure(IllegalStateException("Корзина недоступна"))
            result.fold(
                onSuccess = { done += f.absolutePath },
                onFailure = { failed += f.path to (it.message ?: "Ошибка") },
            )
        }
        return OpReport(done = done, failed = failed, removed = done)
    }

    /** Необратимое удаление (вызывается только после отдельного подтверждения пользователя). */
    fun deletePermanently(files: List<File>): OpReport {
        val done = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        for (f in files) {
            if (f.deleteRecursively()) done += f.absolutePath else failed += f.path to "Не удалось удалить"
        }
        return OpReport(done = done, failed = failed, removed = done)
    }

    private fun sameVolume(a: File, b: File): Boolean = volumeRootOf(a) == volumeRootOf(b)

    companion object {
        const val PARTIAL_PREFIX = ".pfm-partial-"
        private const val BUFFER = 256 * 1024
        private const val SPACE_RESERVE = 1_000_000L

        private val volumeRegex = Regex("""^(/storage/emulated/\d+|/storage/[^/]+|/sdcard)(/|$)""")

        fun defaultVolumeRoot(file: File): String? = volumeRegex.find(file.absolutePath)?.groupValues?.get(1)

        /** true, если [child] — это [parent] или лежит внутри него. */
        fun isInside(child: File, parent: File): Boolean {
            val c = child.absoluteFile.normalize().path
            val p = parent.absoluteFile.normalize().path
            return c == p || c.startsWith("$p/")
        }
    }
}
