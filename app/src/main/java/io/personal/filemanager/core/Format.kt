package io.personal.filemanager.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

private val RU: Locale = Locale.forLanguageTag("ru")

/**
 * Размер в десятичных единицах (как в настройках Android): «3,4 МБ», «340 МБ», «1,2 ГБ».
 * До 100 — один знак после запятой (без «,0»), от 100 — целое.
 */
fun formatSize(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1000) return "$bytes Б"
    val units = arrayOf("КБ", "МБ", "ГБ", "ТБ", "ПБ")
    var value = bytes / 1000.0
    var unit = 0
    while (value >= 999.95 && unit < units.lastIndex) {
        value /= 1000.0
        unit++
    }
    val text = if (value >= 99.95) {
        value.roundToLong().toString()
    } else {
        val tenths = (value * 10).roundToLong()
        if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10},${tenths % 10}"
    }
    return "$text ${units[unit]}"
}

/** Подпись плитки: «1,2 ГБ (282)». */
fun formatSizeAndCount(bytes: Long, count: Int): String = "${formatSize(bytes)} ($count)"

private val sameYearFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", RU)
private val otherYearFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", RU)
private val fullFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm:ss", RU)

/** Короткая дата для списков: «14 мар., 18:29»; другой год — «14 мар. 2024, 18:29». */
fun formatShortDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String {
    if (epochMillis <= 0) return "—"
    val date = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
    val current = ZonedDateTime.ofInstant(now, zone)
    val formatter = if (date.year == current.year) sameYearFormatter else otherYearFormatter
    return date.format(formatter)
}

private val dayMonthFormatter = DateTimeFormatter.ofPattern("dd.MM", RU)
private val hourMinuteFormatter = DateTimeFormatter.ofPattern("HH:mm", RU)

/** Дата и время под миниатюрой в сетке: «25.10» и «16:15» (вместе — «25.10 · 16:15»). */
data class GridStamp(val day: String, val time: String) {
    val oneLine: String get() = "$day · $time"
}

/** null — время неизвестно (0 или отрицательное): ничего не подставляется. */
fun gridStamp(epochMillis: Long?, zone: ZoneId = ZoneId.systemDefault()): GridStamp? {
    if (epochMillis == null || epochMillis <= 0) return null
    val date = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
    return GridStamp(date.format(dayMonthFormatter), date.format(hourMinuteFormatter))
}

/** Полная дата для «Сведений». */
fun formatFullDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (epochMillis <= 0) return "неизвестно"
    return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone).format(fullFormatter)
}

/** Русское склонение: plural(5, "файл", "файла", "файлов") → «5 файлов». */
fun pluralRu(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    val word = when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
    return "$n $word"
}

/** Процент занятого места, 0..100. */
fun usedPercent(used: Long, total: Long): Int {
    if (total <= 0) return 0
    return ((used.toDouble() / total) * 100).roundToLong().toInt().coerceIn(0, 100)
}
