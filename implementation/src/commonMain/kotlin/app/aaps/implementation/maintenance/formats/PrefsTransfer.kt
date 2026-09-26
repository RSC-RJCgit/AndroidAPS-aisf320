package app.aaps.implementation.maintenance.formats

import app.aaps.core.interfaces.maintenance.ImportDecryptResult
import app.aaps.core.interfaces.maintenance.PrefMetadata
import app.aaps.core.interfaces.maintenance.Prefs
import app.aaps.core.interfaces.maintenance.PrefsMetadataKey
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.implementation.maintenance.PrefsMetadataKeyImpl
import app.aaps.implementation.maintenance.data.PrefsStatusImpl

/**
 * Turning stored preferences into an export and back, with no file access and no platform in it.
 *
 * [PrefsFormatCodec] owns the shape of the file. This owns the two steps on either side of it -
 * which settings go in, and what happens to the ones that come out - and they are the same
 * questions on every platform. Keeping them here means the answers cannot drift between shells,
 * and it means they can be tested against a store held in memory rather than a device.
 *
 * The shell above this still differs, and should: picking a file is Android's storage access
 * framework, a Documents directory on iOS and a file dialog on the desktop.
 */
class PrefsTransfer(
    private val codec: PrefsFormatCodec,
    private val store: KeyValueStore,
    private val isExportable: (String) -> Boolean
) {

    /**
     * The text of an export holding every setting that is meant to travel.
     *
     * A preference that is not registered as exportable is left out deliberately. Those describe the
     * device rather than the user's therapy - a paired pump's address, a cached token - and carrying
     * them to another phone would move a fact that is not true there.
     */
    fun exportContents(metadata: Map<PrefsMetadataKey, PrefMetadata>, password: String): String =
        codec.encode(Prefs(exportableValues(), metadata), password)

    private fun exportableValues(): Map<String, String> =
        store.getAll()
            .filterKeys { isExportable(it) }
            .mapNotNull { (key, value) -> value?.let { key to it.toString() } }
            .toMap()

    /**
     * Reads a file the user picked and says whether it can be imported.
     *
     * A wrong password is told apart from a damaged file on purpose: only one of the two is worth
     * offering to try again, and a user who mistyped should not be told their backup is broken.
     */
    fun importResult(contents: String, password: String, engineeringMode: Boolean): ImportDecryptResult =
        try {
            val prefs = codec.decode(contents, password)
            val importOk = prefs.metadata.values.none { it.status == PrefsStatusImpl.ERROR }
            val encryptionFailed = prefs.metadata[PrefsMetadataKeyImpl.ENCRYPTION]?.status == PrefsStatusImpl.ERROR
            if (!importOk && encryptionFailed) ImportDecryptResult.WrongPassword
            else ImportDecryptResult.Success(prefs, importOk, (importOk || engineeringMode) && prefs.values.isNotEmpty())
        } catch (e: Throwable) {
            ImportDecryptResult.Error(e.message ?: "Unknown error")
        }

    /**
     * Replaces what is stored with what the file holds.
     *
     * The store is cleared first, so an import is the file and not the file merged over whatever was
     * there. A setting the old configuration had and the new one does not would otherwise survive an
     * import that was meant to replace it.
     *
     * The local AAPS folder stays, because it is a permission on this phone. Automation states are
     * written off unless the import screen checkbox asked for them. Keys named by [preserve] are
     * copied back as well, so a checked group keeps the current pump, name, BG source, or sync.
     *
     * Booleans are written as booleans. They come back from the file as the strings `true` and
     * `false`, and a store that kept them as text would answer the wrong type to every later read.
     */
    fun currentEntries(): Map<String, *> = store.getAll()

    fun applyImported(
        prefs: Prefs,
        enableAutomationStates: Boolean = false,
        preserve: (String) -> Boolean = { false },
    ) {
        applyImportedStore(store, prefs, enableAutomationStates, preserve)
    }
}

internal fun applyImportedStore(
    store: KeyValueStore,
    prefs: Prefs,
    enableAutomationStates: Boolean,
    preserve: (String) -> Boolean,
) {
    val savedDirectory = store.getString(StringKey.AapsDirectoryUri.key, "")
    val kept = store.getAll().filterKeys(preserve)
    store.clear()
    prefs.values.forEach { (key, value) ->
        if (preserve(key)) return@forEach
        if (value == "true" || value == "false") store.putBoolean(key, value.toBoolean()) else store.putString(key, value)
    }
    kept.forEach { (key, value) -> store.putStored(key, value) }
    if (savedDirectory.isNotEmpty()) store.putString(StringKey.AapsDirectoryUri.key, savedDirectory)
    store.putBoolean(BooleanKey.AutomationStatesEnabled.key, enableAutomationStates)
}

private fun KeyValueStore.putStored(key: String, value: Any?) {
    when (value) {
        is Boolean -> putBoolean(key, value)
        is Int     -> putInt(key, value)
        is Long    -> putLong(key, value)
        is Float   -> putDouble(key, value.toDouble())
        is Double  -> putDouble(key, value)
        is String  -> putString(key, value)
        null       -> Unit
    }
}
