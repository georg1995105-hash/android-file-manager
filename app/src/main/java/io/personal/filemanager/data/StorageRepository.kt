package io.personal.filemanager.data

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager

data class StorageInfo(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
}

class StorageRepository(private val context: Context) {

    val rootPath: String get() = Environment.getExternalStorageDirectory().absolutePath

    val downloadsPath: String
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    /** Доступ ко всем файлам (MANAGE_EXTERNAL_STORAGE). */
    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /**
     * Реальные цифры памяти устройства. StorageStatsManager не требует разрешений и
     * показывает «маркетинговый» объём (как в настройках). При ошибке — StatFs.
     */
    fun internalStorage(): StorageInfo = try {
        val stats = context.getSystemService(StorageStatsManager::class.java)
        StorageInfo(
            totalBytes = stats.getTotalBytes(StorageManager.UUID_DEFAULT),
            freeBytes = stats.getFreeBytes(StorageManager.UUID_DEFAULT),
        )
    } catch (_: Exception) {
        val fs = StatFs(Environment.getDataDirectory().absolutePath)
        StorageInfo(totalBytes = fs.totalBytes, freeBytes = fs.availableBytes)
    }
}
