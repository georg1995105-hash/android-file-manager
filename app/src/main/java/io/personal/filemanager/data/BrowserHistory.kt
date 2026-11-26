package io.personal.filemanager.data

import android.content.Context
import org.json.JSONObject
import java.io.File

data class ScrollPosition(val index: Int = 0, val offset: Int = 0)

/** Bounded local UI history; never restore a folder outside its selected root. */
class BrowserHistory(context: Context) {
    private val prefs = context.getSharedPreferences("pfm_browser_history", Context.MODE_PRIVATE)
    fun restorePath(root: String): String = safeRestoredPath(root, read("paths", root))
    fun savePath(root: String, path: String) = write("paths", root, path)
    fun readScroll(key: String): ScrollPosition {
        val parts = read("scroll", key)?.split(':').orEmpty()
        return ScrollPosition(parts.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100000) ?: 0)
    }
    fun saveScroll(key: String, index: Int, offset: Int) = write("scroll", key, "$index:$offset")
    private fun read(group: String, key: String): String? = synchronized(lock) {
        runCatching { JSONObject(prefs.getString(group, "{}") ?: "{}").optString(key).takeIf { it.isNotEmpty() } }.getOrNull()
    }
    private fun write(group: String, key: String, value: String) = synchronized(lock) {
        val entries = runCatching { JSONObject(prefs.getString(group, "{}") ?: "{}") }.getOrDefault(JSONObject())
        if (entries.optString(key) == value) return@synchronized
        entries.remove(key)
        entries.put(key, value)
        while (entries.length() > 64) entries.keys().next().let { entries.remove(it) }
        prefs.edit().putString(group, entries.toString()).apply()
    }
    companion object {
        private val lock = Any()
        internal fun safeRestoredPath(root: String, saved: String?): String {
            if (saved == null) return root
            return runCatching {
                val base = File(root).canonicalFile.toPath()
                val target = File(saved).canonicalFile
                if (target.isDirectory && target.toPath().startsWith(base) &&
                    !FileSystemRepository.isRestrictedByAndroid(target)) target.absolutePath else root
            }.getOrDefault(root)
        }
    }
}
