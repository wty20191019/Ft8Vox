package com.example.ft8vox.qso

/**
 * 报文里「呼号 → 国家（DXCC 实体名）」的配对与**表格对齐**（U8）。
 *
 * 用途：解码行第二行——左端声音频率、中间把每个国家摆到上一行它自己呼号的**正下方**、
 * 右端距离（贴最右由 UI 负责）。
 *
 * 规则：
 * - 顺序＝**报文原文顺序**：定向报文是 `收方 发方`（FT8 约定，见 [MessageParser]），CQ 只有一个呼叫者；
 *   同一呼号只留一次；
 * - 每个条目带 [Tag.col]＝该呼号在报文里的**起始列**（0 起，按字符计），供 [grid] 对齐；
 * - 未收录进 [Dxcc] 的呼号**不产生条目**（宁缺勿错）：第二行就不给它留位置；
 * - 报文里找不到该呼号（例如只有哈希占位）时同样跳过。
 *
 * 纯 Kotlin，无 Android 依赖，便于 JVM 单测。
 */
object CallCountry {

    /** 一个呼号、它所属的 DXCC 实体名，以及它在报文里的起始列（0 起）。 */
    data class Tag(val call: String, val country: String, val col: Int)

    /** 报文里所有能定位到实体的呼号（报文原文顺序，去重）。 */
    fun tags(parsed: ParsedMessage): List<Tag> {
        val tokens = tokensWithOffset(parsed.raw)
        val order = if (parsed.to != null) listOf(parsed.to, parsed.from) else listOf(parsed.from)
        val out = ArrayList<Tag>(2)
        var next = 0
        for (c in order) {
            val call = c?.trim()?.uppercase() ?: continue
            if (call.isEmpty() || out.any { it.call == call }) continue
            val idx = (next until tokens.size)
                .firstOrNull { tokens[it].first.equals(call, ignoreCase = true) } ?: continue
            next = idx + 1
            val country = Dxcc.resolve(call)?.name ?: continue
            out += Tag(call, country, tokens[idx].second)
        }
        return out
    }

    /**
     * 第二行的「国家行」：把每个国家摆到它呼号所在的列上，便于与上一行的呼号对齐。
     *
     * 全角字符（CJK 等）按 **2 格宽**折算，与等宽字体下的实际宽度近似吻合；
     * 两个国家之间至少留 1 格，避免挤在一起。没有可标的国家时返回空串。
     */
    fun grid(parsed: ParsedMessage): String {
        val sb = StringBuilder()
        for ((i, t) in tags(parsed).withIndex()) {
            val pad = if (i == 0) t.col else maxOf(1, t.col - cells(sb))
            repeat(pad) { sb.append(' ') }
            sb.append(t.country)
        }
        return sb.toString()
    }

    /** 按空白切词，并带上每个词在原文里的起始下标。 */
    private fun tokensWithOffset(text: String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            if (i > start) out += text.substring(start, i) to start
        }
        return out
    }

    /** 等宽字体下的显示宽度（格）：CJK / 全角按 2 格，其余 1 格。 */
    private fun cells(s: CharSequence): Int = s.sumOf { if (it.code >= 0x2E80) 2 else 1 }
}
