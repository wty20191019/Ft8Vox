package com.example.ft8vox.qso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 报文解析的 JVM 单测。 */
class MessageParserTest {

    @Test
    fun parsesPlainCq() {
        val p = MessageParser.parse("CQ F4FSY JN25")
        assertTrue(p.isCq)
        assertEquals("F4FSY", p.from)
        assertEquals("JN25", p.grid)
        assertNull(p.cqModifier)
    }

    @Test
    fun parsesCqWithModifier() {
        val p = MessageParser.parse("CQ DX F4FSY JN25")
        assertTrue(p.isCq)
        assertEquals("DX", p.cqModifier)
        assertEquals("F4FSY", p.from)
        assertEquals("JN25", p.grid)

        val t = MessageParser.parse("CQ TEST K1ABC FN42")
        assertEquals("TEST", t.cqModifier)
        assertEquals("K1ABC", t.from)
    }

    @Test
    fun parsesCqWithoutGrid() {
        val p = MessageParser.parse("CQ F4FSY")
        assertTrue(p.isCq)
        assertEquals("F4FSY", p.from)
        assertNull(p.grid)
    }

    @Test
    fun parsesGridExchange() {
        val p = MessageParser.parse("F4FSY GJ0KYZ IO90")
        assertEquals("F4FSY", p.to)
        assertEquals("GJ0KYZ", p.from)
        assertEquals("IO90", p.grid)
        assertNull(p.report)
    }

    @Test
    fun parsesReport() {
        val p = MessageParser.parse("GJ0KYZ F4FSY -12")
        assertEquals(-12, p.report)
        assertFalse(p.isRoger)

        val q = MessageParser.parse("K1ABC W9XYZ +05")
        assertEquals(5, q.report)
    }

    @Test
    fun parsesRogerReport() {
        val p = MessageParser.parse("F4FSY GJ0KYZ R-09")
        assertTrue(p.isRoger)
        assertEquals(-9, p.report)
        assertEquals("R-09", p.kind)
    }

    @Test
    fun parsesRr73And73() {
        val a = MessageParser.parse("GJ0KYZ F4FSY RR73")
        assertTrue(a.isRr73)
        val b = MessageParser.parse("F4FSY GJ0KYZ 73")
        assertTrue(b.is73)
    }

    @Test
    fun fallsBackToFreeText() {
        val p = MessageParser.parse("HI HI HI HI")
        assertTrue(p.isFreeText)
        assertNull(p.from)

        val q = MessageParser.parse("HELLO WORLD TEST")
        assertTrue(q.isFreeText)
    }

    @Test
    fun addressedToIsCaseInsensitive() {
        val p = MessageParser.parse("f4fsy gj0kyz io90")
        assertTrue(p.addressedTo("F4FSY"))
        assertFalse(p.addressedTo("K1ABC"))
    }

    @Test
    fun formatsReport() {
        assertEquals("-08", MessageParser.formatReport(-8))
        assertEquals("+05", MessageParser.formatReport(5))
        assertEquals("+00", MessageParser.formatReport(0))
        assertEquals("-24", MessageParser.formatReport(-100))
        assertEquals("+30", MessageParser.formatReport(100))
    }

    @Test
    fun callsignHeuristic() {
        assertTrue(MessageParser.looksLikeCallsign("F4FSY"))
        assertTrue(MessageParser.looksLikeCallsign("GJ0KYZ"))
        assertTrue(MessageParser.looksLikeCallsign("VK4BLE/P"))
        assertFalse(MessageParser.looksLikeCallsign("DX"))
        assertFalse(MessageParser.looksLikeCallsign("TEST"))
        assertFalse(MessageParser.looksLikeCallsign("RR73"))
        assertFalse(MessageParser.looksLikeCallsign("73"))
    }
}
