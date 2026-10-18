package app.aaps.core.objects.wizard

import app.aaps.core.data.model.GlucoseUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class GateGlucoseTextTest {

    @Test
    fun mmolShowsBgWithOneDecimalAndDeltasWithTwo() {
        // 133.2 mg/dL = 7.39 mmol, 1.8 mg/dL = 0.10 mmol, -0.9 mg/dL = -0.05 mmol
        assertEquals("BG=7.4, delta=0.10, short delta=-0.05 mmol/L", gateGlucoseText(133.2, 1.8, -0.9, GlucoseUnit.MMOL))
    }

    @Test
    fun mgdlShowsWholeBgAndDeltasWithOneDecimal() {
        assertEquals("BG=133, delta=1.8, short delta=-0.9 mg/dL", gateGlucoseText(133.2, 1.84, -0.94, GlucoseUnit.MGDL))
    }

    @Test
    fun missingValuesShowDashes() {
        assertEquals("BG=--, delta=--, short delta=-- mg/dL", gateGlucoseText(null, null, null, GlucoseUnit.MGDL))
    }
}
