package app.aaps.implementation.maintenance

import app.aaps.core.objects.autoisf.AUTO_ISF_HISTORY_EXPORT_HEADER

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

private fun csvCell(value: String): String =
    if (value.contains(',') || value.contains('"') || value.contains('\n'))
        "\"" + value.replace("\"", "\"\"") + "\""
    else
        value
