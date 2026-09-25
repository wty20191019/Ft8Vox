package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
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

/**
 * 解码行的最高优先级高亮类别（new_ui.md §3.3 色条）。
 *
 * 优先级（高→低）：正在发射 > 与我有关 > CQ > 已通联 > 重复 > 新网格 > 新 DXCC/ITU > 新呼号 > 新解码。
 */
enum class HighlightRole {
    /** 正在发射（黄底黑字）。 */
    TX,

    /** 与我有关 / 当前 QSO 对手（蓝）。 */
    TO_ME,

    /** CQ（橙）。 */
    CQ,

    /** 已通联（红，删除线）。 */
    WORKED,

    /** 重复解码（灰）。 */
    DUPLICATE,

    /** 新网格（紫）。 */
    NEW_GRID,

    /** 新 DXCC / 新 ITU（棕；当前以「新前缀」近似，精确实体表见 U7）。 */
    NEW_ENTITY,

    /** 新呼号（粉）。 */
    NEW_CALL,

    /** 其余新解码（绿）。 */
    NORMAL,
}

/**
 * 一条解码的高亮判定结果。
 *
 * [role] 用于决定左侧色条/行底色；各布尔标记用于显示标记点与详情半屏。
 */
data class DecodeStyle(
    val role: HighlightRole,
    val isCq: Boolean = false,
    val toMe: Boolean = false,
    /** 是否为当前 QSO 对手。 */
    val current: Boolean = false,
    val newCall: Boolean = false,
    val newGrid: Boolean = false,
    /** 新前缀（近似「新 DXCC / 新 ITU」）。 */
    val newPrefix: Boolean = false,
    val worked: Boolean = false,
    val duplicate: Boolean = false,
    val transmitting: Boolean = false,
)

/**
 * 「高亮与提醒」开关（new_ui.md §6.4）。
 *
 * 关闭某类后，该类不再作为**最高优先级角色**（顺序回退到下一类）；
 * 对应的数据标记点也随之隐藏。默认全开，保持既有行为。
 */
data class HighlightPrefs(
    /** 新呼号。 */
    val newCall: Boolean = true,
    /** 新网格。 */
    val newGrid: Boolean = true,
    /** 新 DXCC / 新前缀（近似）。 */
    val newEntity: Boolean = true,
)

/** 解码行的高亮判定与去重键（纯 Kotlin，可 JVM 单测）。 */
object DecodeHighlight {

    /** 解码行的稳定键：同一文本 + 同一时隙视为同一行。 */
    fun rowKey(text: String, slotUtcMs: Long): String = "${text.trim()}@$slotUtcMs"

    /**
     * 计算「重复解码」行的键集合：同一文本在更早时隙已出现过（按时间从旧到新扫描）。
     *
     * 返回的键与 [rowKey] 一致，供 UI 直接比对。
     */
    fun duplicateRowKeys(messages: List<DecodeResult>): Set<String> {
        val seen = HashSet<String>()
        val dup = HashSet<String>()
        for (m in messages.sortedBy { it.slotUtcMs }) {
            if (!seen.add(m.text.trim())) dup.add(rowKey(m.text, m.slotUtcMs))
        }
        return dup
    }

    fun classify(
        parsed: ParsedMessage,
        worked: WorkedIndex = WorkedIndex.EMPTY,
        currentQsoCall: String? = null,
        myCall: String = "",
        duplicate: Boolean = false,
        currentTxText: String? = null,
        prefs: HighlightPrefs = HighlightPrefs(),
    ): DecodeStyle {
        val from = parsed.from
        val toMe = parsed.addressedTo(myCall)
        val rawNewGrid = parsed.grid != null && !worked.hasWorkedGrid(parsed.grid)
        val rawNewPrefix = from != null && !worked.hasWorkedPrefix(from)
        val workedCall = worked.hasWorkedCall(from)
        val current = from != null &&
            currentQsoCall != null &&
            from.equals(currentQsoCall, ignoreCase = true)
        val transmitting = currentTxText != null &&
            parsed.raw.trim().equals(currentTxText.trim(), ignoreCase = true)

        // 被关闭的高亮类别不参与角色判定，也不显示标记点
        val newGrid = rawNewGrid && prefs.newGrid
        val newPrefix = rawNewPrefix && prefs.newEntity
        val newCall = from != null && !workedCall && prefs.newCall

        val role = when {
            transmitting -> HighlightRole.TX
            current || toMe -> HighlightRole.TO_ME
            parsed.isCq -> HighlightRole.CQ
            workedCall -> HighlightRole.WORKED
            duplicate -> HighlightRole.DUPLICATE
            newGrid -> HighlightRole.NEW_GRID
            newPrefix -> HighlightRole.NEW_ENTITY
            newCall -> HighlightRole.NEW_CALL
            else -> HighlightRole.NORMAL
        }
        return DecodeStyle(
            role = role,
            isCq = parsed.isCq,
            toMe = toMe,
            current = current,
            newCall = newCall,
            newGrid = newGrid,
            newPrefix = newPrefix,
            worked = workedCall,
            duplicate = duplicate,
            transmitting = transmitting,
        )
    }
}
