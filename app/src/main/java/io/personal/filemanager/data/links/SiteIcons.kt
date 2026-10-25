package io.personal.filemanager.data.links

import java.net.URI

/** Разбор адресов и HTML для значков сайтов (без Android — проверяется модульными тестами). */
object SiteIcons {
    /** Хост ссылки в нижнем регистре; null — не http(s) или некорректный адрес. */
    fun hostOf(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        return host.takeIf { it.isNotEmpty() && !it.contains(' ') }
    }

    private val linkTag = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(tag)?.let { m ->
            m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] }
        }

    /**
     * Кандидаты значков из HTML страницы: apple-touch-icon (крупный) раньше обычного icon, крупные раньше
     * мелких. SVG и data: пропускаются — их не декодирует BitmapFactory. Адреса — абсолютные https.
     */
    fun iconCandidates(html: String, baseUrl: String): List<String> {
        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return emptyList()
        data class Candidate(val url: String, val score: Int)
        val found = linkTag.findAll(html.take(256 * 1024)).mapNotNull { m ->
            val tag = m.value
            val rel = attr(tag, "rel")?.lowercase() ?: return@mapNotNull null
            if (!rel.split(' ').any { it == "icon" || it == "apple-touch-icon" || it == "apple-touch-icon-precomposed" }) return@mapNotNull null
            val href = attr(tag, "href")?.trim()?.replace("&amp;", "&") ?: return@mapNotNull null
            if (href.isEmpty() || href.startsWith("data:", true)) return@mapNotNull null
            val type = attr(tag, "type")?.lowercase()
            if (type == "image/svg+xml" || href.substringBefore('?').lowercase().endsWith(".svg")) return@mapNotNull null
            val abs = runCatching { base.resolve(href) }.getOrNull() ?: return@mapNotNull null
            if (abs.scheme?.lowercase() != "https") return@mapNotNull null
            val size = attr(tag, "sizes")?.lowercase()?.substringBefore('x')?.toIntOrNull() ?: 0
            val score = (if (rel.contains("apple-touch-icon")) 1000 else 0) + size.coerceAtMost(512)
            Candidate(abs.toString(), score)
        }.toList()
        return found.sortedByDescending { it.score }.map { it.url }.distinct()
    }
}
