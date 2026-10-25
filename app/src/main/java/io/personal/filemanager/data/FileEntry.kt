package io.personal.filemanager.data

import io.personal.filemanager.core.FileKind
import io.personal.filemanager.core.FileTypes
import java.io.File

/**
 * Файл или папка. Даты в миллисекундах.
 * [dateAdded] — дата добавления в медиатеку Android (MediaStore.DATE_ADDED), если известна;
 * это не гарантированная дата скачивания.
 */
data class FileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val dateAdded: Long? = null,
    val mimeType: String? = null,
    val childCount: Int? = null,
    /** _ID в MediaStore, если запись получена из индекса (для системных миниатюр). */
    val mediaId: Long? = null,
) {
    val kind: FileKind get() = FileTypes.kindOf(name, isDirectory)
    val extension: String get() = FileTypes.extensionOf(name)
    val isHidden: Boolean get() = name.startsWith(".")
    val file: File get() = File(path)

    companion object {
        fun from(file: File, countChildren: Boolean = false): FileEntry {
            val dir = file.isDirectory
            return FileEntry(
                path = file.absolutePath,
                name = file.name,
                isDirectory = dir,
                size = if (dir) 0L else file.length(),
                lastModified = file.lastModified(),
                childCount = if (dir && countChildren) file.list()?.size else null,
            )
        }
    }
}
