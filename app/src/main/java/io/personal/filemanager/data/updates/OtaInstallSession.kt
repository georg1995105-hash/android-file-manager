package io.personal.filemanager.data.updates

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

internal data class OtaInstallEvent(val status: Int, val message: String = "", val confirmation: Intent? = null)

internal object OtaInstallSession {
    val events = MutableStateFlow<OtaInstallEvent?>(null)
    private val lock = Any()
    private const val ACTION = "io.personal.filemanager.OTA_INSTALL_RESULT"

    /** Only called with a verified APK by OtaUpdater. Android still requires user confirmation. */
    fun start(context: Context, file: File) = synchronized(lock) {
        val installer = context.packageManager.packageInstaller
        // Cancel only previous sessions explicitly recorded by this OTA component.
        val prefs = context.getSharedPreferences("ota-install", Context.MODE_PRIVATE)
        val previous = prefs.getInt("session", -1)
        if (previous >= 0) runCatching { installer.abandonSession(previous) }
        events.value = null
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        prefs.edit().putInt("session", id).apply()
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 128 * 1024) }
                    session.fsync(out)
                }
                val callback = Intent(context, OtaInstallReceiver::class.java)
                    .setAction(ACTION).putExtra("otaSession", id)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                session.commit(PendingIntent.getBroadcast(context, id, callback, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            prefs.edit().remove("session").apply()
            throw e
        }
    }

    fun receive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val prefs = context.getSharedPreferences("ota-install", Context.MODE_PRIVATE)
        val id = intent.getIntExtra("otaSession", -1)
        if (id < 0 || id != prefs.getInt("session", -1)) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirm = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        }
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) prefs.edit().remove("session").apply()
        events.value = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && confirm == null)
            OtaInstallEvent(PackageInstaller.STATUS_FAILURE, "Android не вернул окно подтверждения")
        else OtaInstallEvent(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty(), confirm)
    }
}

/** The foreground update screen opens the confirmation, not a background broadcast. */
class OtaInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = OtaInstallSession.receive(context, intent)
}
