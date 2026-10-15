package com.example.ft8vox.data.log

import com.example.ft8vox.data.QsoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADIF 导入判重账本单测（[QsoImportLedger]）。
 *
 * 覆盖旧实现「逐条查两次库」的全部判重口径（精确 / ±60 s 近似），以及新增的批量语义：
 * 同一份文件内的重复、以及命中「本次还没落库的新记录」时就地替换（不能当更新写回）。
 */
class QsoImportLedgerTest {

    private val t0 = QsoTime.parseUtc("20260924", "120315")!!

    private fun qso(
        id: Long = 0,
        call: String = "JA1ABC",
        utcMs: Long = t0,
        band: String = "20m",
        mode: String = "FT8",
        grid: String? = "PM95",
    ) = QsoEntity(
        id = id,
        theirCall = call,
        theirGrid = grid,
        myCall = "F4FSY",
        myGrid = "JN25",
        utcMs = utcMs,
        band = band,
        mode = mode,
    )

    @Test
    fun emptyDatabaseInsertNewRecord() {
        val ledger = QsoImportLedger()
        ledger.offer(qso())
        assertEquals(1, ledger.inserts.size)
        assertEquals(0, ledger.updates.size)
        assertEquals(0, ledger.skipped)
    }

    @Test
    fun identicalRecordCountsAsDuplicate() {
        val ledger = QsoImportLedger(listOf(qso(id = 7)))
        ledger.offer(qso())
        assertTrue(ledger.inserts.isEmpty())
        assertTrue(ledger.updates.isEmpty())
        assertEquals(1, ledger.skipped)
    }

    @Test
    fun mergeLightsUpExistingRecordKeepingId() {
        val ledger = QsoImportLedger(listOf(qso(id = 7, grid = null)))
        ledger.offer(qso(grid = "PM96").copy(lotwRcvd = "Y", qslRcvd = "Y"))
        assertEquals(1, ledger.updates.size)
        val merged = ledger.updates.single()
        assertEquals(7L, merged.id)
        assertEquals("PM96", merged.theirGrid)
        assertEquals("Y", merged.lotwRcvd)
        assertEquals(0, ledger.skipped)
    }

    @Test
    fun nearMatchWithinOneMinuteMerges() {
        // LoTW 报告常把 TIME_ON 截到分钟：差 45 秒仍应视为同一通联
        val ledger = QsoImportLedger(listOf(qso(id = 3, grid = null, utcMs = t0)))
        ledger.offer(qso(utcMs = t0 + 45_000L, grid = "PM96"))
        assertEquals(1, ledger.updates.size)
        assertEquals(3L, ledger.updates.single().id)
        assertEquals("PM96", ledger.updates.single().theirGrid)
    }

    @Test
    fun toleranceIsInclusiveAtSixtySeconds() {
        // 边界含端点：恰好 60 秒算命中（对应 SQL 的 BETWEEN）
        val exactEdge = QsoImportLedger(listOf(qso(id = 1, utcMs = t0)))
        exactEdge.offer(qso(utcMs = t0 + 60_000L).copy(lotwRcvd = "Y"))
        assertEquals(1, exactEdge.updates.size)
        assertEquals(1L, exactEdge.updates.single().id)

        // 61 秒即视作另一通联
        val beyond = QsoImportLedger(listOf(qso(id = 1, utcMs = t0)))
        beyond.offer(qso(utcMs = t0 + 60_001L).copy(lotwRcvd = "Y"))
        assertEquals(1, beyond.inserts.size)
        assertTrue(beyond.updates.isEmpty())
    }

    @Test
    fun nearMatchPicksClosestInTime() {
        val ledger = QsoImportLedger(
            listOf(
                qso(id = 1, utcMs = t0, grid = "PM95"),
                qso(id = 2, utcMs = t0 + 50_000L, grid = "PM96"),
            ),
        )
        // 距 id=2 只差 5 秒、距 id=1 差 45 秒 → 命中 id=2（merged 保留其网格）
        ledger.offer(qso(utcMs = t0 + 45_000L, grid = null, band = "20m").copy(comment = "命中"))
        assertEquals(1, ledger.updates.size)
        assertEquals(2L, ledger.updates.single().id)
        assertEquals("PM96", ledger.updates.single().theirGrid)
    }

    @Test
    fun differentBandOrModeIsAnotherQso() {
        val ledger = QsoImportLedger(listOf(qso(id = 1)))
        ledger.offer(qso(band = "40m", grid = null))
        ledger.offer(qso(mode = "FT4", grid = null))
        assertEquals(2, ledger.inserts.size)
        assertTrue(ledger.updates.isEmpty())
    }

    @Test
    fun callComparisonIgnoresCaseAndSurroundingSpaces() {
        val ledger = QsoImportLedger(listOf(qso(id = 5, call = "ja1abc")))
        ledger.offer(qso(call = " JA1ABC ", grid = null))
        // 命中已有记录（且合并后无变化）→ 计入跳过，而不是当成新记录插入
        assertTrue(ledger.inserts.isEmpty())
        assertTrue(ledger.updates.isEmpty())
        assertEquals(1, ledger.skipped)
    }

    @Test
    fun duplicatesInsideSameFileKeepOneInsert() {
        val ledger = QsoImportLedger()
        ledger.offer(qso())
        ledger.offer(qso()) // 同一份文件里的重复
        assertEquals(1, ledger.inserts.size)
        assertEquals(0, ledger.updates.size)
        assertEquals(1, ledger.skipped)
    }

    @Test
    fun duplicateInsideFileMergesIntoPendingInsertNotAsUpdate() {
        // 命中「本次还没落库的新记录」：必须就地替换待新增那条（id 还是 0，不能当更新写回）
        val ledger = QsoImportLedger()
        ledger.offer(qso())
        ledger.offer(qso().copy(lotwRcvd = "Y"))
        assertEquals(1, ledger.inserts.size)
        assertTrue("命中待新增记录时不应产生更新", ledger.updates.isEmpty())
        assertEquals("Y", ledger.inserts.single().lotwRcvd)
        assertEquals(0L, ledger.inserts.single().id)
    }

    @Test
    fun repeatedMergeOfSameRowCollectsSingleUpdateWithFinalState() {
        val ledger = QsoImportLedger(listOf(qso(id = 7, grid = null)))
        ledger.offer(qso(grid = "PM96"))
        ledger.offer(qso().copy(lotwRcvd = "Y")) // 同一行第二次补齐
        assertEquals("同一行多次合并只应写回一条", 1, ledger.updates.size)
        val merged = ledger.updates.single()
        assertEquals(7L, merged.id)
        assertEquals("PM96", merged.theirGrid)
        assertEquals("Y", merged.lotwRcvd)
    }

    @Test
    fun unknownRecordFieldsStayUntouched() {
        val ledger = QsoImportLedger()
        ledger.offer(qso())
        assertEquals("PM95", ledger.inserts.single().theirGrid)
        assertNull(ledger.inserts.single().comment)
    }
}
