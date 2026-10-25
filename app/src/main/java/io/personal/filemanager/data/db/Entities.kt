package io.personal.filemanager.data.db

import kotlinx.serialization.Serializable

@Serializable
data class Note(
    val id: Long = 0,
    val title: String,
    val body: String,
    val created: Long,
    val updated: Long,
    val pinned: Boolean = false,
) {
    /** Заголовок для списка: явный или первая непустая строка текста. */
    val displayTitle: String
        get() = title.ifBlank { body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80) ?: "Без названия" }
}

@Serializable
data class Link(
    val id: Long = 0,
    val title: String,
    val url: String,
    val note: String = "",
    val created: Long,
    val updated: Long,
)

/** Избранное хранит путь и метаданные, а не содержимое файла. */
@Serializable
data class Favorite(
    val id: Long = 0,
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val added: Long,
)

object LinkRules {
    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    /** Приводит ввод к URL: без схемы добавляется https://. null — недопустимо. */
    fun normalize(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val withScheme = if (scheme.containsMatchIn(t)) t else "https://$t"
        val lower = withScheme.lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return null
        val host = withScheme.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if (host.isEmpty() || !host.contains('.') && host != "localhost") return null
        return withScheme
    }
}
