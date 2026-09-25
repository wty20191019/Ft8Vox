package com.example.ft8vox.data.log

import com.example.ft8vox.data.QsoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ADIF 导入时的记录合并单测。
 *
 * 重点是 LoTW / eQSL 确认报告：同一通联再次导入时必须点亮 `lotwRcvd`/`qslRcvd`，
 * 而不是被当成重复记录丢弃。
 */
class QsoMergeTest {

    private val utcMs = QsoTime.parseUtc("20260924", "120315")!!

    private fun local() = QsoEntity(
        id = 7,
        theirCall = "JA1ABC",
        theirGrid = "PM95",
        myCall = "F4FSY",
        myGrid = "JN25",
        utcMs = utcMs,
        band = "20m",
        freqHz = 14_074_000L,
        mode = "FT8",
        reportSent = -8,
        reportReceived = -5,
    )

    private fun lotwReport() = local().copy(
        id = 0,
        reportSent = null,
        reportReceived = null,
        myCall = "",
        myGrid = null,
        qslRcvd = "Y",
        lotwRcvd = "Y",
    )

    @Test
    fun lotwConfirmationLightsUpExistingRecord() {
        val merged = QsoMerge.merge(local(), lotwReport())
        assertEquals("Y", merged.lotwRcvd)
        assertEquals("Y", merged.qslRcvd)
        // 判重字段与主键不动，已有信息不被清空
        assertEquals(7L, merged.id)
        assertEquals("JA1ABC", merged.theirCall)
        assertEquals(utcMs, merged.utcMs)
        assertEquals("F4FSY", merged.myCall)
        assertEquals("JN25", merged.myGrid)
        assertEquals(-8, merged.reportSent ?: 0)
        assertEquals(-5, merged.reportReceived ?: 0)
    }

    @Test
    fun confirmedStateIsSticky() {
        val confirmed = local().copy(lotwRcvd = "Y")
        // 后来的「N」不能撤销已确认
        assertEquals("Y", QsoMerge.merge(confirmed, local().copy(lotwRcvd = "N")).lotwRcvd)
        // 「N」可以被后来的「Y」覆盖
        val denied = local().copy(lotwRcvd = "N")
        assertEquals("Y", QsoMerge.merge(denied, local().copy(lotwRcvd = "Y")).lotwRcvd)
    }

    @Test
    fun fillsMissingFieldsOnly() {
        val sparse = local().copy(
            theirGrid = null,
            myCall = "",
            myGrid = null,
            freqHz = 0,
            reportSent = null,
            comment = null,
        )
        val incoming = local().copy(
            theirGrid = "PM96",
            myCall = "F4FSY",
            myGrid = "JN25",
            freqHz = 14_074_000L,
            reportSent = -12,
            comment = "来自 LoTW 报告",
        )
        val merged = QsoMerge.merge(sparse, incoming)
        assertEquals("PM96", merged.theirGrid)
        assertEquals("F4FSY", merged.myCall)
        assertEquals("JN25", merged.myGrid)
        assertEquals(14_074_000L, merged.freqHz)
        assertEquals(-12, merged.reportSent ?: 0)
        assertEquals("来自 LoTW 报告", merged.comment)

        // 已有非空值不被导入内容覆盖（不冲掉用户手改的备注/网格）
        val rich = local().copy(theirGrid = "PM95", comment = "手写备注")
        val overwritten = QsoMerge.merge(rich, incoming)
        assertEquals("PM95", overwritten.theirGrid)
        assertEquals("手写备注", overwritten.comment)
    }

    @Test
    fun identicalRecordIsUnchangedSoItCountsAsDuplicate() {
        // 完全相同的两份数据合并后必须等于原记录，导入才会计入「跳过」
        assertEquals(local(), QsoMerge.merge(local(), local()))
    }

    @Test
    fun keepsExistingConfirmationWhenIncomingHasNone() {
        val confirmed = local().copy(lotwRcvd = "Y")
        val merged = QsoMerge.merge(confirmed, local())
        assertEquals("Y", merged.lotwRcvd)
        assertNull(QsoMerge.merge(local(), local()).lotwRcvd)
    }
}
