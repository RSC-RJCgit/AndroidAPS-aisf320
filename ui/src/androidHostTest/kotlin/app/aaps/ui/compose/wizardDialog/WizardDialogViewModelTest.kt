package app.aaps.ui.compose.wizardDialog

import androidx.lifecycle.SavedStateHandle
import app.aaps.core.data.model.BolusWizardData
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.bolus.WizardExecutor
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.core.interfaces.profile.ProfileStore
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.runningMode.RunningModeGuard
import app.aaps.core.objects.wizard.BolusWizard
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mockingDetails
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
internal class WizardDialogViewModelTest {

    @Mock private lateinit var constraintChecker: ConstraintsChecker
    @Mock private lateinit var profileFunction: ProfileFunction
    @Mock private lateinit var profileUtil: ProfileUtil
    @Mock private lateinit var profileRepository: ProfileRepository
    @Mock private lateinit var activePlugin: ActivePlugin
    @Mock private lateinit var ch: ConcentrationHelper
    @Mock private lateinit var iobCobCalculator: IobCobCalculator
    @Mock private lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var preferences: Preferences
    @Mock private lateinit var config: Config
    @Mock private lateinit var rh: ResourceHelper
    @Mock private lateinit var dateUtil: DateUtil
    @Mock private lateinit var decimalFormatter: DecimalFormatter
    @Mock private lateinit var aapsLogger: AAPSLogger
    @Mock private lateinit var runningModeGuard: RunningModeGuard
    @Mock private lateinit var automation: Automation
    @Mock private lateinit var wizardExecutor: WizardExecutor
    @Mock private lateinit var rxBus: RxBus
    @Mock private lateinit var commandQueue: CommandQueue

    private val smbBeingDelivered = MutableStateFlow(false)
    private val bolusWizardProvider: () -> BolusWizard = mock()

    private lateinit var sut: WizardDialogViewModel

