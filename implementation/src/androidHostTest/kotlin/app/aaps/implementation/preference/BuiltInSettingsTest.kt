package app.aaps.implementation.preference

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.configuration.builtInSettingsOffered
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** The fork's built-in settings, applied on a yes (2026-11-01) */
class BuiltInSettingsTest {

    private val requestedClientSwitches = listOf(
        BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual,
        BooleanKey.ApsAutoIsfBoostAutomationsEnabled,
        BooleanKey.ApsAutoIsfSmbOffsetOverrideEnabled,
        BooleanKey.ApsAutoIsfUamBoostUnrestrictedEnabled
    )

    private val preferences: Preferences = mock()
    private val config: Config = mock()

    private fun stored(instanceName: String = "", graphs: String = "") {
        whenever(config.VERSION_NAME).thenReturn("4.0.0-test")
        whenever(config.HEAD).thenReturn("abc123")
        whenever(preferences.get(StringKey.GeneralInstanceName)).thenReturn(instanceName)
        whenever(preferences.get(StringNonKey.ComposeGraphConfig)).thenReturn(graphs)
    }

    /** Unlike the fresh-install defaults, a stored value is replaced: that is the point after a reinstall or update */
    @Test
    fun storedValuesAreOverwritten() {
        stored()
        whenever(preferences.getIfExists(BooleanKey.GeneralSimpleMode)).thenReturn(true)

        BuiltInSettings.apply(preferences, config, "AAPS")

        verify(preferences).put(BooleanKey.GeneralSimpleMode, false)
        verify(preferences).put(BooleanNonKey.ObjectivesVirtualPumpBypass, true)
        verify(preferences).put(DoubleKey.ApsAutoIsfMax, 2.6)
    }

    @Test
    fun theAnswerIsRecordedAndTheWizardIsDone() {
        stored()
        BuiltInSettings.apply(preferences, config, "AAPS")

        verify(preferences).put(StringNonKey.BuiltInSettingsChoice, "on")
        verify(preferences).put(StringNonKey.BuiltInSettingsPromptBuild, "4.0.0-test@abc123")
        verify(preferences).put(BooleanNonKey.GeneralSetupWizardProcessed, true)
        verify(preferences).put(BooleanNonKey.BuiltInSettingsAppliedBefore, true)
    }

    @Test
    fun theFiveRequestedSwitchesAreOnWhenTheMasterAccepts() {
        stored()
        // A local Virtual master can already have off values from an earlier install or import.
        for (key in requestedClientSwitches + BooleanKey.ApsAutoIsfAutoCleanGraph) {
            whenever(preferences.getIfExists(key)).thenReturn(false)
            whenever(preferences.get(key)).thenReturn(false)
        }
        BuiltInSettings.apply(preferences, config, "AAPS")

        for (key in requestedClientSwitches + BooleanKey.ApsAutoIsfAutoCleanGraph) {
            verify(preferences).put(key, true)
        }
    }

    @Test
    fun theClientSetsTheRequestedSwitchesWithoutSendingThemToTheMaster() {
        stored()
        whenever(config.AAPSCLIENT).thenReturn(true)
        for (key in requestedClientSwitches + BooleanKey.ApsAutoIsfAutoCleanGraph) {
            whenever(preferences.getIfExists(key)).thenReturn(false)
            whenever(preferences.get(key)).thenReturn(false)
        }
        BuiltInSettings.apply(preferences, config, "AAPSClient")

        for (key in requestedClientSwitches) {
            verify(preferences).putRemote(key, true, 0L)
            verify(preferences, never()).put(key, true)
        }
        verify(preferences).put(BooleanKey.ApsAutoIsfAutoCleanGraph, true)
    }

