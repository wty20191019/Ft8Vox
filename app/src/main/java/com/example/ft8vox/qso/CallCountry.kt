package com.example.ft8vox.qso

/**
 * 报文里「呼号 → 国家（DXCC 实体名）」的配对与**表格对齐**（U8）。
 *
 * 用途：解码行第二行——左端声音频率、中间把每个国家摆到上一行它自己呼号的**正下方**、
 * 右端距离（贴最右由 UI 负责）。
 *
 * 判据与报文类型无关（定向报文 / CQ / 自由文本一视同仁）：**扫报文里的词**，
 * 凡「既有字母又有数字」、不是网格 / 报告 / `73`（见 [MessageParser.isNonCallToken]）、
 * 且 [Dxcc] 前缀表认得出的词，就当台站呼号标上国家。这样：
 * - 普通报文 `VK4BLE OH8JK R-17` → 两个呼号各标各的（`R-17` 不算）；
 * - 含未解析哈希的 `LZ365BM <...> 73`（解析器会当自由文本，`to`/`from` 为空）也能标出保加利亚；
 * - 特殊台呼号 `LZ365BM` / `OR18OSB` / `HF19NY`（前缀段更长，标准呼号正则不认）同样能标。
 *
 * 纯 Kotlin，无 Android 依赖，便于 JVM 单测。
 */
object CallCountry {

    /** 一个呼号、它所属的 DXCC 实体名，以及它在报文里的起始列（0 起）。 */
    data class Tag(val call: String, val country: String, val col: Int)

    /** 报文里所有能认出实体的呼号词（报文原文顺序，去重）。 */
    fun tags(text: String): List<Tag> {
        val out = ArrayList<Tag>(2)
        for ((token, col) in tokensWithOffset(text)) {
            val call = token.uppercase()
            if (call.none { it.isLetter() } || call.none { it.isDigit() }) continue
            if (MessageParser.isNonCallToken(call)) continue
            if (out.any { it.call == call }) continue
            val country = Dxcc.resolve(call)?.name ?: continue
            out += Tag(call, country, col)
        }
        return out
    }

    /**
     * 第二行的「国家行」：把每个国家摆到它呼号所在的列上，便于与上一行的呼号对齐。
     *
     * 全角字符（CJK 等）按 **2 格宽**折算，与等宽字体下的实际宽度近似吻合；
     * 两个国家之间至少留 1 格，避免挤在一起。没有可标的国家时返回空串。
     */
    fun grid(text: String): String {
        val sb = StringBuilder()
        for ((i, t) in tags(text).withIndex()) {
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
