package com.example.ft8vox.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地图文字防重叠（[selectLabels]）的 JVM 单测（docs/UI-MOBILE.md §31）。
 *
 * 口径：**按输入顺序（＝优先级）依次落位，与已落位标签重叠的直接跳过**；
 * 被挤掉的台站只是没文字，点/旗照旧。
 */
class MapLabelsTest {

    private fun box(left: Float, top: Float, w: Float = 30f, h: Float = 10f) =
        LabelBox(left, top, left + w, top + h)

    @Test
    fun nonOverlappingLabelsAllKept() {
        val keep = selectLabels(listOf(box(0f, 0f), box(0f, 20f), box(0f, 40f)))
        assertTrue(keep.all { it })
    }

    @Test
    fun laterOverlappingLabelIsDropped() {
        // 第 1 条优先级最高：留着；第 2 条与它相交：跳过
        val keep = selectLabels(listOf(box(0f, 0f), box(10f, 2f)))
        assertTrue(keep[0])
        assertFalse(keep[1])
    }

    @Test
    fun highPriorityWinsRegardlessOfPosition() {
        // 顺序即优先级：先来的（更重要的）留下
        val keep = selectLabels(listOf(box(10f, 2f), box(0f, 0f)))
        assertTrue(keep[0])
        assertFalse(keep[1])
    }

    @Test
    fun touchingLabelsAreKeptWithoutGap() {
        // 边贴边（不相交）默认保留
        assertTrue(selectLabels(listOf(box(0f, 0f), box(30f, 0f))).all { it })
    }

    @Test
    fun gapPushesApartNearbyLabels() {
        // 留白 4px 时，间距 2px 的也算「挤」
        val boxes = listOf(box(0f, 0f), box(32f, 0f))
        assertTrue(selectLabels(boxes).all { it })
        assertFalse(selectLabels(boxes, gap = 4f)[1])
    }

    @Test
    fun droppedLabelDoesNotBlockTheNextOne() {
        // 第 2 条被跳过，第 3 条与第 1 条不相交 → 仍然保留（跳过者不占位）
        val keep = selectLabels(listOf(box(0f, 0f), box(10f, 2f), box(0f, 40f)))
        assertTrue(keep[0])
        assertFalse(keep[1])
        assertTrue(keep[2])
    }

    @Test
    fun overlapsIsSymmetricAndEdgeAware() {
        val a = box(0f, 0f, w = 10f, h = 10f)
        val b = box(9f, 9f, w = 10f, h = 10f)
        val c = box(10f, 0f, w = 10f, h = 10f)
        assertTrue(a.overlaps(b))
        assertTrue(b.overlaps(a))
        assertFalse(a.overlaps(c))
    }

    @Test
    fun emptyInputIsEmpty() {
        assertTrue(selectLabels(emptyList()).isEmpty())
    }
}
