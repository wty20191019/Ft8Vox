package com.example.ft8vox.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** U7d：「含我呼号哔声」判定。 */
class MyCallAlertTest {

    @Test
    fun offNeverAlerts() {
        assertFalse(shouldAlertMyCall(beepOn = false, myCall = "W1AW", texts = listOf("W1AW JA1ABC -08")))
    }

    @Test
    fun emptyMyCallNeverAlerts() {
        assertFalse(shouldAlertMyCall(beepOn = true, myCall = "  ", texts = listOf("W1AW JA1ABC -08")))
    }

    @Test
    fun alertsWhenAddressedToMe() {
        assertTrue(shouldAlertMyCall(beepOn = true, myCall = "W1AW", texts = listOf("W1AW JA1ABC -08")))
    }

    @Test
    fun ignoresMessagesForOthers() {
        assertFalse(shouldAlertMyCall(beepOn = true, myCall = "W1AW", texts = listOf("K1ABC JA1ABC -08")))
    }

    @Test
    fun ignoresCq() {
        assertFalse(shouldAlertMyCall(beepOn = true, myCall = "W1AW", texts = listOf("CQ W1AW PM95")))
    }

    @Test
    fun caseInsensitive() {
        assertTrue(shouldAlertMyCall(beepOn = true, myCall = "w1aw", texts = listOf("W1AW JA1ABC -08")))
    }

    @Test
    fun alertsWhenAnyOfBatchAddressedToMe() {
        assertTrue(
            shouldAlertMyCall(
                beepOn = true,
                myCall = "W1AW",
                texts = listOf("CQ JA1ABC PM95", "W1AW K1ABC RR73"),
            ),
        )
    }
}
