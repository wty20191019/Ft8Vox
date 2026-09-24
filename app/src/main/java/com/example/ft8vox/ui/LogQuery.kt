package com.example.ft8vox.ui

import com.example.ft8vox.data.BandPlan
import com.example.ft8vox.data.log.QsoEntity

/** 日志页的筛选条件（null 表示不限）。 */
data class LogFilter(
    val query: String = "",
    val band: String? = null,
    val mode: String? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
)

/** 日志统计（阶段 7d 的仪表盘复用同一份数据）。 */
data class LogStats(
    val total: Int = 0,
    val uniqueCalls: Int = 0,
    val uniqueGrids: Int = 0,
    val confirmed: Int = 0,
    /** 波段 → 条数，按波段从低到高排序。 */
    val byBand: List<Pair<String, Int>> = emptyList(),
    /** 模式 → 条数，按条数降序。 */
    val byMode: List<Pair<String, Int>> = emptyList(),
)

/**
 * 日志的筛选与统计（纯函数，无 Android 依赖，便于 JVM 单测）。
 */
object LogQuery {

    fun applyFilter(list: List<QsoEntity>, filter: LogFilter): List<QsoEntity> {
        val q = filter.query.trim().uppercase()
        return list.filter { e ->
            (q.isEmpty() ||
                e.theirCall.uppercase().contains(q) ||
                e.theirGrid?.uppercase()?.contains(q) == true) &&
                (filter.band == null || e.band == filter.band) &&
                (filter.mode == null || e.mode == filter.mode) &&
                (filter.fromMs == null || e.utcMs >= filter.fromMs) &&
                (filter.toMs == null || e.utcMs <= filter.toMs)
        }
    }

    fun computeStats(list: List<QsoEntity>): LogStats {
        val bandOrder = BandPlan.bands.map { it.name }
        return LogStats(
            total = list.size,
            uniqueCalls = list.map { it.theirCall.uppercase() }.toSet().size,
            uniqueGrids = list
                .mapNotNull { it.theirGrid?.uppercase()?.takeIf { g -> g.isNotBlank() } }
                .toSet()
                .size,
            confirmed = list.count { it.qslRcvd == "Y" || it.lotwRcvd == "Y" },
            byBand = list.groupingBy { it.band.ifEmpty { "?" } }.eachCount()
                .entries
                .sortedBy { e -> bandOrder.indexOf(e.key).let { if (it < 0) Int.MAX_VALUE else it } }
                .map { it.key to it.value },
            byMode = list.groupingBy { it.mode }.eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { it.key to it.value },
        )
    }
}
