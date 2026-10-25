package io.personal.filemanager.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.io.File

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val sizeBytes: Long,
    val isSystem: Boolean,
    /** base.apk + split-файлы. Экспорт набора — этап 4. */
    val apkPaths: List<String>,
)

data class ApkInfo(val label: String?, val packageName: String?, val versionName: String?)

/** Установленные приложения через PackageManager в рамках видимости из <queries> (лаунчер-приложения). */
class AppsRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager

    @Suppress("DEPRECATION")
    fun launchableApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
        return packages.mapNotNull { pkg ->
            runCatching {
                val info = pm.getPackageInfo(pkg, 0)
                val app = info.applicationInfo ?: return@runCatching null
                val paths = listOfNotNull(app.sourceDir) + app.splitSourceDirs.orEmpty()
                InstalledApp(
                    packageName = pkg,
                    label = app.loadLabel(pm).toString(),
                    versionName = info.versionName,
                    sizeBytes = paths.sumOf { File(it).length() },
                    isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    apkPaths = paths,
                )
            }.getOrNull()
        }.sortedBy { it.label.lowercase() }
    }

    fun icon(packageName: String): Drawable? = runCatching { pm.getApplicationIcon(packageName) }.getOrNull()

    fun launchIntent(packageName: String): Intent? = pm.getLaunchIntentForPackage(packageName)

    /** Иконка и подписи APK-файла без установки. */
    @Suppress("DEPRECATION")
    fun apkArchive(path: String): Pair<ApkInfo, Drawable?>? = runCatching {
        val info = pm.getPackageArchiveInfo(path, 0) ?: return@runCatching null
        val app = info.applicationInfo
        if (app != null) {
            app.sourceDir = path
            app.publicSourceDir = path
        }
        ApkInfo(
            label = app?.loadLabel(pm)?.toString(),
            packageName = info.packageName,
            versionName = info.versionName,
        ) to app?.loadIcon(pm)
    }.getOrNull()
}
