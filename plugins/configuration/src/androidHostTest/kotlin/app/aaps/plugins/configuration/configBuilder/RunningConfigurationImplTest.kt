package app.aaps.plugins.configuration.configBuilder

import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.nsclient.NSClientRepository
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.scenes.ActiveSceneSnapshot
import app.aaps.core.interfaces.scenes.ActiveSceneSync
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.LongComposedKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.SyncChannel
import app.aaps.core.keys.interfaces.SyncDirection
import app.aaps.core.nssdk.localmodel.configuration.NSActiveScene
import app.aaps.core.nssdk.localmodel.configuration.NSRunningConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Guards the cold/hot settings-doc split: applying the cold config doc must never touch the
 * active-scene state, because the cold doc carries no `activeScene` and the apply used to clear
 * it unconditionally. Only the hot doc ([RunningConfigurationImpl.applyHot]) owns scene state.
 */
internal class RunningConfigurationImplTest {

    @Mock private lateinit var activePlugin: ActivePlugin
    @Mock private lateinit var activeSceneSync: ActiveSceneSync
    @Mock private lateinit var preferences: Preferences
    @Mock private lateinit var aapsLogger: AAPSLogger
    @Mock private lateinit var config: Config
    @Mock private lateinit var pumpSync: PumpSync
    @Mock private lateinit var notificationManager: NotificationManager
    @Mock private lateinit var nsClientRepository: NSClientRepository
    @Mock private lateinit var constraintsChecker: ConstraintsChecker
    @Mock private lateinit var dateUtil: DateUtil

