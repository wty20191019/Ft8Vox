package com.example.ft8vox.ui

/**
 * 地图文字占位矩形（纯 float，JVM 单测友好；Compose 侧自己换算）。
 *
 * 用 `Box` 之外的自定义类型，是为了让防重叠布局能在 JVM 单测里直接跑，不依赖 Compose / Android。
 */
data class LabelBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** 与另一矩形是否相交；[gap] 为额外留白（让贴在一起的文字也自动让位）。 */
    fun overlaps(other: LabelBox, gap: Float = 0f): Boolean =
        left < other.right + gap && other.left < right + gap &&
            top < other.bottom + gap && other.top < bottom + gap
}

/**
 * 地图文字防重叠（docs/UI-MOBILE.md §31）：按**输入顺序**（＝优先级从高到低）依次落位，
 * 与已落位标签相交的直接**跳过不画**。
 *
 * 密集是地图页最主要的问题：同一片区域里几十个呼号文字会糊成一团。这里不做重排（手机上
 * 挪来挪去反而更乱），而是「**保留优先级高的，挤掉优先级低的**」——被挤掉的台站仍然有点/旗，
 * 点一下照样能看详情。
 *
 * 调用方负责排优先级：**我的位置 > 选中的台站 > CQ 台（新→旧） > 其余呼号（新→旧）**。
 *
 * @param boxes 已按优先级排序的标签矩形
 * @param gap 额外留白（px），两标签间距小于它也算「挤」
 * @return 与 [boxes] 等长的布尔数组，true = 保留（画文字）
 */
fun selectLabels(boxes: List<LabelBox>, gap: Float = 0f): BooleanArray {
    val keep = BooleanArray(boxes.size)
    for (i in boxes.indices) {
        val b = boxes[i]
        var ok = true
        for (j in 0 until i) {
            if (keep[j] && b.overlaps(boxes[j], gap)) {
                ok = false
                break
            }
        }
        keep[i] = ok
    }
    return keep
}
