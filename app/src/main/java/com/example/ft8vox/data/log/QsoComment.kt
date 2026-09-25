package com.example.ft8vox.data.log

import com.example.ft8vox.grid.Geo
import kotlin.math.roundToInt

/**
 * 新通联记录的自动备注（写入 ADIF `COMMENT`）。
 *
 * 仿 FT8CN 的写法，把「双方距离」与「来源程序」记在备注里，例如：
 *
 * - `Distance: 1738 km, QSO by Ft8Vox`（双方网格齐全）
 * - `QSO by Ft8Vox`（任一方缺网格，算不出距离）
 * - `台站备注内容, Distance: 1738 km, QSO by Ft8Vox`（设置页填了「台站备注」时前置）
 *
 * 距离按**大圆距离**计算（我的网格中心 ↔ 对方网格中心），取整到 km。
 */
object QsoComment {

    /** 程序标识：写在备注尾部，便于外部日志软件辨识记录来源。 */
    const val PROGRAM = "Ft8Vox"

    /**
     * 生成自动备注。
     *
     * @param stationNote 设置页的「台站备注」（新通联默认 COMMENT），可为空
     * @param myGrid 我方网格（记录时的快照）
     * @param theirGrid 对方网格（QSO 中收到的）
     */
    fun auto(stationNote: String?, myGrid: String?, theirGrid: String?): String {
        val km = Geo.betweenGrids(myGrid, theirGrid)?.first?.roundToInt()
        val tail = if (km == null) {
            "QSO by $PROGRAM"
        } else {
            "Distance: $km km, QSO by $PROGRAM"
        }
        val note = stationNote?.trim().orEmpty()
        return if (note.isEmpty()) tail else "$note, $tail"
    }
}
