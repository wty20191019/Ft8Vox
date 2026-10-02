package com.example.ft8vox.qso

/**
 * FT8 常用报文的解析结果。
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
    /**
     * 载荷原文（收方 / 发方两个呼号之后的全部内容），即 FT8CN 的 `extraInfo`。
     *
     * 报文序号判据 [FunctionOrder] **只看它**（照 FT8CN `GeneralVariables.checkFunOrder`）：
     * `JN25` → 1、`-10` → 2、`R-10` → 3、`RR73`/`RRR` → 4、`73` → 5、空串 → 1。
     */
    val payload: String = "",
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

    /** 该报文是否发给我（[myCall]；宽松匹配，见 [CallMatch]）。 */
    fun addressedTo(myCall: String): Boolean = CallMatch.isCallingMe(to, myCall)
}

/**
 * 呼号匹配（照 FT8CN 的宽松口径，见 `docs/Ft8Vox.md`）。
 *
 * FT8CN 判「是否呼叫我」用 `callsign.contains(短呼号)`，判「目标带 `/`」用 `contains`：
 * `BG7ZJW/P` 与 `BG7ZJW` 互认，`F4FSY` 与 `F4FSY/P` 互认。这样复合呼号（便携/移动台）
 * 不会因为后缀不同而漏应答。
 */
object CallMatch {

    /** 取复合呼号里 `/` 分段**最长**的一段（FT8CN `getShortCallsign`）。 */
    fun shortCall(call: String?): String {
        val c = call?.trim()?.uppercase().orEmpty()
        if (c.isEmpty()) return ""
        if (!c.contains('/')) return c
        return c.split('/').maxByOrNull { it.length }?.trim().orEmpty()
    }

    /** `to` 是否在呼叫我（宽松：`to` 含我方短呼号）。 */
    fun isCallingMe(to: String?, myCall: String): Boolean {
        val t = to?.trim()?.uppercase().orEmpty()
        return t.isNotEmpty() && mentions(t, myCall)
    }

    /**
     * 文本里是否出现我方呼号（宽松：含我方短呼号，`F4FSY/P` 与 `F4FSY` 视为同一个人）。
     *
     * 与 [isCallingMe] 同一口径，区别是看**整条报文文本**：地图连线用它判定「报文里有我 → 标红」
     * （docs/Ft8Vox.md）。
     */
    fun mentions(text: String?, myCall: String): Boolean {
        val me = shortCall(myCall)
        return me.isNotEmpty() && text?.uppercase()?.contains(me) == true
    }

    /**
     * `from` 是否是目标台（FT8CN `checkCallsignIsCallTo` 的**对称放宽版**）：
     * 任一方带 `/` 时用 `contains`，否则精确相等。
     *
     * FT8CN 只在**目标**带 `/` 时才用 `contains`；这里改成双向，避免「对方先以裸呼号
     * 出现、后续改用 `/P` 后缀」时被判成陌生人而漏应答（硬要求：不能不答）。
     */
    fun isFrom(from: String?, target: String?): Boolean {
        val f = from?.trim()?.uppercase().orEmpty()
        val t = target?.trim()?.uppercase().orEmpty()
        if (f.isEmpty() || t.isEmpty()) return false
        return if (f.contains('/') || t.contains('/')) f.contains(t) || t.contains(f) else f == t
    }
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
                payload = tail.joinToString(" "),
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

        return ParsedMessage(raw = raw, payload = raw, isFreeText = true)
    }

    private fun parsePayload(raw: String, to: String, from: String, payload: String): ParsedMessage {
        val upper = payload.uppercase()
        return when {
            upper == "RR73" -> ParsedMessage(raw = raw, to = to, from = from, payload = upper, isRr73 = true)
            upper == "RRR" -> ParsedMessage(raw = raw, to = to, from = from, payload = upper, isRoger = true)
            upper == "73" -> ParsedMessage(raw = raw, to = to, from = from, payload = upper, is73 = true)
            ROGER_REGEX.matches(upper) ->
                ParsedMessage(
                    raw = raw, to = to, from = from, payload = upper,
                    isRoger = true, report = upper.drop(1).toIntOrNull(),
                )
            REPORT_REGEX.matches(upper) ->
                ParsedMessage(raw = raw, to = to, from = from, payload = upper, report = upper.toIntOrNull())
            GRID_REGEX.matches(upper) ->
                ParsedMessage(raw = raw, to = to, from = from, payload = upper, grid = upper)
            else -> ParsedMessage(raw = raw, to = to, from = from, payload = upper, isFreeText = true)
        }
    }
}
