package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.grid.Maidenhead

/**
 * 地图标记的三种状态（docs/UI.md §2.4）。
 *
 * 优先级：**确认 > 通联 > 解码**。蓝色由本会话解码派生（重启即清空）；
 * 黄色/红色来自日志（已通联 / 已确认）。
 */
enum class MapTier(val label: String) {
    DECODED("解码"),
    WORKED("通联"),
    CONFIRMED("确认"),
}

/** 网格标记：4 字符方格 + 归属状态。 */
data class GridMarker(
    val grid: String,
    val bounds: Maidenhead.Bounds,
    val tier: MapTier,
)

/** 呼号标记：有网格取网格中心，无网格取 [CallLocation] 的前缀归属地中心。 */
data class CallMarker(
    val call: String,
    val lat: Double,
    val lon: Double,
    val tier: MapTier,
    val snr: Int,
    /** 最近一次解码的音频频偏（Hz），用于应答时对准频率。 */
    val df: Int = 0,
    val utcMs: Long,
    /** 解析到的网格（无则为 null）。 */
    val grid: String?,
    /** true 表示坐标来自呼号前缀近似，而非真实网格。 */
    val fromPrefix: Boolean,
)

/** CQ 旗（docs/UI.md §2.4）：形状=旗表示「在 CQ」，旗面颜色在绘制时取该台的类别色（§31）。 */
data class CqFlag(
    val call: String,
    val lat: Double,
    val lon: Double,
    val snr: Int,
    val utcMs: Long,
)

/** 信号连线（docs/UI.md §2.4）：方向由 [fromCall] 指向 [toCall]。 */
data class SignalLink(
    val fromCall: String,
    val toCall: String?,
    val fromLat: Double,
    val fromLon: Double,
    val toLat: Double,
    val toLon: Double,
    /** 连线内容：报告数字 / `RR73` / `73`；null 表示只画一个移动方块。 */
    val label: String?,
    val utcMs: Long,
    /**
     * 是否**与我有关**（收发任一方是我方呼号，docs/UI-MOBILE.md §29）。
     *
     * 地图上把这类连线的**线身与文字都画成红色**，一眼看出哪条是本台正在进行的 QSO。
     */
    val mine: Boolean = false,
)

/**
 * 地图页的数据派生（纯 Kotlin，可 JVM 单测）。
 *
 * 把「本会话解码 + 历史日志」整理成地图层的四类元素：网格标记、呼号标记、
 * CQ 旗帜、信号连线。绘制与交互由 `GridMap` / `GridScreen` 负责。
 */
object MapModel {

    /** 呼号标记/CQ 旗帜的默认观察窗口（仅作为可选项，地图页默认用整会话）。 */
    const val DEFAULT_WINDOW_MS = 15 * 60 * 1000L

    /** 归一化为 4 字符方格；不足 4 字符或非法返回 null。 */
    fun square(grid: String?): String? {
        val g = Maidenhead.normalize(grid)
        if (g.length < 4) return null
        val sq = g.substring(0, 4)
        return if (Maidenhead.isValid(sq)) sq else null
    }

    /** 本会话解码报文里出现过的网格（4 字符方格集合，供蓝色标记）。 */
    fun decodedSquares(messages: List<DecodeResult>): Set<String> =
        messages.mapNotNull { square(MessageParser.parse(it.text).grid) }.toSet()

    /**
     * 网格标记。
     *
     * @param decoded 本会话解码到的网格（蓝）
     * @param worked 日志已通联网格（黄）
     * @param confirmed 日志已确认（QSL/LoTW）网格（红）
     */
    fun gridMarkers(
        decoded: Collection<String>,
        worked: Collection<String>,
        confirmed: Collection<String>,
    ): List<GridMarker> {
        val d = decoded.mapNotNull { square(it) }.toSet()
        val w = worked.mapNotNull { square(it) }.toSet()
        val c = confirmed.mapNotNull { square(it) }.toSet()
        val all = LinkedHashSet<String>().apply {
            addAll(d)
            addAll(w)
            addAll(c)
        }
        return all.mapNotNull { sq ->
            val bounds = Maidenhead.parse(sq) ?: return@mapNotNull null
            GridMarker(sq, bounds, tierOf(sq, d, w, c))
        }
    }

