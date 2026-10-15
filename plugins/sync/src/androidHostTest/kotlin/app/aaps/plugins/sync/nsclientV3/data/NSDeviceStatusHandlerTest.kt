package app.aaps.plugins.sync.nsclientV3.data

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringNonPreferenceKey
import app.aaps.core.nssdk.localmodel.devicestatus.NSDeviceStatus
import app.aaps.plugins.sync.nsclientV3.NSClientV3Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

internal class NSDeviceStatusHandlerTest {

    @Mock private lateinit var preferences: Preferences
    @Mock private lateinit var config: Config
    @Mock private lateinit var dateUtil: DateUtil
    @Mock private lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Mock private lateinit var aapsLogger: AAPSLogger
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var overviewData: OverviewData
    @Mock private lateinit var calculationWorkflow: CalculationWorkflow
    @Mock private lateinit var rxBus: RxBus
    @Mock private lateinit var nsClientV3Plugin: NSClientV3Plugin
    @Mock private lateinit var activePlugin: ActivePlugin
    @Mock private lateinit var profileUtil: ProfileUtil

    private lateinit var sut: NSDeviceStatusHandler

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        whenever(config.AAPSCLIENT).thenReturn(true)
        sut = NSDeviceStatusHandler(
            preferences, config, dateUtil, processedDeviceStatusData, aapsLogger,
            persistenceLayer, overviewData, calculationWorkflow, rxBus,
            CoroutineScope(Dispatchers.Unconfined), { nsClientV3Plugin },
            activePlugin, profileUtil
        )
    }

    /**
     * Regression: the master-alive heartbeat must be the NEWEST devicestatus's own created_at, NOT the
     * receipt time. Otherwise a stale historical devicestatus pulled by the catch-up worker at app start
     * (master already offline) would falsely mark the master freshly alive and unlock client editing.
     */
    @Test
    fun bumpsHeartbeatWithNewestCreatedAtNotReceiptTime() {
        whenever(dateUtil.now()).thenReturn(9_999L)                       // receipt time — must NOT be used
        whenever(dateUtil.fromISODateString("old")).thenReturn(1_000L)
        whenever(dateUtil.fromISODateString("new")).thenReturn(5_000L)

        sut.handleNewData(arrayOf(NSDeviceStatus(createdAt = "old"), NSDeviceStatus(createdAt = "new")))

        verify(nsClientV3Plugin).bumpDevicestatusHeartbeat(5_000L)
        verify(nsClientV3Plugin, never()).bumpDevicestatusHeartbeat(9_999L)
    }

    /**
     * A downloaded status counts too. One missed live push must not end the link.
     * The time on the status is what the 9-minute check uses.
     */
    @Test
    fun bumpsHeartbeatOnDownloadedStatus() {
        whenever(dateUtil.fromISODateString("new")).thenReturn(5_000L)
        sut.handleNewData(arrayOf(NSDeviceStatus(createdAt = "new")))
        verify(nsClientV3Plugin).bumpDevicestatusHeartbeat(5_000L)
    }

    @Test
    fun downloadedBatchKeepsHeartbeatAndRebuildsPredictionsOnce() {
        whenever(dateUtil.fromISODateString("old")).thenReturn(1_000L)
        whenever(dateUtil.fromISODateString("new")).thenReturn(5_000L)

        sut.handleNewData(arrayOf(NSDeviceStatus(createdAt = "old"), NSDeviceStatus(createdAt = "new")))

        verify(nsClientV3Plugin).bumpDevicestatusHeartbeat(5_000L)
        verifyBlocking(calculationWorkflow) { runOnReceivedPredictions(overviewData) }
    }

    /** A master device must not stamp a client heartbeat — even for a live push. */
    @Test
    fun doesNotBumpOnMaster() {
        whenever(config.AAPSCLIENT).thenReturn(false)
        sut.handleNewData(arrayOf(NSDeviceStatus(createdAt = "new")))
        verify(nsClientV3Plugin, never()).bumpDevicestatusHeartbeat(any())
    }

    /** created_at absent/unparseable but the numeric `date` (ms) is set → fall back to it. */
    @Test
    fun bumpsWithDateWhenCreatedAtAbsent() {
        sut.handleNewData(arrayOf(NSDeviceStatus(createdAt = null, date = 7_000L)))
        verify(nsClientV3Plugin).bumpDevicestatusHeartbeat(7_000L)
    }

    /** Neither a parseable created_at nor a date → can't determine freshness → don't bump (fail-closed). */
    @Test
    fun doesNotBumpWhenNoTimestamp() {
        sut.handleNewData(arrayOf(NSDeviceStatus(createdAt = null, date = null)))
        verify(nsClientV3Plugin, never()).bumpDevicestatusHeartbeat(any())
    }

    /** `toDb` throws for a result without an algorithm, and an error in the app scope ends the app. */
    @Test
    fun doesNotStoreApsResultWithoutAlgorithm() {
        givenNewSuggested(APSResult.Algorithm.UNKNOWN)

        sut.handleNewData(arrayOf(deviceStatusWithSuggested()))

        verifyBlocking(persistenceLayer, never()) { insertOrUpdateApsResult(any()) }
    }

    @Test
    fun storesApsResultWithAlgorithm() {
        val apsResult = givenNewSuggested(APSResult.Algorithm.SMB)

        sut.handleNewData(arrayOf(deviceStatusWithSuggested()))

        verifyBlocking(persistenceLayer) { insertOrUpdateApsResult(apsResult) }
    }

    private fun givenNewSuggested(algorithm: APSResult.Algorithm): APSResult {
        val apsResult = mock<APSResult>()
        whenever(apsResult.algorithm).thenReturn(algorithm)
        whenever(processedDeviceStatusData.openAPSData).thenReturn(ProcessedDeviceStatusData.OpenAPSData())
        whenever(processedDeviceStatusData.getAPSResult()).thenReturn(apsResult)
        whenever(dateUtil.fromISODateString(SUGGESTED_TIME)).thenReturn(5_000L)
        return apsResult
    }

    private fun deviceStatusWithSuggested() = NSDeviceStatus(
        device = "openaps://test",
        openaps = NSDeviceStatus.OpenAps(suggested = buildJsonObject {
            put("timestamp", SUGGESTED_TIME)
            put("runningDynamicIsf", false)
        })
    )

    // ---- read-only client follows the 3.x master's APS choice (2026-10-16) ----

    private fun apsPlugin(id: String, algorithm: APSResult.Algorithm): PluginBase =
        mock<PluginBase>(extraInterfaces = arrayOf(APS::class)).also {
            whenever(it.pluginId).thenReturn(id)
            whenever((it as APS).algorithm).thenReturn(algorithm)
        }

    private fun statusFromMaster(aps: String) =
        NSDeviceStatus(createdAt = "new", date = 5_000L, configuration = buildJsonObject { put("aps", aps); put("insulin", 5) })

    private fun readOnlyClientWithPlugins() {
        whenever(dateUtil.fromISODateString("new")).thenReturn(5_000L)
        whenever(preferences.get(BooleanKey.NsClientReadOnlyClient)).thenReturn(true)
        whenever(preferences.get(StringNonKey.ActivePluginAps)).thenReturn("OpenAPSSMBPlugin")
        // Made before the stubbing below: Mockito does not allow stubbing inside another thenReturn.
        val plugins = arrayListOf(apsPlugin("OpenAPSSMBPlugin", APSResult.Algorithm.SMB), apsPlugin("OpenAPSAutoISFPlugin", APSResult.Algorithm.AUTO_ISF))
        whenever(activePlugin.getSpecificPluginsList(PluginType.APS)).thenReturn(plugins)
    }

    @Test
    fun readOnlyClientTakesTheApsOfItsMaster() {
        readOnlyClientWithPlugins()

        sut.handleNewData(arrayOf(statusFromMaster("AUTO_ISF")))

        verify(preferences).putRemote(StringNonKey.ActivePluginAps, "OpenAPSAutoISFPlugin", 0L)
    }

    @Test
    fun readOnlyClientAlreadyOnTheSameApsWritesNothing() {
        readOnlyClientWithPlugins()

        sut.handleNewData(arrayOf(statusFromMaster("SMB")))

        verify(preferences, never()).putRemote(any<StringNonPreferenceKey>(), any(), any())
    }

    @Test
    fun aClientThatIsNotReadOnlyKeepsItsOwnAps() {
        readOnlyClientWithPlugins()
        whenever(preferences.get(BooleanKey.NsClientReadOnlyClient)).thenReturn(false)

        sut.handleNewData(arrayOf(statusFromMaster("AUTO_ISF")))

        verify(preferences, never()).putRemote(any<StringNonPreferenceKey>(), any(), any())
    }

    private companion object {

        const val SUGGESTED_TIME = "2026-10-12T16:35:00Z"
    }
}
