package app.aaps.core.objects.wizard

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockingDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SplitRetryTest {
    private val logger: AAPSLogger = mock()
    private val preferences: Preferences = mock()
    private val profileFunction: ProfileFunction = mock()
    private val profile: EffectiveProfile = mock()
    private val dateUtil: DateUtil = mock()
    private val ch: ConcentrationHelper = mock()

    private suspend fun TestScope.wizard(): BolusWizard {
        whenever(dateUtil.now()).thenAnswer { 1_000_000L + testScheduler.currentTime }
        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MMOL)
        whenever(profile.percentage).thenReturn(100)
        whenever(ch.bolusStep(any())).thenReturn(0.05)
        whenever(preferences.get(LongNonKey.ApsAutoIsfLastCycleHp1At)).thenAnswer { dateUtil.now() }
        whenever(preferences.get(LongNonKey.ApsAutoIsfLastCycleHp1MilliMmol)).thenReturn(3800L)
        return BolusWizard(
            aapsLogger = logger, rh = mock(), rxBus = mock(), preferences = preferences,
            profileFunction = profileFunction, profileUtil = mock(), constraintChecker = mock(), loop = mock(),
            iobCobCalculator = mock(), dateUtil = dateUtil, config = mock(), uel = mock(), automation = mock(),
            eatReminder = mock(), glucoseStatusProvider = mock(), persistenceLayer = mock(), runningModeGuard = mock(),
            ch = ch, masterActionExecutor = mock(), activePlugin = mock(), appScope = backgroundScope
        )
    }

    private fun messages(): List<String> = mockingDetails(logger).invocations
        .filter { it.method.name == "info" }.map { it.arguments[1].toString() }

    @Test
    fun lowHpHoldsRepeatedlyAndRecoveryContinuesTheSameSchedule() = runTest {
        val wizard = wizard()
        wizard.scheduleLeftoverSplit(8.0, 5.0, 0.0, Sources.WizardDialog)
        runCurrent()
        advanceTimeBy(14 * 60_000L)
        runCurrent()
        assertThat(messages().count { it.contains("HP1 below") }).isEqualTo(2)
        assertThat(messages().any { it.contains("cancelled") }).isFalse()

        whenever(preferences.get(LongNonKey.ApsAutoIsfLastCycleHp1MilliMmol)).thenReturn(4500L)
        advanceTimeBy(7 * 60_000L)
        runCurrent()
        // HP1 now passes. The next safety gate still holds delivery without fresh glucose.
        assertThat(messages().any { it.contains("no fresh glucose") }).isTrue()
        wizard.cancelLeftoverSplit()
        advanceTimeBy(2 * 60_000L)
        runCurrent()
        val count = messages().size
        advanceTimeBy(20 * 60_000L)
        runCurrent()
        assertThat(messages().size).isEqualTo(count)
    }

    @Test
    fun temporaryProfileRestrictionWaitsButDoesNotExtendExpiry() = runTest {
        val wizard = wizard()
        wizard.scheduleLeftoverSplit(8.0, 5.0, 0.0, Sources.WizardDialog)
        whenever(profile.percentage).thenReturn(80)
        runCurrent()
        advanceTimeBy(14 * 60_000L)
        runCurrent()
        assertThat(messages().count { it.contains("profile missing or under") }).isEqualTo(2)
        assertThat(messages().any { it.contains("cancelled") }).isFalse()

        whenever(profile.percentage).thenReturn(100)
        advanceTimeBy(7 * 60_000L)
        runCurrent()
        assertThat(messages().any { it.contains("HP1 below") }).isTrue()
        whenever(profileFunction.getProfile()).thenReturn(null)
        advanceTimeBy(140 * 60_000L)
        runCurrent()
        assertThat(messages().any { it.contains("time limit passed") }).isTrue()
        val count = messages().size
        advanceTimeBy(20 * 60_000L)
        runCurrent()
        assertThat(messages().size).isEqualTo(count)
    }
}
