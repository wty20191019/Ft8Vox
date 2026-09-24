package com.example.ft8vox.data.log

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条通联记录（阶段 7 起落库）。
 *
 * 时间统一使用 UTC 毫秒（Unix epoch），与解码/发射的时隙口径一致。
 * 我方呼号/网格做一次**快照**，便于 ADIF 导出与历史追溯（台站信息后续可能变更）。
 */
@Entity(
    tableName = "qso",
    indices = [
        Index("theirCall"),
        Index("utcMs"),
        Index("band"),
    ],
)
data class QsoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 对方呼号。 */
    val theirCall: String,
    /** 对方网格（未知为 null）。 */
    val theirGrid: String? = null,
    /** 我方呼号（记录时的快照）。 */
    val myCall: String = "",
    /** 我方网格（记录时的快照）。 */
    val myGrid: String? = null,
    /** 通联完成时间（UTC 毫秒）。 */
    val utcMs: Long,
    /** 波段名，如 "20m"。 */
    val band: String = "",
    /** 频率（Hz），用于 ADIF 的 FREQ 字段。 */
    val freqHz: Long = 0,
    /** 模式：FT8 / FT4。 */
    val mode: String = "FT8",
    /** 我方发出的信号报告（dB）。 */
    val reportSent: Int? = null,
    /** 对方发来的信号报告（dB）。 */
    val reportReceived: Int? = null,
    /** QSL 收到状态：null=未知，"Y"/"N"。 */
    val qslRcvd: String? = null,
    /** LoTW 确认状态：null=未知，"Y"/"N"。 */
    val lotwRcvd: String? = null,
    /** 备注。 */
    val comment: String? = null,
)
