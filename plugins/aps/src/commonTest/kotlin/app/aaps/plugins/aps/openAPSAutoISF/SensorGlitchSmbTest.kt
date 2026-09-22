package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals

class SensorGlitchSmbTest {

    @Test
    fun lowIobAccelCutsToSeventyPercent() {
        val result = sensorGlitchSmb(
            calm(
                bgAcceleration = 0.31 * 18.0,
                delta = 0.50 * 18.0,
                iob = 0.69,
            )
        )
        assertEquals(0.7, result.microBolus, 0.001)
        assertEquals("Low IOB accel glitch 0.712 SMB 0.7. ", result.reason)
    }

    @Test
    fun lowIobAccelWinsOverASwing() {
        val result = sensorGlitchSmb(
            calm(
                bgAcceleration = 0.31 * 18.0,
                delta = 0.50 * 18.0,
                shortDelta = 0.10 * 18.0,
                longDelta = 0.0,
                iob = 0.20,
                cob = 0.0,
                bg = 8.0 * 18.0,
            )
        )
        assertEquals("Low IOB accel glitch 0.712 SMB 0.7. ", result.reason)
    }

    @Test
    fun iobAtPointSevenLeavesAccelAlone() {
        val result = sensorGlitchSmb(
            calm(
                bgAcceleration = 0.31 * 18.0,
                delta = 0.50 * 18.0,
                shortDelta = 1.0 * 18.0,
                longDelta = 1.0 * 18.0,
                iob = 0.70,
            )
        )
        assertEquals(1.0, result.microBolus, 0.001)
        assertEquals("", result.reason)
    }

    @Test
    fun suddenSwingHalvesTheSmb() {
        val result = sensorGlitchSmb(
            calm(
                delta = 0.40 * 18.0,
                shortDelta = 0.10 * 18.0,
                longDelta = 0.0,
                cob = 20.0,
                bg = 9.0 * 18.0,
            )
        )
        assertEquals(0.5, result.microBolus, 0.001)
        assertEquals("Sensor swing 0.511 SMB 0.5. ", result.reason)
    }

    @Test
    fun bgAtTenLeavesTheSwingAlone() {
        val result = sensorGlitchSmb(
            calm(
                delta = 0.40 * 18.0,
                shortDelta = 0.10 * 18.0,
                longDelta = 0.0,
                bg = 10.0 * 18.0,
            )
        )
        assertEquals(1.0, result.microBolus, 0.001)
    }

    @Test
    fun swingAfterAFallHalvesTheSmb() {
        val result = sensorGlitchSmb(
            calm(
                delta = 0.50 * 18.0,
                shortDelta = 0.40 * 18.0,
                longDelta = 0.15 * 18.0,
                bg = 9.0 * 18.0,
            )
        )
        assertEquals(0.5, result.microBolus, 0.001)
        assertEquals("Sensor swing 0.512 SMB 0.5. ", result.reason)
    }

    @Test
    fun eveningCarbsCutToSeventyPercent() {
        val result = sensorGlitchSmb(
            calm(
                delta = 0.50 * 18.0,
                shortDelta = 1.0 * 18.0,
                longDelta = 1.0 * 18.0,
                hour = 18,
                cob = 11.0,
                bg = 9.0 * 18.0,
            )
        )
        assertEquals(0.7, result.microBolus, 0.001)
        assertEquals("Post carb swing 0.713 SMB 0.7. ", result.reason)
    }

    @Test
    fun hourSeventeenLeavesPostCarbAlone() {
        val result = sensorGlitchSmb(
            calm(
                delta = 0.50 * 18.0,
                shortDelta = 1.0 * 18.0,
                longDelta = 1.0 * 18.0,
                hour = 17,
                cob = 11.0,
            )
        )
        assertEquals(1.0, result.microBolus, 0.001)
    }

    @Test
    fun lowTempTargetHalvesTheSmb() {
        val result = sensorGlitchSmb(
            calm(
                delta = 0.25 * 18.0,
                shortDelta = 0.20 * 18.0,
                bg = 9.0 * 18.0,
                tempTargetSet = true,
                targetBg = 4.1 * 18.0,
                rawDelta5 = 0.25 * 18.0,
                aapsDelta1 = 0.25 * 18.0,
            )
        )
        assertEquals(0.5, result.microBolus, 0.001)
        assertEquals("Low temp target SMB 0.5. ", result.reason)
    }

    @Test
    fun targetAboveFourPointOneLeavesLowTempTargetAlone() {
        val result = sensorGlitchSmb(
            calm(
                delta = 0.25 * 18.0,
                shortDelta = 0.20 * 18.0,
                tempTargetSet = true,
                targetBg = 4.2 * 18.0,
                rawDelta5 = 0.25 * 18.0,
                aapsDelta1 = 0.25 * 18.0,
            )
        )
        assertEquals(1.0, result.microBolus, 0.001)
    }

    @Test
    fun sharpRiseAfterAFallZerosTheSmb() {
        val result = sensorGlitchSmb(
            calm(
                delta = 1.01 * 18.0,
                shortDelta = 0.0,
                longDelta = -0.06 * 18.0,
                cob = 21.0,
                bg = 161.0,
            )
        )
        assertEquals(0.0, result.microBolus, 0.001)
        assertEquals("Glitch zero SMB. ", result.reason)
    }

    @Test
    fun shortSpikeWithoutATrendCutsToSeventyPercent() {
        val result = sensorGlitchSmb(
            calm(
                delta = 0.0,
                shortDelta = 0.25 * 18.0,
                longDelta = 0.0,
            )
        )
        assertEquals(0.7, result.microBolus, 0.001)
        assertEquals("Short spike 0.713 SMB 0.7. ", result.reason)
    }

    @Test
    fun aFlatReadingLeavesTheSmbAlone() {
        val result = sensorGlitchSmb(calm())
        assertEquals(1.0, result.microBolus, 0.001)
        assertEquals("", result.reason)
    }

    private fun calm(
        bgAcceleration: Double = 0.0,
        delta: Double = 0.0,
        shortDelta: Double = 0.0,
        longDelta: Double = 0.0,
        iob: Double = 1.0,
        cob: Double = 0.0,
        bg: Double = 8.0 * 18.0,
        hour: Int = 12,
        tempTargetSet: Boolean = false,
        targetBg: Double = 5.5 * 18.0,
        rawDelta5: Double = 0.0,
        aapsDelta1: Double = 0.0,
        microBolus: Double = 1.0,
    ) = SensorGlitchInput(
        bgAcceleration = bgAcceleration,
        delta = delta,
        shortDelta = shortDelta,
        longDelta = longDelta,
        iob = iob,
        cob = cob,
        bg = bg,
        hour = hour,
        tempTargetSet = tempTargetSet,
        targetBg = targetBg,
        rawDelta5 = rawDelta5,
        aapsDelta1 = aapsDelta1,
        microBolus = microBolus,
    )
}
