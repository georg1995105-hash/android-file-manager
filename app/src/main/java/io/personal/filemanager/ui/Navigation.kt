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
    const val VIDEO = "video?path={path}"
    const val SETTINGS_TILES = "settings/tiles"
    const val SETTINGS_TABS = "settings/tabs"

    fun section(id: SectionId) = "section/${id.name}"
    fun image(path: String) = "image?path=${Uri.encode(path)}"
    fun text(path: String) = "text?path=${Uri.encode(path)}"
    fun video(path: String) = "video?path=${Uri.encode(path)}"
}

interface AppNavigator {
    fun openSection(id: SectionId)
    fun openFile(entry: FileEntry, siblings: List<FileEntry> = emptyList())
    fun openSearch()
    fun openTileSettings()
    fun openTabSettings()
    fun back()
}

val LocalNavigator = staticCompositionLocalOf<AppNavigator> { error("AppNavigator не задан") }

/** Список изображений для перелистывания во встроенном просмотре (живёт в памяти процесса). */
object ImageViewerSession {
    @Volatile
    var paths: List<String> = emptyList()
}
