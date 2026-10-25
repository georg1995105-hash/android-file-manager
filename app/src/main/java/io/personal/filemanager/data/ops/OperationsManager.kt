package io.personal.filemanager.data.ops

import android.content.Context
import android.media.MediaScannerConnection
import android.os.storage.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class TransferMode(val verb: String) { COPY("Копирование"), MOVE("Перемещение") }

/** Объекты, выбранные для копирования/перемещения; вставляются в папку кнопкой «Вставить сюда». */
data class FileClipboard(val paths: List<String>, val mode: TransferMode)

data class RunningOperation(val title: String, val progress: OpProgress? = null, val cancellable: Boolean = true)

/**
 * Единая точка изменяющих операций. Одновременно выполняется одна операция; длинные операции
 * не блокируют UI, показывают прогресс и отменяются. После изменений индекс Android (MediaStore)
 * обновляется для затронутых путей — списки «Новые»/категорий обновятся сами.
 */
class OperationsManager(private val context: Context, private val scope: CoroutineScope) {
    val trash = TrashStore { file -> FileOperations.defaultVolumeRoot(file)?.let { File(it, TrashStore.TRASH_DIR) } }
    val ops = FileOperations(trash)

    private val _clipboard = MutableStateFlow<FileClipboard?>(null)
    val clipboard: StateFlow<FileClipboard?> = _clipboard.asStateFlow()

    private val _running = MutableStateFlow<RunningOperation?>(null)
    val running: StateFlow<RunningOperation?> = _running.asStateFlow()

    /** Счётчик завершённых операций: экраны перечитывают содержимое папок при его изменении. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val cancelFlag = AtomicBoolean(false)

    fun setClipboard(paths: List<String>, mode: TransferMode) {
        _clipboard.value = FileClipboard(paths, mode)
    }

    fun clearClipboard() {
        _clipboard.value = null
    }

    fun cancel() = cancelFlag.set(true)

    fun conflicts(paths: List<String>, destDir: File): List<String> = ops.conflicts(paths.map(::File), destDir)

    /** Запуск длинной операции в фоне. false — уже выполняется другая. */
    fun launch(title: String, cancellable: Boolean = true, block: suspend (onProgress: (OpProgress) -> Unit, isCancelled: () -> Boolean) -> OpReport?): Boolean {
        if (_running.value != null) {
            _messages.tryEmit("Дождитесь завершения текущей операции")
            return false
        }
        cancelFlag.set(false)
        _running.value = RunningOperation(title, null, cancellable)
        scope.launch(Dispatchers.IO) {
            val report = try {
                block({ p -> _running.value = RunningOperation(title, p, cancellable) }, { cancelFlag.get() })
            } catch (e: Exception) {
                OpReport(failed = listOf(title to (e.message ?: e.javaClass.simpleName)))
            }
            _running.value = null
            if (report != null) {
                rescan(report.created + report.removed)
                _messages.tryEmit(summary(title, report))
            }
            _version.value++
        }
        return true
    }

    fun paste(destDir: File, policy: ConflictPolicy) {
        val clip = _clipboard.value ?: return
        val sources = clip.paths.map(::File)
        val started = launch(clip.mode.verb) { onProgress, isCancelled ->
            when (clip.mode) {
                TransferMode.COPY -> ops.copy(sources, destDir, policy, onProgress, isCancelled)
                TransferMode.MOVE -> ops.move(sources, destDir, policy, onProgress, isCancelled)
            }
        }
        if (started) _clipboard.value = null
    }

    fun moveToTrash(paths: List<String>) {
        launch("Удаление в корзину", cancellable = false) { _, _ -> ops.moveToTrash(paths.map(::File)) }
    }

    fun deletePermanently(paths: List<String>) {
        launch("Удаление", cancellable = false) { _, _ -> ops.deletePermanently(paths.map(::File)) }
    }

    suspend fun rename(path: String, newName: String): RenameResult = withContext(Dispatchers.IO) {
        val result = ops.rename(File(path), newName)
        if (result is RenameResult.Ok) {
            rescan(listOf(path, result.file.absolutePath))
            _version.value++
        }
        result
    }

    /** Все папки корзины на подключённых носителях. */
    fun trashRoots(): List<File> {
        val sm = context.getSystemService(StorageManager::class.java)
        val dirs = runCatching { sm.storageVolumes.mapNotNull { it.directory } }.getOrDefault(emptyList())
        return dirs.map { File(it, TrashStore.TRASH_DIR) }.filter { it.isDirectory }
    }

    suspend fun trashItems(): List<TrashItem> = withContext(Dispatchers.IO) { trash.list(trashRoots()) }

    fun restore(items: List<TrashItem>) {
        launch("Восстановление", cancellable = false) { _, _ ->
            val done = mutableListOf<String>()
            val failed = mutableListOf<Pair<String, String>>()
            val created = mutableListOf<String>()
            items.forEach { item ->
                trash.restore(item).fold(
                    onSuccess = { done += item.meta.originalPath; created += it.absolutePath },
                    onFailure = { failed += item.meta.name to (it.message ?: "Ошибка") },
                )
            }
            OpReport(done = done, failed = failed, created = created)
        }
    }

    fun purge(items: List<TrashItem>) {
        launch("Очистка корзины", cancellable = false) { _, _ ->
            val done = items.filter { trash.purge(it) }.map { it.meta.name }
            val failed = items.filter { it.dir.exists() }.map { it.meta.name to "Не удалось удалить" }
            OpReport(done = done, failed = failed)
        }
    }

    /** Обновить индекс Android для изменённых путей (для папок — их файлы, с ограничением). */
    fun rescan(paths: List<String>) {
        if (paths.isEmpty()) return
        val expanded = paths.flatMap { p ->
            val f = File(p)
            if (f.isDirectory) f.walkTopDown().filter { it.isFile }.take(MAX_SCAN).map { it.absolutePath }.toList() + p else listOf(p)
        }.distinct().take(MAX_SCAN)
        MediaScannerConnection.scanFile(context, expanded.toTypedArray(), null, null)
    }

    private fun summary(title: String, r: OpReport): String {
        val parts = buildList {
            if (r.done.isNotEmpty()) add("готово: ${r.done.size}")
            if (r.skipped.isNotEmpty()) add("пропущено: ${r.skipped.size}")
            if (r.failed.isNotEmpty()) add("ошибки: ${r.failed.size} (${r.failed.first().second})")
            if (r.cancelled) add("отменено (незавершённый объект не изменён)")
        }
        return "$title — " + parts.joinToString("; ").ifEmpty { "нечего делать" }
    }

    companion object {
        private const val MAX_SCAN = 5_000
    }
}
