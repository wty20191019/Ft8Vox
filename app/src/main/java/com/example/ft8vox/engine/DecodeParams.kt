package com.example.ft8vox.engine

/**
 * 解码器调优参数（对应 native 的 `ftx_decode_params_t`）。
 *
 * 与 [Ft8Config] 不同：这些参数**不改变 STFT/monitor 结构**，可在接收运行中热更新
 * （见 [AudioEngine.setDecodeParams] / [Ft8Engine.setDecodeParams]）；而 [Ft8Config] 里的
 * 频率范围与 OSR 需要重建引擎。
 *
 * 默认值＝设置的「快」预设，照搬 FT8CN「快速解码」（10 / 120 / 20 / 100）；
 * 该默认只在设置下发前的极短窗口内生效，正常运行时用的是设置页的值。
 */
data class DecodeParams(
    /** Costas 同步最低得分，越高候选越少、越快，漏解风险越大。 */
    val minScore: Int = 10,
    /**
     * **整个频谱**的候选上限（名额不属于任何一段，见 [globalCandidates]）。
     *
     * 分段并行时默认按「全局择优」分配：各段先各取一个宽松池，汇总跨段去重后按同步
     * 得分全局排序取前 N，再分发回各段解码；关掉该开关则退回「按段均分」。
     */
    val maxCandidates: Int = 120,
    /** LDPC 最大迭代次数，越高越慢但弱信号解码率更高。 */
    val ldpcIterations: Int = 20,
    /** 单时隙最多解出的报文条数。 */
    val maxDecoded: Int = 100,
    /**
     * 多趟减谱重解（SIC）趟数：1 = 单趟（不做减谱重解），2 = 默认。
     *
     * 见 docs/Ft8Vox.md：解完一趟后把已解报文在瀑布幅度上抹掉、再搜一趟，
     * 把被强信号压住的弱信号挖出来；趟数每加一趟解码耗时约合再乘一次。
     */
    val passes: Int = 2,
    /**
     * 候选名额的分配口径（仅频率分段并行时有意义）：
     * - `true`（默认）：**全局择优** —— 名额不属于任何一段，跨段去重（同一信号最多留
     *   3 个代表）后按同步得分全局排序取前 N，再按所属段分发解码；每个 SIC 趟都重做。
     * - `false`：**按段均分** —— 总名额均分到各段，某段配额用尽就不再收了。
     *
     * 属策略开关（设置页「候选全局择优」），不是「快」预设的数值项。
     */
    val globalCandidates: Boolean = true,
) {
    /** 各字段的安全范围（与 native `sanitize_decode_params` 对应）。 */
    companion object {
        val MIN_SCORE_RANGE = 4..40
        val MAX_CANDIDATES_RANGE = 20..500
        val LDPC_RANGE = 5..60
        val MAX_DECODED_RANGE = 5..100
        /** SIC 趟数上与 native 的 `K_MAX_DECODE_PASSES` 一致。 */
        val PASSES_RANGE = 1..4

        /** 钳制到安全范围，避免越界值传到 native。 */
        fun of(
            minScore: Int,
            maxCandidates: Int,
            ldpcIterations: Int,
            maxDecoded: Int,
            passes: Int = 2,
            globalCandidates: Boolean = true,
        ): DecodeParams = DecodeParams(
            minScore = minScore.coerceIn(MIN_SCORE_RANGE),
            maxCandidates = maxCandidates.coerceIn(MAX_CANDIDATES_RANGE),
            ldpcIterations = ldpcIterations.coerceIn(LDPC_RANGE),
            maxDecoded = maxDecoded.coerceIn(MAX_DECODED_RANGE),
            passes = passes.coerceIn(PASSES_RANGE),
            globalCandidates = globalCandidates,
        )
    }

    /** 钳制到安全范围（布尔开关无需钳制，原样传递）。 */
    fun clamped(): DecodeParams =
        of(minScore, maxCandidates, ldpcIterations, maxDecoded, passes, globalCandidates)
}

/**
 * 分段并行解码的硬件自适应参数。
 *
 * 段数 = min(核数, 4)：每段独立做一次完整 STFT，段数越多 FFT 重复开销越大，
 * 实测 4 段在 4 核上召回不降、耗时约降 1.3×；核更多时靠「每段多线程」补足并行度。
 * 线程总数 = min(核数, 8)：由 native 按 `线程总数 / 段数` 均分到各段（每段至少 1）。
 */
internal fun decodeBandsFor(cores: Int): Int = cores.coerceIn(1, 4)

internal fun decodeThreadsFor(cores: Int): Int = cores.coerceIn(1, 8)

/** native `ft8api.c` 分段并行用的常量：改 native 必须同步这里（单测会卡住漂移）。 */
internal const val BAND_MIN_WIDTH_HZ = 200.0

/** native `FT8_MAX_BANDS`。 */
internal const val MAX_BANDS = 8

/**
 * 解码并行度：**频率分段数 × 每段线程数**（= 线程总预算）。
 *
 * 全部按 CPU 核数自适应、**没有设置项**，这里只是把 native `ft8_decode_slot` 的分支规则
 * 照抄一遍给界面显示用（顶栏读数 + 设置页「解码并行」一行）。段数还受总带宽限制
 * （单段最小 [BAND_MIN_WIDTH_HZ]、最多 [MAX_BANDS] 段），所以把频率范围收窄到 400 Hz
 * 以内时实际会退化成 1 段（不分段）。
 */
data class DecodeParallel(
    /** 频率分段数；1 = 不分段（走 `decode_slot_single`）。 */
    val bands: Int,
    /** 每段的解码线程数。 */
    val threadsPerBand: Int,
    /** 线程总预算（= [decodeThreadsFor]）。 */
    val totalThreads: Int,
) {
    /** 顶栏读数紧凑文案：`4段×2`；不分段时 `8线程`。 */
    val shortLabel: String
        get() = if (bands <= 1) "${totalThreads}线程" else "${bands}段×${threadsPerBand}"

    /** 设置页完整文案：`4 段 × 2 线程 = 8`；不分段时 `1 段 × 8 线程`。 */
    val label: String
        get() = if (bands <= 1) "1 段 × $totalThreads 线程"
        else "$bands 段 × $threadsPerBand 线程 = $totalThreads"

    /** 「候选上限」按段均分后每段拿到的名额（余数给低频段，故各段最多差 1）。 */
    fun candidatesPerBand(totalCap: Int): Int =
        if (bands <= 1) totalCap else (totalCap / bands).coerceAtLeast(1)
}

/**
 * 由频率范围 + CPU 核数推算解码并行度（分支顺序照 native `ft8_decode_slot`）。
 *
 * native 先按带宽钳段数（[BAND_MIN_WIDTH_HZ] / [MAX_BANDS]），再判「不足 2 段」退回单段；
 * 单段时线程预算整份用在候选并行上。
 */
fun decodeParallelism(fMinHz: Int, fMaxHz: Int, cores: Int): DecodeParallel {
    val totalThreads = decodeThreadsFor(cores)
    val span = (fMaxHz - fMinHz).toDouble()
    if (span <= 1.0) return DecodeParallel(1, totalThreads, totalThreads)
    var bands = decodeBandsFor(cores)
    val maxBySpan = (span / BAND_MIN_WIDTH_HZ).toInt().coerceAtLeast(1)
    if (bands > maxBySpan) bands = maxBySpan
    if (bands > MAX_BANDS) bands = MAX_BANDS
    if (bands < 2) return DecodeParallel(1, totalThreads, totalThreads)
    return DecodeParallel(bands, (totalThreads / bands).coerceAtLeast(1), totalThreads)
}
