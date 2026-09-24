package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.grid.Maidenhead

/**
 * 网格地图上的一个实时台站点。
 *
 * [lat]/[lon] 取该台站网格的中心；[style] 决定颜色，[snr] 决定点的大小/亮度。
 */
data class LiveSpot(
    val call: String,
    val grid: String,
    val lat: Double,
    val lon: Double,
    val snr: Int,
    val dt: Float,
    val df: Int,
    val utcMs: Long,
    val style: DecodeStyle,
)

/**
 * 由本会话解码构造实时台站（纯 Kotlin，可 JVM 单测）。
 *
 * - 只保留最近 [windowMs] 内的解码；同呼号取最近一次；
 * - 报文无网格时，用 [gridCache]（来自日志等）补全，仍无网格则不上图；
 * - 过滤掉自己的呼号。
 */
object SpotBuilder {

    /** 默认实时窗口：15 分钟。 */
    const val DEFAULT_WINDOW_MS = 15 * 60 * 1000L

    fun build(
        messages: List<DecodeResult>,
        worked: WorkedIndex = WorkedIndex.EMPTY,
        currentQsoCall: String? = null,
        myCall: String = "",
        nowMs: Long = 0L,
        windowMs: Long = DEFAULT_WINDOW_MS,
        gridCache: Map<String, String> = emptyMap(),
        maxSpots: Int = 300,
    ): List<LiveSpot> {
        val me = myCall.trim().uppercase()
        val cutoff = if (nowMs > 0L) nowMs - windowMs else Long.MIN_VALUE
        val byCall = LinkedHashMap<String, LiveSpot>()

        for (m in messages) {
            // slotUtcMs 为 0 表示离线/未知时间，不参与窗口过滤
            if (m.slotUtcMs > 0L && m.slotUtcMs < cutoff) continue
            val p = MessageParser.parse(m.text)
            val from = p.from?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: continue
            if (from == me) continue
            val grid = p.grid ?: gridCache[from] ?: continue
            val center = Maidenhead.center(grid) ?: continue
            val style = DecodeHighlight.classify(p, worked, currentQsoCall, me)
            val spot = LiveSpot(
                call = from,
                grid = Maidenhead.normalize(grid),
                lat = center.first,
                lon = center.second,
                snr = m.snr,
                dt = m.dt,
                df = m.df,
                utcMs = m.slotUtcMs,
                style = style,
            )
            val prev = byCall[from]
            if (prev == null || spot.utcMs >= prev.utcMs) byCall[from] = spot
        }
        return byCall.values.sortedByDescending { it.utcMs }.take(maxSpots)
    }
}
