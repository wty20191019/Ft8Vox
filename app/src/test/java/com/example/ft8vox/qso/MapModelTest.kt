package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.grid.Maidenhead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 地图数据派生（标记/旗帜/连线）的 JVM 单测。 */
class MapModelTest {

    private fun decoded(
        text: String,
        snr: Int = -10,
        df: Int = 1500,
        slotUtcMs: Long = 0L,
    ) = DecodeResult(text = text, snr = snr, dt = 0f, df = df, score = 20, slotUtcMs = slotUtcMs)

    // ---- square / decodedSquares ----

    @Test
    fun squareNormalizesToFourChars() {
        assertEquals("PM95", MapModel.square("pm95ab"))
        assertEquals("PM95", MapModel.square(" PM95 "))
        assertNull(MapModel.square("PM"))
        assertNull(MapModel.square("XX99"))
    }

    @Test
    fun decodedSquaresCollectsFromMessages() {
        val squares = MapModel.decodedSquares(
            listOf(
                decoded("CQ JA1ABC PM95"),
                decoded("W1AW K1ABC FN42"),
                decoded("K1ABC W1AW -12"),
            ),
        )
        assertEquals(setOf("PM95", "FN42"), squares)
    }

    // ---- decodedGrids ----

    @Test
    fun decodedGridsKeepLatestGridPerCall() {
        val grids = MapModel.decodedGrids(
            listOf(
                decoded("CQ JA1ABC PM95", slotUtcMs = 1000),
                decoded("CQ JA1ABC PM96", slotUtcMs = 2000),
                decoded("W1AW K1ABC FN42", slotUtcMs = 3000),
                decoded("F4FSY JA1ABC -12", slotUtcMs = 4000),
            ),
        )
        assertEquals(mapOf("JA1ABC" to "PM96", "K1ABC" to "FN42"), grids)
    }

    // ---- gridMarkers ----

    @Test
    fun gridTierPriorityConfirmedBeatsWorkedBeatsDecoded() {
        val markers = MapModel.gridMarkers(
            decoded = listOf("PM95", "FN42", "JN25"),
            worked = listOf("PM95ab", "FN42"),
            confirmed = listOf("PM95cd"),
        ).associateBy { it.grid }

        assertEquals(MapTier.CONFIRMED, markers.getValue("PM95").tier)
        assertEquals(MapTier.WORKED, markers.getValue("FN42").tier)
        assertEquals(MapTier.DECODED, markers.getValue("JN25").tier)
    }

    @Test
    fun gridMarkersIgnoreInvalid() {
        val markers = MapModel.gridMarkers(
            decoded = listOf("", "PM", "XX99", "JN25"),
            worked = emptyList(),
            confirmed = emptyList(),
        )
        assertEquals(listOf("JN25"), markers.map { it.grid })
        assertTrue(markers[0].bounds.maxLat > markers[0].bounds.minLat)
    }

    // ---- callMarkers ----

    @Test
    fun callMarkerUsesGridCenter() {
        val markers = MapModel.callMarkers(
            messages = listOf(decoded("CQ JA1ABC PM95", snr = -5, df = 1521, slotUtcMs = 1000)),
            myCall = "F4FSY",
        )
        assertEquals(1, markers.size)
        val m = markers[0]
        assertEquals("JA1ABC", m.call)
        assertEquals("PM95", m.grid)
        assertEquals(-5, m.snr)
        assertEquals(1521, m.df)
        assertTrue(!m.fromPrefix)
    }

    @Test
    fun callMarkerFallsBackToPrefixLocation() {
        val markers = MapModel.callMarkers(
            messages = listOf(decoded("F4FSY JA1ABC -08", slotUtcMs = 1000)),
            myCall = "F4FSY",
        )
        assertEquals(1, markers.size)
        val m = markers[0]
        assertNull(m.grid)
        assertTrue(m.fromPrefix)
        // 日本近似坐标
        assertTrue(m.lat in 30.0..40.0 && m.lon in 130.0..145.0)
    }

    @Test
    fun callMarkerUsesGridReceivedElsewhereInSession() {
        // 本会话里收到过 JA1ABC 的网格（PM95）→ 后面那条没有网格的报文也按 PM95 定位，
        // 不再退到呼号前缀归属地（§30）
        val markers = MapModel.callMarkers(
            messages = listOf(
                decoded("CQ JA1ABC PM95", slotUtcMs = 1000),
                decoded("F4FSY JA1ABC -08", slotUtcMs = 2000),
            ),
            myCall = "F4FSY",
        )
        assertEquals(1, markers.size)
        assertEquals("PM95", markers[0].grid)
        assertFalse(markers[0].fromPrefix)
    }

