package io.personal.filemanager.ui.ops

import androidx.compose.runtime.staticCompositionLocalOf
import io.personal.filemanager.data.FileEntry

/** Изменяющие действия с файлами, доступные из любых списков. Диалоги показывает FileOpsHost. */
interface FileOpsController {
    /** Кнопки архивов/избранного показываются, только когда функция реально подключена. */
    val supportsArchives: Boolean
    val supportsFavorites: Boolean

    fun copy(entries: List<FileEntry>)
    fun move(entries: List<FileEntry>)
    fun rename(entry: FileEntry)
    fun delete(entries: List<FileEntry>)
    fun compress(entries: List<FileEntry>)
    fun extract(entry: FileEntry)
    fun openArchive(entry: FileEntry)
    fun toggleFavorite(entry: FileEntry)
    fun isFavorite(path: String): Boolean

    /** Папка, открытая сейчас в обзоре файлов (для «Вставить сюда»); null — обзор не открыт. */
    fun setCurrentFolder(path: String?)
}

val LocalFileOps = staticCompositionLocalOf<FileOpsController> { error("FileOpsController не задан") }

/** Пустая реализация для превью и скриншот-тестов. */
object NoopFileOps : FileOpsController {
    override val supportsArchives = false
    override val supportsFavorites = false
    override fun copy(entries: List<FileEntry>) = Unit
    override fun move(entries: List<FileEntry>) = Unit
    override fun rename(entry: FileEntry) = Unit
    override fun delete(entries: List<FileEntry>) = Unit
    override fun compress(entries: List<FileEntry>) = Unit
    override fun extract(entry: FileEntry) = Unit
    override fun openArchive(entry: FileEntry) = Unit
    override fun toggleFavorite(entry: FileEntry) = Unit
    override fun isFavorite(path: String) = false
    override fun setCurrentFolder(path: String?) = Unit
}