    /**
     * 呼号标记。
     *
     * 定位优先用**报文里的网格**，其次**本会话解码收到的网格**（[decodedGrids]），再其次 [gridCache]
     * （来自日志），最后才是呼号前缀归属地近似坐标（§30）。
     * 同一呼号取最近一次解码；过滤掉自己。
     */
    fun callMarkers(
        messages: List<DecodeResult>,
        workedCalls: Set<String> = emptySet(),
        confirmedCalls: Set<String> = emptySet(),
        myCall: String = "",
        nowMs: Long = 0L,
        windowMs: Long = DEFAULT_WINDOW_MS,
        gridCache: Map<String, String> = emptyMap(),
        maxMarkers: Int = 400,
    ): List<CallMarker> {
        val me = myCall.trim().uppercase()
        val cutoff = if (nowMs > 0L && windowMs > 0L) nowMs - windowMs else Long.MIN_VALUE
        val cache = gridLookup(messages, gridCache)
        val byCall = LinkedHashMap<String, CallMarker>()
        for (m in messages) {
            if (m.slotUtcMs > 0L && m.slotUtcMs < cutoff) continue
            val p = MessageParser.parse(m.text)
            val from = p.from?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: continue
            if (from == me) continue
            val r = resolve(from, p.grid, cache) ?: continue
            val tier = when {
                from in confirmedCalls -> MapTier.CONFIRMED
                from in workedCalls -> MapTier.WORKED
                else -> MapTier.DECODED
            }
            val marker = CallMarker(
                call = from,
                lat = r.lat,
                lon = r.lon,
                tier = tier,
                snr = m.snr,
                df = m.df,
                utcMs = m.slotUtcMs,
                grid = r.grid,
                fromPrefix = r.fromPrefix,
            )
            val prev = byCall[from]
            if (prev == null || marker.utcMs >= prev.utcMs) byCall[from] = marker
        }
        return byCall.values.sortedByDescending { it.utcMs }.take(maxMarkers)
    }

    /**
     * 解码到的 CQ 台（同一呼号取最近一次，过滤掉自己）。
     *
     * 绘制时这些呼号用**旗子**当标记（替代圆点），旗面颜色取该台的类别色（§31）。
     */
    fun cqFlags(
        messages: List<DecodeResult>,
        myCall: String = "",
        nowMs: Long = 0L,
        windowMs: Long = DEFAULT_WINDOW_MS,
        gridCache: Map<String, String> = emptyMap(),
        maxFlags: Int = 200,
    ): List<CqFlag> {
        val me = myCall.trim().uppercase()
        val cutoff = if (nowMs > 0L && windowMs > 0L) nowMs - windowMs else Long.MIN_VALUE
        val cache = gridLookup(messages, gridCache)
        val byCall = LinkedHashMap<String, CqFlag>()
        for (m in messages) {
            if (m.slotUtcMs > 0L && m.slotUtcMs < cutoff) continue
            val p = MessageParser.parse(m.text)
            if (!p.isCq) continue
            val from = p.from?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: continue
            if (from == me) continue
            val r = resolve(from, p.grid, cache) ?: continue
            val flag = CqFlag(from, r.lat, r.lon, m.snr, m.slotUtcMs)
            val prev = byCall[from]
            if (prev == null || flag.utcMs >= prev.utcMs) byCall[from] = flag
        }
        return byCall.values.sortedByDescending { it.utcMs }.take(maxFlags)
    }

