# Settings Import: "Keep Current Pump Configuration" Option

**Session date:** 2026-07-15
**Projects:** `AaAPS3422a320` (implemented, commit `fd85a5fdb4` "320TDD2AU262importpodoptional"), `APS3421a320` (back-ported patch `importpodoptional_3421.patch`)

---

## 1. Problem

AAPS settings export includes the live Omnipod pod state. On import, `sp.clear()` wipes all
SharedPreferences and replaces them with the file's contents — so importing an older backup
onto a phone with an active pod **overwrites the live pod session with a stale/dead one**,
effectively losing the running pod.

Wanted: a toggle at import time to either use the imported pump settings (stock behaviour,
needed for phone migration) or keep the current ones (same-phone restore, active pod preserved).
Extended during the session to preserving the **entire pump configuration** (driver selection +
driver settings + session state), which also covers the case where the imported file has a
different pump driver than the phone (e.g. Virtual Pump phone importing a pod config).

---

## 2. Investigation — how the existing code works

### 2.1 Export
`ImportExportPrefsImpl.savePreferences()`
(`plugins/configuration/src/main/kotlin/app/aaps/plugins/configuration/maintenance/ImportExportPrefsImpl.kt`)
dumps every SharedPreferences entry passing `preferences.isExportableKey(key)`:

```kotlin
for ((key, value) in sp.getAll()) {
    if (preferences.isExportableKey(key))
        entries[key] = value.toString()
    ...
}
```

`PreferencesImpl.isExportableKey()` (`implementation/src/main/kotlin/app/aaps/implementation/sharedPreferences/PreferencesImpl.kt`)
checks all registered key enums, matching exact keys and `ComposedKey` prefixes:

```kotlin
override fun isExportableKey(key: String): Boolean {
    prefsList
        .flatMap { it.enumConstants!!.asIterable() }
        .forEach {
            if (it.key == key && it.exportable) return true
            if (it is ComposedKey && key.startsWith(it.key) && it.exportable) return true
        }
    return false
}
```

### 2.2 Pod state IS exportable
The pod session lives in SharedPreferences as exportable string keys:

- **Dash** — `pump/omnipod/common/src/main/kotlin/app/aaps/pump/omnipod/common/keys/DashStringNonPreferenceKey.kt`:
  ```kotlin
  enum class DashStringNonPreferenceKey(
      override val key: String,
      override val defaultValue: String,
      override val exportable: Boolean = true
  ) : StringNonPreferenceKey {
      PodState("AAPS.OmnipodDash.pod_state", ""),
  }
  ```
  The pod's unique ID, BLE address, activation state etc. are all inside this one JSON blob.

- **Eros** — `pump/omnipod/eros/src/main/java/app/aaps/pump/omnipod/eros/keys/ErosStringNonPreferenceKey.kt`:
  ```kotlin
  PodState("AAPS.Omnipod.pod_state", ""),
  ActiveBolus("AAPS.Omnipod.current_bolus", ""),
  ```

### 2.3 Import wipes everything
`ImportExportPrefsImpl.doImportSharedPreferences()` did:

```kotlin
activePlugin.beforeImport()
val savedAapsDirectory = sp.getString(StringKey.AapsDirectoryUri.key, "")
sp.clear()
for ((key, value) in prefs.values) { ... sp.putBoolean/putString ... }
if (savedAapsDirectory.isNotEmpty())
    sp.putString(StringKey.AapsDirectoryUri.key, savedAapsDirectory)
activePlugin.afterImport()
```

Two existing precedents for the mechanic we needed:
1. **`AapsDirectoryUri`** is snapshotted before `sp.clear()` and restored after (keep-a-key-across-import).
2. **Automation states** (3422 fork only) get a post-import checkbox dialog (ask-user-with-toggle UI).

### 2.4 Per-driver key ownership already exists
`PluginBaseWithPreferences` (`core/interfaces/src/main/kotlin/app/aaps/core/interfaces/plugin/PluginBaseWithPreferences.kt`):

