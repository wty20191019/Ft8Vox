package com.example.ft8vox.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Maidenhead 网格编解码单测。 */
class MaidenheadTest {

    private val eps = 1e-9

    @Test
    fun parsesSquare() {
        val b = Maidenhead.parse("JN25")!!
        assertEquals(45.0, b.minLat, eps)
        assertEquals(46.0, b.maxLat, eps)
        assertEquals(4.0, b.minLon, eps)
        assertEquals(6.0, b.maxLon, eps)
        assertEquals(45.5, b.centerLat, eps)
        assertEquals(5.0, b.centerLon, eps)
    }

    @Test
    fun parsesFieldOnly() {
        val b = Maidenhead.parse("JN")!!
        assertEquals(40.0, b.minLat, eps)
        assertEquals(50.0, b.maxLat, eps)
        assertEquals(0.0, b.minLon, eps)
        assertEquals(20.0, b.maxLon, eps)
    }

    @Test
    fun parsesSubsquare() {
        val b = Maidenhead.parse("JN25aa")!!
        assertEquals(45.0, b.minLat, eps)
        assertEquals(45.0 + 1.0 / 24.0, b.maxLat, eps)
        assertEquals(4.0, b.minLon, eps)
        assertEquals(4.0 + 2.0 / 24.0, b.maxLon, eps)
    }

    @Test
    fun encodeKnownLocators() {
        assertEquals("JN", Maidenhead.encode(45.5, 5.0, 2))
        assertEquals("JN25", Maidenhead.encode(45.5, 5.0, 4))
        assertEquals("FN42", Maidenhead.encode(42.5, -71.0, 4))
    }

    @Test
    fun encodeThenParseContainsPoint() {
        val grid = Maidenhead.encode(37.7749, -122.4194, 6)
        val b = Maidenhead.parse(grid)!!
        assertTrue(37.7749 >= b.minLat && 37.7749 < b.maxLat)
        assertTrue(-122.4194 >= b.minLon && -122.4194 < b.maxLon)
    }

    @Test
    fun encodeClampsOddPrecision() {
        assertEquals(4, Maidenhead.encode(45.5, 5.0, 5).length)
    }

    @Test
    fun extractsField() {
        assertEquals("JN", Maidenhead.field("jn25"))
        assertEquals("JN", Maidenhead.field("JN"))
        assertNull(Maidenhead.field("ZZ99"))
    }

    @Test
    fun rejectsInvalid() {
        assertFalse(Maidenhead.isValid(""))
        assertFalse(Maidenhead.isValid("J"))
        assertFalse(Maidenhead.isValid("JN2"))
        assertFalse(Maidenhead.isValid("JN2X"))
        assertFalse(Maidenhead.isValid("ZZ99"))
        assertFalse(Maidenhead.isValid("JN25AZ9"))
        assertTrue(Maidenhead.isValid("jn25"))
        assertTrue(Maidenhead.isValid("JN25aa"))
    }

    @Test
    fun normalizeUppercasesAndTrims() {
        assertEquals("JN25", Maidenhead.normalize(" jn25 "))
        assertEquals("", Maidenhead.normalize(null))
    }
}
