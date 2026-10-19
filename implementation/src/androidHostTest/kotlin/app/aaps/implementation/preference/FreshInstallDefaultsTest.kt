package app.aaps.implementation.preference

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
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
        verify(preferences, never()).put(any<BooleanKey>(), any<Boolean>())
    }

    @Test
    fun aMasterIsNotTouched() {
        setUp(client = false, wizardDone = false, stored = null)
        FreshInstallDefaults.apply(preferences, config)
        verify(preferences, never()).put(any<BooleanKey>(), any<Boolean>())
    }
}
