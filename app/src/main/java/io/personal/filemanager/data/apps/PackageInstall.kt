package io.personal.filemanager.data.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import net.lingala.zip4j.ZipFile
import java.io.File

/**
 * Установка только через штатный установщик Android с подтверждением пользователя:
 * одиночный APK — ACTION_VIEW, набор split APK (.apks/.xapk) — сессия PackageInstaller.
 * Никакой скрытой установки и обхода проверок системы.
 */
object PackageInstall {

    /** Разрешено ли приложению предлагать установку (системный переключатель «Установка неизвестных приложений»). */
    fun canRequestInstalls(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openUnknownSourcesSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Устанавливает проверенный набор: каждый APK из ZIP передаётся в сессию потоком.
     * Возвращает текст ошибки или null, если система приняла сессию (дальше — подтверждение пользователя).
     */
    fun installSet(context: Context, setFile: File, info: ApkSetInfo): String? {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(info.totalBytes)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = try {
            installer.createSession(params)
        } catch (e: Exception) {
            return "Не удалось начать установку: ${e.message}"
        }
        val session = installer.openSession(sessionId)
        return try {
            ZipFile(setFile).use { zip ->
                for (name in info.apkEntries) {
                    val header = zip.getFileHeader(name) ?: error("В наборе нет $name")
                    val streamName = name.substringAfterLast('/')
                    session.openWrite(streamName, 0, header.uncompressedSize).use { out ->
                        zip.getInputStream(header).use { it.copyTo(out, 256 * 1024) }
                        session.fsync(out)
                    }
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
            session.close()
            null
        } catch (e: Exception) {
            runCatching { session.abandon() }
            "Ошибка подготовки установки: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}

/** Получает статус сессии установки: показывает системное подтверждение и итог. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(context, "Приложение установлено", Toast.LENGTH_LONG).show()
            PackageInstaller.STATUS_FAILURE_ABORTED -> Toast.makeText(context, "Установка отменена", Toast.LENGTH_SHORT).show()
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "неизвестная ошибка"
                Toast.makeText(context, "Установка не выполнена: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
