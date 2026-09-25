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

    /**
     * 导入结果统计。
     *
     * [updated] = 同一通联被补齐信息 / 点亮确认的条数（不是重复丢弃）。
     */
    data class ImportResult(val added: Int, val updated: Int = 0, val skipped: Int = 0) {
        val total: Int get() = added + updated + skipped
    }

    /**
     * 导入 ADIF 文本。
     *
     * 判重口径：呼号 + 完成时间 + 波段 + 模式；时间允许 ±[NEAR_TOLERANCE_MS] 的偏差，
     * 以兼容 LoTW 等只精确到分钟的导出。
     *
     * 命中同一通联时**合并**而不是丢弃（见 [QsoMerge]）：这样把 LoTW / eQSL 的确认报告
     * 导进来时，已有记录会被点亮为「已确认」，同时保持「同一文件重复导入不产生重复记录」。
     */
    suspend fun importAdif(text: String, myCall: String, myGrid: String?): ImportResult {
        var added = 0
        var updated = 0
        var skipped = 0
        for (record in AdifCodec.decode(text)) {
            val entity = AdifMapper.toEntity(record, myCall, myGrid)
            if (entity == null) {
                skipped++
                continue
            }
            val existing = findExisting(entity)
            if (existing == null) {
                dao.insert(entity)
                added++
                continue
            }
            val merged = QsoMerge.merge(existing, entity)
            if (merged == existing) {
                skipped++
                continue
            }
            dao.update(merged)
            updated++
        }
        return ImportResult(added = added, updated = updated, skipped = skipped)
    }

    /** 先精确匹配，再退化为同一分钟内的近似匹配（对方的导出可能只精确到分钟）。 */
    private suspend fun findExisting(entity: QsoEntity): QsoEntity? =
        dao.findExact(entity.theirCall, entity.utcMs, entity.band, entity.mode)
            ?: dao.findNear(
                call = entity.theirCall,
                band = entity.band,
                mode = entity.mode,
                utcMs = entity.utcMs,
                fromMs = entity.utcMs - NEAR_TOLERANCE_MS,
                toMs = entity.utcMs + NEAR_TOLERANCE_MS,
            )

    /** 导出全部记录为 ADIF 文本。 */
    suspend fun exportAdif(): String =
        AdifCodec.encode(dao.all().map { AdifMapper.toRecord(it) })

    private companion object {
        /** 近似判重的时间容差：LoTW 等导出常把 TIME_ON 截到分钟。 */
        const val NEAR_TOLERANCE_MS = 60_000L
    }
}
