package com.example.ft8vox.data

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/** UTC 日期/时间与毫秒时间戳的互转（纯 Kotlin，便于 JVM 单测）。 */
object QsoTime {

    fun nowUtcMs(): Long = System.currentTimeMillis()

    /**
     * 解析日期与时间。兼容 ADIF 风格（`YYYYMMDD` / `HHMMSS`）与界面风格（`YYYY-MM-DD` / `HH:MM:SS`）。
     * 时间缺省按 00:00:00。任何非法输入返回 null。
     */
    fun parseUtc(date: String?, time: String?): Long? {
        val d = date?.trim()?.replace("-", "")?.replace("/", "") ?: return null
        if (d.length < 8) return null
        val year = d.substring(0, 4).toIntOrNull() ?: return null
        val month = d.substring(4, 6).toIntOrNull() ?: return null
        val day = d.substring(6, 8).toIntOrNull() ?: return null

        val rawTime = time?.trim()?.replace(":", "") ?: ""
        val t = rawTime.padEnd(6, '0')
        val hour = t.substring(0, 2).toIntOrNull() ?: 0
        val minute = t.substring(2, 4).toIntOrNull() ?: 0
        val second = t.substring(4, 6).toIntOrNull() ?: 0

        return try {
            LocalDateTime.of(year, month, day, hour, minute, second)
                .toEpochSecond(ZoneOffset.UTC) * 1000L
        } catch (e: DateTimeException) {
            null
        }
    }

    /** `YYYYMMDD`（ADIF QSO_DATE）。 */
    fun date(utcMs: Long): String {
        val z = Instant.ofEpochMilli(utcMs).atZone(ZoneOffset.UTC)
        return String.format(Locale.US, "%04d%02d%02d", z.year, z.monthValue, z.dayOfMonth)
    }

    /** `HHMMSS`（ADIF TIME_ON）。 */
    fun time(utcMs: Long): String {
        val z = Instant.ofEpochMilli(utcMs).atZone(ZoneOffset.UTC)
        return String.format(Locale.US, "%02d%02d%02d", z.hour, z.minute, z.second)
    }

    /** `YYYY-MM-DD`（界面显示）。 */
    fun isoDate(utcMs: Long): String {
        val z = Instant.ofEpochMilli(utcMs).atZone(ZoneOffset.UTC)
        return String.format(Locale.US, "%04d-%02d-%02d", z.year, z.monthValue, z.dayOfMonth)
    }

    /** `HH:MM:SS`（界面显示）。 */
    fun isoTime(utcMs: Long): String {
        val z = Instant.ofEpochMilli(utcMs).atZone(ZoneOffset.UTC)
        return String.format(Locale.US, "%02d:%02d:%02d", z.hour, z.minute, z.second)
    }

    /** `YYYY-MM-DD HH:MM:SS`。 */
    fun isoDateTime(utcMs: Long): String = isoDate(utcMs) + " " + isoTime(utcMs)
}
