package com.example.ft8vox.data.adif

import com.example.ft8vox.data.QsoTime
import com.example.ft8vox.data.log.QsoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ADIF 记录 ↔ 本地通联实体映射单测。 */
class AdifMapperTest {

    @Test
    fun recordToEntity() {
        val record = adifRecord(
            "CALL" to "ja1abc",
            "QSO_DATE" to "20260924",
            "TIME_ON" to "120315",
            "BAND" to "20m",
            "MODE" to "FT8",
            "RST_SENT" to "-08",
            "RST_RCVD" to "-05",
            "GRIDSQUARE" to "pm95",
            "MY_CALL" to "f4fsy",
            "MY_GRIDSQUARE" to "jn25",
        )
        val e = AdifMapper.toEntity(record, fallbackMyCall = "", fallbackMyGrid = null)!!
        assertEquals("JA1ABC", e.theirCall)
        assertEquals("PM95", e.theirGrid)
        assertEquals("F4FSY", e.myCall)
        assertEquals("JN25", e.myGrid)
        assertEquals("20m", e.band)
        assertEquals("FT8", e.mode)
        assertEquals(-8, e.reportSent ?: 0)
        assertEquals(-5, e.reportReceived ?: 0)
        assertEquals(QsoTime.parseUtc("20260924", "120315"), e.utcMs)
    }

    @Test
    fun infersBandFromFreqWhenBandMissing() {
        val record = adifRecord(
            "CALL" to "JA1ABC",
            "QSO_DATE" to "20260924",
            "TIME_ON" to "120315",
            "FREQ" to "14.074",
        )
        val e = AdifMapper.toEntity(record, "", null)!!
        assertEquals("20m", e.band)
        assertEquals(14_074_000L, e.freqHz)
    }

    @Test
    fun usesFallbackStationInfo() {
        val record = adifRecord("CALL" to "JA1ABC", "QSO_DATE" to "20260924", "TIME_ON" to "120315")
        val e = AdifMapper.toEntity(record, "F4FSY", "JN25")!!
        assertEquals("F4FSY", e.myCall)
        assertEquals("JN25", e.myGrid)
    }

    @Test
    fun mapsMfskSubModeToFt4() {
        val record = adifRecord(
            "CALL" to "JA1ABC",
            "QSO_DATE" to "20260924",
            "TIME_ON" to "120315",
            "MODE" to "MFSK",
            "SUBMODE" to "FT4",
        )
        assertEquals("FT4", AdifMapper.toEntity(record, "", null)!!.mode)
    }

    @Test
    fun rejectsRecordsWithoutCallOrDate() {
        assertNull(AdifMapper.toEntity(adifRecord("QSO_DATE" to "20260924"), "", null))
        assertNull(AdifMapper.toEntity(adifRecord("CALL" to "JA1ABC"), "", null))
        assertNull(AdifMapper.toEntity(adifRecord("CALL" to "JA1ABC", "QSO_DATE" to "bad"), "", null))
    }

    @Test
    fun ignoresRstScaledValues() {
        val record = adifRecord(
            "CALL" to "JA1ABC",
            "QSO_DATE" to "20260924",
            "TIME_ON" to "120315",
            "RST_SENT" to "599",
        )
        assertNull(AdifMapper.toEntity(record, "", null)!!.reportSent)
    }

    @Test
    fun entityToRecordRoundTrip() {
        val entity = QsoEntity(
            id = 7,
            theirCall = "JA1ABC",
            theirGrid = "PM95",
            myCall = "F4FSY",
            myGrid = "JN25",
            utcMs = QsoTime.parseUtc("20260924", "120315")!!,
            band = "20m",
            freqHz = 14_074_000L,
            mode = "FT8",
            reportSent = -8,
            reportReceived = -5,
        )
        val back = AdifMapper.toEntity(AdifMapper.toRecord(entity), "", null)!!
        assertEquals(entity.theirCall, back.theirCall)
        assertEquals(entity.theirGrid, back.theirGrid)
        assertEquals(entity.myCall, back.myCall)
        assertEquals(entity.band, back.band)
        assertEquals(entity.mode, back.mode)
        assertEquals(entity.utcMs, back.utcMs)
        assertEquals(entity.reportSent, back.reportSent)
        assertEquals(entity.reportReceived, back.reportReceived)
        assertEquals(entity.freqHz, back.freqHz)
    }
}