```kotlin
abstract class PluginBaseWithPreferences(
    pluginDescription: PluginDescription,
    val ownPreferences: List<Class<out NonPreferenceKey>> = emptyList(),
    ...
) : PluginBase(...) {
    init { ownPreferences.forEach { preferences.registerPreferences(it) } }
}
```

Each pump plugin declares its key classes:

- **Dash** (`OmnipodDashPumpPlugin.kt:122`):
  `OmnipodBooleanPreferenceKey, OmnipodIntPreferenceKey, DashBooleanPreferenceKey, DashStringNonPreferenceKey`
- **Eros** (`OmnipodErosPumpPlugin.kt:156`):
  `ErosBooleanPreferenceKey, ErosLongNonPreferenceKey, ErosStringNonPreferenceKey`
- **Medtronic** declares the shared **RileyLink** key classes
  (`RileylinkBooleanPreferenceKey, RileyLinkDoubleKey, RileyLinkIntentPreferenceKey, RileyLinkLongKey, RileyLinkStringKey, RileyLinkStringPreferenceKey`),
  so a union across all PUMP plugins covers shared hardware modules too.
- **Virtual** declares only `VirtualBooleanNonPreferenceKey` (no string session state — relevant for checkbox visibility, see §4.3).

### 2.5 Pump driver selection storage
`ConfigBuilderPlugin.savePref()` stores enabled/visible state via composed keys
(`plugins/configuration/src/main/kotlin/app/aaps/plugins/configuration/keys/ConfigurationBooleanComposedKey.kt`):

```kotlin
ConfigBuilderEnabled(key = "ConfigBuilder_Enabled_", format = "%s", defaultValue = false),
ConfigBuilderVisible(key = "ConfigBuilder_Visible_", format = "%s", defaultValue = false),
```

Final key = `ConfigBuilder_Enabled_PUMP_<PluginSimpleClassName>`, e.g.
`ConfigBuilder_Enabled_PUMP_OmnipodDashPumpPlugin`. On load, a missing key falls back to
`enableByDefault` (Virtual Pump), so dropping all pump ConfigBuilder keys is safe even on a
fresh install.

### 2.6 Misc findings
- `SPImpl.getAll()` returns typed values (`Boolean/Int/Long/Float/String`); `putDouble` stores a
  Float — restore must be type-aware (import itself only writes strings/booleans).
- `ActivePlugin.getSpecificPluginsList(PluginType.PUMP)` enumerates all pump plugins at runtime.
- The nested `AndroidAPS/` folder inside `AaAPS3422a320` is **not** part of the build
  (not referenced by `settings.gradle`) — untracked copy, edits go in the root modules only.

---

## 3. Design

**Pump domain** = three groups of SharedPreferences keys, derived generically at runtime
(no per-driver hardcoding):

1. `ConfigBuilder_Enabled_/Visible_PUMP_<Plugin>` for **every** PUMP-type plugin
2. every key from every pump plugin's `ownPreferences` classes (ComposedKeys as prefixes)
3. device session state = pump-owned `NonPreferenceKey`s that are **not** `PreferenceKey`s
   (exactly `pod_state`, `current_bolus`, RileyLink session keys — used for UX heuristics)

**Import with "keep current pump configuration" checked:**
- snapshot all current pump-domain keys before `sp.clear()` (same pattern as `savedAapsDirectory`)
- **skip** every pump-domain key coming from the file — including *all* pump ConfigBuilder keys,
  so a different driver in the file can't land or enable itself (avoids two-pumps-enabled states)
- restore the snapshot with original value types

**Checkbox visibility** (either condition):
- this phone has live string session state (non-empty pod state etc.) in any pump driver's keys
  — works regardless of which driver is currently selected, since switching drivers doesn't
  erase the old driver's keys; **or**
