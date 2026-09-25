package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.grid.Maidenhead

/**
 * 地图标记的三种状态（new_ui.md §4.2/§4.3）。
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

/** CQ 红旗（new_ui.md §4.4）。 */
data class CqFlag(
    val call: String,
    val lat: Double,
    val lon: Double,
    val snr: Int,
    val utcMs: Long,
)

/** 信号连线（new_ui.md §4.5）：方向由 [fromCall] 指向 [toCall]。 */
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
     * 呼号无网格时用 [gridCache]（来自日志）补全，仍无则用呼号前缀归属地近似坐标。
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
        val byCall = LinkedHashMap<String, CallMarker>()
        for (m in messages) {
            if (m.slotUtcMs > 0L && m.slotUtcMs < cutoff) continue
            val p = MessageParser.parse(m.text)
            val from = p.from?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: continue
            if (from == me) continue
            val r = resolve(from, p.grid, gridCache) ?: continue
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

    /** 解码到的 CQ 红旗；同一呼号取最近一次，过滤掉自己。 */
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
        val byCall = LinkedHashMap<String, CqFlag>()
        for (m in messages) {
            if (m.slotUtcMs > 0L && m.slotUtcMs < cutoff) continue
            val p = MessageParser.parse(m.text)
            if (!p.isCq) continue
            val from = p.from?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: continue
            if (from == me) continue
            val r = resolve(from, p.grid, gridCache) ?: continue
            val flag = CqFlag(from, r.lat, r.lon, m.snr, m.slotUtcMs)
            val prev = byCall[from]
            if (prev == null || flag.utcMs >= prev.utcMs) byCall[from] = flag
        }
        return byCall.values.sortedByDescending { it.utcMs }.take(maxFlags)
    }

    /**
     * 信号连线：**只取最近一个时隙**的解码，且**只画收发双方都在的报文**（如 `A B -12`）。
     *
     * 方向为 `发方 → 收方`；**CQ 报文不连线** —— CQ 只有发方、没有收方（对端位置用红旗表示，
     * 见 [cqFlags]），所以 `CQ …` 会被跳过，避免一堆线全汇到「我」身上。
     * 端点位置优先用报文网格，其次 [gridCache]，最后呼号前缀归属地；
     * 收方是「我」时用 [myGrid]（避免把「我」定位到呼号前缀归属地）。
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
        val out = ArrayList<SignalLink>()
        for (m in messages) {
            if (m.slotUtcMs != slot) continue
            val p = MessageParser.parse(m.text)
            val from = p.from?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: continue
            if (from == me) continue
            val fromLoc = resolve(from, p.grid, gridCache) ?: continue
            // CQ：无收方，不画连线（CQ 位置另有红旗标记）
            val to = p.to?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: continue
            val toLat: Double
            val toLon: Double
            if (to == me && mine != null) {
                // 收方是我：用我的网格，避免把「我」定位到呼号前缀归属地
                toLat = mine.first
                toLon = mine.second
            } else {
                val toLoc = resolve(to, null, gridCache) ?: continue
                toLat = toLoc.lat
                toLon = toLoc.lon
            }
            out.add(
                SignalLink(
                    fromCall = from,
                    toCall = to,
                    fromLat = fromLoc.lat,
                    fromLon = fromLoc.lon,
                    toLat = toLat,
                    toLon = toLon,
                    label = labelOf(p),
                    utcMs = slot,
                ),
            )
            if (out.size >= maxLinks) break
        }
        return out
    }

    /** 连线内容（new_ui.md §4.5）：报告数字 / `RR73` / `73`；其余返回 null（移动方块）。 */
    fun labelOf(p: ParsedMessage): String? = when {
        p.isRr73 -> "RR73"
        p.is73 -> "73"
        p.report != null -> (if (p.isRoger) "R" else "") + MessageParser.formatReport(p.report)
        else -> null
    }

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
