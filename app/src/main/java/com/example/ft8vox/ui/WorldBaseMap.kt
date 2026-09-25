package com.example.ft8vox.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.ft8vox.grid.MapProjection
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 底图分块标识：层级 + 块坐标（[WorldBaseMap.BLOCK] 网格）。 */
internal data class MapBlock(val level: Int, val bx: Int, val by: Int) {
    val key: Long get() = (level.toLong() shl 40) or (bx.toLong() shl 20) or by.toLong()
}

private const val TAG = "WorldBaseMap"

/**
 * 离线卫星底图：**单文件** Web Mercator 世界图（`assets/map/world_z5.jpg`，8192×8192，z5）。
 *
 * 整图 8192² × 4B = 268 MB 无法一次性解码，故用 [BitmapRegionDecoder] 只解码可见区域，
 * 并按「层级 + 块」缓存（`inSampleSize = 2^level` 得到 z0–z5 的天然多级缩放）。
 *
 * 首次使用时把 asset 复制到 `cacheDir`（APK 内资产不可随机定位），之后复用。
 */
internal object WorldBaseMap {
    const val ASSET = "map/world_z5.jpg"

    /** 底图边长（像素）：z5 全球 = 32×32 × 256 = 8192。 */
    const val SIZE = 8192

    /** 最高层级：z5（1 张图 = 1:1 原生分辨率）。 */
    const val MAX_LEVEL = 5

    /** 单个解码块的边长（按层级折算后的像素）。 */
    const val BLOCK = 512

    /** 解码块缓存上限（保障内存有界，ARGB_8888 下约 16 MB）。 */
    const val CACHE_LIMIT = 16

    /** 打开底图解码器（失败返回 null，UI 回退纯色底图）。 */
    fun open(context: Context): BitmapRegionDecoder? = runCatching {
        val file = File(context.cacheDir, "world_z5.jpg")
        if (file.length() < 1024L) {
            val tmp = File(context.cacheDir, "world_z5.jpg.tmp")
            context.assets.open(ASSET).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
        BitmapRegionDecoder.newInstance(file.absolutePath, false)
    }.onFailure { Log.w(TAG, "离线底图不可用，退回纯色底图", it) }.getOrNull()

    /** 当前缩放应使用的层级（0 = 原图最清晰，[MAX_LEVEL] = 最粗）。 */
    fun levelFor(scale: Double): Int {
        if (scale <= 0.0) return MAX_LEVEL
        val ideal = SIZE / scale
        val level = if (ideal <= 1.0) 0 else (ln(ideal) / ln(2.0)).roundToInt()
        return level.coerceIn(0, MAX_LEVEL)
    }

    /** 当前视口需要解码/绘制的块。 */
    fun visibleBlocks(p: MapProjection, viewW: Double, viewH: Double): List<MapBlock> {
        val level = levelFor(p.scale)
        val dim = SIZE shr level
        val perAxis = (dim + BLOCK - 1) / BLOCK
        val u0 = (p.centerU - (viewW / 2.0) / p.scale).coerceIn(0.0, 1.0)
        val u1 = (p.centerU + (viewW / 2.0) / p.scale).coerceIn(0.0, 1.0)
        val v0 = (p.centerV - (viewH / 2.0) / p.scale).coerceIn(0.0, 1.0)
        val v1 = (p.centerV + (viewH / 2.0) / p.scale).coerceIn(0.0, 1.0)
        if (u1 <= u0 || v1 <= v0) return emptyList()
        val bx0 = ((u0 * dim) / BLOCK).toInt().coerceIn(0, perAxis - 1)
        val bx1 = (((u1 * dim) - 1.0).toInt() / BLOCK).coerceIn(0, perAxis - 1)
        val by0 = ((v0 * dim) / BLOCK).toInt().coerceIn(0, perAxis - 1)
        val by1 = (((v1 * dim) - 1.0).toInt() / BLOCK).coerceIn(0, perAxis - 1)
        val out = ArrayList<MapBlock>((bx1 - bx0 + 1) * (by1 - by0 + 1))
        for (by in by0..by1) {
            for (bx in bx0..bx1) out += MapBlock(level, bx, by)
        }
        return out
    }
}

/** 底图解码块的内存 LRU 缓存（仅在主线程增删，解码在 [Dispatchers.Default]）。 */
internal class WorldBaseMapState(private val decoder: BitmapRegionDecoder) {
    private val bitmaps = mutableStateMapOf<Long, ImageBitmap>()
    private val order = ArrayDeque<Long>()

    /** 已解码好的块（未就绪返回 null）。读取该 State 会随解码完成自动触发重绘。 */
    fun bitmap(block: MapBlock): ImageBitmap? = bitmaps[block.key]

    /** 确保 [blocks] 都已解码（缺的按序解码，超出 [WorldBaseMap.CACHE_LIMIT] 的按 LRU 淘汰）。 */
    suspend fun ensure(blocks: List<MapBlock>) {
        for (b in blocks) {
            if (bitmaps.containsKey(b.key)) continue
            val img = withContext(Dispatchers.Default) { decode(b) } ?: continue
            bitmaps[b.key] = img
            order.addLast(b.key)
            while (order.size > WorldBaseMap.CACHE_LIMIT) {
                bitmaps.remove(order.removeFirst())
            }
        }
    }

    /** 释放 native 解码器与缓存。 */
    fun close() {
        runCatching { decoder.recycle() }
        bitmaps.clear()
        order.clear()
    }

    private fun decode(b: MapBlock): ImageBitmap? {
        val dim = WorldBaseMap.SIZE shr b.level
        val x = b.bx * WorldBaseMap.BLOCK
        val y = b.by * WorldBaseMap.BLOCK
        val w = minOf(WorldBaseMap.BLOCK, dim - x)
        val h = minOf(WorldBaseMap.BLOCK, dim - y)
        if (w <= 0 || h <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = 1 shl b.level }
        val region = Rect(x shl b.level, y shl b.level, (x + w) shl b.level, (y + h) shl b.level)
        val bitmap = runCatching { decoder.decodeRegion(region, options) }
            .onFailure { Log.w(TAG, "decodeRegion 失败 $region：${it.message}") }
            .getOrNull() ?: return null
        return bitmap.asImageBitmap()
    }
}
