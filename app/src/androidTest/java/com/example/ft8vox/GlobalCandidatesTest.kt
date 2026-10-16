package com.example.ft8vox

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ft8vox.engine.DecodeParams
import com.example.ft8vox.engine.Ft8Config
import com.example.ft8vox.engine.Ft8Engine
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 「候选上限＝**整个频谱**的名额」两种分配口径的对照（真实录音，instrumented）。
 *
 * - 开（默认）＝**全局择优**：各段先各取宽松池 → 汇总跨段去重 → 按同步得分全局排序取前 N，
 *   再按所属段分发回各段自己的瀑布解码；每个 SIC 趟都重做这一轮。
 * - 关＝**按段均分**：总名额 ÷ 段数，某段配额用尽就不再收。
 *
 * 实测（4 核模拟器 → 4 段；繁忙录音 `191111_110615.wav`，期望清单 22 条）：
 *
 * | 候选上限 | 按段均分 | 全局择优 |
 * | --- | --- | --- |
 * | 20 | 15 | 18 |
 * | 30 | 18 | 20 |
 * | 40 | 20 | 20 |
 * | 60 | 20 | 21 |
 * | 120 | 20 | 21 |
 * | 240 | 20 | 21 |
 *
 * 即：名额越紧优势越大（分段配额被强台占满时，全局排名仍能把名额让给别的段分数更高的弱台）；
 * 名额宽松时也至少持平。代价是解码耗时约翻倍（150 → 300 ms 量级，各段按自己的命中做减谱、
 * 条数更少受「跨段去重」限制），仍只占 15 s 时隙的百分之几。
 *
 * 断言只锁「全局择优不劣于按段均分」这条不变量（具体条数随设备核数/负载浮动，只打日志）。
 */
@RunWith(AndroidJUnit4::class)
class GlobalCandidatesTest {

    private val tag = "GlobalCand"

    /** 读取 androidTest/assets 下的 PCM WAV，返回 [-1,1] 的 float 采样。 */
    private fun loadWavAsset(name: String): FloatArray {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bytes = context.assets.open(name).use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        require(bb.getInt(0) == 0x46464952) { "Not a RIFF file: $name" } // "RIFF"

        var pos = 12
        var dataOffset = -1
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val id = bb.getInt(pos)
            val size = bb.getInt(pos + 4)
            if (id == 0x61746164) { // "data"
                dataOffset = pos + 8
                dataSize = size
                break
            }
            pos += 8 + size + (size and 1)
        }
        require(dataOffset >= 0) { "No data chunk in $name" }

        val n = dataSize / 2 // 16-bit mono
        return FloatArray(n) { i -> bb.getShort(dataOffset + i * 2) / 32768f }
    }

    /** 用「快」预设的其余参数 + 指定候选上限/分配口径解一遍，返回报文明文。 */
    private fun decode(pcm: FloatArray, maxCandidates: Int, global: Boolean): List<String> {
        Ft8Engine.initialize(Ft8Config())
        return try {
            Ft8Engine.setDecodeParams(
                DecodeParams.of(
                    minScore = 10,
                    maxCandidates = maxCandidates,
                    ldpcIterations = 20,
                    maxDecoded = 100,
                    passes = 2,
                    globalCandidates = global,
                ),
            )
            Ft8Engine.processAudio(pcm)
            Ft8Engine.decode()
        } finally {
            Ft8Engine.release()
        }
    }

    @Test
    fun globalSelectionKeepsRecallAtDefaultBudget() {
        val pcm = loadWavAsset("191111_110615.wav")
        val perBand = decode(pcm, maxCandidates = 120, global = false) // 4 段 → 每段 30
        val global = decode(pcm, maxCandidates = 120, global = true)
        Log.i(tag, "budget=120 perBand=${perBand.size} global=${global.size}")
        assertTrue(
            "全局择优不应比按段均分少解出：global=${global.size} perBand=${perBand.size}",
            global.size >= perBand.size,
        )
        // 实测（4 核模拟器）全局择优 21 条；留余量防设备差异
        assertTrue("繁忙时隙至少应解出 19 条，实际 ${global.size}", global.size >= 19)
    }

    @Test
    fun globalSelectionBeatsPerBandWhenBudgetIsTight() {
        val pcm = loadWavAsset("191111_110615.wav")
        val perBand = decode(pcm, maxCandidates = 20, global = false) // 4 段 → 每段 5
        val global = decode(pcm, maxCandidates = 20, global = true)
        Log.i(tag, "budget=20 perBand=${perBand.size} global=${global.size}")
        assertTrue(
            "名额紧张时全局择优不应更差：global=${global.size} perBand=${perBand.size}",
            global.size >= perBand.size,
        )
    }

    @Test
    fun switchOffKeepsSparseSlotBehaviour() {
        val pcm = loadWavAsset("191111_110145.wav")
        val perBand = decode(pcm, maxCandidates = 120, global = false)
        val global = decode(pcm, maxCandidates = 120, global = true)
        Log.i(tag, "sparse budget=120 perBand=${perBand.size} global=${global.size}")
        assertTrue("稀疏时隙两种口径都应解出 2 条：global=$global perBand=$perBand", global.size >= 2)
        assertTrue("稀疏时隙两种口径都应解出 2 条：global=$global perBand=$perBand", perBand.size >= 2)
    }
}
