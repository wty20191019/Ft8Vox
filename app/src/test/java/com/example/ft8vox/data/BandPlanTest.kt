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

    @Test
    fun everyBandHasMultipleFrequencies() {
        for (b in BandPlan.bands) {
            assertTrue("${b.name} 应至少有两个刻度频率", b.freqs.size >= 2)
            assertEquals("${b.name} 首项应为默认频率", b.dialHz, b.freqs.first().hz)
            for (f in b.freqs) {
                assertTrue("${b.name} 的 ${f.hz} 应落在波段内", b.containsHz(f.hz))
            }
        }
    }

    @Test
    fun resolvesPreferredFrequencyWithinBand() {
        // 波段内合法频率采用之
        assertEquals(14_080_000L, BandPlan.resolveDialHz("20m", 14_080_000L))
        // 不在波段内则回落波段默认
        assertEquals(14_074_000L, BandPlan.resolveDialHz("20m", 7_074_000L))
        // 频率 0 也回落默认
        assertEquals(14_074_000L, BandPlan.resolveDialHz("20m", 0L))
        // 自定义波段（未知）保留自定义频率
        assertEquals(13_500_000L, BandPlan.resolveDialHz("试验", 13_500_000L))
    }

    @Test
    fun parsesFrequencyText() {
        assertEquals(14_074_000L, BandPlan.parseFreqMhz("14.074"))
        assertEquals(7_047_500L, BandPlan.parseFreqMhz("7,0475"))
        assertNull(BandPlan.parseFreqMhz(""))
        assertNull(BandPlan.parseFreqMhz("abc"))
        assertNull(BandPlan.parseFreqMhz("-1"))
        assertNull(BandPlan.parseFreqMhz("2000"))
    }

    @Test
    fun formatsDialFrequencyWithFourDecimals() {
        assertEquals("14.0740", BandPlan.byName("20m")!!.freqs.first().mhz)
        assertEquals("7.0475", BandPlan.byName("40m")!!.freqs[1].mhz)
    }
}
