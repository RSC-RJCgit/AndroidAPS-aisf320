package app.aaps.core.interfaces.overview.graph

import kotlin.test.Test
import kotlin.test.assertEquals

class DominantIsfTest {

    @Test
    fun aFactorWithinToleranceIsNotAColour() {
        // 1/128 is safely under 0.01, and it is an exact binary fraction.
        assertEquals(DominantIsf.NONE, dominantIsf(1.0, 1.0078125, 0.9921875, 1.0))
    }

    @Test
    fun `a tie uses acce then bg then pp`() {
        // 1.5 and 0.5 are exact, so a tie is a real tie and not a rounding gap.
        assertEquals(DominantIsf.ACCE, dominantIsf(1.5, 0.5, 1.5, 1.5))
        assertEquals(DominantIsf.BG, dominantIsf(1.0, 1.5, 0.5, 1.5))
        assertEquals(DominantIsf.PP, dominantIsf(1.0, 1.0, 0.5, 1.5))
        assertEquals(DominantIsf.DURA, dominantIsf(1.0, 1.0, 1.0, 1.5))
    }

    @Test
    fun `the nearest row inside 15 minutes wins and a further row does not`() {
        data class Row(val time: Long, val acce: Double, val bg: Double, val pp: Double, val dura: Double)

        val rows = listOf(
            Row(0L, 1.5, 1.0, 1.0, 1.0),
            Row(10L * 60L * 1000L, 1.0, 1.5, 1.0, 1.0)
        )
        val at = 12L * 60L * 1000L
        assertEquals(
            DominantIsf.BG,
            dominantIsfAt(at, rows, { it.time }, { it.acce }, { it.bg }, { it.pp }, { it.dura })
        )
        assertEquals(
            DominantIsf.NONE,
            dominantIsfAt(at + DOMINANT_ISF_WINDOW_MS, rows, { it.time }, { it.acce }, { it.bg }, { it.pp }, { it.dura })
        )
        assertEquals(
            DominantIsf.NONE,
            dominantIsfAt(at, emptyList<Row>(), { it.time }, { it.acce }, { it.bg }, { it.pp }, { it.dura })
        )
    }
}