    private lateinit var sut: RunningConfigurationImpl

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        whenever(config.AAPSCLIENT).thenReturn(true)
        sut = RunningConfigurationImpl(
            activePlugin, activeSceneSync,
            preferences, aapsLogger, config, pumpSync, notificationManager, nsClientRepository, constraintsChecker, dateUtil
        )
        whenever(dateUtil.now()).thenReturn(NOW)
    }

    /** Empty cold doc — nothing to apply, and crucially the active scene is left alone. */
    @Test
    fun applyColdEmptyDoesNotTouchActiveScene() {
        sut.applyCold(NSRunningConfiguration())
        verify(activeSceneSync, never()).applyActiveScene(anyOrNull())
    }

    /**
     * Scene *definitions* now ride the cold `syncedPrefs` block (StringNonKey.SceneDefinitions, Bidirectional).
     * Adopting them must NOT clear the running scene — definitions (cold) and active-scene state (hot) are
     * independent; SceneRepository.scenesFlow reloads from the key.
     */
    @Test
    fun applyColdWithSceneDefinitionsDoesNotTouchActiveScene() {
        whenever(preferences.get(StringNonKey.SceneDefinitions.key)).thenReturn(StringNonKey.SceneDefinitions)
        sut.applyCold(NSRunningConfiguration(syncedPrefs = mapOf(StringNonKey.SceneDefinitions.key to "[]")))
        verify(preferences).putRemote(StringNonKey.SceneDefinitions, "[]", 0L)
        verify(activeSceneSync, never()).applyActiveScene(anyOrNull())
    }

    /** The hot doc owns scene state: a null activeScene clears it (master-side dismissal propagates). */
    @Test
    fun applyHotWithNullActiveSceneClearsIt() {
        sut.applyHot(NSRunningConfiguration(activeScene = null, usedAutosensOnMainPhone = true))
        verify(activeSceneSync, times(1)).applyActiveScene(isNull())
        verify(preferences).put(eq(BooleanNonKey.AutosensUsedOnMainPhone), eq(true))
    }

    /** The hot doc applies a present scene snapshot. */
    @Test
    fun applyHotWithActiveSceneAppliesSnapshot() {
        val scene = NSActiveScene(sceneId = "scene-1", activatedAt = 1_000L, durationMs = 60_000L, lifecycle = "ACTIVE")
        sut.applyHot(NSRunningConfiguration(activeScene = scene))
        verify(activeSceneSync).applyActiveScene(
            eq(ActiveSceneSnapshot(sceneId = "scene-1", activatedAt = 1_000L, durationMs = 60_000L))
        )
    }

    /**
     * A String cold-channel key carried in the cold doc's `syncedPrefs` (e.g. AutomationEvents, which
     * moved onto the generic channel) must be adopted via `putRemote` — master-wins, version floored to
     * 0, no client→master echo — not a plain `put`. Guards the downlink apply path at the config layer.
     */
    @Test
    fun applyColdSyncedPrefsAdoptsStringColdKeyViaPutRemote() {
        val json = """[{"id":"x","title":"from-master"}]"""
        whenever(preferences.get(StringNonKey.AutomationEvents.key)).thenReturn(StringNonKey.AutomationEvents)
        sut.applyCold(NSRunningConfiguration(syncedPrefs = mapOf(StringNonKey.AutomationEvents.key to json)))
        verify(preferences).putRemote(StringNonKey.AutomationEvents, json, 0L)
    }

    /**
     * Plugin settings ride ONLY the generic cold `syncedPrefs` block now (flagged `SyncSpec(Cold, Bidirectional)`
     * — the legacy `apsConfiguration`/`sensitivityConfiguration`/`safetyConfiguration` sub-docs were deleted).
     * Representative guard that a plugin-settings key is flagged for the cold path, and that — carried in
     * `syncedPrefs` — it is adopted on the client via `putRemote` (master-wins, version floored).
     */
    /** 2026-10-21: a key that is local to each device now is not taken from an older master that still publishes it. */
    @Test
    fun applyColdIgnoresAKeyThatIsLocalToTheDevice() {
        assertNull(BooleanKey.ApsAutoIsfShowGraph5.sync)
        whenever(preferences.get(BooleanKey.ApsAutoIsfShowGraph5.key)).thenReturn(BooleanKey.ApsAutoIsfShowGraph5)
        sut.applyCold(NSRunningConfiguration(syncedPrefs = mapOf(BooleanKey.ApsAutoIsfShowGraph5.key to "false")))
        verify(preferences, never()).putRemote(eq(BooleanKey.ApsAutoIsfShowGraph5), any<Boolean>(), any())
    }

    /** A read-only client keeps a shared setting it changed itself: the master never got that change. */
    @Test
    fun readOnlyClientKeepsASettingItChangedItself() {
        whenever(preferences.get(BooleanKey.NsClientReadOnlyClient)).thenReturn(true)
        whenever(preferences.get(DoubleKey.AutosensMin.key)).thenReturn(DoubleKey.AutosensMin)
        whenever(preferences.get(LongComposedKey.SyncedPrefModified, DoubleKey.AutosensMin.key)).thenReturn(1_000L)
        sut.applyCold(NSRunningConfiguration(syncedPrefs = mapOf(DoubleKey.AutosensMin.key to "0.85")))
        verify(preferences, never()).putRemote(eq(DoubleKey.AutosensMin), any<Double>(), any())
    }

    /** An unpaired client keeps its change too: it can never reach the master (the iPhone toggles that switched back). */
    @Test
    fun unpairedClientKeepsASettingItChangedItself() {
        whenever(preferences.get(DoubleKey.AutosensMin.key)).thenReturn(DoubleKey.AutosensMin)
        whenever(preferences.get(LongComposedKey.SyncedPrefModified, DoubleKey.AutosensMin.key)).thenReturn(NOW - 60 * 60_000L)
        sut.applyCold(NSRunningConfiguration(syncedPrefs = mapOf(DoubleKey.AutosensMin.key to "0.85")))
        verify(preferences, never()).putRemote(eq(DoubleKey.AutosensMin), any<Double>(), any())
    }

    /** A paired client keeps a change for 3 minutes, so a settings doc already on its way cannot undo it. */
    @Test
    fun pairedClientKeepsAFreshChange() {
        whenever(preferences.get(LongNonKey.NsClientControlPairedAt)).thenReturn(1L)
        whenever(preferences.get(DoubleKey.AutosensMin.key)).thenReturn(DoubleKey.AutosensMin)
        whenever(preferences.get(LongComposedKey.SyncedPrefModified, DoubleKey.AutosensMin.key)).thenReturn(NOW - 60_000L)
        sut.applyCold(NSRunningConfiguration(syncedPrefs = mapOf(DoubleKey.AutosensMin.key to "0.85")))
        verify(preferences, never()).putRemote(eq(DoubleKey.AutosensMin), any<Double>(), any())
    }

    /** After that the master's value counts on a paired client: these settings change the loop. */
    @Test
    fun pairedClientFollowsTheMasterAfterTheHold() {
        whenever(preferences.get(LongNonKey.NsClientControlPairedAt)).thenReturn(1L)
        whenever(preferences.get(DoubleKey.AutosensMin.key)).thenReturn(DoubleKey.AutosensMin)
        whenever(preferences.get(LongComposedKey.SyncedPrefModified, DoubleKey.AutosensMin.key)).thenReturn(NOW - 5 * 60_000L)
        sut.applyCold(NSRunningConfiguration(syncedPrefs = mapOf(DoubleKey.AutosensMin.key to "0.85")))
        verify(preferences).putRemote(DoubleKey.AutosensMin, 0.85, 0L)
    }

    /** The display settings belong to each phone and are not shared at all. */
    @Test
    fun displaySettingsAreLocalToEachPhone() {
        assertNull(BooleanKey.ApsAutoIsfShowInsulinTotals.sync)
        assertNull(BooleanKey.ApsAutoIsfShowCarbModelCurve.sync)
        assertNull(BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled.sync)
        assertNull(BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled.sync)
        assertNull(BooleanKey.AutomationCodedLocationsEnabled.sync)
        assertNull(BooleanKey.OverviewUseBolusAdvisor.sync)
    }

    /** A read-only client still follows the master for a shared setting it never changed. */
    @Test
    fun readOnlyClientFollowsTheMasterForAnUntouchedSetting() {
        whenever(preferences.get(BooleanKey.NsClientReadOnlyClient)).thenReturn(true)
        whenever(preferences.get(DoubleKey.AutosensMin.key)).thenReturn(DoubleKey.AutosensMin)
        sut.applyCold(NSRunningConfiguration(syncedPrefs = mapOf(DoubleKey.AutosensMin.key to "0.85")))
        verify(preferences).putRemote(DoubleKey.AutosensMin, 0.85, 0L)
    }

    @Test
    fun applyColdSyncedPrefsAdoptsPluginSettingDoubleViaPutRemote() {
        assertEquals(SyncChannel.Cold, DoubleKey.AutosensMin.sync?.channel)
        assertEquals(SyncDirection.Bidirectional, DoubleKey.AutosensMin.sync?.direction)
        whenever(preferences.get(DoubleKey.AutosensMin.key)).thenReturn(DoubleKey.AutosensMin)
        sut.applyCold(NSRunningConfiguration(syncedPrefs = mapOf(DoubleKey.AutosensMin.key to "0.85")))
        verify(preferences).putRemote(DoubleKey.AutosensMin, 0.85, 0L)
    }

    /**
     * The master's computed `isFakingTempsByExtendedBoluses` rides the cold doc; the client mirrors it READ-ONLY onto
     * its VirtualPump.fakeDataDetected. A `true` propagates.
     */
    @Test
    fun applyColdMirrorsFakingTrueOntoVirtualPump() {
        val pump = mock<Pump>(extraInterfaces = arrayOf(VirtualPump::class))
        whenever(activePlugin.activePumpInternal).thenReturn(pump)
        sut.applyCold(NSRunningConfiguration(isFakingTempsByExtendedBoluses = true))
        verify(pump as VirtualPump).fakeDataDetected = true
    }

    /** Mirror is verbatim, not one-way: `false` propagates too (the master turning the mode off reaches the client). */
    @Test
    fun applyColdMirrorsFakingFalseOntoVirtualPump() {
        val pump = mock<Pump>(extraInterfaces = arrayOf(VirtualPump::class))
        whenever(activePlugin.activePumpInternal).thenReturn(pump)
        sut.applyCold(NSRunningConfiguration(isFakingTempsByExtendedBoluses = false))
        verify(pump as VirtualPump).fakeDataDetected = false
    }

    /** Older master omits the field (null) → the client's flag is left untouched (no write). */
    @Test
    fun applyColdNullFakingLeavesVirtualPumpUntouched() {
        val pump = mock<Pump>(extraInterfaces = arrayOf(VirtualPump::class))
        whenever(activePlugin.activePumpInternal).thenReturn(pump)
        sut.applyCold(NSRunningConfiguration())
        verify(pump as VirtualPump, never()).fakeDataDetected = any()
    }

    /**
     * The pump type of the master goes into the driver's own description. Through the concentration wrapper
     * it would go into a copy when the insulin is not U100, and be lost.
     */
    @Test
    fun applyColdPutsThePumpTypeOfTheMasterIntoTheDriversOwnDescription() {
        val description = PumpDescription()
        val driver = mock<Pump>()
        whenever(driver.pumpDescription).thenReturn(description)
        whenever(activePlugin.activePumpInternal).thenReturn(driver)
        whenever(preferences.get(StringKey.VirtualPumpType)).thenReturn("Generic AAPS")

        sut.applyCold(NSRunningConfiguration(pump = "Accu-Chek Combo"))

        assertEquals(PumpType.ACCU_CHEK_COMBO, description.pumpType)
        verify(activePlugin, never()).activePump
    }

    /**
     * Pre-init guard: before init completes no pump is selected, and activePump.isInitialized() would throw
     * "No pump selected" (PluginStore.activePumpInternal). configuration() must short-circuit to an empty doc
     * without touching the pump — RunningConfigurationPublisher then skips the empty payload and retries.
     */
    @Test
    fun configurationBeforeAppInitializedReturnsEmptyWithoutTouchingPump() {
        whenever(config.appInitialized).thenReturn(false)
        val result = sut.configuration()
        assertEquals(0, result.size)
        verify(activePlugin, never()).activePump
    }

    private companion object {

        const val NOW = 10_000_000_000L
    }
}
