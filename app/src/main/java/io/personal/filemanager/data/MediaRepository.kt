package io.personal.filemanager.data

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import io.personal.filemanager.core.FileTypes
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** Наборы файлов, которые берутся из индекса Android (MediaStore), а не сканированием накопителя. */
enum class MediaCategory {
    RECENT, PHOTOS, VIDEOS, AUDIO, DOCUMENTS, ARCHIVES, APKS, DOWNLOADS
}

enum class MediaSort { ADDED, MODIFIED, SIZE, NAME }

data class CategoryStats(val totalBytes: Long, val count: Int)

/**
 * Запросы к MediaStore. С разрешением «доступ ко всем файлам» индекс содержит все файлы
 * общего хранилища, включая документы и архивы. Даты MediaStore — в секундах.
 */
class MediaRepository(context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val filesUri: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    @Suppress("DEPRECATION")
    private val dataColumn = FileColumns.DATA

    private val projection = arrayOf(
        FileColumns._ID,
        dataColumn,
        FileColumns.DISPLAY_NAME,
        FileColumns.SIZE,
        FileColumns.DATE_ADDED,
        FileColumns.DATE_MODIFIED,
        FileColumns.MIME_TYPE,
    )

    private data class Selection(val sql: String, val args: Array<String>)

    private fun likeAny(column: String, patterns: Collection<String>): Selection =
        Selection(
            sql = patterns.joinToString(" OR ", prefix = "(", postfix = ")") { "$column LIKE ?" },
            args = patterns.toTypedArray(),
        )

    private fun selectionFor(category: MediaCategory): Selection {
        // Папки в индексе не имеют MIME-типа.
        val base = "${FileColumns.MIME_TYPE} IS NOT NULL"
        val extra: Selection? = when (category) {
            MediaCategory.RECENT -> null
            MediaCategory.PHOTOS -> Selection("${FileColumns.MEDIA_TYPE} = ?", arrayOf(FileColumns.MEDIA_TYPE_IMAGE.toString()))
            MediaCategory.VIDEOS -> Selection("${FileColumns.MEDIA_TYPE} = ?", arrayOf(FileColumns.MEDIA_TYPE_VIDEO.toString()))
            MediaCategory.AUDIO -> Selection("${FileColumns.MEDIA_TYPE} = ?", arrayOf(FileColumns.MEDIA_TYPE_AUDIO.toString()))
            MediaCategory.DOCUMENTS -> likeAny(FileColumns.DISPLAY_NAME, FileTypes.documentExtensions.map { "%.$it" })
            MediaCategory.ARCHIVES -> likeAny(FileColumns.DISPLAY_NAME, FileTypes.archiveLikePatterns)
            MediaCategory.APKS -> likeAny(FileColumns.DISPLAY_NAME, FileTypes.apkExtensions.map { "%.$it" })
            MediaCategory.DOWNLOADS -> Selection("${FileColumns.RELATIVE_PATH} LIKE ?", arrayOf("Download/%"))
        }
        return if (extra == null) Selection(base, emptyArray()) else Selection("$base AND ${extra.sql}", extra.args)
    }

    private fun query(selection: Selection, sortOrder: String?, limit: Int, columns: Array<String> = projection): Cursor? {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection.sql)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selection.args)
            if (sortOrder != null) putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            if (limit > 0) putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        return resolver.query(filesUri, columns, args, null)
    }

    private fun Cursor.readEntries(skipHidden: Boolean): List<FileEntry> {
        val iData = getColumnIndexOrThrow(dataColumn)
        val iName = getColumnIndexOrThrow(FileColumns.DISPLAY_NAME)
        val iSize = getColumnIndexOrThrow(FileColumns.SIZE)
        val iAdded = getColumnIndexOrThrow(FileColumns.DATE_ADDED)
        val iModified = getColumnIndexOrThrow(FileColumns.DATE_MODIFIED)
        val iMime = getColumnIndexOrThrow(FileColumns.MIME_TYPE)
        val iId = getColumnIndex(FileColumns._ID)
        val result = ArrayList<FileEntry>(count)
        while (moveToNext()) {
            val path = getString(iData) ?: continue
            if (skipHidden && FileSystemRepository.hasHiddenSegment(path)) continue
            val name = getString(iName) ?: path.substringAfterLast('/')
            result += FileEntry(
                path = path,
                name = name,
                isDirectory = false,
                size = getLong(iSize),
                lastModified = getLong(iModified) * 1000L,
                dateAdded = getLong(iAdded).takeIf { it > 0 }?.times(1000L),
                mimeType = getString(iMime),
                mediaId = if (iId >= 0) getLong(iId) else null,
            )
        }
        return result
    }

    fun list(category: MediaCategory, sort: MediaSort = MediaSort.ADDED, limit: Int = DEFAULT_LIMIT): List<FileEntry> {
        val sortOrder = when (sort) {
            MediaSort.ADDED -> "${FileColumns.DATE_ADDED} DESC"
            MediaSort.MODIFIED -> "${FileColumns.DATE_MODIFIED} DESC"
            MediaSort.SIZE -> "${FileColumns.SIZE} DESC"
            MediaSort.NAME -> "${FileColumns.DISPLAY_NAME} ASC"
        }
        val entries = query(selectionFor(category), sortOrder, limit)?.use { it.readEntries(skipHidden = true) }.orEmpty()
        // SQLite сравнивает имена побайтно (заглавные раньше строчных) — итог упорядочиваем без учёта регистра.
        return if (sort == MediaSort.NAME) entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) else entries
    }

    /** Поиск по имени во всём индексе; спецсимволы LIKE экранируются. */
    fun search(text: String, limit: Int = DEFAULT_LIMIT): List<FileEntry> {
        val q = text.trim()
        if (q.isEmpty()) return emptyList()
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val selection = Selection(
            "${FileColumns.MIME_TYPE} IS NOT NULL AND ${FileColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
            arrayOf("%$escaped%"),
        )
        return query(selection, "${FileColumns.DATE_MODIFIED} DESC", limit)?.use { it.readEntries(skipHidden = false) }.orEmpty()
    }

    /** Размер и количество файлов категории (для плиток главной). */
    fun stats(category: MediaCategory, addedAfterSeconds: Long? = null): CategoryStats {
        var selection = selectionFor(category)
        if (addedAfterSeconds != null) {
            selection = Selection(
                "${selection.sql} AND ${FileColumns.DATE_ADDED} >= ?",
                selection.args + addedAfterSeconds.toString(),
            )
        }
        val columns = arrayOf(dataColumn, FileColumns.SIZE)
        return query(selection, null, 0, columns)?.use { c ->
            val iData = c.getColumnIndexOrThrow(dataColumn)
            val iSize = c.getColumnIndexOrThrow(FileColumns.SIZE)
            var total = 0L
            var count = 0
            while (c.moveToNext()) {
                val path = c.getString(iData) ?: continue
                if (FileSystemRepository.hasHiddenSegment(path)) continue
                total += c.getLong(iSize)
                count++
            }
            CategoryStats(total, count)
        } ?: CategoryStats(0, 0)
    }

    /** Сигнал об изменениях в индексе: экраны обновляются сами, без кнопки «Обновить». */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(filesUri, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    companion object {
        const val DEFAULT_LIMIT = 300
    }
}
