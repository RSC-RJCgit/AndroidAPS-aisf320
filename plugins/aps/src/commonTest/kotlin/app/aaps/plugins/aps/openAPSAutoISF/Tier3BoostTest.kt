package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Tier3BoostTest {

    @Test
    fun aMildRiseRaisesTheSmbInsideTheDayWindow() {
        val result = tier3BoostMicroBolus(
            enabled = true,
            hour = 12,
            daytimeBypass = false,
            unrestricted = false,
            mildThisCycle = true,
            bg3ThisCycle = false,
            uamBoostRecent = false,
            smbDeliveryRatio = 0.5,
            microBolus = 0.1,
            insulinReq = 1.0,
            basal = 0.4,
            maxBolusSetting = 2.5,
            maxIob = 9.5,
            maxIobPercent = 50.0,
            scaleSetting = 1.0,
            profilePercent = 100,
            bg = 140.0,
            targetBg = 100.0,
            iob = 0.2,
            cob = 0.0,
            delta = 2.0,
            longAvgDelta = 1.0,
            bgAcceleration = 0.0,
            recentLowBg = 999.0,
            roundSmbTo = 20.0,
        )
        assertTrue(result.enhanced)
        assertTrue(result.microBolus > 0.1)
    }

    @Test
    fun offOrOvernightLeavesTheSmbAlone() {
        val off = tier3BoostMicroBolus(
            enabled = false,
            hour = 12,
            daytimeBypass = false,
            unrestricted = false,
            mildThisCycle = true,
            bg3ThisCycle = false,
            uamBoostRecent = false,
            smbDeliveryRatio = 0.5,
            microBolus = 0.1,
            insulinReq = 1.0,
            basal = 0.4,
            maxBolusSetting = 2.5,
            maxIob = 9.5,
            maxIobPercent = 50.0,
            scaleSetting = 1.0,
            profilePercent = 100,
            bg = 140.0,
            targetBg = 100.0,
            iob = 0.2,
            cob = 0.0,
            delta = 2.0,
            longAvgDelta = 1.0,
            bgAcceleration = 0.0,
            recentLowBg = 999.0,
            roundSmbTo = 20.0,
        )
        assertFalse(off.enhanced)
        assertEquals(0.1, off.microBolus)

        val atNight = tier3BoostMicroBolus(
            enabled = true,
            hour = 3,
            daytimeBypass = false,
            unrestricted = false,
            mildThisCycle = true,
            bg3ThisCycle = false,
            uamBoostRecent = false,
            smbDeliveryRatio = 0.5,
            microBolus = 0.1,
            insulinReq = 1.0,
            basal = 0.4,
            maxBolusSetting = 2.5,
            maxIob = 9.5,
            maxIobPercent = 50.0,
            scaleSetting = 1.0,
            profilePercent = 100,
            bg = 140.0,
            targetBg = 100.0,
            iob = 0.2,
            cob = 0.0,
            delta = 2.0,
            longAvgDelta = 1.0,
            bgAcceleration = 0.0,
            recentLowBg = 999.0,
            roundSmbTo = 20.0,
        )
        assertFalse(atNight.enhanced)
        assertEquals(0.1, atNight.microBolus)
    }
}
