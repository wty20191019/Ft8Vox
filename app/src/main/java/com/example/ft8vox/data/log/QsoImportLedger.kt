package com.example.ft8vox.data.log

import kotlin.math.abs

/**
 * ADIF 导入期间的**判重 + 合并账本**（纯 Kotlin，可 JVM 单测）。
 *
 * 取代「每条导入记录查两次库」的做法（`QsoDao.findExact` / `findNear`）：开始导入时
 * **一次性**把现有记录读进内存建索引（构造参数 [existing]），之后每条导入记录都在内存里判重。
 * 于是开销从 `O(导入条数 × 现有条数)` 的两次全表扫描降到 `O(现有条数 + 导入条数)`。
 *
 * 判重口径与旧实现**一致**（唯一有意放宽处：呼号两侧空白会被裁掉再比较，见 [key]）：
 * 1. **精确**：呼号（忽略大小写）+ 完成时间 + 波段 + 模式全同；
 * 2. **近似**：呼号 + 波段 + 模式相同、时间相差不超过 [toleranceMs]（LoTW 等导出只精确到分钟），
 *    取时间最接近的一条。
 *
 * 结果分三筐：[inserts]（新增）、[updates]（合并后有变化的已有记录，按 id 去重）、
 * [skipped]（合并后无变化）。**本次导入新增的记录也会登记进索引**，所以同一份文件里的重复
 * 同样只留一条（与旧实现等价）；命中「本次还没落库的新记录」时就地替换 [inserts] 里的那条
 * ——它的 `id` 还是 0，不能当更新写回。
 */
class QsoImportLedger(
    existing: List<QsoEntity> = emptyList(),
    private val toleranceMs: Long = 60_000L,
) {

    /** 精确键：呼号（大写）+ 完成时间 + 波段 + 模式。 */
    private data class Key(val call: String, val utcMs: Long, val band: String, val mode: String)

    /** 近似匹配的组键：呼号（大写）+ 波段 + 模式。 */
    private data class Group(val call: String, val band: String, val mode: String)

    /** 索引里的一格；[pendingIndex] ≥ 0 表示这条是本次导入的新记录（在 [inserts] 里的下标）。 */
    private class Cell(var entity: QsoEntity, val pendingIndex: Int)

    private val exact = HashMap<Key, Cell>()
    private val groups = HashMap<Group, MutableList<Cell>>()

    /** 待新增（按文件顺序）。 */
    val inserts = ArrayList<QsoEntity>()

    /** 待更新：按 id 去重，保留**最后一次**合并结果（后一次已包含前一次的信息）。 */
    private val updateById = LinkedHashMap<Long, QsoEntity>()
    val updates: List<QsoEntity> get() = updateById.values.toList()

    /** 合并后无变化的条数。 */
    var skipped = 0
        private set

    init {
        for (e in existing) register(Cell(e, pendingIndex = -1))
    }

    /** 处理一条导入记录：新增 / 合并更新 / 无变化跳过。 */
    fun offer(entity: QsoEntity) {
        val hit = find(entity)
        if (hit == null) {
            inserts += entity
            register(Cell(entity, pendingIndex = inserts.lastIndex))
            return
        }
        val merged = QsoMerge.merge(hit.entity, entity)
        if (merged == hit.entity) {
            skipped++
            return
        }
        hit.entity = merged // 同一通联再出现时，以合并后的状态为准
        if (hit.pendingIndex >= 0) {
            inserts[hit.pendingIndex] = merged
        } else {
            updateById[merged.id] = merged
        }
    }

    private fun register(cell: Cell) {
        val e = cell.entity
        exact[Key(key(e.theirCall), e.utcMs, e.band, e.mode)] = cell
        groups.getOrPut(Group(key(e.theirCall), e.band, e.mode)) { ArrayList(1) } += cell
    }

    /** 先精确匹配，再退化为同一时间窗内的最近一条。 */
    private fun find(entity: QsoEntity): Cell? {
        val call = key(entity.theirCall)
        exact[Key(call, entity.utcMs, entity.band, entity.mode)]?.let { return it }
        val group = groups[Group(call, entity.band, entity.mode)] ?: return null
        var best: Cell? = null
        var bestDiff = Long.MAX_VALUE
        for (c in group) {
            val d = abs(c.entity.utcMs - entity.utcMs)
            if (d <= toleranceMs && d < bestDiff) {
                bestDiff = d
                best = c
            }
        }
        return best
    }

    /** 呼号的比较键：裁掉两侧空白 + 转大写（对应 SQL 的 `UPPER(...)`，并顺手容忍存库时的空白）。 */
    private fun key(call: String): String = call.trim().uppercase()
}
