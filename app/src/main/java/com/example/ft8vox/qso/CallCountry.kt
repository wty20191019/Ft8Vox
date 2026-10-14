package com.example.ft8vox.qso

/**
 * 报文里「呼号 → 国家（DXCC 实体名）」的配对（U8）。
 *
 * 用途：解码行第二行 `声音频率 + 每个呼号·国家 + 距离`，以及日志卡片呼号旁的国家标注。
 *
 * 规则：
 * - 顺序＝**报文原文顺序**：定向报文是 `收方 发方`（FT8 约定，见 [MessageParser]），CQ 只有一个呼叫者；
 *   同一呼号只留一次；
 * - 未收录进 [Dxcc] 的呼号**不产生条目**（宁缺勿错）：调用方只在有国家可标时才连呼号一起列，
 *   避免第二行被裸呼号撑长；
 * - `CQ JA1ABC PM95` 这类只有一个呼号的报文，就只标那一个。
 *
 * 纯 Kotlin，无 Android 依赖，便于 JVM 单测。
 */
object CallCountry {

    /** 一个呼号与它所属的 DXCC 实体名。 */
    data class Tag(val call: String, val country: String)

    /** 报文里所有能定位到实体的呼号（报文原文顺序，去重）。 */
    fun tags(parsed: ParsedMessage): List<Tag> {
        val order = if (parsed.to != null) listOf(parsed.to, parsed.from) else listOf(parsed.from)
        val out = ArrayList<Tag>(2)
        for (c in order) {
            val call = c?.trim()?.uppercase() ?: continue
            if (call.isEmpty() || out.any { it.call == call }) continue
            val country = Dxcc.resolve(call)?.name ?: continue
            out += Tag(call, country)
        }
        return out
    }

    /** 解码行第二行用的片段，如 `VK4BLE·澳大利亚 OH8JK·芬兰`；没有可标的国家时返回空串。 */
    fun segment(parsed: ParsedMessage): String =
        tags(parsed).joinToString(" ") { "${it.call}·${it.country}" }
}
