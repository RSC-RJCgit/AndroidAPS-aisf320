package app.aaps.ui.compose.maintenance

import app.aaps.core.interfaces.configuration.BuiltInSettingsApplier
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.L
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.maintenance.CloudDirectoryManager
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.maintenance.Maintenance
import app.aaps.core.interfaces.maintenance.ExportResult
import app.aaps.core.interfaces.maintenance.ExportPreparation
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.protection.PasswordHasher
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.sync.DataSyncSelectorXdrip
import app.aaps.core.interfaces.sync.NsClient
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.shared.tests.generatedTextResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
internal class MaintenanceViewModelTest {

    @Mock private lateinit var aapsLogger: AAPSLogger
    @Mock private lateinit var l: L
    @Mock private lateinit var maintenance: Maintenance
    @Mock private lateinit var importExportPrefs: ImportExportPrefs
    @Mock private lateinit var fileListProvider: FileListProvider
    @Mock private lateinit var cloudDirectoryManager: CloudDirectoryManager
    @Mock private lateinit var activePlugin: ActivePlugin
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var fabricPrivacy: FabricPrivacy
    @Mock private lateinit var uel: UserEntryLogger
    @Mock private lateinit var dataSyncSelectorXdrip: DataSyncSelectorXdrip
    @Mock private lateinit var pumpSync: PumpSync
    @Mock private lateinit var iobCobCalculator: IobCobCalculator
    @Mock private lateinit var overviewData: OverviewData
    @Mock private lateinit var overviewDataCache: OverviewDataCache
    @Mock private lateinit var nsClient: NsClient
    @Mock private lateinit var dateUtil: DateUtil
    @Mock private lateinit var preferences: Preferences
    @Mock private lateinit var config: Config
    @Mock private lateinit var passwordHasher: PasswordHasher
    @Mock private lateinit var builtInSettingsApplier: BuiltInSettingsApplier

