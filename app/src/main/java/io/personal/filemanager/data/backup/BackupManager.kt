package io.personal.filemanager.data.backup

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.personal.filemanager.BuildConfig
import io.personal.filemanager.data.db.PersonalRepository
import io.personal.filemanager.data.settings.BackupMark
import io.personal.filemanager.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

sealed interface RestorePreview {
    data class Ready(val decoded: DecodeResult.Ok, val sourceName: String) : RestorePreview
    data object PasswordRequired : RestorePreview
    data object WrongPassword : RestorePreview
    data class Error(val message: String) : RestorePreview
}

/**
 * Резервные копии собственных данных приложения (ТЗ §7): ручной экспорт/импорт через SAF
 * и автокопирование по расписанию в выбранную папку. Обычные файлы пользователя не копируются.
 */
class BackupManager(
    private val context: Context,
    private val personal: PersonalRepository,
    private val settings: SettingsRepository,
) {
    private val resolver get() = context.contentResolver

    suspend fun buildPayload(): BackupPayload {
        val (notes, links, favorites) = personal.snapshot()
        return BackupPayload(notes, links, favorites, settings.exportSettings())
    }

    fun suggestedName(auto: Boolean): String {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return "${if (auto) AUTO_PREFIX else MANUAL_PREFIX}$stamp.${BackupCodec.FILE_EXTENSION}"
    }

    /**
     * Пишет копию в [uri] и сразу перечитывает её с проверкой. «Сохранено» отмечается только после проверки.
     */
    suspend fun writeBackup(uri: Uri, password: CharArray?, auto: Boolean = false): Result<BackupMark> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = BackupCodec.encode(buildPayload(), BuildConfig.VERSION_NAME, System.currentTimeMillis(), password)
            val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull() ?: resolver.openOutputStream(uri, "w")
            out?.use { it.write(bytes); it.flush() } ?: error("Не удалось открыть файл для записи")
            val back = readAll(uri)
            if (!back.contentEquals(bytes)) error("Проверка записи не прошла: файл отличается")
            when (val check = BackupCodec.decode(back, password)) {
                is DecodeResult.Ok -> Unit
                else -> error("Проверка копии не прошла: $check")
            }
            val mark = BackupMark(System.currentTimeMillis(), bytes.size.toLong(), displayName(uri) ?: "копия")
            settings.setBackupMark(auto, mark)
            mark
        }
    }

    suspend fun preview(uri: Uri, password: CharArray?): RestorePreview = withContext(Dispatchers.IO) {
        val size = runCatching { querySize(uri) }.getOrNull()
        if (size != null && size > BackupCodec.MAX_BACKUP_BYTES) return@withContext RestorePreview.Error("Файл слишком большой")
        val bytes = runCatching { readAll(uri) }.getOrElse { return@withContext RestorePreview.Error("Не удалось прочитать файл: ${it.message}") }
        when (val r = BackupCodec.decode(bytes, password)) {
            is DecodeResult.Ok -> RestorePreview.Ready(r, displayName(uri) ?: "копия")
            DecodeResult.PasswordRequired -> RestorePreview.PasswordRequired
            DecodeResult.WrongPassword -> RestorePreview.WrongPassword
            is DecodeResult.Unsupported -> RestorePreview.Error(r.reason)
            is DecodeResult.Invalid -> RestorePreview.Error(r.reason)
        }
    }

    /**
     * Применяет проверенную копию. Перед заменой сохраняется внутренний снимок текущих данных;
     * база заменяется одной транзакцией, при ошибке выполняется откат к снимку.
     */
    suspend fun restore(decoded: DecodeResult.Ok): Result<Unit> = withContext(Dispatchers.IO) {
        val safety = File(context.filesDir, "before-restore.${BackupCodec.FILE_EXTENSION}")
        val currentBytes = BackupCodec.encode(buildPayload(), BuildConfig.VERSION_NAME, System.currentTimeMillis())
        safety.writeBytes(currentBytes)
        runCatching {
            val p = decoded.payload
            personal.replaceAll(p.notes, p.links, p.favorites)
            settings.importSettings(p.settings)
        }.onFailure {
            // Откат к состоянию до восстановления.
            (BackupCodec.decode(currentBytes) as? DecodeResult.Ok)?.payload?.let { old ->
                runCatching {
                    personal.replaceAll(old.notes, old.links, old.favorites)
                    settings.importSettings(old.settings)
                }
            }
        }
    }

    // ---------- Автокопирование ----------

    fun scheduleAuto(intervalDays: Int) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, AutoBackupJob::class.java))
            .setPeriodic(intervalDays.coerceIn(1, 30) * 24L * 60 * 60 * 1000)
            .setRequiresBatteryNotLow(true)
            .setPersisted(true)
            .build()
        scheduler.schedule(job)
    }

    fun cancelAuto() {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }

    /** Выполняет одну автокопию; ошибки записываются в состояние и показываются в интерфейсе. */
    suspend fun runAutoBackup(): Result<BackupMark> {
        val status = settings.backupStatusNow()
        if (!status.autoEnabled) return Result.failure(IllegalStateException("Автокопирование выключено"))
        val tree = status.autoFolderUri?.let(Uri::parse)
            ?: return fail("Папка для автокопий не выбрана")
        val hasPermission = resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        if (!hasPermission) return fail("Нет доступа к выбранной папке — выберите её заново")
        val password = settings.autoBackupSecret()?.let { SecretStore.decrypt(it) ?: return fail("Пароль автокопий недоступен — задайте его заново") }
        return withContext(Dispatchers.IO) {
            try {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val name = suggestedName(auto = true)
                val doc = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
                    ?: return@withContext fail("Не удалось создать файл в папке автокопий")
                val result = writeBackup(doc, password, auto = true)
                password?.fill('\u0000')
                result.onSuccess { pruneOld(tree, status.autoKeep, keepName = displayName(doc)) }
                    .onFailure {
                        runCatching { DocumentsContract.deleteDocument(resolver, doc) }
                        settings.setAutoBackupError("Автокопия не создана: ${it.message}")
                    }
            } catch (e: Exception) {
                fail("Автокопия не создана: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun <T> fail(message: String): Result<T> {
        settings.setAutoBackupError(message)
        return Result.failure(IllegalStateException(message))
    }

    /** Удаляет только старые автокопии приложения (по шаблону имени) и только после успешной новой. */
    private fun pruneOld(tree: Uri, keep: Int, keepName: String?) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val ours = mutableListOf<Pair<String, String>>() // documentId, name
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (autoNamePattern.matches(name)) ours += c.getString(0) to name
            }
        }
        // Имена содержат дату-время: сортировка по имени = по времени создания.
        val sorted = ours.sortedByDescending { it.second }
        sorted.drop(keep.coerceAtLeast(1)).filter { it.second != keepName }.forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }

    private fun readAll(uri: Uri): ByteArray {
        val input = resolver.openInputStream(uri) ?: error("Не удалось открыть файл")
        return input.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total > BackupCodec.MAX_BACKUP_BYTES) error("Файл слишком большой")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun querySize(uri: Uri): Long? =
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    companion object {
        const val JOB_ID = 4201
        const val MANUAL_PREFIX = "pfm-backup-"
        const val AUTO_PREFIX = "pfm-auto-"
        private val autoNamePattern = Regex("""^pfm-auto-\d{8}-\d{6}(?: ?\(\d+\))?\.pfmbackup$""")
    }
}