    @Test
    fun callMarkerSkipsUnknownLocation() {
        val markers = MapModel.callMarkers(
            messages = listOf(decoded("F4FSY ZZ9ZZZ -08", slotUtcMs = 1000)),
            myCall = "F4FSY",
        )
        assertEquals(0, markers.size)
    }

    @Test
    fun callMarkerExcludesOwnCallAndFlaggedWorkedConfirmed() {
        val markers = MapModel.callMarkers(
            messages = listOf(
                decoded("CQ F4FSY JN25"),
                decoded("CQ W1AW FN42", slotUtcMs = 2000),
                decoded("CQ DL1ABC JN48", slotUtcMs = 3000),
            ),
            workedCalls = setOf("W1AW"),
            confirmedCalls = setOf("DL1ABC"),
            myCall = "F4FSY",
        ).associateBy { it.call }
        assertEquals(setOf("W1AW", "DL1ABC"), markers.keys)
        assertEquals(MapTier.WORKED, markers.getValue("W1AW").tier)
        assertEquals(MapTier.CONFIRMED, markers.getValue("DL1ABC").tier)
    }

    @Test
    fun callMarkerDedupesKeepingLatest() {
        val markers = MapModel.callMarkers(
            messages = listOf(
                decoded("CQ JA1ABC PM95", snr = -15, slotUtcMs = 1000),
                decoded("CQ JA1ABC PM95", snr = -3, slotUtcMs = 2000),
            ),
            myCall = "F4FSY",
        )
        assertEquals(1, markers.size)
        assertEquals(-3, markers[0].snr)
        assertEquals(2000L, markers[0].utcMs)
    }

    // ---- cqFlags ----

    @Test
    fun cqFlagsOnlyCqAndExcludesOwn() {
        val flags = MapModel.cqFlags(
            messages = listOf(
                decoded("CQ JA1ABC PM95", slotUtcMs = 1000),
                decoded("CQ F4FSY JN25", slotUtcMs = 2000),
                decoded("W1AW K1ABC FN42", slotUtcMs = 3000),
            ),
            myCall = "F4FSY",
        )
        assertEquals(listOf("JA1ABC"), flags.map { it.call })
    }

    // ---- signalLinks ----

    @Test
    fun signalLinksUseOnlyLastSlotAndPointFromTo() {
        val links = MapModel.signalLinks(
            messages = listOf(
                decoded("CQ DL1ABC JN48", slotUtcMs = 1000),
                decoded("F4FSY JA1ABC -12", slotUtcMs = 2000),
            ),
            myCall = "F4FSY",
            myGrid = "JN25",
            gridCache = mapOf("JA1ABC" to "PM95"),
        )
        assertEquals(1, links.size)
        val l = links[0]
        assertEquals("JA1ABC", l.fromCall)
        assertEquals("F4FSY", l.toCall)
        assertEquals("-12", l.label)
        assertTrue(l.mine) // 发给我的连线 → 地图上标红
    }

    @Test
    fun linkIsMineOnlyWhenItTouchesMyCall() {
        // 报文格式为「收方 发方 内容」：`F4FSY K1ABC RR73` = K1ABC 发给 F4FSY
        val links = MapModel.signalLinks(
            messages = listOf(
                decoded("JA1ABC DL1ABC -12", slotUtcMs = 1000),
                decoded("F4FSY K1ABC RR73", slotUtcMs = 1000),
            ),
            myCall = "F4FSY",
            myGrid = "JN25",
            gridCache = mapOf("JA1ABC" to "PM95", "DL1ABC" to "JN48", "K1ABC" to "FN42"),
        )
        assertEquals(2, links.size)
        assertEquals("DL1ABC", links[0].fromCall)
        assertFalse(links[0].mine) // 别人之间 → 蓝线
        assertEquals("K1ABC", links[1].fromCall)
        assertTrue(links[1].mine) // 发给我的 → 红线
    }

