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

/** A Virtual master or a client gets the agreed switches once per build; the main master looping phone never (2026-10-21). */
internal class BuildDefaultsResetTest {

    private val preferences: Preferences = mock()
    private val config: Config = mock()

    private fun given(client: Boolean = false, doneFor: String = "") {
        whenever(config.AAPSCLIENT).thenReturn(client)
        whenever(config.VERSION_NAME).thenReturn("3.4.2.6+ai321_1060")
        whenever(preferences.get(StringNonKey.DefaultsResetBuild)).thenReturn(doneFor)
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
    fun theSameBuildDoesNothingTheSecondTime() {
        given(doneFor = "3.4.2.6+ai321_1060@abc123")
        whenever(preferences.get(BooleanKey.ApsUseAutosens)).thenReturn(true)
        assertThat(BuildDefaultsReset.apply(preferences, config, virtualPump = true, headCommit = "abc123")).isEmpty()
        verify(preferences, never()).put(any<BooleanKey>(), any<Boolean>())
    }
}
