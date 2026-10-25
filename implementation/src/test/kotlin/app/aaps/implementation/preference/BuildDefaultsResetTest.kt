package app.aaps.implementation.preference

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Fresh Virtual/Client installs get agreed defaults once; existing installs preserve switches across builds. */
internal class BuildDefaultsResetTest {

    private val preferences: Preferences = mock()
    private val config: Config = mock()

    private fun given(client: Boolean = false, doneFor: String = "", setupProcessed: Boolean = false, resetVersion: String = "") {
        whenever(config.AAPSCLIENT).thenReturn(client)
        whenever(config.VERSION_NAME).thenReturn("3.4.2.6+ai321_1060")
        whenever(preferences.get(StringNonKey.DefaultsResetBuild)).thenReturn(doneFor)
        whenever(preferences.get(StringNonKey.DefaultsResetVersion)).thenReturn(resetVersion)
        whenever(preferences.get(BooleanKey.GeneralSetupWizardProcessed)).thenReturn(setupProcessed)
        BuildDefaultsReset.AGREED.forEach { (key, value) -> whenever(preferences.get(key)).thenReturn(value) }
    }

    @Test
    fun aVirtualMasterGetsTheAgreedSwitches() {
        given()
        whenever(preferences.get(BooleanKey.ApsAutoIsfUamBoostEnabled)).thenReturn(false)
        whenever(preferences.get(BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled)).thenReturn(true)
        val changed = BuildDefaultsReset.apply(preferences, config, virtualPump = true, headCommit = "abc123")
        assertThat(changed).containsExactly(BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled, BooleanKey.ApsAutoIsfUamBoostEnabled)
        verify(preferences).put(BooleanKey.ApsAutoIsfUamBoostEnabled, true)
        verify(preferences).put(BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled, false)
        verify(preferences).put(StringNonKey.DefaultsResetBuild, "3.4.2.6+ai321_1060@abc123")
        verify(preferences).put(StringNonKey.DefaultsResetVersion, "2")
    }

    @Test
    fun theMainMasterLoopingPhoneIsNeverTouched() {
        given()
        whenever(preferences.get(BooleanKey.ApsAutoIsfUamBoostEnabled)).thenReturn(false)
        assertThat(BuildDefaultsReset.apply(preferences, config, virtualPump = false, headCommit = "abc123")).isEmpty()
        verify(preferences, never()).put(any<BooleanKey>(), any<Boolean>())
        verify(preferences, never()).put(eq(StringNonKey.DefaultsResetBuild), anyOrNull())
    }

    @Test
    fun aClientGetsTheAgreedSwitches() {
        given(client = true)
        whenever(preferences.get(BooleanKey.ApsUseAutosens)).thenReturn(true)
        BuildDefaultsReset.apply(preferences, config, virtualPump = false, headCommit = "abc123")
        verify(preferences).put(BooleanKey.ApsUseAutosens, false)
    }

    @Test
    fun anExistingInstallOnlyGetsGraphTextRestored() {
        given(doneFor = "3.4.2.6+ai321_1050@old", setupProcessed = true)
        whenever(preferences.get(BooleanKey.ApsAutoIsfShowGraphText)).thenReturn(false)
        whenever(preferences.get(BooleanKey.ApsUseAutosens)).thenReturn(true)
        val changed = BuildDefaultsReset.apply(preferences, config, virtualPump = true, headCommit = "abc123")
        assertThat(changed).containsExactly(BooleanKey.ApsAutoIsfShowGraphText)
        verify(preferences).put(BooleanKey.ApsAutoIsfShowGraphText, true)
        verify(preferences, never()).put(BooleanKey.ApsUseAutosens, false)
        verify(preferences).put(StringNonKey.DefaultsResetVersion, "2")
    }

    @Test
    fun laterBuildsDoNotResetSavedSwitches() {
        given(doneFor = "3.4.2.6+ai321_1060@old", setupProcessed = true, resetVersion = "2")
        whenever(preferences.get(BooleanKey.ApsUseAutosens)).thenReturn(true)
        assertThat(BuildDefaultsReset.apply(preferences, config, virtualPump = true, headCommit = "abc123")).isEmpty()
        verify(preferences, never()).put(any<BooleanKey>(), any<Boolean>())
        verify(preferences, never()).put(eq(StringNonKey.DefaultsResetBuild), anyOrNull())
    }
}
