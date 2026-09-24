package com.example.ft8vox.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网格地图着色单元的 JVM 单测。 */
class GridIndexTest {

    @Test
    fun fieldGranularityGroupsByTwoChars() {
        val cells = GridIndex.cells(
            worked = listOf("PM95", "PM96ab", "JN25"),
            confirmed = listOf("PM95"),
            granularity = GridGranularity.FIELD,
        )
        val byGrid = cells.associateBy { it.grid }
        assertEquals(2, cells.size)
        assertTrue(byGrid.containsKey("PM"))
        assertTrue(byGrid.containsKey("JN"))
        assertEquals(true, byGrid.getValue("PM").confirmed)
        assertEquals(false, byGrid.getValue("JN").confirmed)
    }

    @Test
    fun squareGranularityUsesFourChars() {
        val cells = GridIndex.cells(
            worked = listOf("PM95ab", "PM95cd", "JN25"),
            confirmed = listOf("PM95cd"),
            granularity = GridGranularity.SQUARE,
        )
        val byGrid = cells.associateBy { it.grid }
        assertEquals(2, cells.size)
        assertEquals(true, byGrid.getValue("PM95").confirmed)
        assertEquals(false, byGrid.getValue("JN25").confirmed)
    }

    @Test
    fun confirmedWinsOverWorkedInSameCell() {
        val cells = GridIndex.cells(
            worked = listOf("PM95", "PM95ab"),
            confirmed = listOf("PM95ab"),
            granularity = GridGranularity.SQUARE,
        )
        assertEquals(1, cells.size)
        assertEquals(true, cells[0].confirmed)
    }

    @Test
    fun ignoresInvalidAndTooShortGrids() {
        val cells = GridIndex.cells(
            worked = listOf("XX", "", "PM"),
            confirmed = emptyList(),
            granularity = GridGranularity.SQUARE,
        )
        assertEquals(0, cells.size)
    }

    @Test
    fun boundsCoverTheCell() {
        val cells = GridIndex.cells(
            worked = listOf("PM95"),
            confirmed = emptyList(),
            granularity = GridGranularity.SQUARE,
        )
        val b = cells[0].bounds
        assertTrue(b.maxLat > b.minLat)
        assertTrue(b.maxLon > b.minLon)
    }
}
