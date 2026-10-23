package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The "b" labels of the SMB totals graph (2026-10-24). */
class TotalsBasalTest {

    private val min = 60_000L

    // 1.2 U/h from 0 to 60 min, zero from 60 to 120 min, 1.2 U/h again from 120 to 240 min
    private val basal = listOf(
        GraphDataPoint(0L, 1.2),
        GraphDataPoint(60 * min, 0.0),
        GraphDataPoint(120 * min, 1.2),
        GraphDataPoint(240 * min, 1.2),
    )

    @Test
    fun zeroDropsAreFound() {
        assertEquals(listOf(60 * min), basalZeroDrops(basal))
    }

    @Test
    fun dropAfterAStackIsLabelledAndTheNextStackCountsFromIt() {
        // Stack 1: last SMB at 20 min, total at 30 min. Stack 2: total at 200 min (window 180..200), last SMB at 190 min.
        val totals = listOf(30 * min to 1.0, 200 * min to 0.5)
        val result = totalsBasal(basal, totals, listOf(20 * min, 190 * min), now = 300 * min)
        // The drop at 60 min, 40 min after the last SMB: 40 min at 1.2 U/h = 0.8 U
        assertEquals(1, result.drops.size)
        assertEquals(60 * min, result.drops[0].first)
        assertEquals(0.8, result.drops[0].second, 1e-9)
        // Stack 2 counts from the drop: zero to 120 min, then 80 min at 1.2 U/h = 1.6 U
        assertEquals(1.6, result.perTotal[1]!!, 1e-9)
    }

    @Test
    fun dropSoonAfterTheStackIsNotLabelled() {
        // Last SMB at 55 min: the drop at 60 min is only 5 minutes later
        val result = totalsBasal(basal, listOf(58 * min to 1.0), listOf(55 * min), now = 300 * min)
        assertTrue(result.drops.isEmpty())
    }

    @Test
    fun firstTotalCountsFromTheLastZeroStartBeforeItsStack() {
        // One stack, total at 200 min (window 180..200): the zero start at 60 min, so basal from 60 to 200 = 80 min at 1.2 U/h
        val result = totalsBasal(basal, listOf(200 * min to 1.0), listOf(190 * min), now = 300 * min)
        assertEquals(1.6, result.perTotal[0]!!, 1e-9)
    }

    @Test
    fun noBasalDataGivesNoLabels() {
        val result = totalsBasal(emptyList(), listOf(30 * min to 1.0), listOf(20 * min), now = 300 * min)
        assertNull(result.perTotal[0])
        assertTrue(result.drops.isEmpty())
    }
}
