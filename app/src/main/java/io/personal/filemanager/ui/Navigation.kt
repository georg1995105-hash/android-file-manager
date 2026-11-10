package io.personal.filemanager.ui

import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import io.personal.filemanager.core.SectionId
import io.personal.filemanager.data.FileEntry

object Routes {
    const val HOME = "home"
    const val SECTION = "section/{id}"
    const val SEARCH = "search"
    const val IMAGE = "image?path={path}"
    const val TEXT = "text?path={path}"
    const val EDIT = "edit?path={path}"
    const val VIDEO = "video?path={path}"
    const val AUDIO = "audio?path={path}"
    const val SETTINGS_TILES = "settings/tiles"
    const val SETTINGS_TABS = "settings/tabs"
    const val TRASH = "trash"
    const val ARCHIVE = "archive?path={path}"
    const val APK = "apk?path={path}"
    const val NOTE = "note?id={id}"
    const val BROWSER = "browser?path={path}"
    const val BACKUP = "backup"
    const val PDF = "pdf?path={path}"

    fun section(id: SectionId) = "section/${id.name}"
    fun image(path: String) = "image?path=${Uri.encode(path)}"
    fun text(path: String) = "text?path=${Uri.encode(path)}"
    fun edit(path: String) = "edit?path=${Uri.encode(path)}"
    fun video(path: String) = "video?path=${Uri.encode(path)}"
    fun audio(path: String) = "audio?path=${Uri.encode(path)}"
    fun archive(path: String) = "archive?path=${Uri.encode(path)}"
    fun apk(path: String) = "apk?path=${Uri.encode(path)}"
    fun note(id: Long) = "note?id=$id"
    fun pdf(path: String) = "pdf?path=${Uri.encode(path)}"
    fun browser(path: String) = "browser?path=${Uri.encode(path)}"
}

interface AppNavigator {
    fun openSection(id: SectionId)
    fun openFile(entry: FileEntry, siblings: List<FileEntry> = emptyList())
    fun openSearch()
    fun openTileSettings()
    fun openTabSettings()

    /** Переход на произвольный маршрут из [Routes]. */
    fun open(route: String)
    fun back()
}

val LocalNavigator = staticCompositionLocalOf<AppNavigator> { error("AppNavigator не задан") }

/** Фото и видео для перелистывания во встроенном просмотре (живёт в памяти процесса). */
object MediaViewerSession {
    @Volatile
    var paths: List<String> = emptyList()
}

/** Соседние аудиофайлы для переключения треков; хранится только в процессе. */
object AudioViewerSession {
    @Volatile var paths: List<String> = emptyList()
}