    private val mainDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // init { viewModelScope.launch { initialize() } } is deferred by StandardTestDispatcher, so construction
        // touches no collaborators and the internal BolusWizard stays null; the pure state flips below don't need it.
        // The scheduler is shared with runTest below, so the first advanceUntilIdle() in a test body runs
        // initialize() before the refresh queued after it, and drives everything the view model launches.
        // Stubbed before construction: init { } queues initialize() on the shared scheduler, so it runs
        // before any test body. With no profile store it returns straight away, which is all these tests need.
        whenever(profileRepository.profile).thenReturn(MutableStateFlow(null))
        // The max bolus suggestion reads the last glucose from the autosens store when the dialog has none. In the app
        // that store always exists; here an empty one stands in, so "no glucose" is the case these tests run.
        val ads = mock<AutosensDataStore>()
        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(commandQueue.smbBeingDelivered).thenReturn(smbBeingDelivered)
        whenever(rxBus.toFlow(EventAutosensCalculationFinished::class)).thenReturn(MutableSharedFlow())
        Dispatchers.setMain(mainDispatcher)
        sut = createViewModel()
    }

    private fun createViewModel(state: SavedStateHandle = SavedStateHandle()) = WizardDialogViewModel(
            state, bolusWizardProvider, constraintChecker, profileFunction, profileUtil,
            profileRepository, activePlugin, ch, iobCobCalculator, persistenceLayer, preferences, config,
            rh, dateUtil, decimalFormatter, aapsLogger, runningModeGuard, automation, wizardExecutor, rxBus,
            glucoseStatusProvider,
            CoroutineScope(UnconfinedTestDispatcher()), commandQueue
        )

    @Test
    fun `turning default auto FPU off removes suggested grams`() {
        val state = WizardDialogUiState(carbs = 40, fat = 14, protein = 16, fpuInstead = true)
        val cleared = state.withoutMealExtra(null, null, null)
        assertThat(cleared.fat).isEqualTo(0)
        assertThat(cleared.protein).isEqualTo(0)
        assertThat(cleared.fpuInstead).isFalse()
        assertThat(cleared.carbs).isEqualTo(40)
    }

    @Test
    fun `turning auto FPU off keeps manual protein and removes suggested fat`() {
        val state = WizardDialogUiState(fat = 14, protein = 25, fpuInstead = true)
        val cleared = state.withoutMealExtra(null, null, 25)
        assertThat(cleared.fat).isEqualTo(0)
        assertThat(cleared.protein).isEqualTo(25)
    }

    @Test
    fun `turning auto FPU off restores earlier manual grams and percentage`() {
        val state = WizardDialogUiState(fat = 14, protein = 16, percentage = 90, unreliableSmb = true)
        val restored = state.withoutMealExtra(Triple(5, 10, 100), null, null)
        assertThat(restored.fat).isEqualTo(5)
        assertThat(restored.protein).isEqualTo(10)
        assertThat(restored.percentage).isEqualTo(100)
        assertThat(restored.unreliableSmb).isFalse()
    }

    @Test
    fun `manual entry is retained while the profile is unavailable`() {
        val manual = createViewModel(SavedStateHandle(mapOf("carbs" to "0", "insulin" to "0.0")))
        mainDispatcher.scheduler.advanceUntilIdle()
        assertThat(manual.uiState.value.insulinOnly).isTrue()
        assertThat(manual.uiState.value.deliveredInsulin).isEqualTo(0.0)
        assertThat(manual.uiState.value.okVisible).isFalse()
    }

    @Test
    fun `zero manual insulin stays zero even when the calculator suggests a dose`() {
        val state = WizardDialogUiState(fixedInsulin = parseFixedInsulin("0.0"), totalInsulin = 3.5)
        assertThat(state.insulinOnly).isTrue()
        assertThat(state.deliveredInsulin).isEqualTo(0.0)
        assertThat(state.carbs).isEqualTo(0)
        assertThat(state.okVisible).isFalse()
    }

    @Test
    fun `a missing manual insulin argument keeps the usual calculator`() {
        val state = WizardDialogUiState(fixedInsulin = parseFixedInsulin(null), totalInsulin = 3.5)
        assertThat(state.insulinOnly).isFalse()
        assertThat(state.deliveredInsulin).isEqualTo(3.5)
    }

    @Test
    fun `an invalid manual insulin argument cannot fall back to the calculated dose`() {
        for (argument in listOf("-1", "NaN", "Infinity", "invalid")) {
            val state = WizardDialogUiState(fixedInsulin = parseFixedInsulin(argument), totalInsulin = 3.5)
            assertThat(state.insulinOnly).isTrue()
            assertThat(state.deliveredInsulin).isEqualTo(0.0)
        }
    }

    @Test
    fun `a positive manual dose is kept instead of the calculator suggestion`() {
        val state = WizardDialogUiState(fixedInsulin = parseFixedInsulin("1.25"), totalInsulin = 3.5)
        assertThat(state.deliveredInsulin).isEqualTo(1.25)
    }

    @Test
    fun `typing a manual dose enables confirm and clearing it disables confirm`() {
        val opened = WizardDialogUiState(fixedInsulin = 0.0, totalInsulin = 3.5, hasResult = true)
        val typed = opened.withFixedInsulin(1.25)
        assertThat(typed.okVisible).isTrue()
        assertThat(typed.deliveredInsulin).isEqualTo(1.25)
        assertThat(typed.totalInsulin).isEqualTo(3.5)
        assertThat(typed.withFixedInsulin(0.0).okVisible).isFalse()
        assertThat(opened.copy(hasResult = false).withFixedInsulin(1.25).okVisible).isFalse()
    }

    @Test
    fun `positive carbs leave manual mode and restore meal defaults`() {
        val manual = WizardDialogUiState(fixedInsulin = 1.25, maxCarbs = 100, totalInsulin = 3.5, hasResult = true, okVisible = true, giveRestLater = false)
        val meal = manual.withMealCarbs(30, autoFpu = true, unreliable = false, small = true)
        assertThat(meal.insulinOnly).isFalse()
        assertThat(meal.deliveredInsulin).isEqualTo(3.5)
        assertThat(meal.carbs).isEqualTo(30)
        assertThat(meal.fpuInstead).isTrue()
        assertThat(meal.smallMeal).isTrue()
        assertThat(meal.giveRestLater).isTrue()
        // Wait for the meal calculation before allowing confirmation.
        assertThat(meal.hasResult).isFalse()
        assertThat(meal.okVisible).isFalse()
        assertThat(meal.withMealCarbs(0, autoFpu = false, unreliable = false, small = false).insulinOnly).isFalse()
    }

    @Test
    fun `zero carbs keep the entered dose and positive carbs respect unreliable defaults`() {
        val manual = WizardDialogUiState(fixedInsulin = 1.25, maxCarbs = 100, giveRestLater = false)
        assertThat(manual.withMealCarbs(0, true, true, true)).isEqualTo(manual)
        val meal = manual.withMealCarbs(200, autoFpu = true, unreliable = true, small = false)
        assertThat(meal.carbs).isEqualTo(100)
        assertThat(meal.unreliableSmb).isTrue()
        assertThat(meal.fpuInstead).isFalse()
        assertThat(meal.smallMeal).isFalse()
        assertThat(manual.copy(maxCarbs = 0).withMealCarbs(30, true, true, true).insulinOnly).isTrue()
    }

    // The BG field follows readings until the user edits it.
    @Test
    fun `the BG field follows new readings until the user edits it`() {
        val opened = WizardDialogUiState(bg = 6.1, bgAgeMinutes = 4, hasBgData = true)

        val followed = opened.withNewReading(bg = 7.3, ageMinutes = 0)
        assertThat(followed.bg).isEqualTo(7.3)
        assertThat(followed.bgAgeMinutes).isEqualTo(0)

        val edited = opened.copy(bg = 5.0, bgEdited = true)
        assertThat(edited.withNewReading(bg = 7.3, ageMinutes = 0)).isEqualTo(edited)
    }

    @Test
    fun `only a changed BG counts as edited`() {
        val shown = sut.uiState.value.bg
        sut.updateBg(shown)
        assertThat(sut.uiState.value.bgEdited).isFalse()

        sut.updateBg(shown + 1.0)
        assertThat(sut.uiState.value.bgEdited).isTrue()
    }

    // #5381: the master calculates the dose only after the SMB has finished, with the SMB in IOB.
    @Test
    fun `OK waits while an SMB is being delivered`() {
        mainDispatcher.scheduler.advanceUntilIdle()
        smbBeingDelivered.value = true
        mainDispatcher.scheduler.advanceUntilIdle()
        assertThat(sut.uiState.value.smbBeingDelivered).isTrue()

        val withInsulin = sut.uiState.value.copy(totalInsulin = 1.5)
        assertThat(withInsulin.waitsForSmb).isTrue()
        assertThat(withInsulin.copy(forcedRecordOnly = true).waitsForSmb).isFalse()
        assertThat(withInsulin.copy(totalInsulin = 0.0).waitsForSmb).isFalse()

        smbBeingDelivered.value = false
        mainDispatcher.scheduler.advanceUntilIdle()
        assertThat(sut.uiState.value.smbBeingDelivered).isFalse()
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    /**
     * #5415: the confirmation and the record-only path read the alarm and the note from the wizard of the last
     * calculation. Changed as the last step, they did not reach it: the alarm rang although it was turned off,
     * or did not ring although it was turned on, and the note was not saved.
     */
    @Test
    fun `a changed alarm or note reaches the wizard`() {
        val wizards = givenWizardsThatCalculate()

        sut.updateCarbs(40)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertThat(calculation(wizards.last())[USE_ALARM]).isEqualTo(false)

        sut.toggleAlarm(true)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertThat(calculation(wizards.last())[USE_ALARM]).isEqualTo(true)

        sut.updateNotes("pen")
        mainDispatcher.scheduler.advanceUntilIdle()
        assertThat(calculation(wizards.last())[NOTES]).isEqualTo("pen")
    }

    /**
     * #5415, seen on a phone: a note typed fast starts a calculation for every letter, and they can end in another
     * order. "test5415" was saved as "test54", because an older calculation ended last and its wizard was kept.
     */
    @Test
    fun `an older calculation that ends last does not replace a newer one`() {
        val wizards = givenWizardsThatCalculate()
        var first = true
        persistenceLayer.stub {
            onBlocking { getTemporaryTargetActiveAt(any()) } doSuspendableAnswer {
                if (first) {
                    first = false
                    delay(1_000)
                }
                null
            }
        }

        sut.updateNotes("test54")
        mainDispatcher.scheduler.runCurrent() // its calculation starts and waits for the slow read
        sut.updateNotes("test5415")
        mainDispatcher.scheduler.advanceUntilIdle() // the newer one ends first, then the older one

        sut.getConfirmationSummary()
        val used = wizards.single { wizard -> mockingDetails(wizard).invocations.any { it.method.name == "buildConfirmationLines" } }
        assertThat(calculation(used)[NOTES]).isEqualTo("test5415")
    }

    /** Every calculation gets its own wizard, whose `doCalc` returns itself. Returns them in the order made. */
    private fun givenWizardsThatCalculate(): List<BolusWizard> {
        // Only after initialize() has run with no profile store, so it does not need everything a real start does
        mainDispatcher.scheduler.advanceUntilIdle()
        whenever(profileRepository.profile).thenReturn(MutableStateFlow(mock<ProfileStore>()))
        profileFunction.stub {
            onBlocking { getProfile() } doReturn mock<EffectiveProfile>()
            onBlocking { getProfileName() } doReturn "Test"
        }
        whenever(constraintChecker.applyCarbsConstraints(any())).thenAnswer { it.arguments[0] }
        val wizards = mutableListOf<BolusWizard>()
        whenever(bolusWizardProvider.invoke()).thenAnswer {
            mock<BolusWizard>(defaultAnswer = { invocation ->
                if (invocation.method.name == "doCalc") invocation.mock else RETURNS_DEFAULTS.answer(invocation)
            }).also { wizard ->
                whenever(wizard.data).thenReturn(mock<BolusWizardData>())
                wizards += wizard
            }
        }
        return wizards
    }

    /** The arguments of the `doCalc` of [wizard]. */
    private fun calculation(wizard: BolusWizard): Array<Any?> =
        mockingDetails(wizard).invocations.single { it.method.name == "doCalc" }.arguments

    @Test
    fun updateNotesAndToggleAlarmUpdateTheState() {
        sut.updateNotes("wizard note")
        sut.toggleAlarm(true)

        assertThat(sut.uiState.value.notes).isEqualTo("wizard note")
        assertThat(sut.uiState.value.alarmChecked).isTrue()
    }

    // #5327: eating later turns the "time to eat" alarm on, as in 3.x. A manual untick is kept while the minutes change.
    @Test
    fun `eating later ticks the alarm, and an untick stays while the time changes`() {
        assertThat(sut.uiState.value.alarmChecked).isFalse()

        sut.updateCarbTime(15)
        assertThat(sut.uiState.value.alarmChecked).isTrue()

        sut.toggleAlarm(false)
        sut.updateCarbTime(20)
        assertThat(sut.uiState.value.alarmChecked).isFalse()

        // Back to now and later again: a new decision, so it is ticked again.
        sut.updateCarbTime(0)
        sut.updateCarbTime(10)
        assertThat(sut.uiState.value.alarmChecked).isTrue()
    }

    @Test
    fun `eating earlier does not tick the alarm`() {
        sut.updateCarbTime(-15)

        assertThat(sut.uiState.value.alarmChecked).isFalse()
    }

    /**
     * `initialize()` and `recalculateSuspend()` both return as soon as there is no profile store, so
     * these tests reach the percentage decision without standing up the whole wizard.
     */
    private fun stubForRefresh(storedPercentage: Int) {
        whenever(preferences.get(BooleanNonKey.WizardIncludeTrend)).thenReturn(false)
        whenever(preferences.get(BooleanNonKey.WizardIncludeCob)).thenReturn(false)
        whenever(preferences.get(BooleanKey.OverviewUseBolusAdvisor)).thenReturn(false)
        whenever(preferences.get(IntKey.OverviewBolusPercentage)).thenReturn(storedPercentage)
        whenever(preferences.get(IntKey.OverviewResetBolusPercentageTime)).thenReturn(30)
        whenever(dateUtil.now()).thenReturn(NOW)
    }

    private fun lastGlucoseValueAt(timestamp: Long) =
        GV(timestamp = timestamp, raw = null, value = 100.0, trendArrow = TrendArrow.FLAT, noise = null, sourceSensor = SourceSensor.UNKNOWN)

    @Test
    fun `dismissing the settings sheet keeps the percentage the user set in the dialog`() = runTest(mainDispatcher.scheduler) {
        stubForRefresh(storedPercentage = 100)
        sut.refreshAfterSettings()
        advanceUntilIdle()

        sut.updatePercentage(150)
        advanceUntilIdle()
        assertThat(sut.uiState.value.percentage).isEqualTo(150)

        // The sheet has no control for the per bolus percentage, so dismissing it must not touch it.
        sut.refreshAfterSettings()
        advanceUntilIdle()

        assertThat(sut.uiState.value.percentage).isEqualTo(150)
    }

    /**
     * The first read has nothing to compare against, so it only remembers the stored value. Without
     * this, a dismissal before the dialog finished starting up would apply the stored percentage over
     * whatever the slider held.
     */
    @Test
    fun `the first read of the stored percentage does not touch the slider`() = runTest(mainDispatcher.scheduler) {
        stubForRefresh(storedPercentage = 150)
        val before = sut.uiState.value.percentage

        sut.refreshAfterSettings()
        advanceUntilIdle()

        assertThat(sut.uiState.value.percentage).isEqualTo(before)
    }

    @Test
    fun `a percentage really changed in the settings sheet is applied`() = runTest(mainDispatcher.scheduler) {
        stubForRefresh(storedPercentage = 100)
        sut.refreshAfterSettings()
        advanceUntilIdle()

        whenever(preferences.get(IntKey.OverviewBolusPercentage)).thenReturn(120)
        whenever(persistenceLayer.getLastGlucoseValue()).thenReturn(lastGlucoseValueAt(NOW))
        sut.refreshAfterSettings()
        advanceUntilIdle()

        assertThat(sut.uiState.value.percentage).isEqualTo(120)
    }

    @Test
    fun `a percentage above 100 is dropped when the last glucose value is too old`() = runTest(mainDispatcher.scheduler) {
        stubForRefresh(storedPercentage = 100)
        sut.refreshAfterSettings()
        advanceUntilIdle()

        // Older than OverviewResetBolusPercentageTime, so the stored percentage must not be re-applied.
        whenever(preferences.get(IntKey.OverviewBolusPercentage)).thenReturn(150)
        whenever(persistenceLayer.getLastGlucoseValue()).thenReturn(lastGlucoseValueAt(NOW - T.mins(45).msecs()))
        sut.refreshAfterSettings()
        advanceUntilIdle()

        assertThat(sut.uiState.value.percentage).isEqualTo(100)
    }

    @Test
    fun `a percentage above 100 is dropped when there is no glucose value at all`() = runTest(mainDispatcher.scheduler) {
        stubForRefresh(storedPercentage = 100)
        sut.refreshAfterSettings()
        advanceUntilIdle()

        whenever(preferences.get(IntKey.OverviewBolusPercentage)).thenReturn(150)
        whenever(persistenceLayer.getLastGlucoseValue()).thenReturn(null)
        sut.refreshAfterSettings()
        advanceUntilIdle()

        assertThat(sut.uiState.value.percentage).isEqualTo(100)
    }

    private companion object {

        const val NOW = 1_700_000_000_000L

        /** Positions of `useAlarm` and `notes` in the parameters of `BolusWizard.doCalc`. */
        const val USE_ALARM = 15
        const val NOTES = 16
    }
}
