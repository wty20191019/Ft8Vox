package com.example.ft8vox.data.adif

import com.example.ft8vox.data.BandPlan
import com.example.ft8vox.data.QsoTime
import com.example.ft8vox.data.log.QsoEntity
import com.example.ft8vox.grid.Maidenhead
import com.example.ft8vox.qso.MessageParser
import java.util.Locale

/** ADIF 记录与本地通联实体之间的映射。 */
object AdifMapper {

    /**
     * ADIF 记录 → 本地实体。
     *
     * 缺少呼号或时间视为无效，返回 null。[fallbackMyCall]/[fallbackMyGrid] 用于补全
     * 别家软件未写入的我方信息。
     */
    fun toEntity(record: AdifRecord, fallbackMyCall: String, fallbackMyGrid: String?): QsoEntity? {
        val call = record.call?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        val utcMs = QsoTime.parseUtc(record.qsoDate, record.timeOn) ?: return null

        val band = record.band?.trim()?.takeIf { BandPlan.contains(it) }
            ?: BandPlan.fromFreqMhz(record.freq)?.name
            ?: ""
        val freqHz = record.freq?.trim()?.replace(',', '.')?.toDoubleOrNull()
            ?.let { (it * 1_000_000).toLong() }
            ?: BandPlan.dialHz(band)

        return QsoEntity(
            theirCall = call,
            theirGrid = Maidenhead.normalize(record.gridSquare).takeIf { it.isNotEmpty() },
            myCall = record.myCall?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: fallbackMyCall,
            myGrid = Maidenhead.normalize(record.myGridSquare).takeIf { it.isNotEmpty() } ?: fallbackMyGrid,
            utcMs = utcMs,
            band = band,
            freqHz = freqHz,
            mode = normalizeMode(record),
            reportSent = parseReport(record.rstSent),
            reportReceived = parseReport(record.rstRcvd),
            qslRcvd = record.qslRcvd?.trim()?.uppercase()?.take(1),
            lotwRcvd = record.lotwQslRcvd?.trim()?.uppercase()?.take(1),
            comment = record.comment?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** 本地实体 → ADIF 记录。 */
    fun toRecord(entity: QsoEntity): AdifRecord {
        val fields = LinkedHashMap<String, String>()
        fields["CALL"] = entity.theirCall
        fields["QSO_DATE"] = QsoTime.date(entity.utcMs)
        fields["TIME_ON"] = QsoTime.time(entity.utcMs)
        if (entity.band.isNotEmpty()) fields["BAND"] = entity.band
        val freqHz = if (entity.freqHz > 0) entity.freqHz else BandPlan.dialHz(entity.band)
        if (freqHz > 0) fields["FREQ"] = String.format(Locale.US, "%.5f", freqHz / 1_000_000.0)
        fields["MODE"] = entity.mode
        entity.myCall.takeIf { it.isNotEmpty() }?.let { fields["MY_CALL"] = it }
        entity.myGrid?.takeIf { it.isNotEmpty() }?.let { fields["MY_GRIDSQUARE"] = it }
        entity.reportSent?.let { fields["RST_SENT"] = MessageParser.formatReport(it) }
        entity.reportReceived?.let { fields["RST_RCVD"] = MessageParser.formatReport(it) }
        entity.theirGrid?.takeIf { it.isNotEmpty() }?.let { fields["GRIDSQUARE"] = it }
        entity.qslRcvd?.takeIf { it.isNotEmpty() }?.let { fields["QSL_RCVD"] = it }
        entity.lotwRcvd?.takeIf { it.isNotEmpty() }?.let { fields["LOTW_QSL_RCVD"] = it }
        entity.comment?.takeIf { it.isNotEmpty() }?.let { fields["COMMENT"] = it }
        return AdifRecord(fields)
    }

    /** 归一化模式：ADIF 旧规范用 `MODE=MFSK` + `SUBMODE=FT4` 表示 FT4。 */
    private fun normalizeMode(record: AdifRecord): String {
        val sub = record.subMode?.uppercase()
        if (sub == "FT4") return "FT4"
        if (sub == "FT8") return "FT8"
        return when (record.mode?.uppercase()) {
            "FT4" -> "FT4"
            "FT8" -> "FT8"
            "MFSK" -> if (sub == "FT4") "FT4" else "FT8"
            else -> "FT8"
        }
    }

    /** 解析信号报告：`-08` / `+05` / `5` / `R-08`。RST（如 599）不属本刻度，丢弃。 */
    private fun parseReport(text: String?): Int? {
        val cleaned = text?.trim()?.removePrefix("R")?.removePrefix("r")?.trim() ?: return null
        val value = cleaned.toIntOrNull() ?: return null
        return if (value in -40..40) value else null
    }
}