- the imported file's set of enabled PUMP drivers differs from the current one
  (covers the Virtual-Pump-phone test case, added in a follow-up request)

**Checkbox default:** pre-checked **only** when local live session state differs from the file's
(i.e. the import would clobber a running pod). A mere driver difference shows the box unchecked,
because a file with a different pump usually means genuine phone migration.

**Boundary (intentional):** therapy settings outside the driver (Safety max basal/bolus, insulin
type) still come from the imported file — they're therapy settings, not hardware pairing. The
checkbox label states what is kept.

Export side: **no changes** — works retroactively with every existing export file.

---

## 4. Implementation (commit `fd85a5fdb4` in AaAPS3422a320)

Files changed (all in `plugins/configuration`):

| File | Change |
|---|---|
| `maintenance/ImportExportPrefsImpl.kt` | pump-domain matcher, session-key detection, preserve/skip/restore in import flow |
| `maintenance/dialogs/PrefImportSummaryDialog.kt` | new params + checkbox wiring, `ok` callback now receives the choice |
| `res/layout/dialog_alert_import_summary.xml` | `MaterialCheckBox @+id/keep_pump_config` (gone by default) |
| `res/values/strings.xml` | `keep_current_pump_config` |

### 4.1 ImportExportPrefsImpl.kt — new imports

```kotlin
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.keys.interfaces.ComposedKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.plugins.configuration.keys.ConfigurationBooleanComposedKey
```

### 4.2 ImportExportPrefsImpl.kt — helpers (before `checkIfImportIsOk`)

```kotlin
/**
 * Matcher for all SharedPreferences keys belonging to the "pump domain":
 * ConfigBuilder selection keys of every PUMP-type plugin plus every key class
 * declared by pump plugins via [PluginBaseWithPreferences.ownPreferences]
 * (driver settings and device session state like Omnipod pod_state).
 */
private class PumpKeyMatcher(private val exactKeys: Set<String>, private val prefixes: Set<String>) {

    fun matches(key: String): Boolean = key in exactKeys || prefixes.any { key.startsWith(it) }
}

private fun pumpKeyMatcher(): PumpKeyMatcher {
    val exact = mutableSetOf<String>()
    val prefixes = mutableSetOf<String>()
    for (plugin in activePlugin.getSpecificPluginsList(PluginType.PUMP)) {
        val pluginId = PluginType.PUMP.name + "_" + plugin.javaClass.simpleName
        exact.add(ConfigurationBooleanComposedKey.ConfigBuilderEnabled.composeKey(pluginId))
        exact.add(ConfigurationBooleanComposedKey.ConfigBuilderVisible.composeKey(pluginId))
        (plugin as? PluginBaseWithPreferences)?.ownPreferences?.forEach { clazz ->
            clazz.enumConstants?.forEach { k ->
                if (k is ComposedKey) prefixes.add(k.key) else exact.add(k.key)
            }
        }
    }
    return PumpKeyMatcher(exact, prefixes)
}

/**
 * Keys of pump plugins holding live device session state (pairing, active pod, running bolus)
 * as opposed to user-editable preferences: [app.aaps.core.keys.interfaces.NonPreferenceKey]s
 * that are not [PreferenceKey]s.
 */
private fun pumpSessionStateKeys(): Set<String> =
    activePlugin.getSpecificPluginsList(PluginType.PUMP)
        .filterIsInstance<PluginBaseWithPreferences>()
        .flatMap { it.ownPreferences }
        .flatMap { it.enumConstants?.toList() ?: emptyList() }
        .filter { it !is PreferenceKey && it !is ComposedKey }
        .map { it.key }
        .toSet()
```

### 4.3 ImportExportPrefsImpl.kt — import flow (inside `doImportSharedPreferences`)

