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

    /** 判重：呼号 + 完成时间 + 波段 + 模式。 */
    @Query(
        "SELECT COUNT(*) FROM qso WHERE UPPER(theirCall) = UPPER(:call) " +
            "AND utcMs = :utcMs AND band = :band AND mode = :mode",
    )
    suspend fun countExact(call: String, utcMs: Long, band: String, mode: String): Int
}
