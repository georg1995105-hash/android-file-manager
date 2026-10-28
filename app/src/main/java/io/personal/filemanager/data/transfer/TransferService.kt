package io.personal.filemanager.data.transfer

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import io.personal.filemanager.FileManagerApp
import io.personal.filemanager.MainActivity
import io.personal.filemanager.R

class TransferService : Service() {
    private val manager get() = (application as FileManagerApp).container.transfer
    private var wake: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("transfer", "Передача файлов", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TransferService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "transfer").setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Локальная передача включена").setContentText("Откройте приложение для подтверждения запросов")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Остановить", stop).build()).build()
        startForeground(40, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "filemanager:transfer").apply { acquire(6 * 3600_000L) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") stopSelf() else manager.start()
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
    override fun onDestroy() { manager.stop(); wake?.let { if (it.isHeld) it.release() }; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