```kotlin
// if at end we allow to import preferences
val importPossible = (importOk || config.isEngineeringMode()) && (prefs.values.isNotEmpty())

// Pump domain: driver selection, driver settings and device session state (e.g. active pod).
// Offer to keep the current pump configuration when this phone has live session state
// that the imported file would overwrite (e.g. importing an old backup on the same phone).
val pumpKeys = pumpKeyMatcher()
val currentSp = sp.getAll()
val liveSessionValues = pumpSessionStateKeys()
    .mapNotNull { key -> (currentSp[key] as? String)?.takeIf { it.isNotEmpty() }?.let { key to it } }
val hasLocalPumpSession = liveSessionValues.isNotEmpty()
val importChangesPumpSession = liveSessionValues.any { (key, value) -> prefs.values[key] != value }
// Also offer the choice when the imported file would switch to a different pump driver
// (e.g. Virtual Pump running locally, file contains an Omnipod config)
val enabledPumpKeys = activePlugin.getSpecificPluginsList(PluginType.PUMP).map {
    ConfigurationBooleanComposedKey.ConfigBuilderEnabled.composeKey(PluginType.PUMP.name + "_" + it.javaClass.simpleName)
}
val currentEnabledPumps = enabledPumpKeys.filter { currentSp[it] == true }.toSet()
val importedEnabledPumps = enabledPumpKeys.filter { prefs.values[it] == "true" }.toSet()
val importChangesPumpDriver = importedEnabledPumps.isNotEmpty() && importedEnabledPumps != currentEnabledPumps

PrefImportSummaryDialog.showSummary(
    activity, importOk, importPossible, prefs,
    showKeepPumpConfig = hasLocalPumpSession || importChangesPumpDriver,
    keepPumpConfigDefault = importChangesPumpSession,
    ok = { keepPumpConfig ->
    if (importPossible) {
        activePlugin.beforeImport()
        val savedAapsDirectory = sp.getString(StringKey.AapsDirectoryUri.key, "")
        val preservedPumpConfig: Map<String, Any?> =
            if (keepPumpConfig) sp.getAll().filterKeys { pumpKeys.matches(it) } else emptyMap()
        if (keepPumpConfig)
            aapsLogger.info(LTag.CORE, "Import: keeping current pump configuration, preserving ${preservedPumpConfig.size} keys, ignoring imported pump keys")
        sp.clear()
        for ((key, value) in prefs.values) {
            if (keepPumpConfig && pumpKeys.matches(key)) continue
            if (value == "true" || value == "false") {
                sp.putBoolean(key, value.toBoolean())
            } else {
                sp.putString(key, value)
            }
        }
        // Restore current pump configuration with original value types
        for ((key, value) in preservedPumpConfig) {
            when (value) {
                is Boolean -> sp.putBoolean(key, value)
                is Int     -> sp.putInt(key, value)
                is Long    -> sp.putLong(key, value)
                is Float   -> sp.putDouble(key, value.toDouble())
                is String  -> sp.putString(key, value)
            }
        }
        // ... existing tail unchanged: AutomationStates off (3422 fork), restore AapsDirectoryUri,
        // activePlugin.afterImport(), automation-states dialog (3422 fork), restartAppAfterImport()
```

### 4.4 PrefImportSummaryDialog.kt

```kotlin
fun showSummary(
    context: Context, importOk: Boolean, importPossible: Boolean, prefs: Prefs,
    showKeepPumpConfig: Boolean = false, keepPumpConfigDefault: Boolean = false,
    ok: ((keepPumpConfig: Boolean) -> Unit)?, cancel: (() -> Unit)? = null
) {
    ...
    val keepPumpConfigCheckbox = (innerLayout.findViewById<View>(R.id.keep_pump_config) as CheckBox)

    if (showKeepPumpConfig && importPossible) {
        keepPumpConfigCheckbox.visibility = View.VISIBLE
        keepPumpConfigCheckbox.isChecked = keepPumpConfigDefault
    }
    ...
    // positive button:
    val keepPumpConfig = keepPumpConfigCheckbox.visibility == View.VISIBLE && keepPumpConfigCheckbox.isChecked
    dialog.dismiss()
    SystemClock.sleep(100)
    if (ok != null) runOnUiThread { ok(keepPumpConfig) }
```

