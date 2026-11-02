package app.aaps.core.interfaces.configuration

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BuiltInSettingsPromptTest {

    private val preferences: Preferences = mock()
    private val config: Config = mock()

    private fun given(choice: String = "off", seen: String = "4.0.0@current", pump: String = "VirtualPumpPlugin") {
        whenever(config.VERSION_NAME).thenReturn("4.0.0")
        whenever(config.HEAD).thenReturn("current")
        whenever(preferences.get(StringNonKey.BuiltInSettingsChoice)).thenReturn(choice)
        whenever(preferences.get(StringNonKey.BuiltInSettingsPromptBuild)).thenReturn(seen)
        whenever(preferences.get(StringNonKey.ActivePluginPump)).thenReturn(pump)
    }

    @Test
    fun restartingTheSameBuildDoesNotAskAgain() {
        given()
        assertThat(builtInSettingsPromptPending(preferences, config)).isFalse()
    }

    @Test
    fun aNewCommitAsksAgainWithoutApplyingSettings() {
        given(seen = "4.0.0@previous")
        assertThat(builtInSettingsPromptPending(preferences, config)).isTrue()
        verify(preferences, never()).put(any<BooleanKey>(), any<Boolean>())
    }

    @Test
    fun anEarlierYesDoesNotSkipTheNextUpdateQuestion() {
        given(choice = "on", seen = "3.9.0@previous")
        assertThat(builtInSettingsPromptPending(preferences, config)).isTrue()
    }

    @Test
    fun aRealPumpMasterIsNotOfferedAReset() {
        given(choice = "", seen = "", pump = "OmnipodDashPumpPlugin")
        assertThat(builtInSettingsPromptPending(preferences, config)).isFalse()
    }

    @Test
    fun aClientCanBeOfferedTheChoiceWithoutAPump() {
        given(seen = "", pump = "")
        whenever(config.AAPSCLIENT).thenReturn(true)
        assertThat(builtInSettingsPromptPending(preferences, config)).isTrue()
    }

    @Test
    fun keepingSettingsRecordsOnlyTheAnswerForThisBuild() {
        given()
        recordBuiltInSettingsAnswer(preferences, config, useBuiltIn = false)
        verify(preferences).put(StringNonKey.BuiltInSettingsChoice, "off")
        verify(preferences).put(StringNonKey.BuiltInSettingsPromptBuild, "4.0.0@current")
        verify(preferences, never()).put(any<BooleanKey>(), any<Boolean>())
    }
}
