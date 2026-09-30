package com.example.ft8vox.ui

/**
 * 接收列表（操作页的 Band Activity）里的一行：**接收解码**（[Rx]）或**我方发射**（[Tx]）。
 *
 * 两类行按**同一时间轴**（[slotUtcMs] 新→旧）混排，见 [mergeActivity]；条数上限
 * [ACTIVITY_LIMIT] 由两类共用（谁更旧谁先被淘汰）。
 *
 * 实机口径见 docs/Ft8Vox.md。
 */
sealed interface ActivityRow {

    /** 所属时隙的 UTC 起点（毫秒）——两类行统一的排序键。 */
    val slotUtcMs: Long

    /** 自动翻到最新（`LaunchedEffect` 的键）用的稳定标识。 */
    val key: String

    /** 接收解码行。 */
    data class Rx(val row: DecodeRow) : ActivityRow {
        override val slotUtcMs: Long get() = row.msg.slotUtcMs
        override val key: String get() = "rx:${row.msg.text}@${row.msg.slotUtcMs}"
    }

    /** 我方发射行（TX 行）。 */
    data class Tx(val rec: TxRecord) : ActivityRow {
        override val slotUtcMs: Long get() = rec.slotUtcMs
        override val key: String get() = "tx:${rec.id}"
    }
}

/** 接收列表两类行**共用**的条数上限（一起淘汰最旧的）。 */
const val ACTIVITY_LIMIT = 200

/**
 * 我方一次发射的记录（接收列表里的「TX 行」）。
 *
 * @param id 本次会话内单调递增的稳定标识。**同一时隙可能换目标就地重发两次**，
 *   「文本 + 时隙」并不唯一，故另用 id 定位。
 * @param text 实际排出去播的报文明文。
 * @param slotUtcMs 所属时隙的 UTC 起点（ms）。
 * @param order 报文序号（1..6，照 `TxMessageKind`；自定义 / 解析不出为 0）。
 * @param outcome 播出结果，见 [TxOutcome]。
 */
data class TxRecord(
    val id: Long,
    val text: String,
    val slotUtcMs: Long,
    val order: Int = 0,
    val outcome: TxOutcome = TxOutcome.PLAYING,
)

/** 一次发射的播出结果（决定 TX 行尾是否标「未发完」）。 */
enum class TxOutcome {
    /** 正在播（尚未收尾）：不标注。 */
    PLAYING,

    /** 已完整播出：不标注。 */
    DONE,

    /** **未播完**：被「停止发射」/ 换目标就地重发作废，或写入失败 → 行尾标「未发完」。 */
    ABORTED,
}

/**
 * 把接收解码行与我方发射行按**同一时间轴**混排（[ActivityRow.slotUtcMs] 新→旧），
 * 并截断到 [limit] 行（两类共用上限：谁更旧谁先被淘汰）。
 *
 * **稳定排序**：同一时隙内保持传入顺序（接收解码在前、TX 在后）。因为 FT8 收发分时隙，
 * 正常不会出现「同一时隙既有我发的、又有我收的」；这里的稳定只是兜底。
 */
fun mergeActivity(
    rx: List<DecodeRow>,
    tx: List<TxRecord>,
    limit: Int = ACTIVITY_LIMIT,
): List<ActivityRow> {
    val out = ArrayList<ActivityRow>(rx.size + tx.size)
    rx.forEach { out += ActivityRow.Rx(it) }
    tx.forEach { out += ActivityRow.Tx(it) }
    return out.sortedByDescending { it.slotUtcMs }.take(limit)
}
