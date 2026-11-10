package io.personal.filemanager.ui.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Подсветка меняет только цвет символов, но не текст и не позиции курсора/выделения.
 * Большие документы подсвечиваются без лишних затрат процессора.
 */
class SyntaxHighlight(private val extension: String) : VisualTransformation {
    private val key = Color(0xFF72D6C5)
    private val string = Color(0xFFF0BD88)
    private val number = Color(0xFF9FB9FF)
    private val keyword = Color(0xFFC1A9EA)
    private val comment = Color(0xFF8C96A4)

    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        if (source.length > 40_000) return TransformedText(text, OffsetMapping.Identity)
        val builder = AnnotatedString.Builder(text)
        when (extension.lowercase()) {
            "json" -> colorJson(source, builder)
            "md", "markdown" -> {
                Regex("(?m)^#{1,6}[^\\n]*").findAll(source).forEach { span(builder, it.range.first, it.range.last + 1, key) }
                Regex("`[^`\\n]+`").findAll(source).forEach { span(builder, it.range.first, it.range.last + 1, string) }
                Regex("(?m)^\\s*[-*+] (?=\\S)").findAll(source).forEach { span(builder, it.range.first, it.range.last + 1, number) }
            }
            "yaml", "yml", "conf", "ini", "cfg", "toml", "properties" -> {
                Regex("(?m)^[ \\t]*[A-Za-zА-Яа-я0-9_.-]+(?=[ \\t]*[:=])").findAll(source)
                    .forEach { span(builder, it.range.first, it.range.last + 1, key) }
                Regex("(?m)#[^\\n]*").findAll(source)
                    .forEach { span(builder, it.range.first, it.range.last + 1, comment) }
            }
            "xml", "html", "htm" -> {
                Regex("<[^>]*>").findAll(source).forEach { span(builder, it.range.first, it.range.last + 1, key) }
                Regex("[\\w:-]+(?==\\\")").findAll(source).forEach { span(builder, it.range.first, it.range.last + 1, number) }
            }
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    private fun colorJson(s: String, out: AnnotatedString.Builder) {
        var i = 0
        while (i < s.length) {
            val start = i
            when {
                s[i] == '"' -> {
                    i++
                    while (i < s.length) {
                        if (s[i] == '\\' && i + 1 < s.length) { i += 2; continue }
                        if (s[i++] == '"') break
                    }
                    var next = i
                    while (next < s.length && s[next].isWhitespace()) next++
                    span(out, start, i, if (next < s.length && s[next] == ':') key else string)
                }
                s[i] == '-' || s[i].isDigit() -> {
                    i++
                    while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
                    span(out, start, i, number)
                }
                else -> {
                    val literal = listOf("true", "false", "null").firstOrNull {
                        s.regionMatches(i, it, 0, it.length) &&
                            (i + it.length == s.length || !s[i + it.length].isLetterOrDigit())
                    }
                    if (literal != null) {
                        i += literal.length
                        span(out, start, i, keyword)
                    } else i++
                }
            }
        }
    }

    private fun span(builder: AnnotatedString.Builder, start: Int, end: Int, color: Color) {
        if (start < end) builder.addStyle(SpanStyle(color = color), start, end)
    }
}

object JsonPrettyPrinter {
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun format(source: String): kotlin.Result<String> = runCatching {
        val parsed = Json.parseToJsonElement(source)
        pretty.encodeToString(JsonElement.serializer(), parsed)
    }
}