(plus `import android.widget.CheckBox`)

### 4.5 dialog_alert_import_summary.xml

```xml
<com.google.android.material.checkbox.MaterialCheckBox
    android:id="@+id/keep_pump_config"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginStart="10dp"
    android:layout_marginTop="10dp"
    android:layout_marginEnd="10dp"
    android:text="@string/keep_current_pump_config"
    android:textSize="12sp"
    android:visibility="gone" />
```

### 4.6 strings.xml

```xml
<string name="keep_current_pump_config">Keep current pump configuration (pump driver selection, its settings and active pod/session state will NOT be overwritten by the imported file)</string>
```

### 4.7 Build verification
`.\gradlew.bat :plugins:configuration:compileFullDebugKotlin` → **BUILD SUCCESSFUL**
(JDK: `C:\Users\arjay\.jdks\jbr-17.0.14`; system `java` is too old for the Gradle wrapper,
and the Android Studio `jbr` install is broken — missing `lib/jvm.cfg`).
Only pre-existing warnings (GlobalScope in cloud export, deprecated `startActivityForResult`).

---

## 5. Virtual Pump test path

Virtual Pump has **no string session-state keys**, so on a phone that never had a pod the
original visibility rule would hide the checkbox. The `importChangesPumpDriver` OR-condition
(§4.3) was added for exactly this: with Virtual Pump selected and a pod-config file imported,
the file enables `OmnipodDashPumpPlugin` ≠ current `VirtualPumpPlugin` → checkbox shows
(unchecked). Note: on a phone that *has* had a pod, the pod_state key survives driver switches,
so the session-state rule triggers there anyway.

**Test procedure (Virtual Pump phone):**
1. Virtual Pump selected → import a settings file containing an Omnipod config
2. Checkbox appears, unchecked → tick it → import → AAPS restarts
3. Verify: Config Builder shows Virtual Pump as the **only** enabled pump, no Omnipod tab,
   non-pump settings came from the file
4. Repeat unticked → stock behaviour (Dash becomes the pump)

**Live-pod test (real phone, later):** import an *older* export → checkbox appears
**pre-checked** → import → pod still active in Omnipod tab and responds to status refresh.
Do it at a low-stakes time; exporting settings immediately before gives a rescue file with
the current pod state.

---

## 6. v3421 back-port

The direct patch from the 3422 commit does not apply to any of the 3421 trees (their
`ImportExportPrefsImpl.kt` lacks the fork's cloud-import and automation-states code; four
3421 repos on disk all differ from each other):

| Repo | ImportExportPrefsImpl.kt MD5 |
|---|---|
| `StudioProjects\APS3421a320` | 77520EF7…  ← **patch adapted against this one** |
| `StudioProjects3421\aAPS3421a320patch` | CC6A8FFC… |
| `StudioProjects\aISF3421ai320` | B2BB1DD7… |
| `StudioProjects\tobias-fresh-3421ai320` | CC62210D… |

The dialog + layout hunks apply cleanly everywhere; only `ImportExportPrefsImpl.kt` and
`strings.xml` needed adaptation. 3421 has both prerequisites (`ownPreferences`,
`ConfigurationBooleanComposedKey`). Differences in the adapted version:
- no automation-states block — flow goes straight to `restartAppAfterImport(activity)`
- `strings.xml` addition anchored after `google_drive_reauth_required`

Verified by compiling `:plugins:configuration:compileFullDebugKotlin` in `APS3421a320`.
Patch file: **`importpodoptional_3421.patch`** (apply with `git apply importpodoptional_3421.patch`
from the 3421 repo root; for the other 3421 variants expect the same two files to need
`--3way` or minor manual merge).

---

## 7. Round 2 — hardened trigger, follower-phone options, 3421 build fix

