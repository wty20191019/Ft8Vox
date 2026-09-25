package com.example.ft8vox.data.log

/**
 * ADIF 导入时的记录合并：判重命中同一通联后，用新数据**补齐 / 点亮**已有记录。
 *
 * 关键用途是 QSL / LoTW 确认：LoTW、eQSL 导出的报告里，QSO 本身与本地记录完全同一条
 * （呼号 + 时间 + 波段 + 模式相同），只是多了 `LOTW_QSL_RCVD=Y` / `QSL_RCVD=Y`。
 * 若按「重复即丢弃」处理，确认状态永远读不进来 —— 必须合并。
 *
 * 合并原则：
 * - 判重字段（[QsoEntity.theirCall] / [QsoEntity.utcMs] / [QsoEntity.mode]）与主键保持不变；
 * - 其余字段「非空优先」：已有非空值不会被导入内容覆盖（不冲掉用户手改的备注等）；
 * - 确认状态特殊处理：`"Y"` 具有粘性（已确认不可撤销），其余情况取非空的新值，
 *   这样先导入的 `N` 能被后来导入的 `Y` 覆盖。
 */
object QsoMerge {

    /** 合并同一通联的两份数据，返回应写回的实体（与 [existing] 相同表示无变化）。 */
    fun merge(existing: QsoEntity, incoming: QsoEntity): QsoEntity = existing.copy(
        theirGrid = pick(existing.theirGrid, incoming.theirGrid),
        myCall = pick(existing.myCall, incoming.myCall).orEmpty(),
        myGrid = pick(existing.myGrid, incoming.myGrid),
        band = pick(existing.band, incoming.band).orEmpty(),
        freqHz = if (existing.freqHz > 0) existing.freqHz else incoming.freqHz,
        reportSent = existing.reportSent ?: incoming.reportSent,
        reportReceived = existing.reportReceived ?: incoming.reportReceived,
        qslRcvd = mergeQsl(existing.qslRcvd, incoming.qslRcvd),
        lotwRcvd = mergeQsl(existing.lotwRcvd, incoming.lotwRcvd),
        comment = pick(existing.comment, incoming.comment),
    )

    /** 非空优先：已有非空值保留，否则取导入值。 */
    private fun pick(old: String?, incoming: String?): String? =
        old?.takeIf { it.isNotBlank() } ?: incoming?.takeIf { it.isNotBlank() }

    /** QSL 确认状态合并：`"Y"` 不可被覆盖，其余取非空的新值。 */
    private fun mergeQsl(old: String?, incoming: String?): String? = when {
        old == "Y" -> "Y"
        !incoming.isNullOrBlank() -> incoming
        else -> old
    }
}
