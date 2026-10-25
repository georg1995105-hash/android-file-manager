package io.personal.filemanager.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class FormatTest {
    @Test
    fun sizesAreRussianDecimal() {
        assertEquals("0 Б", formatSize(0))
        assertEquals("999 Б", formatSize(999))
        assertEquals("1 КБ", formatSize(1_000))
        assertEquals("3,4 МБ", formatSize(3_400_000))
        assertEquals("51,2 МБ", formatSize(51_200_000))
        assertEquals("15 МБ", formatSize(15_000_000))
        assertEquals("340 МБ", formatSize(340_000_000))
        assertEquals("1,2 ГБ", formatSize(1_200_000_000))
        assertEquals("512 ГБ", formatSize(512_000_000_000))
        assertEquals("1 ТБ", formatSize(999_999_999_999))
        assertEquals("—", formatSize(-1))
    }

    @Test
    fun tileSubtitle() {
        assertEquals("1,2 ГБ (282)", formatSizeAndCount(1_200_000_000, 282))
    }

    @Test
    fun shortDateSameAndOtherYear() {
        val now = Instant.parse("2026-10-25T12:00:00Z")
        val sameYear = Instant.parse("2026-03-14T18:29:00Z").toEpochMilli()
        val otherYear = Instant.parse("2024-03-14T18:29:00Z").toEpochMilli()
        assertEquals("14 мар., 18:29", formatShortDate(sameYear, ZoneOffset.UTC, now))
        assertEquals("14 мар. 2024, 18:29", formatShortDate(otherYear, ZoneOffset.UTC, now))
        assertEquals("—", formatShortDate(0, ZoneOffset.UTC, now))
    }

    @Test
    fun russianPlural() {
        assertEquals("1 файл", pluralRu(1, "файл", "файла", "файлов"))
        assertEquals("3 файла", pluralRu(3, "файл", "файла", "файлов"))
        assertEquals("5 файлов", pluralRu(5, "файл", "файла", "файлов"))
        assertEquals("11 файлов", pluralRu(11, "файл", "файла", "файлов"))
        assertEquals("21 файл", pluralRu(21, "файл", "файла", "файлов"))
        assertEquals("112 файлов", pluralRu(112, "файл", "файла", "файлов"))
    }

    @Test
    fun percent() {
        assertEquals(15, usedPercent(76_400_000_000, 512_000_000_000)) // 14,9 % → 15
        assertEquals(0, usedPercent(5, 0))
    }

    @Test
    fun gridStampShowsDayMonthAndExactTime() {
        val zone = java.time.ZoneId.of("Europe/Moscow")
        val ms = java.time.ZonedDateTime.of(2026, 10, 25, 16, 15, 42, 0, zone).toInstant().toEpochMilli()
        val stamp = gridStamp(ms, zone)!!
        assertEquals("25.10", stamp.day)
        assertEquals("16:15", stamp.time)
        assertEquals("25.10 · 16:15", stamp.oneLine)
        assertEquals("05.01", gridStamp(java.time.ZonedDateTime.of(2026, 1, 5, 9, 3, 0, 0, zone).toInstant().toEpochMilli(), zone)!!.day)
        assertEquals(null, gridStamp(0L, zone))
        assertEquals(null, gridStamp(null, zone))
    }
}
