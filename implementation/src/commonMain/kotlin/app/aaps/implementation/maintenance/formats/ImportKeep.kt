package app.aaps.implementation.maintenance.formats

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.maintenance.ImportKeepChoices
import app.aaps.core.interfaces.maintenance.ImportKeepOffer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.keys.BooleanComposedKey
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.IntentKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.ComposedKey
import app.aaps.core.keys.interfaces.NonPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceKey

internal class KeyDomain(
    private val exact: Set<String>,
    private val prefixes: Set<String>,
) {
    fun matches(key: String): Boolean = key in exact || prefixes.any { key.startsWith(it) }

    operator fun plus(extra: Set<String>): KeyDomain = KeyDomain(exact + extra, prefixes)
}

internal fun importKeepOfferFor(
    activePlugin: ActivePlugin,
    current: Map<String, *>,
    imported: Map<String, String>,
): ImportKeepOffer {
    val pump = domainFor(activePlugin, PluginType.PUMP)
    val session = sessionKeys(activePlugin.getSpecificPluginsList(PluginType.PUMP))
    return pumpOffer(current, imported, pump, session)
}

internal fun preserveKeys(activePlugin: ActivePlugin, choices: ImportKeepChoices): (String) -> Boolean {
    val pump = domainFor(activePlugin, PluginType.PUMP)
    val bg = domainFor(activePlugin, PluginType.BGSOURCE) + coreKeysByEnumNamePrefix("BgSource")
    val sync = domainFor(activePlugin, PluginType.SYNC) + coreKeysByEnumNamePrefix("NsClient", "Tidepool", "OpenHumans", "Xdrip")
    val patient = StringKey.GeneralPatientName.key
    return { key ->
        (choices.keepPump && pump.matches(key)) ||
            (choices.keepPatientName && key == patient) ||
            (choices.keepBgSource && bg.matches(key)) ||
            (choices.keepSync && sync.matches(key))
    }
}

internal fun pumpOffer(
    current: Map<String, *>,
    imported: Map<String, String>,
    pump: KeyDomain,
    sessionKeys: Set<String>,
): ImportKeepOffer {
    val domainKeys = (imported.keys + current.keys).filter { pump.matches(it) }
    val changesPump = domainKeys.any { key -> imported[key] != current[key]?.toString() }
    val liveSession = sessionKeys.mapNotNull { key ->
        (current[key] as? String)?.takeIf { it.isNotEmpty() }?.let { key to it }
    }
    val changesSession = liveSession.any { (key, value) -> imported[key] != value }
    return ImportKeepOffer(showKeepPump = changesPump, keepPumpChecked = changesSession)
}

internal fun coreKeysByEnumNamePrefix(vararg prefixes: String): Set<String> =
    listOf(
        BooleanKey.entries, StringKey.entries, IntKey.entries, DoubleKey.entries,
        UnitDoubleKey.entries, IntentKey.entries, BooleanNonKey.entries,
        StringNonKey.entries, IntNonKey.entries, LongNonKey.entries,
    ).flatten()
        .filter { key -> prefixes.any { key.name.startsWith(it) } }
        .map { it.key }
        .toSet()

private fun domainFor(activePlugin: ActivePlugin, type: PluginType): KeyDomain {
    val plugins = activePlugin.getSpecificPluginsList(type)
    val fromPlugins = domainFrom(plugins, type)
    val active = when (type) {
        PluginType.PUMP     -> setOf(StringNonKey.ActivePluginPump.key)
        PluginType.BGSOURCE -> setOf(StringNonKey.ActivePluginBgSource.key)
        else                -> emptySet()
    }
    return fromPlugins + active
}

private fun domainFrom(plugins: List<PluginBase>, type: PluginType): KeyDomain {
    val exact = mutableSetOf<String>()
    val prefixes = mutableSetOf<String>()
    for (plugin in plugins) {
        val composed = type.name + "_" + (plugin::class.simpleName ?: "")
        exact.add(BooleanComposedKey.ConfigBuilderEnabled.composeKey(composed))
        val owned = (plugin as? PluginBaseWithPreferences)?.ownPreferences ?: continue
        for (key in owned) {
            if (key is ComposedKey) prefixes.add(key.key) else exact.add(key.key)
        }
    }
    return KeyDomain(exact, prefixes)
}

private fun sessionKeys(plugins: List<PluginBase>): Set<String> =
    plugins.filterIsInstance<PluginBaseWithPreferences>()
        .flatMap { it.ownPreferences }
        .filter { it !is PreferenceKey && it !is ComposedKey }
        .map { it.key }
        .toSet()

private val NonPreferenceKey.name: String
    get() = (this as Enum<*>).name
