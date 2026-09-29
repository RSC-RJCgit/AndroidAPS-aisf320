package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.configuration.Constants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApkInstallTest {

    @Test
    fun featureNumberReadsTheNameAndTheNnnLine() {
        assertEquals(804, apkFeatureNumber("aaps-3.4.2.6+aisf321UK_804.apk"))
        assertEquals(797, apkFeatureNumber("aisf321UK_797\nnnn=797\n"))
        assertNull(apkFeatureNumber("app-full-debug.apk"))
        assertNull(apkFeatureNumber("aisf321UK_12"))
    }

    @Test
    fun autoInstallWaitsForANewerGrantedApk() {
        val now = 1_000_000L
        assertEquals(
            AutoApkChoice.DEFER_BOOST,
            autoApkChoice(true, true, 0L, now, 805, 804, true),
        )
        assertEquals(
            AutoApkChoice.COOLDOWN,
            autoApkChoice(false, true, now - 10L * 60_000L, now, 805, 804, true),
        )
        assertEquals(
            AutoApkChoice.NOT_NEWER,
            autoApkChoice(false, true, 0L, now, 804, 804, true),
        )
        assertEquals(
            AutoApkChoice.NO_PERMISSION,
            autoApkChoice(false, true, 0L, now, 805, 804, false),
        )
        assertEquals(
            AutoApkChoice.INSTALL,
            autoApkChoice(false, true, now - 50L * 60_000L, now, 805, 804, true),
        )
    }

    @Test
    fun virtualWizardStaysClosedUnlessEveryGateIsOpen() {
        assertFalse(ready(virtualPump = false))
        assertFalse(ready(hp = 7.5))
        assertFalse(ready(bolusInQueue = true))
        assertTrue(ready())
        assertEquals(20, pseudoWizardCalculationCarbs(13.1 * Constants.MMOLL_TO_MGDL))
        assertEquals(15, pseudoWizardCalculationCarbs(11.1 * Constants.MMOLL_TO_MGDL))
        assertEquals(10, pseudoWizardCalculationCarbs(10.0 * Constants.MMOLL_TO_MGDL))
    }

    private fun ready(
        virtualPump: Boolean = true,
        hp: Double? = 7.6,
        bolusInQueue: Boolean = false,
    ): Boolean = pseudoWizardReady(
        virtualPump = virtualPump,
        ukfMgdl = 12.1 * Constants.MMOLL_TO_MGDL,
        bgAcceleration = 3.1,
        shortAvgDelta = 0.6 * Constants.MMOLL_TO_MGDL,
        allBgHigh = true,
        duraActiveMinutes = 6.0,
        acceActiveMinutes = 5.0,
        mealCob = 0.0,
        noTempTarget = true,
        steps60 = 100,
        bgMgdl = 12.1 * Constants.MMOLL_TO_MGDL,
        hp = hp,
        lastNormalBolusMinutes = 120,
        cooldownReady = true,
        bolusInQueue = bolusInQueue,
    )
}
