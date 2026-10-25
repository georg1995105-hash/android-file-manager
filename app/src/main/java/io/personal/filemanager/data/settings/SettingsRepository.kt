package io.personal.filemanager.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import io.personal.filemanager.data.backup.BackupSettings
import java.io.IOException

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore

    private val keyUiConfig = stringPreferencesKey("ui_config_json")
    private val keyRecentSort = stringPreferencesKey("recent_sort")
    private val keyBrowserSort = stringPreferencesKey("browser_sort")
    private val keyBrowserDesc = stringPreferencesKey("browser_desc")
    private val keyShowHidden = stringPreferencesKey("show_hidden")

    // Резервные копии (состояние, не секреты).
    private val keyBackupLastManual = stringPreferencesKey("backup_last_manual")
    private val keyBackupAutoEnabled = stringPreferencesKey("backup_auto_enabled")
    private val keyBackupAutoFolder = stringPreferencesKey("backup_auto_folder")
    private val keyBackupAutoInterval = stringPreferencesKey("backup_auto_interval_days")
    private val keyBackupAutoKeep = stringPreferencesKey("backup_auto_keep")
    private val keyBackupLastAuto = stringPreferencesKey("backup_last_auto")
    private val keyBackupAutoError = stringPreferencesKey("backup_auto_error")
    /** Пароль автокопий, зашифрованный ключом Android Keystore (сам пароль не хранится). */
    private val keyBackupAutoSecret = stringPreferencesKey("backup_auto_secret")

    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val uiConfig: Flow<UiConfig> = data.map { UiConfigCodec.decode(it[keyUiConfig]) }

    val recentSort: Flow<RecentSort> = data.map { prefs ->
        RecentSort.entries.firstOrNull { it.name == prefs[keyRecentSort] } ?: RecentSort.ADDED
    }

    val browserPrefs: Flow<BrowserPrefs> = data.map { prefs ->
        BrowserPrefs(
            sort = BrowserSort.entries.firstOrNull { it.name == prefs[keyBrowserSort] } ?: BrowserSort.NAME,
            descending = prefs[keyBrowserDesc] == "true",
            showHidden = prefs[keyShowHidden] == "true",
        )
    }

    suspend fun updateUiConfig(transform: (UiConfig) -> UiConfig) {
        store.edit { prefs ->
            val current = UiConfigCodec.decode(prefs[keyUiConfig])
            prefs[keyUiConfig] = UiConfigCodec.encode(UiConfigCodec.normalize(transform(current)))
        }
    }

    suspend fun recentSortNow(): RecentSort = recentSort.first()

    suspend fun setRecentSort(sort: RecentSort) {
        store.edit { it[keyRecentSort] = sort.name }
    }

    val backupStatus: Flow<BackupStatus> = data.map { p ->
        BackupStatus(
            lastManual = p[keyBackupLastManual]?.let(BackupMark::parse),
            autoEnabled = p[keyBackupAutoEnabled] == "true",
            autoFolderUri = p[keyBackupAutoFolder],
            autoIntervalDays = p[keyBackupAutoInterval]?.toIntOrNull() ?: 1,
            autoKeep = p[keyBackupAutoKeep]?.toIntOrNull() ?: 7,
            lastAuto = p[keyBackupLastAuto]?.let(BackupMark::parse),
            autoError = p[keyBackupAutoError],
            autoEncrypted = p[keyBackupAutoSecret] != null,
        )
    }

    suspend fun backupStatusNow(): BackupStatus = backupStatus.first()

    suspend fun autoBackupSecret(): String? = data.first()[keyBackupAutoSecret]

    suspend fun setBackupMark(auto: Boolean, mark: BackupMark) {
        store.edit {
            if (auto) {
                it[keyBackupLastAuto] = mark.serialize()
                it.remove(keyBackupAutoError)
            } else {
                it[keyBackupLastManual] = mark.serialize()
            }
        }
    }

    suspend fun setAutoBackupError(message: String?) {
        store.edit { if (message == null) it.remove(keyBackupAutoError) else it[keyBackupAutoError] = message }
    }

    suspend fun setAutoBackup(enabled: Boolean, folderUri: String?, intervalDays: Int, keep: Int, encryptedSecret: String?) {
        store.edit {
            it[keyBackupAutoEnabled] = enabled.toString()
            if (folderUri != null) it[keyBackupAutoFolder] = folderUri else it.remove(keyBackupAutoFolder)
            it[keyBackupAutoInterval] = intervalDays.toString()
            it[keyBackupAutoKeep] = keep.toString()
            if (encryptedSecret != null) it[keyBackupAutoSecret] = encryptedSecret else it.remove(keyBackupAutoSecret)
        }
    }

    /** Настройки для резервной копии. */
    suspend fun exportSettings(): BackupSettings {
        val p = data.first()
        return BackupSettings(
            uiConfigJson = p[keyUiConfig],
            recentSort = p[keyRecentSort],
            browserSort = p[keyBrowserSort],
            browserDescending = p[keyBrowserDesc]?.toBooleanStrictOrNull(),
            showHidden = p[keyShowHidden]?.toBooleanStrictOrNull(),
        )
    }

    /** Применение настроек из копии; некорректные значения нормализуются. */
    suspend fun importSettings(s: BackupSettings) {
        store.edit { p ->
            s.uiConfigJson?.let { p[keyUiConfig] = UiConfigCodec.encode(UiConfigCodec.decode(it)) }
            s.recentSort?.takeIf { v -> RecentSort.entries.any { it.name == v } }?.let { p[keyRecentSort] = it }
            s.browserSort?.takeIf { v -> BrowserSort.entries.any { it.name == v } }?.let { p[keyBrowserSort] = it }
            s.browserDescending?.let { p[keyBrowserDesc] = it.toString() }
            s.showHidden?.let { p[keyShowHidden] = it.toString() }
        }
    }

    suspend fun setBrowserPrefs(prefs: BrowserPrefs) {
        store.edit {
            it[keyBrowserSort] = prefs.sort.name
            it[keyBrowserDesc] = prefs.descending.toString()
            it[keyShowHidden] = prefs.showHidden.toString()
        }
    }
}

/** Отметка успешной и проверенной копии. */
data class BackupMark(val at: Long, val size: Long, val name: String) {
    fun serialize() = "$at|$size|$name"

    companion object {
        fun parse(raw: String): BackupMark? {
            val parts = raw.split('|', limit = 3)
            if (parts.size != 3) return null
            return BackupMark(parts[0].toLongOrNull() ?: return null, parts[1].toLongOrNull() ?: 0, parts[2])
        }
    }
}

data class BackupStatus(
    val lastManual: BackupMark?,
    val autoEnabled: Boolean,
    val autoFolderUri: String?,
    val autoIntervalDays: Int,
    val autoKeep: Int,
    val lastAuto: BackupMark?,
    val autoError: String?,
    val autoEncrypted: Boolean,
)
