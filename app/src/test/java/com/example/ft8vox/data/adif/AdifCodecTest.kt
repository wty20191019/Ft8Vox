package com.example.ft8vox.data.adif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADIF 编解码单测（含 UTF-8 字节长度、容错解析、未知字段保留）。 */
class AdifCodecTest {

    private fun sampleRecord() = adifRecord(
        "CALL" to "JA1ABC",
        "QSO_DATE" to "20260924",
        "TIME_ON" to "120315",
        "BAND" to "20m",
        "FREQ" to "14.07400",
        "MODE" to "FT8",
        "RST_SENT" to "-08",
        "RST_RCVD" to "-05",
        "GRIDSQUARE" to "PM95",
        "MY_CALL" to "F4FSY",
        "MY_GRIDSQUARE" to "JN25",
    )

    @Test
    fun encodesWithByteLength() {
        val text = AdifCodec.encode(listOf(adifRecord("CALL" to "JA1ABC")))
        assertTrue(text.contains("<CALL:6>JA1ABC"))
        assertTrue(text.contains("<EOH>"))
        assertTrue(text.contains("<EOR>"))
    }

    @Test
    fun roundTripsKnownFields() {
        val back = AdifCodec.decode(AdifCodec.encode(listOf(sampleRecord())))
        assertEquals(1, back.size)
        val r = back[0]
        assertEquals("JA1ABC", r.call)
        assertEquals("20260924", r.qsoDate)
        assertEquals("120315", r.timeOn)
        assertEquals("20m", r.band)
        assertEquals("14.07400", r.freq)
        assertEquals("FT8", r.mode)
        assertEquals("-08", r.rstSent)
        assertEquals("-05", r.rstRcvd)
        assertEquals("PM95", r.gridSquare)
        assertEquals("F4FSY", r.myCall)
        assertEquals("JN25", r.myGridSquare)
    }

    @Test
    fun roundTripsMultipleRecords() {
        val text = AdifCodec.encode(
            listOf(
                sampleRecord(),
                adifRecord(
                    "CALL" to "W1AW",
                    "QSO_DATE" to "20260101",
                    "TIME_ON" to "010203",
                    "MODE" to "FT4",
                ),
            ),
        )
        val back = AdifCodec.decode(text)
        assertEquals(2, back.size)
        assertEquals("JA1ABC", back[0].call)
        assertEquals("W1AW", back[1].call)
    }

    @Test
    fun roundTripsNonAsciiUsingByteLength() {
        val record = adifRecord("CALL" to "JA1ABC", "COMMENT" to "测试中文")
        val back = AdifCodec.decode(AdifCodec.encode(listOf(record)))
        assertEquals("测试中文", back[0].comment)
    }

    @Test
    fun preservesUnknownTags() {
        val record = adifRecord("CALL" to "JA1ABC", "MY_SIG" to "HOME", "TX_PWR" to "5")
        val back = AdifCodec.decode(AdifCodec.encode(listOf(record)))
        assertEquals("HOME", back[0].get("MY_SIG"))
        assertEquals("5", back[0].get("TX_PWR"))
    }

    @Test
    fun skipsHeaderTagsWhenNoEoh() {
        val text = "<ADIF_VER:5>3.1.4<PROGRAMID:6>Ft8Vox<CALL:6>JA1ABC<QSO_DATE:8>20260924<EOR>"
        val back = AdifCodec.decode(text)
        assertEquals(1, back.size)
        assertEquals("JA1ABC", back[0].call)
    }

    @Test
    fun handlesTagWithoutLength() {
        val text = "FT8 log\n<CALL>JA1ABC<QSO_DATE:8>20260924<TIME_ON:6>120315<MODE:3>FT8<EOR>"
        val back = AdifCodec.decode(text)
        assertEquals(1, back.size)
        assertEquals("JA1ABC", back[0].call)
        assertEquals("20260924", back[0].qsoDate)
        assertEquals("FT8", back[0].mode)
    }

    @Test
    fun handlesLowercaseTagsAndBom() {
        val text = "\uFEFF<call:6>ja1abc<qso_date:8>20260924<mode:3>ft8<eor>"
        val back = AdifCodec.decode(text)
        assertEquals(1, back.size)
        assertEquals("ja1abc", back[0].call)
        assertEquals("ft8", back[0].mode)
    }

    @Test
    fun ignoresTrailingGarbageWithoutEor() {
        val back = AdifCodec.decode("<CALL:6>JA1ABC")
        assertEquals(1, back.size)
        assertEquals("JA1ABC", back[0].call)
    }

    @Test
    fun emptyInputYieldsNoRecords() {
        assertTrue(AdifCodec.decode("").isEmpty())
        assertTrue(AdifCodec.decode("ADIF export from Ft8Vox<EOH>").isEmpty())
    }
}
