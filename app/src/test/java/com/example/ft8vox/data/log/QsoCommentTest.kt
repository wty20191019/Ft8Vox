package com.example.ft8vox.data.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 新通联自动备注（ADIF COMMENT）单测。 */
class QsoCommentTest {

    @Test
    fun writesDistanceAndProgramWhenGridsKnown() {
        val c = QsoComment.auto(stationNote = null, myGrid = "JN25", theirGrid = "PM95")
        // 形状与 FT8CN 一致：Distance: 1738 km, QSO by Ft8Vox
        assertTrue(c, Regex("""^Distance: \d+ km, QSO by Ft8Vox$""").matches(c))
        // JN25（都灵附近）↔ PM95（东京附近）约 9900 km
        val km = c.removePrefix("Distance: ").substringBefore(" km").toInt()
        assertTrue("实际 $km km", km in 9800..10000)
    }

    @Test
    fun sameGridGivesZeroKm() {
        assertEquals("Distance: 0 km, QSO by Ft8Vox", QsoComment.auto(null, "PM95", "PM95"))
    }

    @Test
    fun dropsDistanceWhenEitherGridMissing() {
        assertEquals("QSO by Ft8Vox", QsoComment.auto(null, "JN25", null))
        assertEquals("QSO by Ft8Vox", QsoComment.auto(null, null, "PM95"))
        assertEquals("QSO by Ft8Vox", QsoComment.auto(null, "", ""))
        // 非法网格（Maidenhead 解析失败）同样只留程序标识
        assertEquals("QSO by Ft8Vox", QsoComment.auto(null, "JN25", "ZZ99"))
    }

    @Test
    fun stationNoteIsPrepended() {
        assertEquals(
            "QRP 5W, Distance: 0 km, QSO by Ft8Vox",
            QsoComment.auto("QRP 5W", "PM95", "PM95"),
        )
        // 只有空白的备注视为未填
        assertEquals(
            "Distance: 0 km, QSO by Ft8Vox",
            QsoComment.auto("   ", "PM95", "PM95"),
        )
        assertEquals("QRP 5W, QSO by Ft8Vox", QsoComment.auto(" QRP 5W ", null, null))
    }
}
