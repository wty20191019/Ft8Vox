package com.example.ft8vox.data.log

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.ft8vox.data.adif.AdifCodec
import com.example.ft8vox.data.adif.AdifMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** 通联日志仓库：对 DAO 做一层领域封装（ADIF 导入导出、去重、已通联索引）。 */
class QsoRepository(private val dao: QsoDao, private val db: RoomDatabase) {

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
     * 判重口径：呼号（忽略大小写）+ 完成时间 + 波段 + 模式；时间允许 ±60 秒的偏差
     * （见 [QsoImportLedger]），以兼容 LoTW 等只精确到分钟的导出。
     *
     * 命中同一通联时**合并**而不是丢弃（见 [QsoMerge]）：这样把 LoTW / eQSL 的确认报告
     * 导进来时，已有记录会被点亮为「已确认」，同时保持「同一文件重复导入不产生重复记录」。
     *
     * 性能（大文件导入不再逐条查库、不卡界面）：
     * 1. 解析与判重是纯 CPU 工作，整体跑在 [Dispatchers.Default]，不占主线程；
     * 2. 判重用 [QsoImportLedger] 的**一次性内存索引**，把原来的 `O(导入条数 × 现有条数)`
     *    两次全表扫描降到 `O(现有条数 + 导入条数)`；
     * 3. 新增与更新分别收集，最后在**一个事务**里批量写回 —— 一次提交只触发一轮整表重算；
     * 4. [onProgress] 回调报告「导入中 n/N」（约 [PROGRESS_STEPS] 次，供界面显示进度）。
     *
     * 已知取舍：现有记录是导入开始时**一次性快照**读出的；若导入期间后台仍在写日志
     * （自动通联），极少数情况下可能插入一条本可合并的记录 —— 下次导入即会合并掉。
     */
    suspend fun importAdif(
        text: String,
        myCall: String,
        myGrid: String?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportResult = withContext(Dispatchers.Default) {
        val records = AdifCodec.decode(text)
        val total = records.size
        onProgress(0, total)

        val ledger = QsoImportLedger(dao.all())
        var invalid = 0
        val step = maxOf(1, total / PROGRESS_STEPS)
        records.forEachIndexed { i, record ->
            val entity = AdifMapper.toEntity(record, myCall, myGrid)
            if (entity == null) invalid++ else ledger.offer(entity)
            val done = i + 1
            if (done % step == 0 || done == total) onProgress(done, total)
        }

        if (ledger.inserts.isNotEmpty() || ledger.updates.isNotEmpty()) {
            db.withTransaction {
                if (ledger.inserts.isNotEmpty()) dao.insertAll(ledger.inserts)
                if (ledger.updates.isNotEmpty()) dao.updateAll(ledger.updates)
            }
        }
        ImportResult(
            added = ledger.inserts.size,
            updated = ledger.updates.size,
            skipped = invalid + ledger.skipped,
        )
    }

    /** 导出全部记录为 ADIF 文本。 */
    suspend fun exportAdif(): String =
        AdifCodec.encode(dao.all().map { AdifMapper.toRecord(it) })

    private companion object {
        /** 导入进度回调的粒度：约上报这么多档（避免为每条记录都刷一次界面）。 */
        const val PROGRESS_STEPS = 50
    }
}
