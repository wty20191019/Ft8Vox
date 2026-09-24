package com.example.ft8vox.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 波段表单测。 */
class BandPlanTest {

    @Test
    fun looksUpByName() {
        assertEquals(14_074_000L, BandPlan.dialHz("20m"))
        assertEquals("20m", BandPlan.byName("20m")!!.name)
        assertNull(BandPlan.byName("11m"))
        assertEquals(0L, BandPlan.dialHz("bogus"))
    }

    @Test
    fun infersBandFromFrequency() {
        assertEquals("20m", BandPlan.fromFreqHz(14_074_000L)!!.name)
        assertEquals("40m", BandPlan.fromFreqHz(7_100_000L)!!.name)
        assertNull(BandPlan.fromFreqHz(100L))
        assertNull(BandPlan.fromFreqHz(100_000_000L))
    }

    @Test
    fun infersBandFromAdifFreq() {
        assertEquals("20m", BandPlan.fromFreqMhz("14.074")!!.name)
        assertEquals("20m", BandPlan.fromFreqMhz("14,074")!!.name)
        assertNull(BandPlan.fromFreqMhz("nope"))
    }

    @Test
    fun formatsAdifFrequency() {
        assertEquals("14.07400", BandPlan.byName("20m")!!.freqMhz)
    }

    @Test
    fun containsChecks() {
        assertTrue(BandPlan.contains("15m"))
        assertFalse(BandPlan.contains("bogus"))
        assertFalse(BandPlan.contains(null))
    }
}
