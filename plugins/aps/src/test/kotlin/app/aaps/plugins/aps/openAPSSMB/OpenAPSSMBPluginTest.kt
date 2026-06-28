package app.aaps.plugins.aps.openAPSSMB

import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import app.aaps.core.data.afrezza.AfrezzaMaxBasalState
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.interfaces.aps.AutosensData
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.keys.DoubleKey
import app.aaps.core.objects.constraints.ConstraintObject
import org.junit.jupiter.api.AfterEach
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking

class OpenAPSSMBPluginTest : TestBaseWithProfile() {

    @Mock lateinit var constraintChecker: ConstraintsChecker
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock lateinit var determineBasalSMB: DetermineBasalSMB
    @Mock lateinit var bgQualityCheck: BgQualityCheck
    @Mock lateinit var ads: AutosensDataStore
    @Mock lateinit var tddCalculator: TddCalculator
    @Mock lateinit var profiler: Profiler
    private lateinit var openAPSSMBPlugin: OpenAPSSMBPlugin

    @BeforeEach fun prepare() {
        openAPSSMBPlugin = OpenAPSSMBPlugin(
            aapsLogger, rxBus, constraintChecker, rh, profileFunction, profileUtil, config, activePlugin, insulin,
            iobCobCalculator, hardLimits, preferences, dateUtil, processedTbrEbData, persistenceLayer, glucoseStatusProvider,
            tddCalculator, bgQualityCheck, notificationManager, determineBasalSMB, profiler, GlucoseStatusCalculatorSMB(aapsLogger, iobCobCalculator, dateUtil, decimalFormatter, deltaCalculator), apsResultProvider, ch,
            fabricPrivacy
        )
    }

    @Test
    fun specialEnableConditionTest() {
        assertThat(openAPSSMBPlugin.specialEnableCondition()).isTrue()
    }

    @Test
    fun specialShowInListConditionTest() {
        assertThat(openAPSSMBPlugin.specialShowInListCondition()).isTrue()
    }


    // ---------- Afrezza max-basal constraint tests ----------
    //
    // applyBasalConstraints now lives in OpenAPSSMBPlugin. The Afrezza block runs AFTER the
    // standard APS caps and raises basal (setIfGreater) up to minOf(AfrezzaMaxBasalState.rate,
    // ApsMaxBasal) while the state is active. ApsMaxBasal is the hard ceiling.
    //
    // These are regression guards for the CODE PATH, not clinical validation. Raising basal off a
    // manual flag must be confirmed by open-loop observation and a clinician before closed-loop use.

    private val afrezzaRate = 2.0
    private val baseRate = 0.5        // below afrezzaRate so a raise is observable
    private val apsMaxHigh = 5.0      // ApsMaxBasal high enough not to cap the afrezzaRate

    private fun constraint() = ConstraintObject(baseRate, aapsLogger)

    // Only .recalculated is read; mock the GV as its own statement (mock-inside-whenever => UnfinishedStubbing).
    private fun stubBg(recalc: Double) {
        val gv = mock<InMemoryGlucoseValue>()
        whenever(gv.recalculated).thenReturn(recalc)
        whenever(ads.actualBg()).thenReturn(gv)
    }
    private fun stubBgNull() { whenever(ads.actualBg()).thenReturn(null) }

    private fun cobOf(value: Double) {
        val autosens = mock<AutosensData>()
        whenever(autosens.cob).thenReturn(value)
        whenever(iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish(any())).thenReturn(autosens)
    }

    // Must be called in every Afrezza test: the block reads ApsMaxBasal for its minOf cap.
    private fun stubApsMaxBasal(v: Double) {
        whenever(preferences.get(DoubleKey.ApsMaxBasal)).thenReturn(v)
    }

    @BeforeEach
    fun afrezzaSetUp() {
        whenever(iobCobCalculator.ads).thenReturn(ads)
        AfrezzaMaxBasalState.cancel()
    }

    @AfterEach
    fun afrezzaTearDown() { AfrezzaMaxBasalState.cancel() }

    @Test
    fun `Afrezza inactive leaves rate untouched`() {
        stubApsMaxBasal(apsMaxHigh)
        val result = openAPSSMBPlugin.applyBasalConstraints(constraint(), validProfile)
        assertThat(result.value()).isWithin(1e-9).of(baseRate)
    }