    private lateinit var sut: MaintenanceViewModel
    private lateinit var testDispatcher: TestDispatcher

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // StandardTestDispatcher does NOT run the init{}-launched refreshExportConfig() coroutine
        // (no advanceUntilIdle), so construction stays clean and we test the synchronous state methods
        // against the default state.
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        // Answered already, so no built-in settings question is up unless a test asks for one
        whenever(preferences.get(StringNonKey.BuiltInSettingsChoice)).thenReturn("off")
        whenever(config.VERSION_NAME).thenReturn("4.0.0-test")
        whenever(config.HEAD).thenReturn("abc123")
        whenever(preferences.get(StringNonKey.BuiltInSettingsPromptBuild)).thenReturn("4.0.0-test@abc123")
        whenever(preferences.get(StringNonKey.ActivePluginPump)).thenReturn("VirtualPumpPlugin")
        whenever(config.appName).thenReturn(TextRef.Literal("AAPS"))
        sut = build()
    }

    private fun build() = MaintenanceViewModel(
        aapsLogger, generatedTextResolver(), l, maintenance, importExportPrefs, fileListProvider, cloudDirectoryManager,
        activePlugin, persistenceLayer, fabricPrivacy, uel, dataSyncSelectorXdrip, pumpSync,
        iobCobCalculator, overviewData, overviewDataCache, nsClient, dateUtil,
        preferences, config, passwordHasher, builtInSettingsApplier
    )

    // ----- Built-in settings (2026-11-01) -----

    @Test
    fun `without a master password defaults apply without a query`() {
        assertThat(sut.builtInStep.value).isNull()
        whenever(preferences.get(StringNonKey.BuiltInSettingsChoice)).thenReturn("")
        assertThat(build().builtInStep.value).isNull()
        verify(builtInSettingsApplier).apply("AAPS")
    }

    @Test
    fun `a master with a real pump is never asked`() {
        whenever(preferences.get(StringNonKey.BuiltInSettingsChoice)).thenReturn("")
        whenever(preferences.get(StringNonKey.ActivePluginPump)).thenReturn("OmnipodDashPumpPlugin")
        assertThat(build().builtInStep.value).isNull()
    }

    @Test
    fun `an update with a password asks immediately and keeps settings after ten seconds`() {
        whenever(preferences.get(BooleanNonKey.BuiltInSettingsAppliedBefore)).thenReturn(true)
        whenever(config.HEAD).thenReturn("next-build")
        whenever(importExportPrefs.isMasterPasswordSet()).thenReturn(true)
        val updated = build()
        assertThat(updated.builtInStep.value).isEqualTo(MaintenanceViewModel.BuiltInStep.Ask)
        verify(preferences).put(BooleanNonKey.GeneralSetupWizardProcessed, true)
        testDispatcher.scheduler.runCurrent()
        testDispatcher.scheduler.advanceTimeBy(9_999)
        testDispatcher.scheduler.runCurrent()
        assertThat(updated.builtInStep.value).isEqualTo(MaintenanceViewModel.BuiltInStep.Ask)
        testDispatcher.scheduler.advanceTimeBy(1)
        testDispatcher.scheduler.runCurrent()
        assertThat(updated.builtInStep.value).isNull()
        verify(preferences).put(StringNonKey.BuiltInSettingsChoice, "off")
        verify(builtInSettingsApplier, never()).apply(any())
    }

    @Test
    fun `defaults never applied leaves the prompt open beyond ten seconds`() {
        whenever(config.HEAD).thenReturn("next-build")
        whenever(importExportPrefs.isMasterPasswordSet()).thenReturn(true)
        val updated = build()

        testDispatcher.scheduler.runCurrent()
        testDispatcher.scheduler.advanceTimeBy(30_000)
        testDispatcher.scheduler.runCurrent()

        assertThat(updated.builtInStep.value).isEqualTo(MaintenanceViewModel.BuiltInStep.Ask)
        assertThat(updated.builtInStartupOffer.value).isFalse()
        verify(preferences, never()).put(StringNonKey.BuiltInSettingsChoice, "off")
        verify(preferences, never()).put(BooleanNonKey.BuiltInSettingsAppliedBefore, true)
        verify(builtInSettingsApplier, never()).apply(any())
    }

    @Test
    fun `a previous on choice records past use and keeps the timeout`() {
        whenever(config.HEAD).thenReturn("next-build")
        whenever(preferences.get(StringNonKey.BuiltInSettingsChoice)).thenReturn("on")
        whenever(importExportPrefs.isMasterPasswordSet()).thenReturn(true)
        val updated = build()

        verify(preferences).put(BooleanNonKey.BuiltInSettingsAppliedBefore, true)
        assertThat(updated.builtInStartupOffer.value).isTrue()
        testDispatcher.scheduler.runCurrent()
        testDispatcher.scheduler.advanceTimeBy(10_000)
        testDispatcher.scheduler.runCurrent()

        assertThat(updated.builtInStep.value).isNull()
        verify(preferences).put(StringNonKey.BuiltInSettingsChoice, "off")
        verify(preferences, never()).put(BooleanNonKey.BuiltInSettingsAppliedBefore, false)
    }

    @Test
    fun `an update without a password applies the preset silently`() {
        whenever(config.HEAD).thenReturn("next-build")
        assertThat(build().builtInStep.value).isNull()
        verify(builtInSettingsApplier).apply("AAPS")
    }

    @Test
    fun `opening defaults manually cancels the automatic timeout`() {
        whenever(preferences.get(BooleanNonKey.BuiltInSettingsAppliedBefore)).thenReturn(true)
        whenever(config.HEAD).thenReturn("next-build")
        whenever(importExportPrefs.isMasterPasswordSet()).thenReturn(true)
        val updated = build()
        updated.openBuiltInSettings()
        updated.answerBuiltIn(false)
        testDispatcher.scheduler.advanceTimeBy(10_001)
        testDispatcher.scheduler.runCurrent()
        assertThat(updated.builtInStep.value).isNull()
        verify(builtInSettingsApplier, never()).apply(any())
    }

    @Test
    fun `choosing defaults cancels the timer and waits for the password`() {
        whenever(config.HEAD).thenReturn("next-build")
        whenever(importExportPrefs.isMasterPasswordSet()).thenReturn(true)
        val updated = build()
        testDispatcher.scheduler.runCurrent()
        testDispatcher.scheduler.advanceTimeBy(9_000)
        updated.answerBuiltIn(true)
        testDispatcher.scheduler.advanceTimeBy(2_000)
        testDispatcher.scheduler.runCurrent()
        assertThat(updated.builtInStep.value).isEqualTo(MaintenanceViewModel.BuiltInStep.EnterPassword())
        verify(builtInSettingsApplier, never()).apply(any())
    }

    @Test
    fun `no changes nothing and records the answer`() {
        sut.openBuiltInSettings()
        sut.answerBuiltIn(useThem = false)

        verify(preferences).put(StringNonKey.BuiltInSettingsChoice, "off")
        verify(preferences).put(StringNonKey.BuiltInSettingsPromptBuild, "4.0.0-test@abc123")
        verify(builtInSettingsApplier, never()).apply(any())
        assertThat(sut.builtInStep.value).isNull()
    }

    @Test
    fun `with a master password set it is asked before anything is overwritten`() {
        whenever(importExportPrefs.isMasterPasswordSet()).thenReturn(true)
        whenever(importExportPrefs.isMasterPasswordCorrect("right")).thenReturn(true)
        whenever(builtInSettingsApplier.apply(any())).thenReturn(64)
        sut.openBuiltInSettings()
        sut.answerBuiltIn(useThem = true)
        assertThat(sut.builtInStep.value).isEqualTo(MaintenanceViewModel.BuiltInStep.EnterPassword())

        sut.enterBuiltInPassword("wrong")
        assertThat(sut.builtInStep.value).isEqualTo(MaintenanceViewModel.BuiltInStep.EnterPassword(wrong = true))
        verify(builtInSettingsApplier, never()).apply(any())

        sut.enterBuiltInPassword("right")
        verify(builtInSettingsApplier).apply("AAPS")
        assertThat(sut.builtInStep.value).isEqualTo(MaintenanceViewModel.BuiltInStep.Done(64))
    }

    @Test
    fun `without a master password one is set, and only when both fields match`() {
        whenever(importExportPrefs.isMasterPasswordSet()).thenReturn(false)
        whenever(passwordHasher.hashPassword("secret")).thenReturn("hash")
        sut.openBuiltInSettings()
        sut.answerBuiltIn(useThem = true)
        assertThat(sut.builtInStep.value).isEqualTo(MaintenanceViewModel.BuiltInStep.SetPassword())

        sut.setBuiltInPassword("secret", "secrte")
        assertThat(sut.builtInStep.value).isEqualTo(MaintenanceViewModel.BuiltInStep.SetPassword(mismatch = true))
        verify(builtInSettingsApplier, never()).apply(any())

        sut.setBuiltInPassword("secret", "secret")
        verify(preferences).put(StringKey.ProtectionMasterPassword, "hash")
        verify(builtInSettingsApplier).apply("AAPS")
    }

    @Test
    fun `cancelling password setup keeps the saved settings`() {
        whenever(importExportPrefs.isMasterPasswordSet()).thenReturn(false)
        sut.openBuiltInSettings()
        sut.answerBuiltIn(useThem = true)
        sut.answerBuiltIn(useThem = false)

        verify(preferences, never()).put(eq(StringKey.ProtectionMasterPassword), any<String>())
        verify(builtInSettingsApplier, never()).apply(any())
        verify(preferences).put(StringNonKey.BuiltInSettingsChoice, "off")
        assertThat(sut.builtInStep.value).isNull()
    }

    @Test
    fun `cancelling master password entry keeps the saved settings`() {
        whenever(importExportPrefs.isMasterPasswordSet()).thenReturn(true)
        sut.openBuiltInSettings()
        sut.answerBuiltIn(useThem = true)
        sut.answerBuiltIn(useThem = false)

        verify(builtInSettingsApplier, never()).apply(any())
        verify(preferences).put(StringNonKey.BuiltInSettingsChoice, "off")
        assertThat(sut.builtInStep.value).isNull()
    }

    @Test
    fun `a late password result after cancel cannot apply settings`() {
        whenever(importExportPrefs.isMasterPasswordSet()).thenReturn(true)
        whenever(importExportPrefs.isMasterPasswordCorrect("right")).thenReturn(true)
        sut.openBuiltInSettings()
        sut.answerBuiltIn(useThem = true)
        sut.answerBuiltIn(useThem = false)
        sut.enterBuiltInPassword("right")

        verify(builtInSettingsApplier, never()).apply(any())
        assertThat(sut.builtInStep.value).isNull()
    }

    @Test
    fun `a late password setup result after cancel cannot change settings or password`() {
        whenever(importExportPrefs.isMasterPasswordSet()).thenReturn(false)
        sut.openBuiltInSettings()
        sut.answerBuiltIn(useThem = true)
        sut.answerBuiltIn(useThem = false)
        sut.setBuiltInPassword("secret", "secret")

        verify(preferences, never()).put(eq(StringKey.ProtectionMasterPassword), any<String>())
        verify(builtInSettingsApplier, never()).apply(any())
        assertThat(sut.builtInStep.value).isNull()
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun defaultStateIsIdleExportHiddenCloudDirectoryNoConfig() {
        assertThat(sut.exportState.value).isEqualTo(MaintenanceViewModel.ExportState.Idle)
        assertThat(sut.cloudDirectoryState.value).isEqualTo(MaintenanceViewModel.CloudDirectoryState.Hidden)
        assertThat(sut.exportConfig.value).isNull()
        assertThat(sut.isDirectoryAccessGranted.value).isFalse()
        assertThat(sut.directoryPath.value).isNull()
    }

    @Test
    fun `refreshExportConfig shows the chosen directory even when access is lost`() = runBlocking {
        whenever(fileListProvider.isDirectoryAccessGranted()).thenReturn(false)
        whenever(fileListProvider.directoryPath()).thenReturn("Documents/AAPS")
        runEagerly()

        sut.refreshExportConfig()

        // The read runs on the IO dispatcher, so wait for it rather than assume it is done.
        val path = withTimeout(10_000) { sut.directoryPath.first { it != null } }
        assertThat(path).isEqualTo("Documents/AAPS")
        assertThat(sut.isDirectoryAccessGranted.value).isFalse()
    }

    @Test
    fun onExportConfirmedMovesExportStateToAskPassword() {
        sut.onExportConfirmed()

        assertThat(sut.exportState.value).isEqualTo(MaintenanceViewModel.ExportState.AskPassword())
    }

    @Test
    fun settingsExportUsesRetainedMasterWithoutAPasswordDialog() = runBlocking<Unit> {
        runEagerly()
        whenever(importExportPrefs.isMasterPasswordSet()).thenReturn(true)
        whenever(importExportPrefs.prepareExport()).thenReturn(ExportPreparation("settings.json", "master"))
        whenever(importExportPrefs.executeExport("master")).thenReturn(ExportResult(localSuccess = true))
        val event = expectEvent()

        sut.startExport()

        assertThat(event.await()).isInstanceOf(MaintenanceEvent.Snackbar::class.java)
        assertThat(sut.exportState.value).isEqualTo(MaintenanceViewModel.ExportState.Idle)
        verify(importExportPrefs).executeExport("master")
    }

    @Test
    fun cancelExportResetsExportStateToIdle() {
        sut.onExportConfirmed()
        sut.cancelExport()

        assertThat(sut.exportState.value).isEqualTo(MaintenanceViewModel.ExportState.Idle)
    }

    @Test
    fun onExportPasswordEnteredRejectsAPasswordThatIsNotTheMasterPassword() {
        whenever(importExportPrefs.isMasterPasswordCorrect("not-the-master")).thenReturn(false)
        sut.onExportConfirmed()

        sut.onExportPasswordEntered("not-the-master")

        // The wrong password is NOT cached and NOT used to export; the dialog stays open for a retry.
        verify(importExportPrefs, never()).cacheExportPassword(any())
        // The dialog reappears with the inline wrong-password error instead of silently staying open.
        assertThat(sut.exportState.value).isEqualTo(MaintenanceViewModel.ExportState.AskPassword(wrongPassword = true))
    }

    @Test
    fun onExportPasswordEnteredAcceptsAndCachesTheMasterPassword() {
        whenever(importExportPrefs.isMasterPasswordCorrect("master")).thenReturn(true)
        whenever(importExportPrefs.cacheExportPassword("master")).thenReturn("master")
        sut.onExportConfirmed()

        sut.onExportPasswordEntered("master")

        verify(importExportPrefs).cacheExportPassword("master")
        assertThat(sut.exportState.value).isEqualTo(MaintenanceViewModel.ExportState.Idle)
    }

    // The four below wait in real time rather than on the test scheduler. Every maintenance action
    // hops to `aapsIoDispatcher` for its slow part, and that is a real dispatcher on a real thread
    // pool - a global `expect val`, so a test cannot swap it. Virtual time simply does not reach it:
    // `advanceUntilIdle` returns while the work is still on its way, and the assertion then reads an
    // empty list and calls a working feature broken. `Main` is moved to an unconfined dispatcher for
    // the same reason, so the coroutine finishes on whichever thread resumed it.

    @Test
    fun sendLogsSaysSoOnScreenWhenThePlatformHasNoWayToSendThem() = runBlocking {
        // What iOS does: there is no mail composer, so Maintenance throws instead of pretending.
        // NotImplementedError is an Error rather than an Exception, so before the shared handler it
        // walked straight past `catch (e: Exception)` and took the app down.
        runEagerly()
        whenever(maintenance.executeSendLogs()).thenAnswer { throw NotImplementedError("no mail composer") }
        val event = expectEvent()

        sut.sendLogs()

        assertThat(event.await()).isEqualTo(MaintenanceEvent.Error("This function is not ready on this platform yet"))
    }

    @Test
    fun failedExtraExportDoesNotTurnAnOpenedLogEmailIntoAnError() = runBlocking {
        runEagerly()
        whenever(maintenance.executeSendLogs()).thenReturn(ExportResult(localSuccess = true))
        whenever(maintenance.exportCoordinated("MANUAL", withLogs = false)).thenAnswer { throw IllegalStateException("Cloud unavailable") }
        val event = expectEvent()

        sut.sendLogs()

        assertThat(event.await()).isInstanceOf(MaintenanceEvent.Snackbar::class.java)
    }

    @Test
    fun resetDatabasesSaysSoOnScreenWhenThePlatformCannotClearThem() = runBlocking {
        // Desktop still answers this way, and iOS did until the tables were cleared with SQL.
        runEagerly()
        whenever(persistenceLayer.clearDatabases()).thenAnswer { throw UnsupportedOperationException("no clearAllTables") }
        val event = expectEvent()

        sut.resetDatabases()

        assertThat(event.await()).isEqualTo(MaintenanceEvent.Error("This function is not ready on this platform yet"))
    }

    @Test
    fun aRealFailureGetsThePlainErrorMessageNotTheNotReadyOne() = runBlocking {
        runEagerly()
        whenever(persistenceLayer.cleanupDatabase(any(), any())).thenAnswer { throw IllegalStateException("database is locked") }
        val event = expectEvent()

        sut.cleanupDatabases()

        assertThat(event.await()).isEqualTo(MaintenanceEvent.Error("Error"))
    }

    @Test
    fun cleanupDatabasesReportsWhatItRemovedWhenItWorks() = runBlocking {
        runEagerly()
        whenever(persistenceLayer.cleanupDatabase(any(), any())).thenReturn("GlucoseValue 12")
        val event = expectEvent()

        sut.cleanupDatabases()

        assertThat(event.await()).isEqualTo(MaintenanceEvent.CleanupResult("GlucoseValue 12"))
    }

    /**
     * Lets the view model's coroutines run as they are started.
     *
     * Called after the view model is built, never before: the standard dispatcher from `setUp` is
     * what keeps the `init` block's `refreshExportConfig` from running against mocks nothing has
     * stubbed, and that would fail every test in the class rather than only these.
     */
    private fun runEagerly() = Dispatchers.setMain(UnconfinedTestDispatcher())

    /**
     * Starts listening and returns before the action does anything.
     *
     * `events` has no replay and no buffer, so an event sent before anybody listens is not kept and
     * not delivered - waiting for the subscription first is what makes the test about the action
     * rather than about who won the race. The timeout is only so a broken action fails as a test
     * instead of hanging the build.
     */
    private suspend fun CoroutineScope.expectEvent(): Deferred<MaintenanceEvent> {
        val listening = CompletableDeferred<Unit>()
        val event = async(Dispatchers.Default) {
            withTimeout(10_000) { sut.events.onSubscription { listening.complete(Unit) }.first() }
        }
        listening.await()
        return event
    }
}
