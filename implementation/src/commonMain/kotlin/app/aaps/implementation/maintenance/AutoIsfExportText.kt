package app.aaps.implementation.maintenance

import app.aaps.core.data.configuration.Constants
import app.aaps.core.interfaces.tempTargets.toTTPresets
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.autoisf.AUTO_ISF_HISTORY_EXPORT_HEADER
import kotlin.math.roundToInt

/** How far back the user-entries file looks. */
const val USER_ENTRIES_EXPORT_WINDOW_MS = 30L * 60L * 60L * 1000L

/** The table rows (see `AutoIsfHistoryRow.cells`) as csv, with the column names first. */
fun autoIsfExportCsv(rows: List<List<String>>): String {
    val lines = listOf(AUTO_ISF_HISTORY_EXPORT_HEADER) + rows
    return lines.joinToString("\n") { line -> line.joinToString(",") { csvCell(it) } }
}

fun autoIsfExportText(rows: List<List<String>>): String {
    val lines = listOf(AUTO_ISF_HISTORY_EXPORT_HEADER) + rows
    return lines.joinToString("\n") { it.joinToString(" | ") }
}

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

private fun csvCell(value: String): String =
    if (value.contains(',') || value.contains('"') || value.contains('\n'))
        "\"" + value.replace("\"", "\"\"") + "\""
    else
        value
