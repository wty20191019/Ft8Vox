package com.example.ft8vox.data.adif

/**
 * 一条 ADIF 记录：以「大写标签 → 值」的映射保存。
 *
 * 之所以不展开成强类型字段，是为了在**导入 → 导出**往返中保留未识别字段，
 * 避免把别人的日志导出后丢失信息。访问器只覆盖 Ft8Vox 关心的字段。
 */
data class AdifRecord(val fields: Map<String, String>) {

    operator fun get(tag: String): String? = fields[tag.uppercase()]?.takeIf { it.isNotBlank() }

    val call: String? get() = get("CALL")
    val qsoDate: String? get() = get("QSO_DATE")
    val timeOn: String? get() = get("TIME_ON")
    val band: String? get() = get("BAND")
    val freq: String? get() = get("FREQ")
    val mode: String? get() = get("MODE")
    val subMode: String? get() = get("SUBMODE")
    val rstSent: String? get() = get("RST_SENT")
    val rstRcvd: String? get() = get("RST_RCVD")
    val gridSquare: String? get() = get("GRIDSQUARE")
    val qslRcvd: String? get() = get("QSL_RCVD")
    val lotwQslRcvd: String? get() = get("LOTW_QSL_RCVD")
    val comment: String? get() = get("COMMENT")
    val myCall: String? get() = get("MY_CALL") ?: get("STATION_CALLSIGN")
    val myGridSquare: String? get() = get("MY_GRIDSQUARE")
}

/** 便捷构造：`adifRecord("CALL" to "JA1ABC", "BAND" to "20m")`。 */
fun adifRecord(vararg pairs: Pair<String, String>): AdifRecord =
    AdifRecord(pairs.associate { it.first.uppercase() to it.second })
