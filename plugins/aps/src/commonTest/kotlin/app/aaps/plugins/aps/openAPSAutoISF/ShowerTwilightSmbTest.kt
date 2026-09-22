package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals

class ShowerTwilightSmbTest {

    @Test
    fun quietMorningRiseCapsTheSmb() {
        val result = showerTwilightSmb(morning())
        assertEquals(0.4, result.microBolus, 0.001)
        assertEquals("Shower SMB cap 0.4. ", result.reason)
    }

    @Test
    fun iobCeilingCutsFurther() {
        val result = showerTwilightSmb(morning(iob = 0.7))
        assertEquals(0.2, result.microBolus, 0.001)
        assertEquals("Shower SMB cap 0.4. Shower IOB ceiling 0.2. ", result.reason)
    }

    @Test
    fun busierStepsUseTheStrongerRiseGate() {
        val result = showerTwilightSmb(
            morning(
                steps60 = 50,
                delta = 0.40 * 18.0,
                shortDelta = 0.20 * 18.0,
                rawDelta5 = 0.40 * 18.0,
                aapsDelta1 = 0.40 * 18.0,
            )
        )
        assertEquals(0.4, result.microBolus, 0.001)
        assertEquals("Shower SMB cap 0.4. Shower time. ", result.reason)
    }

    @Test
    fun quietStepsWinWhenBothGatesMatch() {
        val result = showerTwilightSmb(
            morning(
                delta = 0.40 * 18.0,
                shortDelta = 0.20 * 18.0,
                rawDelta5 = 0.40 * 18.0,
                aapsDelta1 = 0.40 * 18.0,
            )
        )
        assertEquals("Shower SMB cap 0.4. ", result.reason)
    }

    @Test
    fun hourTenLeavesTheSmbAlone() {
        val result = showerTwilightSmb(morning(hour = 10))
        assertEquals(1.0, result.microBolus, 0.001)
        assertEquals("", result.reason)
    }

    @Test
    fun carbsLeaveTheSmbAlone() {
        val result = showerTwilightSmb(morning(cob = 1.0))
        assertEquals(1.0, result.microBolus, 0.001)
        assertEquals("", result.reason)
    }

    @Test
    fun tempTargetLeavesTheSmbAlone() {
        val result = showerTwilightSmb(morning(tempTargetSet = true))
        assertEquals(1.0, result.microBolus, 0.001)
    }

    @Test
    fun highIobThresholdLeavesTheSmbAlone() {
        val result = showerTwilightSmb(morning(iobThUser = 71))
        assertEquals(1.0, result.microBolus, 0.001)
    }

    @Test
    fun oneHundredStepsLeaveTheSmbAlone() {
        val result = showerTwilightSmb(
            morning(
                steps60 = 100,
                delta = 0.40 * 18.0,
                shortDelta = 0.20 * 18.0,
                rawDelta5 = 0.40 * 18.0,
                aapsDelta1 = 0.40 * 18.0,
            )
        )
        assertEquals(1.0, result.microBolus, 0.001)
    }

    @Test
    fun lowRawDeltaLeavesTheSmbAlone() {
        val result = showerTwilightSmb(morning(rawDelta5 = 0.10 * 18.0, aapsDelta1 = 0.10 * 18.0))
        assertEquals(1.0, result.microBolus, 0.001)
    }

    private fun morning(
        hour: Int = 7,
        bg: Double = 7.0 * 18.0,
        steps60: Int = 0,
        cob: Double = 0.0,
        tempTargetSet: Boolean = false,
        delta: Double = 0.30 * 18.0,
        shortDelta: Double = 0.20 * 18.0,
        iobThUser: Int = 70,
        rawDelta5: Double = 0.30 * 18.0,
        aapsDelta1: Double = 0.30 * 18.0,
        microBolus: Double = 1.0,
        iob: Double = 0.2,
        maxIob: Double = 10.0,
    ) = ShowerTwilightInput(
        hour = hour,
        bg = bg,
        steps60 = steps60,
        cob = cob,
        tempTargetSet = tempTargetSet,
        delta = delta,
        shortDelta = shortDelta,
        iobThUser = iobThUser,
        rawDelta5 = rawDelta5,
        aapsDelta1 = aapsDelta1,
        microBolus = microBolus,
        iob = iob,
        maxIob = maxIob,
    )
}