### 7.1 Field test result & diagnosis (question B)
On a Virtual Pump phone importing a pod-settings file: **no checkbox appeared, yet Virtual Pump
remained selected**. The installed commit did contain the driver-switch condition, so the imported
file's `ConfigBuilder_Enabled_PUMP_*` keys evidently did not match the current key format/plugin
names (old-format export or different fork). That also explains Virtual surviving: with no
recognizable pump-enable key in the file, ConfigBuilder falls back to `enableByDefault` → Virtual.

**Fix:** the visibility rule was replaced with a single broad rule — show the checkbox whenever
the import would change *anything* in the pump domain:

```kotlin
val pumpDomainKeys = (prefs.values.keys + currentSp.keys).filter { pumpKeys.matches(it) }.distinct()
val importChangesPumpDomain = pumpDomainKeys.any { key -> prefs.values[key] != currentSp[key]?.toString() }
```

A `pod_state` in the file is alone enough to trigger it now. Default-checked rule unchanged
(live local session differs from file).

### 7.2 Follower-phone options (question C)
Three additional checkboxes, shown **only when Virtual Pump is the active pump**
(`activePlugin.activePump is VirtualPump`):

- **Keep current patient name** (`StringKey.GeneralPatientName`)
- **Keep current BG source configuration** (`pluginDomainKeyMatcher(PluginType.BGSOURCE)`)
- **Keep current Synchronization configuration** — NSClient etc. (`pluginDomainKeyMatcher(PluginType.SYNC)`)

`PumpKeyMatcher` was generalized to `KeyDomainMatcher` + `pluginDomainKeyMatcher(type: PluginType)`
(ConfigBuilder Enabled/Visible keys of every plugin of that type + their `ownPreferences` keys).
The dialog now returns an `ImportChoices(keepPumpConfig, keepPatientName, keepBgSource, keepSync)`
object; the import flow builds a list of preserve-matchers from the selections and applies the same
snapshot → skip → typed-restore sequence for the union.

All defaults unchecked except the pump checkbox in the live-pod-loss case.

### 7.3 3421 pre-existing build breakage (question A)
`APS3421a320` (branch `3.4.2.1+aisf3.2.0`) did not compile at HEAD, before any of these changes:
`CloudPrefImportListActivity.kt` (from the partially-ported cloud-import feature in the branch's
recent commits) references `ImportExportPrefsImpl.cloudPrefsFiles / cloudNextPageToken /
cloudTotalFilesCount`, which were never added to 3421's `ImportExportPrefsImpl`. Fixed with the
matching companion object (same declarations as the 3422 fork). The cloud list activity remains
unreachable in 3421 (no cloud import entry point there) but now compiles.

### 7.4 State after round 2
- **AaAPS3422a320**: round-2 changes uncommitted in working tree on top of `fd85a5fdb4`;
  `:plugins:configuration:compileFullDebugKotlin` BUILD SUCCESSFUL (JDK 17).
- **APS3421a320**: complete feature set + companion fix in working tree;
  `:plugins:configuration:compileFullDebugKotlin` BUILD SUCCESSFUL (needs JDK 21+; used
  `.jdks\openjdk-23.0.2` — project targets Java 21).
- **`importpodoptional_3421.patch`** (in `AaAPS3422a320` root): regenerated from the 3421 working
  tree, includes everything (feature + follower options + companion fix); validated with
  `git apply --check --reverse`.

---

## 8. Session tooling notes

- PowerShell `>` redirection writes UTF-16 — git can't read such patch files; use
  `git format-patch -o` so git writes the file itself.
- PowerShell 5.1 `Get-Content -Raw`/`Set-Content -Encoding utf8` mangles UTF-8 specials
  (smart quotes → mojibake, adds BOM) — edit XML with a UTF-8-aware editor/tool instead.
- Gradle task name needs the flavor: `compileFullDebugKotlin` (module has
  full/aapsclient/pumpcontrol flavors).