    @Test
    fun outboundLinkFromMeIsDrawnAndAnchoredAtMyGrid() {
        // `JA1ABC F4FSY -12` = F4FSY（我）发给 JA1ABC → 也要画，并且红色
        val links = MapModel.signalLinks(
            messages = listOf(decoded("JA1ABC F4FSY -12", slotUtcMs = 1000)),
            myCall = "F4FSY",
            myGrid = "JN25",
            gridCache = mapOf("JA1ABC" to "PM95"),
        )
        assertEquals(1, links.size)
        val l = links[0]
        assertEquals("F4FSY", l.fromCall)
        assertEquals("JA1ABC", l.toCall)
        assertEquals("-12", l.label)
        assertTrue(l.mine)
        // 我的那一端用 myGrid 中心定位，不是呼号前缀归属地
        val my = Maidenhead.center("JN25")!!
        assertEquals(my.first, l.fromLat, 1e-9)
        assertEquals(my.second, l.fromLon, 1e-9)
    }

    @Test
    fun outboundLinkWithoutMyGridIsSkipped() {
        // 不知道自己网格时宁可不画，也不把「我」摆到呼号前缀归属地
        val links = MapModel.signalLinks(
            messages = listOf(decoded("JA1ABC F4FSY -12", slotUtcMs = 1000)),
            myCall = "F4FSY",
            myGrid = null,
            gridCache = mapOf("JA1ABC" to "PM95"),
        )
        assertEquals(0, links.size)
    }

    @Test
    fun linkEndpointPrefersReceivedGridOverPrefixLocation() {
        // 先收到 JA1ABC 的网格 PM95；之后那条报文里没有网格 → 端点仍落在 PM95，
        // 而不是呼号前缀归属地（日本近似坐标）
        val links = MapModel.signalLinks(
            messages = listOf(
                decoded("CQ JA1ABC PM95", slotUtcMs = 1000),
                decoded("F4FSY JA1ABC -12", slotUtcMs = 2000),
            ),
            myCall = "F4FSY",
            myGrid = "JN25",
        )
        assertEquals(1, links.size)
        val pm95 = Maidenhead.center("PM95")!!
        assertEquals(pm95.first, links[0].fromLat, 1e-9)
        assertEquals(pm95.second, links[0].fromLon, 1e-9)
    }

    @Test
    fun linkMineWhenMessageMentionsMyCallWithPortableSuffix() {
        // 我带 /P 出门：报文里写的是 F4FSY/P，仍算「报文里有我」→ 标红，并用我的网格定位
        val links = MapModel.signalLinks(
            messages = listOf(decoded("JA1ABC F4FSY/P -12", slotUtcMs = 1000)),
            myCall = "F4FSY",
            myGrid = "JN25",
            gridCache = mapOf("JA1ABC" to "PM95"),
        )
        assertEquals(1, links.size)
        assertTrue(links[0].mine)
        val my = Maidenhead.center("JN25")!!
        assertEquals(my.first, links[0].fromLat, 1e-9)
    }

    @Test
    fun signalLinksSkipCqMessages() {
        // CQ 只有发方、没有收方 → 不画连线（即使已知我的网格）
        val links = MapModel.signalLinks(
            messages = listOf(decoded("CQ JA1ABC PM95", slotUtcMs = 1000)),
            myCall = "F4FSY",
            myGrid = "JN25",
        )
        assertEquals(0, links.size)
    }

    @Test
    fun signalLinksSkipsCqWithinSameSlot() {
        // 同一时隙内 CQ 与双方报文混合：只画有收方的那条
        val links = MapModel.signalLinks(
            messages = listOf(
                decoded("CQ DL1ABC JN48", slotUtcMs = 1000),
                decoded("F4FSY JA1ABC -12", slotUtcMs = 1000),
            ),
            myCall = "F4FSY",
            myGrid = "JN25",
            gridCache = mapOf("JA1ABC" to "PM95"),
        )
        assertEquals(1, links.size)
        assertEquals("JA1ABC", links[0].fromCall)
        assertEquals("F4FSY", links[0].toCall)
    }

    @Test
    fun labelRules() {
        assertEquals("-12", MapModel.labelOf(MessageParser.parse("JA1ABC F4FSY -12")))
        assertEquals("+03", MapModel.labelOf(MessageParser.parse("JA1ABC F4FSY +03")))
        assertEquals("R-10", MapModel.labelOf(MessageParser.parse("JA1ABC F4FSY R-10")))
        assertEquals("RR73", MapModel.labelOf(MessageParser.parse("JA1ABC F4FSY RR73")))
        assertEquals("73", MapModel.labelOf(MessageParser.parse("JA1ABC F4FSY 73")))
        // 网格交换只在线上跑方块，不带文字
        assertNull(MapModel.labelOf(MessageParser.parse("JA1ABC F4FSY PM95")))
    }
}
