package io.personal.filemanager.data

import android.app.usage.StorageStatsManager
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
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

    /** Android 11+: all files; Android 8-10: ask for legacy storage access. */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

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
