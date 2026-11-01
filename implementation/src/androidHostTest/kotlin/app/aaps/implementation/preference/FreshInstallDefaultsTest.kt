package app.aaps.implementation.preference

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class FreshInstallDefaultsTest {

    private val preferences: Preferences = mock()
    private val config: Config = mock()

    private fun setUp(client: Boolean, wizardDone: Boolean, stored: Boolean?) {
        whenever(config.AAPSCLIENT).thenReturn(client)
        whenever(preferences.get(BooleanNonKey.GeneralSetupWizardProcessed)).thenReturn(wizardDone)
        whenever(preferences.getIfExists(BooleanKey.NsClientReadOnlyClient)).thenReturn(stored)
    }

    @Test
    fun aNewClientStartsReadOnly() {
        setUp(client = true, wizardDone = false, stored = null)
        FreshInstallDefaults.apply(preferences, config)
        verify(preferences).put(BooleanKey.NsClientReadOnlyClient, true)
    }

    @Test
    fun anExistingClientKeepsItsValue() {
        // Set up before: an update must not change it, even when the setting was never touched
        setUp(client = true, wizardDone = true, stored = null)
        FreshInstallDefaults.apply(preferences, config)
        verify(preferences, never()).put(any<BooleanKey>(), any<Boolean>())
    }

    @Test
    fun aStoredValueIsKept() {
        setUp(client = true, wizardDone = false, stored = false)
        FreshInstallDefaults.apply(preferences, config)
        verify(preferences, never()).put(BooleanKey.NsClientReadOnlyClient, true)
    }

    @Test
    fun aMasterIsNotMadeReadOnly() {
        setUp(client = false, wizardDone = false, stored = null)
        FreshInstallDefaults.apply(preferences, config)
        verify(preferences, never()).put(BooleanKey.NsClientReadOnlyClient, true)
    }

    @Test
    fun everyNewInstallShowsTheGraphTextRows() {
        // Text rows remain visible on the main graph for both master and client.
        setUp(client = false, wizardDone = false, stored = null)
        FreshInstallDefaults.apply(preferences, config)
        verify(preferences).put(BooleanKey.ApsAutoIsfShowGraphText, true)
        verify(preferences).put(BooleanKey.ApsAutoIsfAutoCleanGraph, false)
    }

    @Test
    fun aStoredGraphChoiceIsKept() {
        setUp(client = false, wizardDone = false, stored = null)
        whenever(preferences.getIfExists(BooleanKey.ApsAutoIsfShowGraphText)).thenReturn(false)
        FreshInstallDefaults.apply(preferences, config)
        verify(preferences, never()).put(BooleanKey.ApsAutoIsfShowGraphText, true)
    }

    // The fork's AutoISF settings (2026-11-01)

    private fun forkSetUp(client: Boolean, pumpDrivers: Boolean) {
        whenever(config.AAPSCLIENT).thenReturn(client)
        whenever(config.PUMPDRIVERS).thenReturn(pumpDrivers)
    }

    @Test
    fun aVirtualMasterGetsTheForkSettings() {
        forkSetUp(client = false, pumpDrivers = false)
        FreshInstallDefaults.applyForkDefaults(preferences, config)
        verify(preferences).put(DoubleKey.ApsAutoIsfMax, 2.6)
        verify(preferences).put(StringKey.ApsAutoIsfStandardProfileName, "Profile150")
        verify(preferences).put(BooleanKey.ApsAutoIsfStepsFromPhone, true)
    }

    /** A real-pump master is set up by hand */
    @Test
    fun aMasterWithPumpDriversGetsNone() {
        forkSetUp(client = false, pumpDrivers = true)
        FreshInstallDefaults.applyForkDefaults(preferences, config)
        verify(preferences, never()).put(any<DoubleKey>(), any<Double>())
        verify(preferences, never()).put(any<BooleanKey>(), any<Boolean>())
    }

    /** A client takes the master-owned ones from its master; it gets only its own */
    @Test
    fun aClientGetsOnlyTheSettingsItOwns() {
        forkSetUp(client = true, pumpDrivers = false)
        FreshInstallDefaults.applyForkDefaults(preferences, config)
        verify(preferences, never()).put(DoubleKey.ApsAutoIsfMax, 2.6)
        verify(preferences, never()).put(StringKey.ApsAutoIsfStandardProfileName, "Profile150")
        verify(preferences).put(BooleanKey.ApsAutoIsfShowInsulinTotals, true)
    }

    @Test
    fun aStoredForkSettingIsKept() {
        forkSetUp(client = false, pumpDrivers = false)
        whenever(preferences.getIfExists(DoubleKey.ApsAutoIsfMax)).thenReturn(1.5)
        FreshInstallDefaults.applyForkDefaults(preferences, config)
        verify(preferences, never()).put(DoubleKey.ApsAutoIsfMax, 2.6)
    }
}
