package io.personal.filemanager.data

import io.personal.filemanager.data.settings.BrowserPrefs
import io.personal.filemanager.data.settings.BrowserSort
import java.io.File
import java.text.Collator
import java.util.Locale

sealed interface FolderResult {
    data class Ok(val entries: List<FileEntry>) : FolderResult
    /** Android 11+ закрывает Android/data и Android/obb даже при доступе ко всем файлам. */
    data object RestrictedByAndroid : FolderResult
    data object NotFound : FolderResult
    data object AccessDenied : FolderResult
}

sealed interface CreateFolderResult {
    data class Created(val entry: FileEntry) : CreateFolderResult
    data class Invalid(val reason: String) : CreateFolderResult
    data object AlreadyExists : CreateFolderResult
    data object Failed : CreateFolderResult
}

/** Работа с файловой системой через java.io (требует доступа ко всем файлам). Без Android-зависимостей. */
class FileSystemRepository {

    fun list(path: String, prefs: BrowserPrefs): FolderResult {
        val dir = File(path)
        if (isRestrictedByAndroid(dir)) return FolderResult.RestrictedByAndroid
        if (!dir.exists() || !dir.isDirectory) return FolderResult.NotFound
        val children = dir.listFiles() ?: return FolderResult.AccessDenied
        val entries = children
            .asSequence()
            .filter { prefs.showHidden || !it.name.startsWith(".") }
            .map { FileEntry.from(it, countChildren = true) }
            .toList()
        return FolderResult.Ok(sort(entries, prefs))
    }

    fun sort(entries: List<FileEntry>, prefs: BrowserPrefs): List<FileEntry> {
        val collator = Collator.getInstance(Locale.forLanguageTag("ru")).apply { strength = Collator.SECONDARY }
        val byName = Comparator<FileEntry> { a, b -> collator.compare(a.name, b.name) }
        val primary: Comparator<FileEntry> = when (prefs.sort) {
            BrowserSort.NAME -> byName
            BrowserSort.DATE -> compareBy<FileEntry> { it.lastModified }.then(byName)
            BrowserSort.SIZE -> compareBy<FileEntry> { if (it.isDirectory) (it.childCount ?: 0).toLong() else it.size }.then(byName)
            BrowserSort.TYPE -> compareBy<FileEntry> { it.extension }.then(byName)
        }
        val ordered = if (prefs.descending) primary.reversed() else primary
        // Папки всегда сверху.
        return entries.sortedWith(compareBy<FileEntry> { !it.isDirectory }.then(ordered))
    }

    fun createFolder(parentPath: String, rawName: String): CreateFolderResult {
        val name = rawName.trim()
        validateName(name)?.let { return CreateFolderResult.Invalid(it) }
        val parent = File(parentPath)
        if (!parent.isDirectory) return CreateFolderResult.Failed
        val target = File(parent, name)
        if (target.exists()) return CreateFolderResult.AlreadyExists
        return if (target.mkdir()) CreateFolderResult.Created(FileEntry.from(target)) else CreateFolderResult.Failed
    }

    /**
     * Создать пустой файл в доступной папке. createNewFile() атомарно отклоняет
     * существующее имя: содержимое пользовательского файла никогда не перезаписываем.
     * Тип определяется расширением имени; автоматически исполняемый файл не создаём.
     */
    /** Validate a new document without writing an empty file before the editor is saved. */
    fun prepareFile(parentPath: String, rawName: String): CreateFolderResult {
        val name = rawName.trim()
        validateName(name)?.let { return CreateFolderResult.Invalid(it) }
        val parent = File(parentPath)
        if (!parent.isDirectory || !parent.canWrite() || isRestrictedByAndroid(parent)) return CreateFolderResult.Failed
        val target = File(parent, name)
        return if (target.exists()) CreateFolderResult.AlreadyExists
        else CreateFolderResult.Created(FileEntry.from(target))
    }

    fun createFile(parentPath: String, rawName: String): CreateFolderResult {
        val name = rawName.trim()
        validateName(name)?.let { return CreateFolderResult.Invalid(it) }
        val parent = File(parentPath)
        if (!parent.isDirectory || isRestrictedByAndroid(parent)) return CreateFolderResult.Failed
        val target = File(parent, name)
        return try {
            if (!target.createNewFile()) CreateFolderResult.AlreadyExists
            else CreateFolderResult.Created(FileEntry.from(target))
        } catch (_: java.io.IOException) {
            CreateFolderResult.Failed
        } catch (_: SecurityException) {
            CreateFolderResult.Failed
        }
    }

    fun parentOf(path: String, root: String): String? {
        val normalizedRoot = File(root).absolutePath
        val current = File(path).absolutePath
        if (current == normalizedRoot) return null
        return File(current).parent
    }

    companion object {
        private val forbiddenChars = charArrayOf('/', '\u0000')

        fun validateName(name: String): String? = when {
            name.isEmpty() -> "Введите имя"
            name == "." || name == ".." -> "Недопустимое имя"
            name.any { it in forbiddenChars } -> "Имя не может содержать «/»"
            name.toByteArray(Charsets.UTF_8).size > 255 -> "Слишком длинное имя"
            else -> null
        }

        private val restrictedPath = Regex(
            """^(/storage/emulated/\d+|/storage/[^/]+|/sdcard)/Android/(data|obb)(/.*)?$""",
            RegexOption.IGNORE_CASE,
        )

        fun isRestrictedByAndroid(dir: File): Boolean = restrictedPath.matches(dir.absolutePath.trimEnd('/'))

        /** Скрытые сегменты пути (/.thumbnails/, /.trash/) — для фильтрации «Новых». */
        fun hasHiddenSegment(path: String): Boolean = path.split('/').any { it.length > 1 && it.startsWith(".") }
    }
}
