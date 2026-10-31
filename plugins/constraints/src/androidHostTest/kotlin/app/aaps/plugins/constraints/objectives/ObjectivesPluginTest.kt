package app.aaps.plugins.constraints.objectives

import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.constraints.Objectives
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.PasswordCheck
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.implementation.sharedPreferences.PreferencesImpl
import app.aaps.plugins.constraints.ConstraintsStringsValues
import app.aaps.plugins.constraints.objectives.objectives.Objective0
import app.aaps.plugins.constraints.objectives.objectives.Objective1
import app.aaps.plugins.constraints.objectives.objectives.Objective2
import app.aaps.plugins.constraints.objectives.objectives.Objective3
import app.aaps.plugins.constraints.objectives.objectives.Objective4
import app.aaps.plugins.constraints.objectives.objectives.Objective5
import app.aaps.plugins.constraints.objectives.objectives.Objective6
import app.aaps.plugins.constraints.objectives.objectives.Objective7
import app.aaps.plugins.constraints.objectives.objectives.Objective8
import app.aaps.plugins.constraints.objectives.objectives.Objective9
import app.aaps.plugins.constraints.objectives.objectives.PlainDurationText
import app.aaps.pump.virtual.VirtualPumpPlugin
import app.aaps.shared.impl.sharedPreferences.SPImpl
import app.aaps.shared.tests.SharedPreferencesMock
import app.aaps.shared.tests.TestBaseWithProfile
import app.aaps.shared.tests.generatedTextResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ObjectivesPluginTest : TestBaseWithProfile() {

    @Mock lateinit var virtualPumpPlugin: VirtualPumpPlugin
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var loop: Loop
    @Mock lateinit var passwordCheck: PasswordCheck
    @Mock lateinit var profileUtilMock: ProfileUtil
    @Mock lateinit var profileFunctionMock: ProfileFunction
    @Mock lateinit var hardLimitsMock: HardLimits
    @Mock lateinit var notices: NotificationManager
    @Mock lateinit var pumpOnVirtual: PumpWithConcentration

    /** Real English for the objective reasons, so the sentences asserted below are what the user reads. */
    private val text = generatedTextResolver("constraints" to ConstraintsStringsValues::textOf)

    private lateinit var objectivesPlugin: ObjectivesPlugin
    private lateinit var emulatedPreferences: Preferences

    @BeforeEach
    fun setupMock() {
        val sp = SPImpl(SharedPreferencesMock(), context)
        emulatedPreferences = PreferencesImpl(sp, { profileUtilMock }, { profileFunctionMock }, { hardLimitsMock }, persistenceLayer, config, dateUtil)

        // Real formatter: pure arithmetic over a duration, and only read for display.
        val durationText = PlainDurationText()
        val objectives = listOf(
            Objective0(emulatedPreferences, text, durationText, dateUtil, activePlugin, virtualPumpPlugin, persistenceLayer, loop, iobCobCalculator, passwordCheck),
            Objective1(emulatedPreferences, text, durationText, dateUtil),
            Objective2(emulatedPreferences, text, durationText, dateUtil),
            Objective3(emulatedPreferences, text, durationText, dateUtil),
            Objective4(emulatedPreferences, text, durationText, dateUtil, profileFunction),
            Objective5(emulatedPreferences, text, durationText, dateUtil),
            Objective6(emulatedPreferences, text, durationText, dateUtil, constraintsChecker, loop),
            Objective7(emulatedPreferences, text, durationText, dateUtil),
            Objective8(emulatedPreferences, text, durationText, dateUtil),
            Objective9(emulatedPreferences, text, durationText, dateUtil)
        )
        objectivesPlugin = ObjectivesPlugin(aapsLogger, text, emulatedPreferences, config, objectives, notices, activePlugin)
        runBlocking { objectivesPlugin.onStart() }
    }

    @Test fun notStartedObjectivesShouldLimitLoopInvocation() {
        objectivesPlugin.objectives[Objectives.FIRST_OBJECTIVE].startedOn = 0
        val c = objectivesPlugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger))
        assertThat(c.getReasons()).isEqualTo("Objectives: Objective 1 not started")
        assertThat(c.value()).isFalse()
        objectivesPlugin.objectives[Objectives.FIRST_OBJECTIVE].startedOn = dateUtil.now()
    }

    @Test fun notStartedObjective5ShouldForceLgs() {
        objectivesPlugin.objectives[Objectives.LGS_OBJECTIVE].startedOn = 1
        objectivesPlugin.objectives[Objectives.LGS_OBJECTIVE].accomplishedOn = 0
        val c = objectivesPlugin.isLgsForced(ConstraintObject(false, aapsLogger))
        assertThat(c.getReasons()).contains("Objective 6 not finished")
        assertThat(c.value()).isTrue()
    }

    @Test fun notStartedObjective6ShouldLimitClosedLoop() = runTest {
        objectivesPlugin.objectives[Objectives.CLOSED_LOOP_OBJECTIVE].startedOn = 0
        val c = objectivesPlugin.isClosedLoopAllowed(ConstraintObject(true, aapsLogger))
        assertThat(c.getReasons()).contains("Objective 7 not started")
        assertThat(c.value()).isFalse()
    }

    @Test fun notStartedObjective8ShouldLimitAutosensMode() {
        objectivesPlugin.objectives[Objectives.AUTOSENS_OBJECTIVE].startedOn = 0
        val c = objectivesPlugin.isAutosensModeEnabled(ConstraintObject(true, aapsLogger))
        assertThat(c.getReasons()).contains("Objective 8 not started")
        assertThat(c.value()).isFalse()
    }

    @Test fun notStartedObjective10ShouldLimitSMBMode() = runTest {
        objectivesPlugin.objectives[Objectives.SMB_OBJECTIVE].startedOn = 0
        val c = objectivesPlugin.isSMBModeEnabled(ConstraintObject(true, aapsLogger))
        assertThat(c.getReasons()).contains("Objective 9 not started")
        assertThat(c.value()).isFalse()
    }

    // Virtual master bypass and install/update notice (2026-10-31)

    private fun onVirtualPump() {
        whenever(pumpOnVirtual.selectedActivePump()).thenReturn(virtualPumpPlugin)
        whenever(activePlugin.activePump).thenReturn(pumpOnVirtual)
    }

    private fun allObjectivesDone() {
        objectivesPlugin.objectives.forEach {
            it.startedOn = 1
            it.accomplishedOn = 1
        }
    }

    private fun installedBuild(head: String) {
        whenever(config.APS).thenReturn(true)
        whenever(config.VERSION_NAME).thenReturn("4.0.0-rc1")
        whenever(config.HEAD).thenReturn(head)
    }

    /** The install/update notice was posted [count] times, its text passing [check] (the overload post(id, text) picks). */
    private fun verifyNotice(count: Int, check: (String) -> Boolean = { true }) {
        verify(notices, times(count)).post(
            eq(NotificationId.OBJECTIVES_LIMIT_FUNCTIONS),
            argThat<String> { check(this) },
            any<NotificationLevel>(),
            any<Int>(),
            anyOrNull<AlarmSound>(),
            any<List<NotificationAction>>(),
            anyOrNull<() -> Boolean>()
        )
    }

    @Test fun bypassNeedsBothTheSwitchAndTheVirtualPump() = runTest {
        objectivesPlugin.objectives[Objectives.SMB_OBJECTIVE].startedOn = 0

        // Switch on, real pump: still limited
        emulatedPreferences.put(BooleanNonKey.ObjectivesVirtualPumpBypass, true)
        assertThat(objectivesPlugin.isBypassed).isFalse()
        assertThat(objectivesPlugin.isSMBModeEnabled(ConstraintObject(true, aapsLogger)).value()).isFalse()

        // Virtual pump, switch off: still limited
        onVirtualPump()
        emulatedPreferences.put(BooleanNonKey.ObjectivesVirtualPumpBypass, false)
        assertThat(objectivesPlugin.isBypassed).isFalse()
        assertThat(objectivesPlugin.isSMBModeEnabled(ConstraintObject(true, aapsLogger)).value()).isFalse()

        // Both: nothing is limited
        emulatedPreferences.put(BooleanNonKey.ObjectivesVirtualPumpBypass, true)
        assertThat(objectivesPlugin.isBypassed).isTrue()
        val c = objectivesPlugin.isSMBModeEnabled(ConstraintObject(true, aapsLogger))
        assertThat(c.value()).isTrue()
        assertThat(c.getReasons()).isEmpty()
        assertThat(objectivesPlugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger)).value()).isTrue()
        assertThat(objectivesPlugin.isClosedLoopAllowed(ConstraintObject(true, aapsLogger)).value()).isTrue()
        assertThat(objectivesPlugin.isAutomationEnabled(ConstraintObject(true, aapsLogger)).value()).isTrue()
        assertThat(objectivesPlugin.limitedFunctions()).isEmpty()
    }

    @Test fun limitedFunctionsNameWhatIsLimitedAndWhy() {
        allObjectivesDone()
        assertThat(objectivesPlugin.limitedFunctions()).isEmpty()

        objectivesPlugin.objectives[Objectives.SMB_OBJECTIVE].startedOn = 0
        objectivesPlugin.objectives[Objectives.AUTO_OBJECTIVE].startedOn = 0
        assertThat(objectivesPlugin.limitedFunctions()).containsExactly(
            "SMB (Objective 9 not started)",
            "Automation (Objective 10 not started)"
        ).inOrder()
    }

    @Test fun freshInstallListsEveryLimitedFunction() {
        objectivesPlugin.objectives.forEach {
            it.startedOn = 0
            it.accomplishedOn = 0
        }
        assertThat(objectivesPlugin.limitedFunctions()).containsExactly(
            "Loop (Objective 1 not started)",
            "Closed loop (Objective 7 not started)",
            "Autosens (Objective 8 not started)",
            "SMB (Objective 9 not started)",
            "Automation (Objective 10 not started)",
            "Insulin concentration (Objective 3 not finished)"
        ).inOrder()
    }

    @Test fun noticeOnInstallOrUpdateOnlyOnce() {
        installedBuild("aaaaaaa")
        allObjectivesDone()
        objectivesPlugin.objectives[Objectives.SMB_OBJECTIVE].startedOn = 0

        objectivesPlugin.checkAfterInstallOrUpdate()
        verifyNotice(1) { it.contains("SMB (Objective 9 not started)") }

        // A restart of the same build says nothing
        objectivesPlugin.checkAfterInstallOrUpdate()
        verifyNotice(1)

        // A new build does
        installedBuild("bbbbbbb")
        objectivesPlugin.checkAfterInstallOrUpdate()
        verifyNotice(2)
    }

    @Test fun noNoticeWhenNothingIsLimitedOrBypassed() {
        installedBuild("ccccccc")
        allObjectivesDone()
        objectivesPlugin.checkAfterInstallOrUpdate()

        installedBuild("ddddddd")
        objectivesPlugin.objectives[Objectives.SMB_OBJECTIVE].startedOn = 0
        onVirtualPump()
        emulatedPreferences.put(BooleanNonKey.ObjectivesVirtualPumpBypass, true)
        objectivesPlugin.checkAfterInstallOrUpdate()

        verifyNotice(0)
    }

    @Test fun noticeIsTakenAwayOnceNothingIsLimited() {
        installedBuild("eeeeeee")
        allObjectivesDone()
        objectivesPlugin.objectives[Objectives.SMB_OBJECTIVE].startedOn = 0
        objectivesPlugin.checkAfterInstallOrUpdate()

        objectivesPlugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger))
        verify(notices, never()).dismiss(NotificationId.OBJECTIVES_LIMIT_FUNCTIONS)

        objectivesPlugin.objectives[Objectives.SMB_OBJECTIVE].startedOn = 1
        objectivesPlugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger))
        verify(notices).dismiss(NotificationId.OBJECTIVES_LIMIT_FUNCTIONS)
    }
}
