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
            targetBgOffsetMgdl = 100.0,
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
        // The text a client reads is the same as the 3426 master writes.
        assertTrue(result.reason.startsWith("UAM Boost candidate 0.1 -> "), result.reason)
        assertTrue(result.reason.contains("IOB ceiling 50.0%=4.75U;"), result.reason)
        assertTrue(result.console.contains(">>> TIER 3: UAM Boost candidate <<<"), result.console)
    }

    @Test
    fun theBlockedAndSkippedCasesKeepTheirNotesAndAddTheScriptDebugLine() {
        val night = tier3BoostMicroBolus(
            enabled = true, hour = 3, daytimeBypass = false, unrestricted = false, mildThisCycle = true, bg3ThisCycle = false,
            uamBoostRecent = false, smbDeliveryRatio = 0.5, microBolus = 0.1, insulinReq = 1.0, basal = 0.4, maxBolusSetting = 2.5,
            maxIob = 9.5, maxIobPercent = 50.0, scaleSetting = 1.0, profilePercent = 100, bg = 140.0, targetBg = 100.0,
            targetBgOffsetMgdl = 100.0, iob = 0.2, cob = 0.0, delta = 2.0, longAvgDelta = 1.0, bgAcceleration = 0.0,
            recentLowBg = 999.0, roundSmbTo = 20.0,
        )
        assertEquals("Tier 3 blocked outside 09:00-21:00; ", night.reason)
        assertEquals("Tier 3 UAM Boost blocked outside 09:00-21:00 (local hour 3)", night.console)
        val recent = tier3BoostMicroBolus(
            enabled = true, hour = 12, daytimeBypass = false, unrestricted = false, mildThisCycle = true, bg3ThisCycle = false,
            uamBoostRecent = true, smbDeliveryRatio = 0.5, microBolus = 0.1, insulinReq = 1.0, basal = 0.4, maxBolusSetting = 2.5,
            maxIob = 9.5, maxIobPercent = 50.0, scaleSetting = 1.0, profilePercent = 100, bg = 140.0, targetBg = 100.0,
            targetBgOffsetMgdl = 100.0, iob = 0.2, cob = 0.0, delta = 2.0, longAvgDelta = 1.0, bgAcceleration = 0.0,
            recentLowBg = 999.0, roundSmbTo = 20.0,
        )
        assertEquals("T3skip<20min; ", recent.reason)
        assertEquals("Tier 3 UAM Boost skipped: last UamBst <20 min", recent.console)
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
            targetBgOffsetMgdl = 100.0,
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
            targetBgOffsetMgdl = 100.0,
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

    @Test
    fun aHighRiseOpensTier3AtNightAboveItsIobCap() {
        val held = tier3BoostMicroBolus(
            enabled = true,
            hour = 3,
            daytimeBypass = false,
            unrestricted = false,
            mildThisCycle = true,
            bg3ThisCycle = false,
            uamBoostRecent = true,
            smbDeliveryRatio = 0.6,
            microBolus = 0.1,
            insulinReq = 1.0,
            basal = 0.4,
            maxBolusSetting = 2.5,
            maxIob = 9.5,
            maxIobPercent = 10.0,
            scaleSetting = 1.0,
            profilePercent = 100,
            bg = 180.0,
            targetBg = 100.0,
            targetBgOffsetMgdl = 100.0,
            iob = 5.0,
            cob = 0.0,
            delta = 12.0,
            longAvgDelta = 8.0,
            bgAcceleration = 20.0,
            recentLowBg = 70.0,
            roundSmbTo = 20.0,
            riseHold = true,
        )
        assertTrue(held.enhanced)
        assertTrue(held.microBolus > 0.1)
    }

    @Test
    fun aQuietHighBrakeBlocksTheUamBoost() {
        assertTrue(
            hiBrkQuietUamBlock(
                ttMgdl = 72.1,
                highBrakeRecent = true,
                bg = 122.0,
                shortDelta = 1.0,
                ukfDelta15 = 3.0,
            )
        )
    }

    @Test
    fun aMissingRawDoesNotBlockTheUamBoost() {
        assertFalse(
            hiBrkQuietUamBlock(
                ttMgdl = 72.1,
                highBrakeRecent = true,
                bg = 122.0,
                shortDelta = 1.0,
                ukfDelta15 = null,
            )
        )
    }

    @Test
    fun aSteepShortDeltaDoesNotBlockTheUamBoost() {
        assertFalse(
            hiBrkQuietUamBlock(
                ttMgdl = 72.1,
                highBrakeRecent = true,
                bg = 122.0,
                shortDelta = 1.8,
                ukfDelta15 = 1.0,
            )
        )
    }
}
