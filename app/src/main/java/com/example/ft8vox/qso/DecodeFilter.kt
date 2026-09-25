package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult

/**
 * 解码列表的筛选项（new_ui.md §3.2）。
 *
 * [ALL] 为互斥项：选中它表示不再过滤；选中其余任意项会自动取消 [ALL]，其余项之间为**多选并集**。
 */
enum class DecodeFilterTag(val label: String) {
    ALL("全部"),
    TO_ME("与我有关"),
    CQ("CQ"),
    REPLY("回复"),
    SEVENTY_THREE("73"),
    WORKED("已通联"),
}

/** 解码列表的显示过滤条件（不丢弃解码结果，仅影响展示与 Call 1st 挑台）。 */
data class DecodeFilterState(
    /** 已选中的筛选项；空集表示「一个都没开」，列表显示空态提示。 */
    val tags: Set<DecodeFilterTag> = setOf(DecodeFilterTag.ALL),
    /** 呼号/前缀搜索串，逗号或空白分隔多个关键词，任一命中即可。 */
    val query: String = "",
    /** 被用户忽略的呼号（右滑忽略 / 长按菜单忽略），一律不显示。 */
    val ignoredCalls: Set<String> = emptySet(),
) {
    /** 是否一个筛选项都没开（用于空态提示）。 */
    val isEmptySelection: Boolean get() = tags.isEmpty()

    /** 点击某个 chip 后的新状态：全部互斥，其余多选。 */
    fun toggle(tag: DecodeFilterTag): DecodeFilterState = when {
        tag == DecodeFilterTag.ALL ->
            copy(tags = if (DecodeFilterTag.ALL in tags) emptySet() else setOf(DecodeFilterTag.ALL))
        DecodeFilterTag.ALL in tags -> copy(tags = setOf(tag))
        tag in tags -> copy(tags = tags - tag)
        else -> copy(tags = tags + tag)
    }

    /** 是否选中了指定 chip（默认展示态为 [ALL]）。 */
    fun isSelected(tag: DecodeFilterTag): Boolean = tag in tags
}

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

    /** 该报文是否属于「回复」（推进 QSO 的定向报文，不含 CQ 与 73）。 */
    fun isReply(parsed: ParsedMessage): Boolean =
        !parsed.isCq && !is73(parsed) &&
            (parsed.report != null || parsed.isRoger || (parsed.grid != null && parsed.to != null))

    /** 该报文是否属于「73」类（73 / RR73 / RRR）。 */
    fun is73(parsed: ParsedMessage): Boolean =
        parsed.is73 || parsed.isRr73 || (parsed.isRoger && parsed.report == null)

    /** 该报文是否通过过滤。 */
    fun matches(
        parsed: ParsedMessage,
        filter: DecodeFilterState,
        worked: WorkedIndex = WorkedIndex.EMPTY,
        myCall: String = "",
    ): Boolean {
        // 忽略名单最优先：被忽略的呼号一律不显示
        val from = parsed.from?.trim()?.uppercase()
        if (from != null && from in filter.ignoredCalls) return false

        val toks = tokens(filter.query)
        if (toks.isNotEmpty()) {
            val hay = listOfNotNull(parsed.from, parsed.to, parsed.grid, myCall.ifEmpty { null })
                .joinToString(" ")
                .uppercase()
            if (toks.none { hay.contains(it) }) return false
        }

        val tags = filter.tags
        if (tags.isEmpty()) return false
        if (DecodeFilterTag.ALL in tags) return true

        // 多选并集
        if (DecodeFilterTag.TO_ME in tags && parsed.addressedTo(myCall)) return true
        if (DecodeFilterTag.CQ in tags && parsed.isCq) return true
        if (DecodeFilterTag.REPLY in tags && isReply(parsed)) return true
        if (DecodeFilterTag.SEVENTY_THREE in tags && is73(parsed)) return true
        if (DecodeFilterTag.WORKED in tags && worked.hasWorkedCall(parsed.from)) return true
        return false
    }

    /** 便捷入口：直接传原始文本。 */
    fun matches(
        text: String,
        filter: DecodeFilterState,
        worked: WorkedIndex = WorkedIndex.EMPTY,
        myCall: String = "",
    ): Boolean = matches(MessageParser.parse(text), filter, worked, myCall)

    /** 各 chip 右侧角标计数（忽略名单已被排除）。 */
    fun counts(
        messages: List<DecodeResult>,
        worked: WorkedIndex = WorkedIndex.EMPTY,
        myCall: String = "",
        ignoredCalls: Set<String> = emptySet(),
    ): Map<DecodeFilterTag, Int> {
        var all = 0
        var toMe = 0
        var cq = 0
        var reply = 0
        var s73 = 0
        var workedN = 0
        for (m in messages) {
            val p = MessageParser.parse(m.text)
            val from = p.from?.trim()?.uppercase()
            if (from != null && from in ignoredCalls) continue
            all++
            if (p.addressedTo(myCall)) toMe++
            if (p.isCq) cq++
            if (isReply(p)) reply++
            if (is73(p)) s73++
            if (worked.hasWorkedCall(p.from)) workedN++
        }
        return mapOf(
            DecodeFilterTag.ALL to all,
            DecodeFilterTag.TO_ME to toMe,
            DecodeFilterTag.CQ to cq,
            DecodeFilterTag.REPLY to reply,
            DecodeFilterTag.SEVENTY_THREE to s73,
            DecodeFilterTag.WORKED to workedN,
        )
    }
}


