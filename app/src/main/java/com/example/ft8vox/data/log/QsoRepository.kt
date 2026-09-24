package com.example.ft8vox.data.log

import com.example.ft8vox.data.adif.AdifCodec
import com.example.ft8vox.data.adif.AdifMapper
import kotlinx.coroutines.flow.Flow

/** 通联日志仓库：对 DAO 做一层领域封装（ADIF 导入导出、去重、已通联索引）。 */
class QsoRepository(private val dao: QsoDao) {

    fun observeAll(): Flow<List<QsoEntity>> = dao.observeAll()

    fun observeRecent(limit: Int): Flow<List<QsoEntity>> = dao.observeRecent(limit)

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun add(entity: QsoEntity): Long = dao.insert(entity)

    suspend fun update(entity: QsoEntity) = dao.update(entity)

    suspend fun delete(entity: QsoEntity) = dao.delete(entity)

    suspend fun clear() = dao.clear()

    suspend fun findById(id: Long): QsoEntity? = dao.findById(id)

    suspend fun all(): List<QsoEntity> = dao.all()

    suspend fun count(): Int = dao.count()

    /** 已通联呼号（大写去重），用于「排除已通联」与颜色高亮。 */
    suspend fun workedCalls(): Set<String> = dao.workedCalls().toSet()

    /** 已通联网格（大写去重）。 */
    suspend fun workedGrids(): Set<String> = dao.workedGrids().toSet()

    /** 导入结果统计。 */
    data class ImportResult(val added: Int, val skipped: Int) {
        val total: Int get() = added + skipped
    }

    /**
     * 导入 ADIF 文本。
     *
     * 判重口径：呼号 + 完成时间 + 波段 + 模式。同一文件重复导入不会产生重复记录。
     */
    suspend fun importAdif(text: String, myCall: String, myGrid: String?): ImportResult {
        var added = 0
        var skipped = 0
        for (record in AdifCodec.decode(text)) {
            val entity = AdifMapper.toEntity(record, myCall, myGrid)
            if (entity == null) {
                skipped++
                continue
            }
            if (dao.countExact(entity.theirCall, entity.utcMs, entity.band, entity.mode) > 0) {
                skipped++
                continue
            }
            dao.insert(entity)
            added++
        }
        return ImportResult(added = added, skipped = skipped)
    }

    /** 导出全部记录为 ADIF 文本。 */
    suspend fun exportAdif(): String =
        AdifCodec.encode(dao.all().map { AdifMapper.toRecord(it) })
}
