package com.example.ft8vox.qso

/**
 * FT8CN 的**报文序号判据**（`GeneralVariables.checkFunOrder` `:391`、`checkFun1..5` `:399-445`）
 * 的 Kotlin 移植 —— 本机 QSO 层的骨架就是这套序号。
 *
 * 序号即 FT8CN 的 `functionOrder`，也是本机的 `QsoEngine` 状态：
 *
 * | 序号 | 报文 | 语义 |
 * | :--: | --- | --- |
 * | 1 | `<对方> <我> [网格]` | 应答 CQ / 首次呼叫（载荷为空也算） |
 * | 2 | `<对方> <我> <±dd>` | 信号报告 |
 * | 3 | `<对方> <我> R<±dd>` | 收到报告的确认 |
 * | 4 | `<对方> <我> RR73` / `RRR` | 收尾 |
 * | 5 | `<对方> <我> 73` | 收尾 |
 * | 6 | `CQ [修饰符] <我> [网格]` | 主叫 |
 *
 * **推进规则**（照 FT8CN `FT8TransmitSignal.parseMessageToFunction` `:862`）：
 * 我下一条要发的报文序号 ＝ **对方这条报文的序号 + 1**；收到 5（对方 73）即通联完成。
 *
 * 判据顺序照 FT8CN：先看是不是 CQ → 6；否则按 **5→4→3→2→1** 依次命中；全不中返回 [NONE]。
 * 因为 `RRR` / `73` 先被 4 / 5 吃掉，所以它们不会落进 2（纯报告）。
 *
 * 判据只看**载荷原文**（[ParsedMessage.payload]，即 FT8CN 的 `extraInfo`），
 * 与报文的收方 / 发方无关 —— 「是不是发给我的」由 `CallMatch` 另判。
 */
object FunctionOrder {

    /** 解析不出序号（FT8CN 返回 `-1`）。 */
    const val NONE: Int = -1

    /** CQ 的序号。 */
    const val CQ: Int = 6

    /** `[A-Z][A-Z][0-9][0-9]`：4 位网格（照 FT8CN `checkFun1`）。 */
    private val GRID4 = Regex("^[A-Z][A-Z][0-9][0-9]$")

    /**
     * 6 位扩展网格。**本机在 FT8CN 之上多认这一种**：FT8CN `checkFun1` 只认 4 位，
     * 收到 6 位网格时解析不出序号（不推进）；本机解析器本来就认 6 位网格
     * （[MessageParser] `GRID_REGEX`），若这里不认，对方发 6 位网格就会让整段 QSO 停住。
     */
    private val GRID6 = Regex("^[A-Z][A-Z][0-9][0-9][A-X][A-X]$")

    /** 从解析后的报文取序号（CQ 先判，照 FT8CN `checkFunOrder`）。 */
    fun of(parsed: ParsedMessage): Int = if (parsed.isCq) CQ else byExtraInfo(parsed.payload)

    /** 从载荷原文取序号（照 FT8CN `checkFunOrderByExtraInfo`）。 */
    fun byExtraInfo(extraInfo: String): Int {
        val e = extraInfo.trim().uppercase()
        if (checkFun5(e)) return 5
        if (checkFun4(e)) return 4
        if (checkFun3(e)) return 3
        if (checkFun2(e)) return 2
        if (checkFun1(e)) return 1
        return NONE
    }

    /** 网格报告（4 位网格 / 6 位扩展网格；空载荷也算「还在等 / 还在报网格」）。 */
    fun checkFun1(e: String): Boolean {
        val s = e.trim().uppercase()
        return (GRID4.matches(s) || GRID6.matches(s)) && s != "RR73" || s.isEmpty()
    }

    /** 信号报告（如 `-10`；`73` 不算，归序号 5）。 */
    fun checkFun2(e: String): Boolean {
        val s = e.trim()
        if (s.length < 2) return false
        val v = s.toIntOrNull() ?: return false
        return v != 73
    }

    /** 带 R 的信号报告（如 `R-10`；`RR73` / `RRR` 不算，归序号 4）。 */
    fun checkFun3(e: String): Boolean {
        val s = e.trim().uppercase()
        if (s.length < 3) return false
        if (s[0] != 'R' || s[1] == 'R') return false
        return s.substring(1).toIntOrNull() != null
    }

    /** `RR73` / `RRR`。 */
    fun checkFun4(e: String): Boolean {
        val s = e.trim().uppercase()
        return s == "RR73" || s == "RRR"
    }

    /** `73`。 */
    fun checkFun5(e: String): Boolean = e.trim().uppercase() == "73"
}
