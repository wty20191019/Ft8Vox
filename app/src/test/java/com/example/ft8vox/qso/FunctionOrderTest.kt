package com.example.ft8vox.qso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FT8CN 报文序号判据（`GeneralVariables.checkFunOrder` `:391`、`checkFun1..5` `:399-445`）的单测。
 *
 * 这套序号是本机 QSO 层的骨架（`QsoEngine` 的 `order`＝「我下一条要发的报文序号」）。
 */
class FunctionOrderTest {

    private fun order(text: String): Int = FunctionOrder.of(MessageParser.parse(text))

    @Test
    fun mapsSixFunctionOrders() {
        assertEquals(6, order("CQ F4FSY JN25"))
        assertEquals(6, order("CQ DX F4FSY JN25"))
        assertEquals(1, order("F4FSY GJ0KYZ JN25"))
        assertEquals(2, order("F4FSY GJ0KYZ -12"))
        assertEquals(3, order("F4FSY GJ0KYZ R-12"))
        assertEquals(4, order("F4FSY GJ0KYZ RR73"))
        assertEquals(4, order("F4FSY GJ0KYZ RRR"))
        assertEquals(5, order("F4FSY GJ0KYZ 73"))
    }

    @Test
    fun seventythreeIsFun5NotFun2() {
        // 判据顺序照 FT8CN：5 → 4 → 3 → 2 → 1，故 73 先被 5 吃掉
        assertEquals(5, FunctionOrder.byExtraInfo("73"))
        assertEquals(4, FunctionOrder.byExtraInfo("RR73"))
        assertEquals(4, FunctionOrder.byExtraInfo("RRR"))
    }

    @Test
    fun emptyPayloadCountsAsGridStep() {
        // 照 FT8CN `checkFun1`：空载荷也算序号 1
        assertEquals(1, FunctionOrder.byExtraInfo(""))
        assertEquals(1, FunctionOrder.byExtraInfo("   "))
    }

    @Test
    fun sixCharGridAlsoCountsAsGridStep() {
        // 本机在 FT8CN 之上多认 6 位扩展网格（否则对方发 6 位网格会卡住整段 QSO）
        assertEquals(1, FunctionOrder.byExtraInfo("JN25AB"))
    }

    @Test
    fun freeTextHasNoOrder() {
        assertEquals(FunctionOrder.NONE, order("F4FSY GJ0KYZ 599 599"))
        assertEquals(FunctionOrder.NONE, order("F4FSY GJ0KYZ HELLO WORLD"))
        assertEquals(FunctionOrder.NONE, FunctionOrder.byExtraInfo("JN2"))
    }

    @Test
    fun predicatesMatchFt8cn() {
        assertTrue(FunctionOrder.checkFun5("73"))
        assertTrue(FunctionOrder.checkFun4("RR73"))
        assertTrue(FunctionOrder.checkFun4("rrr")) // 大小写不敏感
        assertTrue(FunctionOrder.checkFun3("R-10"))
        assertTrue(FunctionOrder.checkFun2("-10"))
        assertTrue(FunctionOrder.checkFun1("JN25"))
        assertFalse(FunctionOrder.checkFun2("73")) // 73 归序号 5
        assertFalse(FunctionOrder.checkFun3("RR73")) // 归序号 4
        assertTrue(FunctionOrder.checkFun3("R12")) // 只要 R 后面能解析成整数（照 FT8CN 原样）
    }
}
