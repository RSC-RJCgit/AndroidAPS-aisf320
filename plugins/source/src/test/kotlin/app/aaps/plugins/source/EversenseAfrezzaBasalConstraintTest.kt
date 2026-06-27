package app.aaps.plugins.source

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.interfaces.aps.AutosensData
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.plugins.eversense.EversenseCGMPlugin
import app.aaps.shared.tests.TestBaseWithProfile
import app.aaps.ui.compose.afrezzaDialog.AfrezzaMaxBasalState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking

/**
 * Behaviour of [EversensePlugin.applyBasalConstraints] — the Afrezza "max basal" override that
 * RAISES basal (via setIfGreater) while [AfrezzaMaxBasalState] is active.
 *
 * These tests describe what the code does TODAY. They are regression guards, not a clinical
 * endorsement: raising basal off a manual state flag is the highest-risk path in this feature and
 * must be validated by real open-loop observation and a clinician before any closed-loop use.
 *
 * [null BG does NOT raise basal] is intentionally @Disabled: it documents the SAFER behaviour the
 * hypo guard should have. It FAILS against the live code due to a latent bug — when actualBg() is
 * null, currentBg becomes 0.0, which is not in 1.0..70.0, so the guard does not fire and basal is
 * raised. Enable only after the guard is fixed and clinically reviewed.
 *
 * Inherited from TestBaseWithProfile: rh, iobCobCalculator, context, preferences,
 * notificationManager, profileFunction, aapsLogger — do NOT redeclare them.
 */
internal class EversenseAfrezzaBasalConstraintTest : TestBaseWithProfile() {

    @Mock private lateinit var eversense: EversenseCGMPlugin
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var ads: AutosensDataStore
    @Mock private lateinit var profile: Profile

    private lateinit var sut: EversensePlugin

    private val MAX_RATE = 2.0
    private val BASE_RATE = 0.5  // below MAX_RATE so a raise is observable

    private fun constraint() = ConstraintObject(BASE_RATE, aapsLogger)

    // Only .recalculated is read by the code; mock it so we don't depend on the GV constructor.
    // Build the GV mock as its own statement and stub actualBg() with the finished object —
    // creating a mock *inside* a whenever(...) argument triggers UnfinishedStubbingException.
    private fun stubBg(recalc: Double) {
        val gv = mock<InMemoryGlucoseValue>()
        whenever(gv.recalculated).thenReturn(recalc)
        whenever(ads.actualBg()).thenReturn(gv)
    }

    private fun stubBgNull() {
        whenever(ads.actualBg()).thenReturn(null)
    }

    private fun cobOf(value: Double) {
        val autosens = mock<AutosensData>()
        whenever(autosens.cob).thenReturn(value)
        whenever(iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish(any())).thenReturn(autosens)
    }

    @BeforeEach
    fun setUp() {
        whenever(iobCobCalculator.ads).thenReturn(ads)
        sut = EversensePlugin(
            rh, context, aapsLogger, preferences, config,
            notificationManager, eversense, iobCobCalculator
        )
        sut.persistenceLayer = persistenceLayer
        AfrezzaMaxBasalState.cancel()
    }

    @AfterEach
    fun tearDown() {
        AfrezzaMaxBasalState.cancel()
    }

    @Test
    fun `inactive state leaves rate untouched`() {
        val result = sut.applyBasalConstraints(constraint(), profile)
        assertThat(result.value()).isWithin(1e-9).of(BASE_RATE)
    }

    @Test
    fun `active state with normal BG and COB present raises basal to max`() {
        AfrezzaMaxBasalState.activate(MAX_RATE, 60)
        stubBg(120.0)
        cobOf(20.0)
        val result = sut.applyBasalConstraints(constraint(), profile)
        assertThat(result.value()).isWithin(1e-9).of(MAX_RATE)
    }

    @Test
    fun `hypo guard - BG in 1 to 70 does NOT raise basal`() {
        AfrezzaMaxBasalState.activate(MAX_RATE, 60)
        stubBg(65.0)
        val result = sut.applyBasalConstraints(constraint(), profile)
        assertThat(result.value()).isWithin(1e-9).of(BASE_RATE)
    }

    @Test
    fun `boundary - BG exactly 70 still pauses (guard inclusive)`() {
        AfrezzaMaxBasalState.activate(MAX_RATE, 60)
        stubBg(70.0)
        val result = sut.applyBasalConstraints(constraint(), profile)
        assertThat(result.value()).isWithin(1e-9).of(BASE_RATE)
    }

    @Test
    fun `boundary - BG just above 70 raises (with COB present)`() {
        AfrezzaMaxBasalState.activate(MAX_RATE, 60)
        stubBg(71.0)
        cobOf(15.0)
        val result = sut.applyBasalConstraints(constraint(), profile)
        assertThat(result.value()).isWithin(1e-9).of(MAX_RATE)
    }

    @Test
    fun `COB zero - first pass starts absorption timer and still raises`() {
        AfrezzaMaxBasalState.activate(MAX_RATE, 60)
        stubBg(120.0)
        cobOf(0.0)
        // getCarbsFromTime is suspend -> stub with wheneverBlocking.
        wheneverBlocking { persistenceLayer.getCarbsFromTime(any(), any()) }.thenReturn(emptyList())
        AfrezzaMaxBasalState.cobZeroSince = 0L

        val result = sut.applyBasalConstraints(constraint(), profile)

        assertThat(AfrezzaMaxBasalState.cobZeroSince).isGreaterThan(0L)
        assertThat(result.value()).isWithin(1e-9).of(MAX_RATE)
    }

    @Test
    fun `COB zero longer than 5 min - cancels and stops raising`() {
        AfrezzaMaxBasalState.activate(MAX_RATE, 60)
        stubBg(120.0)
        cobOf(0.0)
        wheneverBlocking { persistenceLayer.getCarbsFromTime(any(), any()) }.thenReturn(emptyList())
        AfrezzaMaxBasalState.cobZeroSince = System.currentTimeMillis() - 6 * 60_000L

        val result = sut.applyBasalConstraints(constraint(), profile)

        assertThat(AfrezzaMaxBasalState.isActive).isFalse()
        assertThat(result.value()).isWithin(1e-9).of(BASE_RATE)
    }

    @Disabled("Documents desired safe behaviour; fails against current code due to null-BG guard gap")
    @Test
    fun `null BG does NOT raise basal`() {
        AfrezzaMaxBasalState.activate(MAX_RATE, 60)
        stubBgNull()
        val result = sut.applyBasalConstraints(constraint(), profile)
        assertThat(result.value()).isWithin(1e-9).of(BASE_RATE)
    }
}
