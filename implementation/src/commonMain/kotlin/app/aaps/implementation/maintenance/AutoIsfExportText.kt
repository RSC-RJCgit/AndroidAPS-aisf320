package app.aaps.implementation.maintenance

import app.aaps.core.data.configuration.Constants
import app.aaps.core.interfaces.tempTargets.toTTPresets
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.autoisf.autoIsfV3ExportCsv
import app.aaps.core.objects.autoisf.autoIsfV3ExportText
import kotlin.math.roundToInt

/** How far back the user-entries file looks. */
const val USER_ENTRIES_EXPORT_WINDOW_MS = 30L * 60L * 60L * 1000L

/**
 * The exported rows as csv, with the column names first. Since 2026-11-20 (per explicit request) the rows and columns are the
 * 3426 app's (see loadAutoIsfV3ExportRows and AUTO_ISF_V3_EXPORT_HEADER), so v3 and v4 files read the same way.
 */
fun autoIsfExportCsv(rows: List<List<String>>): String = autoIsfV3ExportCsv(rows)

/** The exported rows as the 3426 app's padded text table (was one " | " line per row until 2026-11-20). */
fun autoIsfExportText(rows: List<List<String>>): String = autoIsfV3ExportText(rows)

/** One "key = value" line per setting, sorted by key. */
fun autoIsfSettingsText(entries: List<Pair<String, String>>): String =
    entries.sortedBy { it.first }.joinToString("\n") { "${it.first} = ${it.second}" }

/**
 * 2026-11-16, per explicit request: the temp target presets, one line each, so the settings file shows them
 * (e.g. "temp_target_preset_eatingsoon = 4.4 mmol (79 mg/dL), 20 min"). Stored in mg/dL.
 */
fun tempTargetPresetSettings(preferences: Preferences): List<Pair<String, String>> =
    preferences.get(StringNonKey.TempTargetPresets).toTTPresets().map { preset ->
        val name = (preset.name ?: preset.id).replace(' ', '_')
        val mmol = (preset.targetValue * Constants.MGDL_TO_MMOLL * 10.0).roundToInt() / 10.0
        "temp_target_preset_$name" to "$mmol mmol (${preset.targetValue.roundToInt()} mg/dL), ${preset.duration / 60_000L} min"
    }

