package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.grid.Maidenhead

/**
 * 呼号前缀的**紧凑近似**（取首个数字之前的连续字母）。
 *
 * 例：`JA1ABC` → `JA`、`W1AW` → `W`、`F4FSY/P` → `F`。
 * 这不是精确的 DXCC 前缀，只用于「新前缀」高亮的**粗略**判定；
 * 精确到 DXCC 实体 / CQ / ITU 区域的判定见 [Dxcc]（U7）。
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
 * 已通联索引（呼号 / 网格 / 前缀 / DXCC 实体 / CQ / ITU 区域），供显示过滤、颜色高亮与 Call 1st 共用。
 *
 * 网格统一按 **4 字符方格**归并（如 `PM95ab` → `PM95`），与「新网格」的常见口径一致。
 * 实体与区域由 [Dxcc] 按呼号前缀解析（U7 起替代原「紧凑前缀」近似）。
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

    /** 已通联前缀（由呼号近似得到，保留旧口径）。 */
    val prefixes: Set<String> = this.calls.mapNotNull { CallPrefix.of(it) }.toSet()

    /** 已通联 DXCC 实体（规范名去重）。 */
    val entities: Set<String> = this.calls.mapNotNull { Dxcc.resolve(it)?.name }.toSet()

    /** 已通联 CQ 区域。 */
    val cqZones: Set<Int> = this.calls.mapNotNull { Dxcc.resolve(it)?.cqZone }.toSet()

    /** 已通联 ITU 区域。 */
    val ituZones: Set<Int> = this.calls.mapNotNull { Dxcc.resolve(it)?.ituZone }.toSet()

    fun hasWorkedCall(call: String?): Boolean =
        call != null && call.trim().uppercase() in calls

    fun hasWorkedGrid(grid: String?): Boolean {
        val g = Maidenhead.normalize(grid)
        return g.length >= 4 && g.substring(0, 4) in grids
    }

    fun hasWorkedPrefix(call: String?): Boolean =
        CallPrefix.of(call)?.let { it in prefixes } ?: false

    fun hasWorkedEntity(call: String?): Boolean =
        Dxcc.resolve(call)?.name?.let { it in entities } ?: false

    fun hasWorkedCqZone(call: String?): Boolean =
        Dxcc.resolve(call)?.cqZone?.let { it in cqZones } ?: false

    fun hasWorkedItuZone(call: String?): Boolean =
        Dxcc.resolve(call)?.ituZone?.let { it in ituZones } ?: false

    /**
     * 增量并入一个呼号 / 网格，产出新索引（落库后立即生效，见方案 §4.4）。
     *
     * 只并入非空项；呼号按大写、网格按 4 字符方格归并（与构造口径一致）。
     * 索引不可变，调用方用 `_worked.update { it.plus(call, grid) }` 替换。
     */
    fun plus(call: String?, grid: String?): WorkedIndex {
        if (call.isNullOrBlank() && grid.isNullOrBlank()) return this
        return WorkedIndex(
            calls = calls + listOfNotNull(call),
            grids = grids + listOfNotNull(grid),
        )
    }

    companion object {
        val EMPTY = WorkedIndex()
    }
}

/**
 * 解码行的最高优先级高亮类别（docs/Ft8Vox.md 色条）。
 *
 * 优先级（高→低）：正在发射 / 自己发的报文 > 与我有关 > CQ > 已通联 > 重复 > 新网格 >
 * 新 DXCC/ITU > 新呼号 > 新解码。
 */
enum class HighlightRole {
    /**
     * **最高优先级**（黄底黑字）：正在发射的那条报文，以及**自己发的报文**。
     *
     * 「自己发的报文」＝解码行的**发方呼号就是我方呼号**（本地回采：声学耦合 / 监听口，
     * 或对端把我方报文转发回来）。这类行在解码列表里最显眼，一眼就能和别人的信号区分开。
     */
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

    /** 新 DXCC / 新 ITU / 新 CQ 区域 / 新前缀（棕）。 */
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
    /** 新前缀（由呼号近似得到，保留旧口径）。 */
    val newPrefix: Boolean = false,
    /** 新 DXCC 实体（前缀映射）。 */
    val newEntity: Boolean = false,
    /** 新 ITU 区域。 */
    val newItu: Boolean = false,
    /** 新 CQ 区域。 */
    val newCqZone: Boolean = false,
    val worked: Boolean = false,
    val duplicate: Boolean = false,
    val transmitting: Boolean = false,
) {
    /** 是否应显示「新实体」标记点：新 DXCC / ITU / CQ 区域 / 新前缀任一成立。 */
    val hasNewEntityMark: Boolean get() = newEntity || newItu || newCqZone || newPrefix
}

/**
 * 解码行的高亮判定与去重键（纯 Kotlin，可 JVM 单测）。
 *
 * 「高亮与提醒」的开关已取消（docs/Ft8Vox.md）：所有高亮类别**恒启用**，颜色含义改由
 * 设置页的「颜色说明」图例统一解释。
 */
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
    ): DecodeStyle {
        val from = parsed.from
        val toMe = parsed.addressedTo(myCall)
        val entity = from?.let { Dxcc.resolve(it) }
        val newGrid = parsed.grid != null && !worked.hasWorkedGrid(parsed.grid)
        val newPrefix = from != null && !worked.hasWorkedPrefix(from)
        val newEntity = entity != null && entity.name !in worked.entities
        val newItu = entity != null && entity.ituZone !in worked.ituZones
        val newCqZone = entity != null && entity.cqZone !in worked.cqZones
        val workedCall = worked.hasWorkedCall(from)
        val current = from != null &&
            currentQsoCall != null &&
            from.equals(currentQsoCall, ignoreCase = true)
        val transmitting = currentTxText != null &&
            parsed.raw.trim().equals(currentTxText.trim(), ignoreCase = true)
        // 自己发的报文：解码行的**发方就是我方呼号**（本地回采 / 对端转发时会解码到自己）。
        // 按「正在发射」同一档高亮（黄底黑字，最高优先级）；用 CallMatch 比较以兼容复合呼号（`/P`）。
        val fromMe = CallMatch.isFrom(parsed.from, myCall)

        val newCall = from != null && !workedCall

        val role = when {
            transmitting || fromMe -> HighlightRole.TX
            current || toMe -> HighlightRole.TO_ME
            parsed.isCq -> HighlightRole.CQ
            workedCall -> HighlightRole.WORKED
            duplicate -> HighlightRole.DUPLICATE
            newGrid -> HighlightRole.NEW_GRID
            newEntity || newItu || newCqZone || newPrefix -> HighlightRole.NEW_ENTITY
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
            newEntity = newEntity,
            newItu = newItu,
            newCqZone = newCqZone,
            worked = workedCall,
            duplicate = duplicate,
            transmitting = transmitting,
        )
    }
}
