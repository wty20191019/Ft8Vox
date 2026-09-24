package com.example.ft8vox.qso

import com.example.ft8vox.grid.Maidenhead

/**
 * 呼号前缀的**紧凑近似**（取首个数字之前的连续字母）。
 *
 * 例：`JA1ABC` → `JA`、`W1AW` → `W`、`F4FSY/P` → `F`。
 * 这不是精确的 DXCC 前缀，只用于「新前缀」高亮的近似判定，
 * 精确实体表留待后续阶段（见 `docs/UI-DESIGN.md` 第 9 节）。
 */
object CallPrefix {

    fun of(call: String?): String? {
        val c = call?.trim()?.uppercase()?.substringBefore('/') ?: return null
        val sb = StringBuilder()
        for (ch in c) {
            if (ch in 'A'..'Z') sb.append(ch) else break
        }
        return sb.toString().ifEmpty { null }
    }
}

/**
 * 已通联索引（呼号 / 网格 / 前缀），供显示过滤、颜色高亮与 Call 1st 共用。
 *
 * 网格统一按 **4 字符方格**归并（如 `PM95ab` → `PM95`），与「新网格」的常见口径一致。
 */
class WorkedIndex(
    calls: Collection<String> = emptyList(),
    grids: Collection<String> = emptyList(),
) {
    /** 已通联呼号（大写去重）。 */
    val calls: Set<String> = calls.mapNotNull { it.trim().uppercase().takeIf { c -> c.isNotEmpty() } }.toSet()

    /** 已通联网格（大写、截到 4 字符方格）。 */
    val grids: Set<String> = grids
        .mapNotNull { Maidenhead.normalize(it).takeIf { g -> g.length >= 4 }?.substring(0, 4) }
        .toSet()

    /** 已通联前缀（由呼号近似得到）。 */
    val prefixes: Set<String> = this.calls.mapNotNull { CallPrefix.of(it) }.toSet()

    fun hasWorkedCall(call: String?): Boolean =
        call != null && call.trim().uppercase() in calls

    fun hasWorkedGrid(grid: String?): Boolean {
        val g = Maidenhead.normalize(grid)
        return g.length >= 4 && g.substring(0, 4) in grids
    }

    fun hasWorkedPrefix(call: String?): Boolean =
        CallPrefix.of(call)?.let { it in prefixes } ?: false

    companion object {
        val EMPTY = WorkedIndex()
    }
}

/** 解码行的最高优先级高亮类别。 */
enum class HighlightRole {
    /** 当前 QSO 对手。 */
    CURRENT_QSO,

    /** 发给我（`to == myCall`）。 */
    TO_ME,

    /** 对方网格未通联。 */
    NEW_GRID,

    /** 对面前缀未通联（近似）。 */
    NEW_PREFIX,

    /** 其余（含已通联）。 */
    NORMAL,
}

/**
 * 一条解码的高亮判定结果。
 *
 * [role] 用于决定左侧色条/行底色；各布尔标记用于显示圆点与外层过滤。
 */
data class DecodeStyle(
    val role: HighlightRole,
    val isCq: Boolean = false,
    val toMe: Boolean = false,
    val newGrid: Boolean = false,
    val newPrefix: Boolean = false,
    val worked: Boolean = false,
)

/** 依据 `docs/UI-DESIGN.md` 第 5.1 节的判定顺序给解码行分类。 */
object DecodeHighlight {

    fun classify(
        parsed: ParsedMessage,
        worked: WorkedIndex = WorkedIndex.EMPTY,
        currentQsoCall: String? = null,
        myCall: String = "",
    ): DecodeStyle {
        val from = parsed.from
        val toMe = parsed.addressedTo(myCall)
        val newGrid = parsed.grid != null && !worked.hasWorkedGrid(parsed.grid)
        val newPrefix = from != null && !worked.hasWorkedPrefix(from)
        val workedCall = worked.hasWorkedCall(from)
        val current = from != null &&
            currentQsoCall != null &&
            from.equals(currentQsoCall, ignoreCase = true)

        val role = when {
            current -> HighlightRole.CURRENT_QSO
            toMe -> HighlightRole.TO_ME
            newGrid -> HighlightRole.NEW_GRID
            newPrefix -> HighlightRole.NEW_PREFIX
            else -> HighlightRole.NORMAL
        }
        return DecodeStyle(
            role = role,
            isCq = parsed.isCq,
            toMe = toMe,
            newGrid = newGrid,
            newPrefix = newPrefix,
            worked = workedCall,
        )
    }
}
