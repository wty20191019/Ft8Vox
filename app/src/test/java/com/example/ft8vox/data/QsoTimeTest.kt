package com.example.ft8vox.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** UTC 日期/时间转换单测。 */
class QsoTimeTest {

    @Test
    fun roundTrip() {
        val ms = QsoTime.parseUtc("20260924", "120315")!!
        assertEquals("20260924", QsoTime.date(ms))
        assertEquals("120315", QsoTime.time(ms))
        assertEquals("2026-09-24", QsoTime.isoDate(ms))
        assertEquals("12:03:15", QsoTime.isoTime(ms))
        assertEquals("2026-09-24 12:03:15", QsoTime.isoDateTime(ms))
    }

    @Test
    fun acceptsIsoStyleInput() {
        val a = QsoTime.parseUtc("20260924", "120315")!!
        val b = QsoTime.parseUtc("2026-09-24", "12:03:15")!!
        assertEquals(a, b)
    }

    @Test
    fun missingTimeDefaultsToMidnight() {
        val ms = QsoTime.parseUtc("20260924", null)!!
        assertEquals("00:00:00", QsoTime.isoTime(ms))
    }

    @Test
    fun shortTimeIsPadded() {
        val ms = QsoTime.parseUtc("20260924", "1203")!!
        assertEquals("12:03:00", QsoTime.isoTime(ms))
    }

    @Test
    fun rejectsInvalid() {
        assertNull(QsoTime.parseUtc(null, "120315"))
        assertNull(QsoTime.parseUtc("", ""))
        assertNull(QsoTime.parseUtc("2026-13-45", "000000"))
        assertNull(QsoTime.parseUtc("2026-02-30", "000000"))
    }
}
