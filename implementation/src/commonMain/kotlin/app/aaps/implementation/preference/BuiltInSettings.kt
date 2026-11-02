package app.aaps.implementation.preference

import app.aaps.core.interfaces.configuration.recordBuiltInSettingsAnswer
import app.aaps.core.interfaces.configuration.BuiltInSettingsApplier
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.overview.graph.GraphConfig
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.SyncDirection
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * The fork's built-in settings, applied when the user says yes to them (2026-11-01, per explicit request): on a fresh
 * install, a reinstall or an update, or again later from Maintenance.
 *
 * Unlike [FreshInstallDefaults], which only fills settings never stored, this **overwrites**: after a reinstall or an
 * update most of these are already stored (simple mode on, graphs at 100 dp), and filling only the empty ones changed
 * nothing. It runs once per yes, so a value the user changes afterwards stays until they apply again.
 *
 * Settings a client takes from its master are left out, except the four switches in [clientSwitches]. Those are set
 * locally without sending changes to the master; the master's next settings document can replace them.
 * An instance name already set is kept: it names this phone, so it is only filled when empty.
 */
object BuiltInSettings {

    /** The general settings, on top of [forkAutoIsfDefaults] */
    internal val general: List<ForkDefault> = listOf(
        ForkDefault.Bool(BooleanKey.GeneralSimpleMode, false),
        ForkDefault.Bool(BooleanKey.ApsAutoIsfAutoCleanGraph, true),
        ForkDefault.NonBool(BooleanNonKey.ObjectivesVirtualPumpBypass, true),
    )

    /** The overview graph heights, in dp (2026-11-01, per explicit request) */
    internal const val BG_HEIGHT = 300
    internal const val IOB_HEIGHT = 250
    internal const val SECONDARY_HEIGHT = 250
    internal const val GRAPH5_HEIGHT = 500

    // These shared switches are also requested on the client when its user accepts this preset.
    // The next settings document from the master can still replace them.
    internal val clientSwitches = listOf(
        BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual,
        BooleanKey.ApsAutoIsfBoostAutomationsEnabled,
        BooleanKey.ApsAutoIsfSmbOffsetOverrideEnabled,
        BooleanKey.ApsAutoIsfUamBoostUnrestrictedEnabled
    )

    /**
     * Writes the built-in settings and records the yes. [instanceName] is the name a phone without one gets.
     * @return how many settings were written
     */
    fun apply(preferences: Preferences, config: Config, instanceName: String): Int {
        var written = 0
        for (default in forkAutoIsfDefaults + general) {
            if (config.AAPSCLIENT && default.synced) continue
            default.write(preferences)
            written++
        }
        if (config.AAPSCLIENT) {
            for (key in clientSwitches) {
                // Set the local copy without sending a settings change to the dosing master.
                if (key.sync?.direction == SyncDirection.Bidirectional) preferences.putRemote(key, true, 0L)
                else preferences.put(key, true)
                written++
            }
        }
        preferences.put(StringNonKey.ComposeGraphConfig, withBuiltInGraphHeights(preferences.get(StringNonKey.ComposeGraphConfig)))
        written++
        if (preferences.get(StringKey.GeneralInstanceName).isBlank() && instanceName.isNotBlank()) {
            preferences.put(StringKey.GeneralInstanceName, instanceName)
            written++
        }
        // A yes covers what the setup wizard asks, apart from the master password, which the yes asks for itself
        preferences.put(BooleanNonKey.GeneralSetupWizardProcessed, true)
        preferences.put(BooleanNonKey.BuiltInSettingsAppliedBefore, true)
        recordBuiltInSettingsAnswer(preferences, config, useBuiltIn = true)
        return written
    }

    /**
     * The stored graph layout with the built-in heights. Only the heights change: which series each graph shows stays as
     * the user chose. With nothing stored, the default layout is written with these heights.
     */
    internal fun withBuiltInGraphHeights(json: String): String {
        val stored = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: defaultLayout()
        val secondary = (stored["secondaryGraphs"] as? JsonArray).orEmpty().map { graph ->
            if (graph is JsonObject) JsonObject(graph + ("height" to JsonPrimitive(SECONDARY_HEIGHT))) else graph
        }
        return JsonObject(
            stored + mapOf(
                "bgHeight" to JsonPrimitive(BG_HEIGHT),
                "iobHeight" to JsonPrimitive(IOB_HEIGHT),
                "graph5Height" to JsonPrimitive(GRAPH5_HEIGHT),
                "secondaryGraphs" to JsonArray(secondary)
            )
        ).toString()
    }

    /** The layout a new install starts with ([GraphConfig]'s defaults), in the form the overview stores it */
    private fun defaultLayout(): JsonObject {
        val defaults = GraphConfig()
        return buildJsonObject {
            put("bgOverlays", buildJsonArray { defaults.bgOverlays.forEach { add(JsonPrimitive(it.name)) } })
            put("iobOverlays", buildJsonArray { defaults.iobOverlays.forEach { add(JsonPrimitive(it.name)) } })
            put("secondaryGraphs", buildJsonArray {
                defaults.secondaryGraphs.forEach { graph ->
                    add(buildJsonObject {
                        put("series", buildJsonArray { graph.series.forEach { add(JsonPrimitive(it.name)) } })
                        put("height", JsonPrimitive(graph.height))
                    })
                }
            })
        }
    }
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class BuiltInSettingsApplierImpl(
    private val preferences: Preferences,
    private val config: Config
) : BuiltInSettingsApplier {

    override fun apply(instanceName: String): Int = BuiltInSettings.apply(preferences, config, instanceName)
}
