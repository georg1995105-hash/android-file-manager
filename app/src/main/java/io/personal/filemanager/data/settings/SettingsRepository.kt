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
import java.io.IOException

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class RecentSort(val title: String) {
    ADDED("По дате добавления"),
    MODIFIED("По дате изменения"),
}

enum class BrowserSort(val title: String) {
    NAME("По имени"),
    DATE("По дате изменения"),
    SIZE("По размеру"),
    TYPE("По типу"),
}

data class BrowserPrefs(
    val sort: BrowserSort = BrowserSort.NAME,
    val descending: Boolean = false,
    val showHidden: Boolean = false,
)

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore

    private val keyUiConfig = stringPreferencesKey("ui_config_json")
    private val keyRecentSort = stringPreferencesKey("recent_sort")
    private val keyBrowserSort = stringPreferencesKey("browser_sort")
    private val keyBrowserDesc = stringPreferencesKey("browser_desc")
    private val keyShowHidden = stringPreferencesKey("show_hidden")

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

    suspend fun setBrowserPrefs(prefs: BrowserPrefs) {
        store.edit {
            it[keyBrowserSort] = prefs.sort.name
            it[keyBrowserDesc] = prefs.descending.toString()
            it[keyShowHidden] = prefs.showHidden.toString()
        }
    }
}