    @Test
    fun anEmptyInstanceNameIsFilledAndASetOneKept() {
        stored(instanceName = "")
        BuiltInSettings.apply(preferences, config, "AAPS")
        verify(preferences).put(StringKey.GeneralInstanceName, "AAPS")

        val other: Preferences = mock()
        whenever(other.get(StringKey.GeneralInstanceName)).thenReturn("Dell17")
        whenever(other.get(StringNonKey.ComposeGraphConfig)).thenReturn("")
        BuiltInSettings.apply(other, config, "AAPS")
        verify(other, never()).put(eq(StringKey.GeneralInstanceName), any<String>())
    }

    /** A client applies the whole preset locally without sending it to its master. */
    @Test
    fun aClientAppliesTheWholePresetLocally() {
        stored()
        whenever(config.AAPSCLIENT).thenReturn(true)
        BuiltInSettings.apply(preferences, config, "AAPSClient")

        verify(preferences).putRemote(DoubleKey.ApsAutoIsfMax, 2.6, 0L)
        verify(preferences, never()).put(DoubleKey.ApsAutoIsfMax, 2.6)
        verify(preferences).putRemote(BooleanKey.ApsAutoIsfCustomAutomationsEnabled, true, 0L)
        verify(preferences, never()).put(BooleanKey.ApsAutoIsfCustomAutomationsEnabled, true)
        // This phone's own
        verify(preferences).put(BooleanNonKey.ObjectivesVirtualPumpBypass, true)
    }

    @Test
    fun graphHeightsAreSetAndTheChosenSeriesKept() {
        val layout = """{"bgOverlays":["PREDICTIONS"],"iobOverlays":[],"bgHeight":100,"iobHeight":100,""" +
            """"secondaryGraphs":[{"series":["STEPS"],"height":100},{"series":["IOB_TH"],"height":80}]}"""
        val result = Json.parseToJsonElement(BuiltInSettings.withBuiltInGraphHeights(layout)).jsonObject

        assertThat(result["bgHeight"]!!.jsonPrimitive.int).isEqualTo(300)
        assertThat(result["iobHeight"]!!.jsonPrimitive.int).isEqualTo(250)
        assertThat(result["graph5Height"]!!.jsonPrimitive.int).isEqualTo(500)
        val graphs = result["secondaryGraphs"]!!.jsonArray
        assertThat(graphs.map { it.jsonObject["height"]!!.jsonPrimitive.int }).containsExactly(250, 250)
        assertThat(graphs[0].jsonObject["series"].toString()).isEqualTo("""["STEPS"]""")
        assertThat(result["bgOverlays"].toString()).isEqualTo("""["PREDICTIONS"]""")
    }

    @Test
    fun withNoLayoutStoredTheDefaultLayoutGetsTheHeights() {
        val result = Json.parseToJsonElement(BuiltInSettings.withBuiltInGraphHeights("")).jsonObject

        assertThat(result["bgHeight"]!!.jsonPrimitive.int).isEqualTo(300)
        assertThat(result["secondaryGraphs"]!!.jsonArray.map { it.jsonObject["height"]!!.jsonPrimitive.int }).containsExactly(250, 250, 250, 250)
    }

    @Test
    fun theGraphLayoutIsWritten() {
        stored()
        BuiltInSettings.apply(preferences, config, "AAPS")

        val written = argumentCaptor<String>()
        verify(preferences).put(eq(StringNonKey.ComposeGraphConfig), written.capture())
        assertThat(written.firstValue).contains("\"bgHeight\":300")
    }

    @Test
    fun offeredOnClientsAndVirtualMastersOnly() {
        whenever(config.AAPSCLIENT).thenReturn(false)
        assertThat(builtInSettingsOffered(config, "VirtualPumpPlugin")).isTrue()
        assertThat(builtInSettingsOffered(config, "")).isTrue()
        assertThat(builtInSettingsOffered(config, "OmnipodDashPumpPlugin")).isFalse()
        whenever(config.AAPSCLIENT).thenReturn(true)
        assertThat(builtInSettingsOffered(config, "OmnipodDashPumpPlugin")).isTrue()
    }
}
