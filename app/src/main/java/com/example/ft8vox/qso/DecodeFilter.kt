package com.example.ft8vox.qso

import com.example.ft8vox.data.settings.CallFirstMode
import com.example.ft8vox.engine.DecodeResult

/** 解码列表的显示过滤条件（不丢弃解码结果，仅影响展示与 Call 1st 挑台）。 */
data class DecodeFilterState(
    /** 只看 CQ。 */
    val cqOnly: Boolean = false,
    /** 排除已通联呼号（依据 Room 索引）。 */
    val excludeWorked: Boolean = false,
    /** 呼号/前缀搜索串，逗号或空白分隔多个关键词，任一命中即可。 */
    val query: String = "",
)

/**
 * 解码列表的显示过滤（纯 Kotlin，可 JVM 单测）。
 *
 * 与 Call 1st 共用同一份判定，避免「过滤里看不到、自动却应答」的不一致。
 */
object DecodeFilter {

    /** 拆分搜索串为关键词（大写）。 */
    fun tokens(query: String): List<String> =
        query.split(',', ';', ' ', '\t')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }

    /** 该报文是否通过过滤。 */
    fun matches(
        parsed: ParsedMessage,
        filter: DecodeFilterState,
        worked: WorkedIndex = WorkedIndex.EMPTY,
        myCall: String = "",
    ): Boolean {
        val toks = tokens(filter.query)
        if (toks.isNotEmpty()) {
            val hay = listOfNotNull(parsed.from, parsed.to, parsed.grid, myCall.ifEmpty { null })
                .joinToString(" ")
                .uppercase()
            if (toks.none { hay.contains(it) }) return false
        }
        if (filter.cqOnly && !parsed.isCq) return false
        if (filter.excludeWorked && worked.hasWorkedCall(parsed.from)) return false
        return true
    }

    /** 便捷入口：直接传原始文本。 */
    fun matches(
        text: String,
        filter: DecodeFilterState,
        worked: WorkedIndex = WorkedIndex.EMPTY,
        myCall: String = "",
    ): Boolean = matches(MessageParser.parse(text), filter, worked, myCall)
}

/** Call 1st 挑出的候选台站。 */
data class CallFirstCandidate(
    val call: String,
    val grid: String?,
    val snr: Int,
    val df: Int,
)

/**
 * Call 1st 自动应答的候选挑选（纯 Kotlin，可 JVM 单测）。
 *
 * 只考虑「是 CQ、非自己、通过显示过滤、非已通联」的台站；同一时隙内同呼号去重后，
 * 按策略取最强或最先出现的一条。
 */
object CallFirstSelector {

    fun pick(
        messages: List<DecodeResult>,
        mode: CallFirstMode,
        filter: DecodeFilterState = DecodeFilterState(),
        worked: WorkedIndex = WorkedIndex.EMPTY,
        myCall: String = "",
    ): CallFirstCandidate? {
        if (mode == CallFirstMode.OFF) return null

        val candidates = ArrayList<CallFirstCandidate>()
        val seen = HashSet<String>()
        for (m in messages) {
            val p = MessageParser.parse(m.text)
            if (!p.isCq) continue
            val from = p.from ?: continue
            if (from.equals(myCall, ignoreCase = true)) continue
            if (!DecodeFilter.matches(p, filter, worked, myCall)) continue
            if (!seen.add(from.uppercase())) continue
            candidates.add(CallFirstCandidate(from.uppercase(), p.grid, m.snr, m.df))
        }
        if (candidates.isEmpty()) return null
        return when (mode) {
            CallFirstMode.STRONGEST -> candidates.maxByOrNull { it.snr }
            CallFirstMode.FIRST -> candidates.first()
            CallFirstMode.OFF -> null
        }
    }
}
