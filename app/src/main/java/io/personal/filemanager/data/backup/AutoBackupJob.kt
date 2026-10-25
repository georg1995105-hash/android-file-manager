package io.personal.filemanager.data.backup

import android.app.job.JobParameters
import android.app.job.JobService
import io.personal.filemanager.FileManagerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Периодическая автокопия через системный планировщик (JobScheduler). Android не гарантирует
 * запуск в точное время; результат и ошибки сохраняются и показываются на экране «Резервные копии».
 */
class AutoBackupJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as FileManagerApp).container
        running = scope.launch {
            container.backups.runAutoBackup()
            // При ошибке следующая попытка — по расписанию; ошибка уже сохранена для показа в интерфейсе.
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
