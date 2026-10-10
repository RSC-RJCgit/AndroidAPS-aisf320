package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TodOffsetsTest {

    @Test
    fun quietNomjWithHighPredictionClearsPositiveOffsets() {
        val signals = todOffsetClearSignals(nomjRemains = true, hp = 6.5, shortDeltaMgdl = 0.0, steps60 = 100)
        assertTrue(signals.clearPositive)
        assertFalse(signals.clearNegative)
    }

    @Test
    fun missingPredictionDoesNotClear() {
        val signals = todOffsetClearSignals(nomjRemains = true, hp = null, shortDeltaMgdl = 0.0, steps60 = 100)
        assertFalse(signals.clearPositive)
        assertFalse(signals.clearNegative)
    }

    @Test
    fun notNomjClearsNegativeOffsets() {
        val signals = todOffsetClearSignals(nomjRemains = false, hp = null, shortDeltaMgdl = 5.0, steps60 = 0)
        assertTrue(signals.clearNegative)
        assertFalse(signals.clearPositive)
    }

    @Test
    fun manyStepsClearNegativeOffsets() {
        val signals = todOffsetClearSignals(nomjRemains = true, hp = 5.0, shortDeltaMgdl = 0.0, steps60 = 1000)
        assertTrue(signals.clearNegative)
        assertFalse(signals.clearPositive)
    }
}
