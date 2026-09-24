package com.example.ft8vox.qso

/**
 * FT8/FT4 常用报文的解析结果。
 *
 * 只覆盖 QSO 需要的主要形态（CQ / 呼号网格 / 信号报告 / R 报告 / RR73 / 73），
 * 其余一律归为自由文本。
 */
data class ParsedMessage(
    /** 原始文本。 */
    val raw: String,
    /** 是否为 CQ 呼叫。 */
    val isCq: Boolean = false,
    /** CQ 修饰符（DX / NA / TEST / 001 等），无则为 null。 */
    val cqModifier: String? = null,
    /** 收方呼号（CQ 时为 null）。 */
    val to: String? = null,
    /** 发方呼号（CQ 时为呼叫者）。 */
    val from: String? = null,
    /** 网格（如 JN25 / PM95ab）。 */
    val grid: String? = null,
    /** 信号报告（dB，如 -12、+05）。 */
    val report: Int? = null,
    /** 是否为 R<报告> / RRR（roger）。 */
    val isRoger: Boolean = false,
    /** 是否为 RR73。 */
    val isRr73: Boolean = false,
    /** 是否为 73。 */
    val is73: Boolean = false,
    /** 是否为自由文本（无法归入上述类型）。 */
    val isFreeText: Boolean = false,
) {
    /** 该报文的载荷类型描述，便于 UI 展示。 */
    val kind: String
        get() = when {
            isCq -> "CQ"
            isRr73 -> "RR73"
            is73 -> "73"
            isRoger -> "R${report?.let { MessageParser.formatReport(it) } ?: ""}"
            report != null -> MessageParser.formatReport(report)
            grid != null -> "GRID"
            else -> "TEXT"
        }

    /** 该报文是否发给我（[myCall]）。 */
    fun addressedTo(myCall: String): Boolean =
        to != null && to.equals(myCall, ignoreCase = true)
}

/** 报文解析器（纯 Kotlin，便于单测）。 */
object MessageParser {

    private val CALLSIGN_REGEX = Regex("^[A-Z0-9]{1,3}[0-9][A-Z]{1,4}(/[A-Z0-9]{1,4})?$")
    private val GRID_REGEX = Regex("^[A-R]{2}[0-9]{2}([A-X]{2})?$")
    private val REPORT_REGEX = Regex("^[+-][0-9]{2}$")
    private val ROGER_REGEX = Regex("^R[+-][0-9]{2}$")

    /**
     * 判断是否为呼号：形如 `前缀(1-3) + 数字 + 后缀字母(1-4)`，可选 `/` 后缀。
     *
     * 该结构可正确排除网格（`JN25`）与 `RR73`，但会漏掉少数特设台/异形呼号，属于 MVP 取舍。
     */
    fun looksLikeCallsign(token: String): Boolean = CALLSIGN_REGEX.matches(token.uppercase())

    /** 格式化信号报告：带符号两位数字，如 -08、+05、+00。 */
    fun formatReport(db: Int): String {
        val v = db.coerceIn(-24, 30)
        val sign = if (v < 0) "-" else "+"
        return sign + "%02d".format(kotlin.math.abs(v))
    }

    fun parse(text: String): ParsedMessage {
        val raw = text.trim()
        val tokens = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ParsedMessage(raw = raw, isFreeText = true)

        if (tokens[0].equals("CQ", ignoreCase = true)) {
            // CQ [修饰符...] <呼号> [网格]
            val rest = tokens.drop(1)
            val callIdx = rest.indexOfFirst { looksLikeCallsign(it) }
            if (callIdx < 0) return ParsedMessage(raw = raw, isCq = true, isFreeText = true)
            val modifier = if (callIdx > 0) rest.take(callIdx).joinToString(" ") else null
            val from = rest[callIdx]
            val tail = rest.drop(callIdx + 1)
            val grid = tail.firstOrNull { GRID_REGEX.matches(it.uppercase()) }
            return ParsedMessage(
                raw = raw,
                isCq = true,
                cqModifier = modifier,
                from = from,
                grid = grid,
            )
        }

        // 定向报文：<收方> <发方> [载荷]
        if (tokens.size >= 3 && looksLikeCallsign(tokens[0]) && looksLikeCallsign(tokens[1])) {
            val to = tokens[0]
            val from = tokens[1]
            val payload = tokens.drop(2).joinToString(" ")
            return parsePayload(raw, to, from, payload)
        }

        return ParsedMessage(raw = raw, isFreeText = true)
    }

    private fun parsePayload(raw: String, to: String, from: String, payload: String): ParsedMessage {
        val upper = payload.uppercase()
        return when {
            upper == "RR73" -> ParsedMessage(raw = raw, to = to, from = from, isRr73 = true)
            upper == "RRR" -> ParsedMessage(raw = raw, to = to, from = from, isRoger = true)
            upper == "73" -> ParsedMessage(raw = raw, to = to, from = from, is73 = true)
            ROGER_REGEX.matches(upper) ->
                ParsedMessage(
                    raw = raw, to = to, from = from,
                    isRoger = true, report = upper.drop(1).toIntOrNull(),
                )
            REPORT_REGEX.matches(upper) ->
                ParsedMessage(raw = raw, to = to, from = from, report = upper.toIntOrNull())
            GRID_REGEX.matches(upper) ->
                ParsedMessage(raw = raw, to = to, from = from, grid = upper)
            else -> ParsedMessage(raw = raw, to = to, from = from, isFreeText = true)
        }
    }
}
