package com.example.ft8vox.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.ft8vox.grid.MapProjection
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** 解码块缓存上限（保障内存有界）。当前视口需要的块会被「钉住」不淘汰，见 [WorldBaseMapState.evict]。 */
    const val CACHE_LIMIT = 24

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

/**
 * 底图解码器的**应用级单例** + 解码块 LRU 缓存。
 *
 * 关键设计（都为了「切页不卡、快速切页不崩」）：
 * - 解码器**应用生命周期内只打开一次、永不 `recycle()`**。此前每次进入地图页都会
 *   `produceState` 新建解码器、离开时 `DisposableEffect` 里 `recycle()`；而
 *   `ensure()` 的解码跑在 [Dispatchers.Default] 上无法被协程取消打断（`decodeRegion`
 *   是阻塞 JNI），于是「快速切页」时 `recycle()` 与正在执行的 `decodeRegion` 并发 →
 *   native use-after-free（`SIGSEGV`）。永不 recycle 从根上消除该竞态，且切换页面后
 *   再回来无需重新解码整屏底图，明显更顺。
 * - [ensure] 用 [ensureMutex] 串行化；解码在后台**纯计算**，Compose 状态只在持有锁的
 *   调用方线程上修改；协程被取消只是丢弃结果，不会留下半更新状态。
 * - 解码器访问统一走 [decoderLock]，任何路径都不会在解码中释放它。
 */
internal object WorldBaseMapState {
    private val decoderLock = Any()
    private val prepareMutex = Mutex()
    private val ensureMutex = Mutex()

    @Volatile
    private var decoder: BitmapRegionDecoder? = null

    @Volatile
    private var opened = false

    /** `null` = 尚未尝试打开；`true`/`false` = 可用/不可用（UI 据此决定是否回退纯色底图）。 */
    var ready by mutableStateOf<Boolean?>(null)
        private set

    private val bitmaps = mutableStateMapOf<Long, ImageBitmap>()
    private val order = ArrayDeque<Long>()

    /** 已解码好的块（未就绪返回 null）。读取该 State 会随解码完成自动触发重绘。 */
    fun bitmap(block: MapBlock): ImageBitmap? = bitmaps[block.key]

    /** 打开解码器（幂等，只真正打开一次）。 */
    suspend fun prepare(context: Context) {
        if (opened) return
        prepareMutex.withLock {
            if (opened) return
            val d = withContext(Dispatchers.IO) {
                synchronized(decoderLock) {
                    if (decoder == null) decoder = WorldBaseMap.open(context)
                    decoder
                }
            }
            ready = d != null
            opened = true
        }
    }

    /** 确保 [blocks] 都已解码（缺的按序解码；淘汰时**不淘汰** [blocks] 内的块）。 */
    suspend fun ensure(blocks: List<MapBlock>) {
        if (blocks.isEmpty()) return
        ensureMutex.withLock {
            val pinned = HashSet<Long>(blocks.size * 2)
            for (b in blocks) pinned += b.key
            val missing = blocks.filter { it.key !in bitmaps }
            if (missing.isEmpty()) return
            // 纯解码，不触碰 Compose 状态；协程被取消只是丢弃结果
            val decoded = withContext(Dispatchers.Default) {
                missing.mapNotNull { b -> decode(b)?.let { b.key to it } }
            }
            for ((key, img) in decoded) {
                if (key in bitmaps) continue
                bitmaps[key] = img
                order.addLast(key)
                evict(pinned)
            }
        }
    }

    /**
     * LRU 淘汰，但**当前视口需要的块（[pinned]）永不淘汰**。
     *
     * 否则会出现这样一个 bug：一次 [ensure] 要解码的块数超过上限时，先解码的块会被后解码的挤掉，
     * 而 `needed` 不变又不会触发重新解码 → 视口顶部永久缺一块底图（真机截图的现象）。
     */
    private fun evict(pinned: Set<Long>) {
        val it = order.iterator()
        while (order.size > WorldBaseMap.CACHE_LIMIT && it.hasNext()) {
            val key = it.next()
            if (key in pinned) continue
            it.remove()
            bitmaps.remove(key)
        }
    }

    private fun decode(b: MapBlock): ImageBitmap? = synchronized(decoderLock) {
        val d = decoder ?: return@synchronized null
        val dim = WorldBaseMap.SIZE shr b.level
        val x = b.bx * WorldBaseMap.BLOCK
        val y = b.by * WorldBaseMap.BLOCK
        val w = minOf(WorldBaseMap.BLOCK, dim - x)
        val h = minOf(WorldBaseMap.BLOCK, dim - y)
        if (w <= 0 || h <= 0) return@synchronized null
        val options = BitmapFactory.Options().apply { inSampleSize = 1 shl b.level }
        val region = Rect(x shl b.level, y shl b.level, (x + w) shl b.level, (y + h) shl b.level)
        val bitmap = runCatching { d.decodeRegion(region, options) }
            .onFailure { Log.w(TAG, "decodeRegion 失败 $region：${it.message}") }
            .getOrNull() ?: return@synchronized null
        bitmap.asImageBitmap()
    }
}

