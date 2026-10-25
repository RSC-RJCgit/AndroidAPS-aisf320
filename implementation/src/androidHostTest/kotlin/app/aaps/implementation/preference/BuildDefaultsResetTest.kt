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
        whenever(config.VERSION_NAME).thenReturn("4.0.0-rc1")
        whenever(config.HEAD).thenReturn("abc123")
        whenever(preferences.get(StringNonKey.DefaultsResetBuild)).thenReturn(doneFor)
        // Every switch already as agreed, unless a test says otherwise
        BuildDefaultsReset.AGREED.forEach { (key, value) -> whenever(preferences.get(key)).thenReturn(value) }
    }

    @Test
    fun aVirtualMasterGetsTheAgreedSwitches() {
        given()
        whenever(preferences.get(BooleanKey.ApsUseAutosens)).thenReturn(true)
        whenever(preferences.get(BooleanKey.ApsAutoIsfUamBoostEnabled)).thenReturn(false)
        val changed = BuildDefaultsReset.apply(preferences, config, "VirtualPumpPlugin")
        assertThat(changed).containsExactly(BooleanKey.ApsUseAutosens, BooleanKey.ApsAutoIsfUamBoostEnabled)
        verify(preferences).put(BooleanKey.ApsUseAutosens, false)
        verify(preferences).put(BooleanKey.ApsAutoIsfUamBoostEnabled, true)
        verify(preferences).put(StringNonKey.DefaultsResetBuild, "4.0.0-rc1@abc123")
    }

    @Test
    fun theMainMasterLoopingPhoneIsNeverTouched() {
        given()
        whenever(preferences.get(BooleanKey.ApsUseAutosens)).thenReturn(true)
        whenever(preferences.get(BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled)).thenReturn(true)
        assertThat(BuildDefaultsReset.apply(preferences, config, "DanaRSPlugin")).isEmpty()
        verify(preferences, never()).put(any<BooleanKey>(), any<Boolean>())
        verify(preferences, never()).put(eq(StringNonKey.DefaultsResetBuild), anyOrNull())
    }

    @Test
    fun aClientSetsASharedSwitchWithoutSendingItToTheMaster() {
        given(client = true)
        whenever(preferences.get(BooleanKey.ApsUseAutosens)).thenReturn(true)
        BuildDefaultsReset.apply(preferences, config, "")
        verify(preferences).putRemote(BooleanKey.ApsUseAutosens, false, 0L)
        verify(preferences, never()).put(eq(BooleanKey.ApsUseAutosens), any<Boolean>())
    }

    @Test
    fun aClientSetsAPhoneOnlySwitchDirectly() {
        given(client = true)
        whenever(preferences.get(BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled)).thenReturn(true)
        BuildDefaultsReset.apply(preferences, config, "")
        verify(preferences).put(BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled, false)
    }

    @Test
    fun anUpdateRestoresTheGraphTextRows() {
        given()
        whenever(preferences.get(BooleanKey.ApsAutoIsfShowGraphText)).thenReturn(false)
        whenever(preferences.get(BooleanKey.ApsAutoIsfAutoCleanGraph)).thenReturn(true)
        BuildDefaultsReset.apply(preferences, config, "VirtualPumpPlugin")
        verify(preferences).put(BooleanKey.ApsAutoIsfShowGraphText, true)
        verify(preferences).put(BooleanKey.ApsAutoIsfAutoCleanGraph, false)
    }

    @Test
    fun theSameBuildDoesNothingTheSecondTime() {
        given(doneFor = "4.0.0-rc1@abc123")
        whenever(preferences.get(BooleanKey.ApsUseAutosens)).thenReturn(true)
        assertThat(BuildDefaultsReset.apply(preferences, config, "VirtualPumpPlugin")).isEmpty()
        verify(preferences, never()).put(any<BooleanKey>(), any<Boolean>())
    }

    @Test
    fun theCodeDefaultsStayAsTheMainMasterLoopingPhoneNeedsThem() {
        assertThat(BooleanKey.OverviewUseBolusAdvisor.defaultValue).isTrue()
        assertThat(BooleanKey.OverviewUseBolusReminder.defaultValue).isTrue()
        assertThat(BooleanKey.ApsUseAutosens.defaultValue).isTrue()
        assertThat(BooleanKey.ApsUseAutoIsfWeights.defaultValue).isFalse()
        assertThat(BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual.defaultValue).isFalse()
    }
}
