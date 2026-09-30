package com.example.ft8vox.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 旧版「自动收录 CQ 台」残留条目的清理单测。
 *
 * 锁死的语义：只剔除 `auto_follow_order` 记录（纯自动收录）的呼号，**手动跟踪的台一律保留**；
 * 比较忽略大小写与空白；旧名单里不在跟踪名单中的条目无副作用。
 */
class LegacyFollowCleanupTest {

    @Test
    fun purgesLegacyAutoCollectedCalls() {
        assertEquals(
            setOf("DL1XYZ"),
            purgeLegacyAutoFollow(setOf("W1AW", "JA1ABC", "DL1XYZ"), listOf("W1AW", "JA1ABC")),
        )
    }

    @Test
    fun keepsManualFollows() {
        assertEquals(setOf("W1AW"), purgeLegacyAutoFollow(setOf("W1AW"), emptyList()))
    }

    @Test
    fun matchIgnoresCaseAndBlank() {
        assertEquals(
            emptySet<String>(),
            purgeLegacyAutoFollow(setOf("W1AW"), listOf(" w1aw ", "")),
        )
    }

    @Test
    fun legacyEntriesOutsideFollowListAreHarmless() {
        assertEquals(
            setOf("DL1XYZ"),
            purgeLegacyAutoFollow(setOf("DL1XYZ"), listOf("W1AW", "N0CALL")),
        )
    }

    @Test
    fun emptyLegacyListIsNoop() {
        val follows = setOf("W1AW", "JA1ABC")
        assertSame(follows, purgeLegacyAutoFollow(follows, emptyList()))
    }
}
