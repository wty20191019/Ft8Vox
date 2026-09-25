package com.example.ft8vox

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ft8vox.data.QsoTime
import com.example.ft8vox.data.adif.AdifCodec
import com.example.ft8vox.data.adif.adifRecord
import com.example.ft8vox.data.log.AppDatabase
import com.example.ft8vox.data.log.QsoEntity
import com.example.ft8vox.data.log.QsoRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Room 通联仓库的集成测试（内存库）。 */
@RunWith(AndroidJUnit4::class)
class QsoRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: QsoRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        repo = QsoRepository(db.qsoDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(
        call: String,
        utcMs: Long = QsoTime.parseUtc("20260924", "120315")!!,
        grid: String? = "PM95",
        band: String = "20m",
        mode: String = "FT8",
    ) = QsoEntity(
        theirCall = call,
        theirGrid = grid,
        myCall = "F4FSY",
        myGrid = "JN25",
        utcMs = utcMs,
        band = band,
        freqHz = 14_074_000L,
        mode = mode,
        reportSent = -8,
        reportReceived = -5,
    )

    @Test
    fun insertsAndQueries() = runBlocking {
        repo.add(entity("JA1ABC"))
        repo.add(entity("W1AW", utcMs = QsoTime.parseUtc("20260101", "010203")!!))

        assertEquals(2, repo.count())
        assertEquals(2, repo.observeAll().first().size)
        assertEquals("JA1ABC", repo.observeAll().first()[0].theirCall) // 新→旧
        assertEquals("JA1ABC", repo.observeRecent(1).first().single().theirCall) // 最近一条是最新的
    }

    @Test
    fun updatesAndDeletes() = runBlocking {
        val id = repo.add(entity("JA1ABC"))
        val saved = repo.findById(id)!!
        repo.update(saved.copy(comment = "已确认", qslRcvd = "Y"))
        assertEquals("已确认", repo.findById(id)!!.comment)
        assertEquals("Y", repo.observeAll().first().single().qslRcvd)

        repo.delete(repo.findById(id)!!)
        assertEquals(0, repo.count())
        assertNull(repo.findById(id))
    }

    @Test
    fun importAddsThenDedupes() = runBlocking {
        val text = AdifCodec.encode(
            listOf(
                adifRecord(
                    "CALL" to "JA1ABC",
                    "QSO_DATE" to "20260924",
                    "TIME_ON" to "120315",
                    "BAND" to "20m",
                    "MODE" to "FT8",
                    "GRIDSQUARE" to "PM95",
                ),
                adifRecord(
                    "CALL" to "W1AW",
                    "QSO_DATE" to "20260924",
                    "TIME_ON" to "121500",
                    "BAND" to "20m",
                    "MODE" to "FT8",
                ),
            ),
        )

        val first = repo.importAdif(text, myCall = "F4FSY", myGrid = "JN25")
        assertEquals(2, first.added)
        assertEquals(0, first.skipped)

        // 同一份文件再次导入不应产生重复
        val second = repo.importAdif(text, myCall = "F4FSY", myGrid = "JN25")
        assertEquals(0, second.added)
        assertEquals(2, second.skipped)
        assertEquals(2, repo.count())
    }

    @Test
    fun importSkipsInvalidRecords() = runBlocking {
        val text = AdifCodec.encode(
            listOf(
                adifRecord("CALL" to "JA1ABC", "QSO_DATE" to "20260924", "TIME_ON" to "120315"),
                adifRecord("QSO_DATE" to "20260924", "TIME_ON" to "120315"), // 缺呼号
                adifRecord("CALL" to "W1AW", "QSO_DATE" to "bad"), // 日期非法
            ),
        )
        val result = repo.importAdif(text, myCall = "F4FSY", myGrid = null)
        assertEquals(1, result.added)
        assertEquals(2, result.skipped)
        assertEquals(3, result.total)
    }

    @Test
    fun exportImportRoundTrip() = runBlocking {
        repo.add(entity("JA1ABC"))
        repo.add(entity("W1AW", utcMs = QsoTime.parseUtc("20260101", "010203")!!, grid = "FN42", mode = "FT4"))

        val exported = repo.exportAdif()
        assertTrue(exported.contains("<CALL:6>JA1ABC"))
        assertTrue(exported.contains("<PROGRAMID:6>Ft8Vox"))

        repo.clear()
        assertEquals(0, repo.count())

        val result = repo.importAdif(exported, myCall = "", myGrid = null)
        assertEquals(2, result.added)

        val restored = repo.observeAll().first()
        assertEquals(2, restored.size)
        val ja = restored.first { it.theirCall == "JA1ABC" }
        assertEquals("PM95", ja.theirGrid)
        assertEquals("F4FSY", ja.myCall)
        assertEquals("20m", ja.band)
        assertEquals(14_074_000L, ja.freqHz)
        assertEquals(-8, ja.reportSent)
        assertEquals(-5, ja.reportReceived)
        assertEquals(QsoTime.parseUtc("20260924", "120315"), ja.utcMs)
    }

    @Test
    fun importMergesLotwConfirmationIntoExistingRecords() = runBlocking {
        repo.add(entity("JA1ABC")) // 本机记录：尚无确认
        assertEquals(1, repo.count())

        // LoTW 确认报告：同一条通联 + LOTW_QSL_RCVD=Y
        val report = AdifCodec.encode(
            listOf(
                adifRecord(
                    "CALL" to "JA1ABC",
                    "QSO_DATE" to "20260924",
                    "TIME_ON" to "120315",
                    "BAND" to "20m",
                    "MODE" to "FT8",
                    "QSL_RCVD" to "Y",
                    "LOTW_QSL_RCVD" to "Y",
                ),
            ),
        )

        val result = repo.importAdif(report, myCall = "F4FSY", myGrid = "JN25")
        assertEquals(0, result.added)
        assertEquals(1, result.updated)
        assertEquals(0, result.skipped)
        assertEquals(1, repo.count()) // 不新增重复行

        val row = repo.observeAll().first().single()
        assertEquals("Y", row.lotwRcvd)
        assertEquals("Y", row.qslRcvd)
        assertEquals(-8, row.reportSent) // 原有信息不被清空

        // 再次导入同一份报告：已确认，无变化 → 全部跳过
        val again = repo.importAdif(report, myCall = "F4FSY", myGrid = "JN25")
        assertEquals(0, again.added)
        assertEquals(0, again.updated)
        assertEquals(1, again.skipped)
    }

    @Test
    fun importMatchesLoTWTimeRoundedToMinute() = runBlocking {
        repo.add(entity("JA1ABC")) // 12:03:15

        // LoTW 导出常只精确到分钟
        val report = AdifCodec.encode(
            listOf(
                adifRecord(
                    "CALL" to "JA1ABC",
                    "QSO_DATE" to "20260924",
                    "TIME_ON" to "1203",
                    "BAND" to "20m",
                    "MODE" to "FT8",
                    "LOTW_QSL_RCVD" to "Y",
                ),
            ),
        )
        val result = repo.importAdif(report, myCall = "", myGrid = null)
        assertEquals(0, result.added)
        assertEquals(1, result.updated)
        assertEquals(1, repo.count())
        assertEquals("Y", repo.observeAll().first().single().lotwRcvd)
    }

    @Test
    fun workedIndexesAreUppercasedAndDeduplicated() = runBlocking {
        repo.add(entity("ja1abc"))
        repo.add(entity("JA1ABC", utcMs = QsoTime.parseUtc("20260924", "130000")!!))
        repo.add(entity("W1AW", utcMs = QsoTime.parseUtc("20260924", "140000")!!, grid = null))

        val calls = repo.workedCalls()
        assertEquals(setOf("JA1ABC", "W1AW"), calls)

        val grids = repo.workedGrids()
        assertTrue(grids.contains("PM95"))
        assertFalse(grids.any { it.isBlank() })
    }
}