    /**
     * 信号连线：**只取最近一个时隙**的解码，且**只画收发双方都在的报文**（如 `A B -12`）。
     *
     * 方向为 `发方 → 收方`，**两个方向都画**：对端发给我的（收方是我）与我发出去的（发方是我，
     * 本地回采 / 对端转发时会解码到自己）都会出现在地图上。
     * **CQ 报文不连线** —— CQ 只有发方、没有收方（对端位置用旗子表示，见 [cqFlags]），
     * 所以 `CQ …` 会被跳过，避免一堆线全汇到「我」身上。
     *
     * 端点位置（docs/UI-MOBILE.md §30）：**本条报文的网格 > 本会话解码收到的网格 > 日志网格 >
     * 呼号前缀归属地**（前三级都是真收到过的网格，最后一级只是近似，只做兜底）；
     * 任一端是「我」时一律用 [myGrid]（避免把「我」定位到呼号前缀归属地）。`myGrid` 为空又不认识
     * 那一端时，这条线画不出来（宁可不画，也不把我摆到几百公里外）。
     *
     * `mine = true` 的判据是**报文里出现我的呼号**（[CallMatch.mentions]，兼顾 `/P` 这类复合呼号），
     * 地图上把这条连线的线身与文字都画成红色。
     */
    fun signalLinks(
        messages: List<DecodeResult>,
        myCall: String = "",
        myGrid: String? = null,
        gridCache: Map<String, String> = emptyMap(),
        maxLinks: Int = 100,
    ): List<SignalLink> {
        val slot = messages.filter { it.slotUtcMs > 0L }.maxOfOrNull { it.slotUtcMs } ?: return emptyList()
        val me = myCall.trim().uppercase()
        val mine = myGrid?.let { Maidenhead.center(it) }
        val cache = gridLookup(messages, gridCache)
        val out = ArrayList<SignalLink>()
        for (m in messages) {
            if (m.slotUtcMs != slot) continue
            val p = MessageParser.parse(m.text)
            val from = p.from?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: continue
            // CQ：无收方，不画连线（CQ 位置另有红旗标记）
            val to = p.to?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: continue
            val fromIsMe = CallMatch.isFrom(from, me)
            val toIsMe = CallMatch.isCallingMe(to, me)
            val fromPos = if (fromIsMe) {
                mine
            } else {
                resolve(from, p.grid, cache)?.let { it.lat to it.lon }
            } ?: continue
            val toPos = if (toIsMe) {
                mine
            } else {
                resolve(to, null, cache)?.let { it.lat to it.lon }
            } ?: continue
            out.add(
                SignalLink(
                    fromCall = from,
                    toCall = to,
                    fromLat = fromPos.first,
                    fromLon = fromPos.second,
                    toLat = toPos.first,
                    toLon = toPos.second,
                    label = labelOf(p),
                    utcMs = slot,
                    mine = CallMatch.mentions(m.text, myCall),
                ),
            )
            if (out.size >= maxLinks) break
        }
        return out
    }

    /** 连线内容（docs/UI.md §2.4）：报告数字 / `RR73` / `73`；其余返回 null（移动方块）。 */
    fun labelOf(p: ParsedMessage): String? = when {
        p.isRr73 -> "RR73"
        p.is73 -> "73"
        p.report != null -> (if (p.isRoger) "R" else "") + MessageParser.formatReport(p.report)
        else -> null
    }

    /**
     * 本会话解码里**收到过**的网格：呼号 → 最近一次报文里的网格（docs/UI-MOBILE.md §30）。
     *
     * 报文里的网格永远属于**发方**（`CQ JA1ABC PM95` 是 JA1ABC 在 PM95），所以按 `from` 归档；
     * 同一呼号后收到的覆盖先收到的（对方换格子/带 `/P` 出门时以最新的为准）。
     * 只收 `Maidenhead.isValid` 的表，垃圾串不会污染坐标。
     */
    fun decodedGrids(messages: List<DecodeResult>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in messages.sortedBy { it.slotUtcMs }) {
            val p = MessageParser.parse(m.text)
            val from = p.from?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: continue
            val g = p.grid?.takeIf { Maidenhead.isValid(it) } ?: continue
            out[from] = g
        }
        return out
    }

    /**
     * 端点定位用的网格表：**本会话解码收到的网格 > 日志网格**（后者用 [MapModel] 的入参 `gridCache` 传入）。
     *
     * 两级都是「真收到过的网格」；都查不到时才由 [resolve] 退到呼号前缀归属地近似坐标。
     */
    private fun gridLookup(messages: List<DecodeResult>, gridCache: Map<String, String>): Map<String, String> =
        if (gridCache.isEmpty()) decodedGrids(messages) else gridCache + decodedGrids(messages)

    private fun tierOf(sq: String, d: Set<String>, w: Set<String>, c: Set<String>): MapTier = when {
        sq in c -> MapTier.CONFIRMED
        sq in w -> MapTier.WORKED
        else -> MapTier.DECODED
    }

    /** 解析结果：坐标 + 网格（前缀近似时为 null）+ 是否来自前缀。 */
    private data class Resolved(val lat: Double, val lon: Double, val grid: String?, val fromPrefix: Boolean)

    private fun resolve(call: String, msgGrid: String?, cache: Map<String, String>): Resolved? {
        val g = msgGrid?.takeIf { Maidenhead.isValid(it) }
            ?: cache[call]?.takeIf { Maidenhead.isValid(it) }
        if (g != null) {
            val c = Maidenhead.center(g) ?: return null
            return Resolved(c.first, c.second, Maidenhead.normalize(g), fromPrefix = false)
        }
        val place = CallLocation.locate(call) ?: return null
        return Resolved(place.lat, place.lon, grid = null, fromPrefix = true)
    }
}
