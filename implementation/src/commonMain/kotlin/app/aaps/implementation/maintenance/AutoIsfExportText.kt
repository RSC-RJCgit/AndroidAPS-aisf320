package app.aaps.implementation.maintenance

import app.aaps.core.data.model.AIV

/** How far back the automatic history files look. Same window as the on-screen table. */
const val AUTO_ISF_EXPORT_WINDOW_MS = 6L * 60L * 60L * 1000L

/** How far back the user-entries file looks. */
const val USER_ENTRIES_EXPORT_WINDOW_MS = 30L * 60L * 60L * 1000L

/** Column names for the history file. Same fields as the on-screen table, in English. */
val AUTO_ISF_EXPORT_HEADER = listOf(
    "Time", "BGL", "Final", "acce", "bg", "pp", "dura", "Accel", "Delta", "Short", "Long", "IOB", "SMB"
)

/** One stored row, already in the user's glucose units. A factor of 1.0 and a zero insulin amount are a dash. */
fun autoIsfExportCells(
    row: AIV,
    timeText: (Long) -> String,
    glucoseText: (Double) -> String,
    deltaText: (Double) -> String,
    format2: (Double) -> String
): List<String> = listOf(
    timeText(row.timestamp),
    glucoseText(row.glucose),
    format2(row.finalIsf),
    dashIfNeutral(row.acceIsf, format2),
    dashIfNeutral(row.bgIsf, format2),
    dashIfNeutral(row.ppIsf, format2),
    dashIfNeutral(row.duraIsf, format2),
    format2(row.bgAcceleration),
    deltaText(row.delta),
    deltaText(row.shortAvgDelta),
    deltaText(row.longAvgDelta),
    dashIfZero(row.iob, format2),
    dashIfZero(row.smbDelivered, format2)
)

fun autoIsfExportCsv(rows: List<List<String>>): String {
    val lines = listOf(AUTO_ISF_EXPORT_HEADER) + rows
    return lines.joinToString("\n") { line -> line.joinToString(",") { csvCell(it) } }
}

fun autoIsfExportText(rows: List<List<String>>): String {
    val lines = listOf(AUTO_ISF_EXPORT_HEADER) + rows
    return lines.joinToString("\n") { it.joinToString(" | ") }
}

/** One "key = value" line per setting, sorted by key. */
fun autoIsfSettingsText(entries: List<Pair<String, String>>): String =
    entries.sortedBy { it.first }.joinToString("\n") { "${it.first} = ${it.second}" }

private fun dashIfNeutral(value: Double, format2: (Double) -> String): String =
    if (value == 1.0) "--" else format2(value)

private fun dashIfZero(value: Double, format2: (Double) -> String): String =
    if (value == 0.0) "--" else format2(value)

private fun csvCell(value: String): String =
    if (value.contains(',') || value.contains('"') || value.contains('\n'))
        "\"" + value.replace("\"", "\"\"") + "\""
    else
        value
