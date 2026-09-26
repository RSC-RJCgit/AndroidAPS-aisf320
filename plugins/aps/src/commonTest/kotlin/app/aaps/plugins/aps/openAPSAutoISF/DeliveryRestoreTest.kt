package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeliveryRestoreTest {

    @Test
    fun aRatioAwayFromBaselineReturnsWhenNoTargetIsOn() {
        assertTrue(restore(currentRatio = 0.29))
        assertFalse(restore(currentRatio = 0.1405))
        assertFalse(restore(currentRatio = 0.29, tempTargetSet = true))
        assertFalse(restore(currentRatio = 0.35, recentDeliveryBoost = true))
    }

    @Test
    fun stackingAtTheLowerTargetIsLeftAlone() {
        assertFalse(restore(currentRatio = 0.11, atHardStackTarget = true, smbStacking = true))
        assertTrue(restore(currentRatio = 0.11, atHardStackTarget = true, smbStacking = false))
    }

    @Test
    fun fourCloseSmbsCountAsStacking() {
        assertTrue(smbIsStacking(intervalSec = 64.9, count5 = 4))
        assertFalse(smbIsStacking(intervalSec = 65.0, count5 = 4))
        assertFalse(smbIsStacking(intervalSec = 30.0, count5 = 3))
    }

    @Test
    fun stackingLowersTheRatioUnlessABoostOrAMealOwnsIt() {
        assertTrue(reduce())
        assertFalse(reduce(atHardStackTarget = true))
        assertFalse(reduce(smbStacking = false))
        assertFalse(reduce(recentOwnBoost = true))
        assertFalse(reduce(mealCob = 9.0))
        assertTrue(reduce(mealCob = 8.9))
    }

    private fun restore(
        currentRatio: Double = 0.29,
        restingBaseline: Double = 0.14,
        tempTargetSet: Boolean = false,
        atHardStackTarget: Boolean = false,
        smbStacking: Boolean = false,
        recentDeliveryBoost: Boolean = false,
    ) = delOffShouldRestore(currentRatio, restingBaseline, tempTargetSet, atHardStackTarget, smbStacking, recentDeliveryBoost)

    private fun reduce(
        atHardStackTarget: Boolean = false,
        smbStacking: Boolean = true,
        recentOwnBoost: Boolean = false,
        mealCob: Double = 0.0,
    ) = hardStackShouldReduce(atHardStackTarget, smbStacking, recentOwnBoost, mealCob)
}
