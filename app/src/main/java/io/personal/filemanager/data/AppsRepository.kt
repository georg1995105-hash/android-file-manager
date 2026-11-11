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
    val lastUpdateTime: Long = 0L,
    /** base.apk + split-файлы (для экспорта набора .apks). */
    val apkPaths: List<String>,
)

data class ApkInfo(
    val label: String?,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long = 0,
    val minSdk: Int = 0,
    val targetSdk: Int = 0,
)

/** Что уже установлено на телефоне для пакета (если приложение видно нашему приложению). */
data class InstalledVersion(val versionName: String?, val versionCode: Long)

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
                    lastUpdateTime = info.lastUpdateTime,
                    apkPaths = paths,
                )
            }.getOrNull()
        }.sortedBy { it.label.lowercase() }
    }

    @Volatile
    private var statsCache: Pair<Long, CategoryStats>? = null

    /**
     * Число и общий размер приложений для плитки главной — без загрузки подписей (дорого) и с кэшем
     * на 2 минуты: главная обновляется при каждом возврате на неё.
     */
    fun launchableStats(): CategoryStats {
        val now = System.currentTimeMillis()
        statsCache?.let { (at, stats) -> if (now - at < STATS_TTL_MS) return stats }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.distinct()
            .filter { it != context.packageName }
        var total = 0L
        var count = 0
        for (pkg in packages) {
            val app = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
            total += (listOfNotNull(app.sourceDir) + app.splitSourceDirs.orEmpty()).sumOf { File(it).length() }
            count++
        }
        return CategoryStats(total, count).also { statsCache = now to it }
    }

    @Suppress("DEPRECATION")
    fun installedVersion(packageName: String): InstalledVersion? = runCatching {
        val info = pm.getPackageInfo(packageName, 0)
        InstalledVersion(info.versionName, info.longVersionCode)
    }.getOrNull()

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
            versionCode = info.longVersionCode,
            minSdk = app?.minSdkVersion ?: 0,
            targetSdk = app?.targetSdkVersion ?: 0,
        ) to app?.loadIcon(pm)
    }.getOrNull()
}

private const val STATS_TTL_MS = 120_000L
