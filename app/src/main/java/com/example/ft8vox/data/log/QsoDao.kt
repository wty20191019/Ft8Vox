package com.example.ft8vox.data.log

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 通联日志的数据访问接口。所有查询默认按时间倒序。 */
@Dao
interface QsoDao {

    @Query("SELECT * FROM qso ORDER BY utcMs DESC")
    fun observeAll(): Flow<List<QsoEntity>>

    /** 最近若干条（用于操作页的「最近通联」）。 */
    @Query("SELECT * FROM qso ORDER BY utcMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<QsoEntity>>

    @Query("SELECT * FROM qso ORDER BY utcMs DESC")
    suspend fun all(): List<QsoEntity>

    @Query("SELECT * FROM qso WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): QsoEntity?

    @Insert
    suspend fun insert(entity: QsoEntity): Long

    @Insert
    suspend fun insertAll(entities: List<QsoEntity>): List<Long>

    @Update
    suspend fun update(entity: QsoEntity)

    @Delete
    suspend fun delete(entity: QsoEntity)

    @Query("DELETE FROM qso")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM qso")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM qso")
    suspend fun count(): Int

    /** 已通联过的呼号（去重、大写）。 */
    @Query("SELECT DISTINCT UPPER(theirCall) FROM qso WHERE theirCall != ''")
    suspend fun workedCalls(): List<String>

    /** 已通联过的网格（去重、大写）。 */
    @Query("SELECT DISTINCT UPPER(theirGrid) FROM qso WHERE theirGrid IS NOT NULL AND theirGrid != ''")
    suspend fun workedGrids(): List<String>

    /** 判重：呼号 + 完成时间 + 波段 + 模式。用于 ADIF 导入时的精确匹配。 */
    @Query(
        "SELECT * FROM qso WHERE UPPER(theirCall) = UPPER(:call) " +
            "AND utcMs = :utcMs AND band = :band AND mode = :mode LIMIT 1",
    )
    suspend fun findExact(call: String, utcMs: Long, band: String, mode: String): QsoEntity?

    /**
     * 近似判重：呼号 + 波段 + 模式相同且时间落在 `[fromMs, toMs]` 内，取时间最接近的一条。
     *
     * 用于兼容 LoTW 等只把 `TIME_ON` 精确到分钟的导出（与本地记录可能差几十秒）。
     */
    @Query(
        "SELECT * FROM qso WHERE UPPER(theirCall) = UPPER(:call) " +
            "AND band = :band AND mode = :mode " +
            "AND utcMs BETWEEN :fromMs AND :toMs " +
            "ORDER BY ABS(utcMs - :utcMs) LIMIT 1",
    )
    suspend fun findNear(
        call: String,
        band: String,
        mode: String,
        utcMs: Long,
        fromMs: Long,
        toMs: Long,
    ): QsoEntity?
}