    @Test
    fun `Afrezza active with normal BG and COB present raises to rate`() {
        stubApsMaxBasal(apsMaxHigh)
        AfrezzaMaxBasalState.activate(afrezzaRate, 60)
        stubBg(120.0); cobOf(20.0)
        val result = openAPSSMBPlugin.applyBasalConstraints(constraint(), validProfile)
        assertThat(result.value()).isWithin(1e-9).of(afrezzaRate)
    }

    @Test
    fun `Afrezza hypo guard - BG 65 does NOT raise`() {
        stubApsMaxBasal(apsMaxHigh)
        AfrezzaMaxBasalState.activate(afrezzaRate, 60)
        stubBg(65.0)
        val result = openAPSSMBPlugin.applyBasalConstraints(constraint(), validProfile)
        assertThat(result.value()).isWithin(1e-9).of(baseRate)
    }

    @Test
    fun `Afrezza boundary - BG exactly 70 still pauses`() {
        stubApsMaxBasal(apsMaxHigh)
        AfrezzaMaxBasalState.activate(afrezzaRate, 60)
        stubBg(70.0)
        val result = openAPSSMBPlugin.applyBasalConstraints(constraint(), validProfile)
        assertThat(result.value()).isWithin(1e-9).of(baseRate)
    }

    @Test
    fun `Afrezza boundary - BG just above 70 raises (COB present)`() {
        stubApsMaxBasal(apsMaxHigh)
        AfrezzaMaxBasalState.activate(afrezzaRate, 60)
        stubBg(71.0); cobOf(15.0)
        val result = openAPSSMBPlugin.applyBasalConstraints(constraint(), validProfile)
        assertThat(result.value()).isWithin(1e-9).of(afrezzaRate)
    }

    @Test
    fun `Afrezza COB zero - first pass starts timer and still raises`() {
        stubApsMaxBasal(apsMaxHigh)
        AfrezzaMaxBasalState.activate(afrezzaRate, 60)
        stubBg(120.0); cobOf(0.0)
        wheneverBlocking { persistenceLayer.getCarbsFromTime(any(), any()) }.thenReturn(emptyList())
        AfrezzaMaxBasalState.cobZeroSince = 0L
        val result = openAPSSMBPlugin.applyBasalConstraints(constraint(), validProfile)
        assertThat(AfrezzaMaxBasalState.cobZeroSince).isGreaterThan(0L)
        assertThat(result.value()).isWithin(1e-9).of(afrezzaRate)
    }

    @Test
    fun `Afrezza COB zero over 5 min - cancels and stops raising`() {
        stubApsMaxBasal(apsMaxHigh)
        AfrezzaMaxBasalState.activate(afrezzaRate, 60)
        stubBg(120.0); cobOf(0.0)
        wheneverBlocking { persistenceLayer.getCarbsFromTime(any(), any()) }.thenReturn(emptyList())
        AfrezzaMaxBasalState.cobZeroSince = System.currentTimeMillis() - 6 * 60_000L
        val result = openAPSSMBPlugin.applyBasalConstraints(constraint(), validProfile)
        assertThat(AfrezzaMaxBasalState.isActive).isFalse()
        assertThat(result.value()).isWithin(1e-9).of(baseRate)
    }

    // The safety rule: Afrezza can never exceed the user's OpenAPS Max Basal.
    @Test
    fun `Afrezza rate is capped at ApsMaxBasal`() {
        stubApsMaxBasal(1.0)                       // user's OpenAPS max = 1.0
        AfrezzaMaxBasalState.activate(2.0, 60)     // slider asks for 2.0
        stubBg(120.0); cobOf(20.0)
        val result = openAPSSMBPlugin.applyBasalConstraints(constraint(), validProfile)
        assertThat(result.value()).isWithin(1e-9).of(1.0)   // capped to 1.0, not 2.0
    }

    // null BG must pause, not raise basal.
    @Test
    fun `Afrezza null BG does NOT raise basal`() {
        stubApsMaxBasal(apsMaxHigh)
        AfrezzaMaxBasalState.activate(afrezzaRate, 60)
        stubBgNull()
        val result = openAPSSMBPlugin.applyBasalConstraints(constraint(), validProfile)
        assertThat(result.value()).isWithin(1e-9).of(baseRate)
    }

}
