package io.personal.filemanager.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

private val Context.playbackStore: DataStore<Preferences> by preferencesDataStore(name = "playback")

/**
 * Позиция просмотра видео (локально, только в приложении). Ключ — хеш пути, сам путь не хранится.
 * Ограничение на число записей: при превышении удаляются самые старые.
 */
class PlaybackPositions(context: Context) {
    private val store = context.applicationContext.playbackStore

    private fun key(path: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(path.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(20)
    }

    suspend fun get(path: String): Long {
        val prefs = store.data.first()
        return prefs[longPreferencesKey("pos_" + key(path))] ?: 0L
    }

    /** Сохраняет позицию; почти досмотренное или самое начало — сбрасывает. */
    suspend fun save(path: String, positionMs: Long, durationMs: Long) {
        val k = key(path)
        val keepPosition = durationMs > 0 && positionMs > START_SKIP_MS && positionMs < durationMs - END_SKIP_MS
        store.edit { prefs ->
            val posKey = longPreferencesKey("pos_$k")
            val timeKey = longPreferencesKey("time_$k")
            if (keepPosition) {
                prefs[posKey] = positionMs
                prefs[timeKey] = System.currentTimeMillis()
            } else {
                prefs.remove(posKey)
                prefs.remove(timeKey)
            }
            val times = prefs.asMap().entries
                .filter { it.key.name.startsWith("time_") }
                .sortedBy { it.value as? Long ?: 0L }
            if (times.size > MAX_ENTRIES) {
                times.take(times.size - MAX_ENTRIES).forEach { e ->
                    val id = e.key.name.removePrefix("time_")
                    prefs.remove(longPreferencesKey("pos_$id"))
                    prefs.remove(longPreferencesKey("time_$id"))
                }
            }
        }
    }

    companion object {
        const val START_SKIP_MS = 5_000L
        const val END_SKIP_MS = 10_000L
        const val MAX_ENTRIES = 100
    }
}
