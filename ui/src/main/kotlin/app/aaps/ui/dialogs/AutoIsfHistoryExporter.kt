package app.aaps.ui.dialogs

import app.aaps.core.data.model.AIV
import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Single source of truth for the AutoISF history CSV / plain-text / settings export and for the
 * derived column values ("fast rise", steps, IOB-5-min-change, scheduled basal). Both
 * [AutoISFHistoryDialog] (for on-screen display and its open-time export) and KeepAliveWorker
 * (for the automatic 6-hourly export that rides alongside the log-file export) go through this
 * class, so the two never drift.
 */
class AutoIsfHistoryExporter @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val fileListProvider: FileListProvider,
    private val dateUtil: DateUtil,
    private val profileFunction: ProfileFunction,
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger
) {

    private val df1 = DecimalFormat("0.0")
    private val df2 = DecimalFormat("0.00")

    companion object {
        private const val MGDL_TO_MMOL = 18.0182
        const val WINDOW_HOURS = 6L
    }

    /** Queries the last [WINDOW_HOURS] hours and writes the CSV + text + settings files. Used by the
     *  background worker; the dialog uses [writeExport] directly with data it has already loaded. */
    fun exportLast6Hours(now: Long) {
        val from = now - TimeUnit.HOURS.toMillis(WINDOW_HOURS)
        val records = persistenceLayer.getAutoIsfValuesFromTimeToTime(from, now).sortedByDescending { it.timestamp }
        val apsResults = persistenceLayer.getApsResults(from, now)
        val stepsCounts = persistenceLayer.getStepsCountFromTimeToTime(from, now)
        writeExport(records, apsResults, stepsCounts, now)
    }

    // -----------------------------------------------------------------------------------------------
    // File export
    // -----------------------------------------------------------------------------------------------

    val exportHeaders = listOf(
        "Time", "BGL", "Final", "acce", "bg", "pp", "dura", "SMB", "FastRise", "SmbRatio", "iobTH",
        "acceBG", "Delta", "SDelta", "rawD5", "rawD15", "Req", "TBR", "IOB", "IOBd5", "Basal", "S5", "S15", "S30", "S60", "S180"
    )

    /** One record's export fields, in the same order as [exportHeaders], shared by both the CSV
     *  and the plain-text table export so the two stay in sync automatically. `allRecords` is the
     *  full (unfiltered) set, used for the IOB-5-min-change look-back. */
    private fun exportFields(r: AIV, apsResults: List<APSResult>, stepsCountList: List<SC>, allRecords: List<AIV>): List<String> {
        val sc = stepsAt(r.timestamp, stepsCountList)
        return listOf(
            dateUtil.timeString(r.timestamp),
            df1.format(r.glucose / MGDL_TO_MMOL),
            df2.format(r.finalIsf),
            df2.format(r.acceIsf),
            df2.format(r.bgIsf),
            df2.format(r.ppIsf),
            df2.format(r.duraIsf),
            df2.format(r.smbDelivered),
            exactFastRiseStr(r.timestamp, apsResults),
            df2.format(r.smbDeliveryRatio),
            df2.format(r.iobThEffective),
            df2.format(r.bgAcceleration),
            df2.format(r.delta / MGDL_TO_MMOL),
            df2.format(r.shortAvgDelta / MGDL_TO_MMOL),
            rawDeltaStr(r, allRecords, 5),
            rawDeltaStr(r, allRecords, 15),
            df2.format(r.insulinReq),
            df2.format(r.tbrRate),
            df2.format(r.iob),
            iob5MinChangeStr(r, allRecords),
            basalStr(r),
            stepsValue(sc, r.timestamp, apsResults, 5)?.toString() ?: "",
            stepsValue(sc, r.timestamp, apsResults, 15)?.toString() ?: "",
            stepsValue(sc, r.timestamp, apsResults, 30)?.toString() ?: "",
            stepsValue(sc, r.timestamp, apsResults, 60)?.toString() ?: "",
            stepsValue(sc, r.timestamp, apsResults, 180)?.toString() ?: ""
        )
    }

    /** Writes AutoISF_<stamp>.csv, AutoISF_<stamp>.txt and AutoISF_settings_<stamp>.txt into the
     *  aapsLogs directory. `records` is the set to export (also used as the IOB-5-min look-back set),
     *  so callers should pass the full unfiltered window. Runs on the caller's thread. */
    fun writeExport(records: List<AIV>, apsResults: List<APSResult>, stepsCountList: List<SC>, now: Long) {
        try {
            fileListProvider.ensureAapsLogsDirExists()
            val dir = fileListProvider.aapsLogsPath
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
            val rows = records.map { exportFields(it, apsResults, stepsCountList, records) }

            val csvFile = File(dir, "AutoISF_$stamp.csv")
            csvFile.bufferedWriter().use { writer ->
                writer.write(exportHeaders.joinToString(",") + "\n")
                for (row in rows) writer.write(row.joinToString(",") + "\n")
            }
            aapsLogger.debug(LTag.UI, "AutoISF history exported to ${csvFile.absolutePath}")

            exportTableAsText(dir, stamp, rows)
            exportSettingsText(dir, stamp)
        } catch (e: Exception) {
            aapsLogger.error(LTag.UI, "AutoISF CSV export failed", e)
        }
    }

    /** Same table data as the CSV, in a human-readable, column-aligned plain-text file — for
     *  viewing directly on a phone/PC without needing a spreadsheet app. */
    private fun exportTableAsText(dir: File, stamp: String, rows: List<List<String>>) {
        try {
            val file = File(dir, "AutoISF_$stamp.txt")
            val widths = exportHeaders.indices.map { i ->
                maxOf(exportHeaders[i].length, rows.maxOfOrNull { it[i].length } ?: 0)
            }
            file.bufferedWriter().use { writer ->
                writer.write(exportHeaders.mapIndexed { i, h -> h.padEnd(widths[i]) }.joinToString("  ").trimEnd() + "\n")
                for (row in rows) {
                    writer.write(row.mapIndexed { i, v -> v.padEnd(widths[i]) }.joinToString("  ").trimEnd() + "\n")
                }
            }
            aapsLogger.debug(LTag.UI, "AutoISF history text export to ${file.absolutePath}")
        } catch (e: Exception) {
            aapsLogger.error(LTag.UI, "AutoISF text export failed", e)
        }
    }

    /** Plain-text dump of just the AutoISF-specific preference values (not the full app settings
     *  list), sorted by name, written alongside the CSV so a reviewer knows what AutoISF settings
     *  were in effect for that export. */
    private fun exportSettingsText(dir: File, stamp: String) {
        try {
            val file = File(dir, "AutoISF_settings_$stamp.txt")
            val lines = mutableListOf<String>()
            BooleanKey.entries.filter { it.name.contains("AutoIsf") }.forEach { lines.add("${it.name} (${it.key}) = ${preferences.get(it)}") }
            IntKey.entries.filter { it.name.contains("AutoIsf") }.forEach { lines.add("${it.name} (${it.key}) = ${preferences.get(it)}") }
            DoubleKey.entries.filter { it.name.contains("AutoIsf") }.forEach { lines.add("${it.name} (${it.key}) = ${preferences.get(it)}") }
            UnitDoubleKey.entries.filter { it.name.contains("AutoIsf") }.forEach { lines.add("${it.name} (${it.key}) = ${preferences.get(it)}") }
            StringKey.entries.filter { it.name.contains("AutoIsf") }.forEach { lines.add("${it.name} (${it.key}) = ${preferences.get(it)}") }
            file.bufferedWriter().use { writer ->
                for (line in lines.sorted()) writer.write("$line\n")
            }
            aapsLogger.debug(LTag.UI, "AutoISF settings exported to ${file.absolutePath}")
        } catch (e: Exception) {
            aapsLogger.error(LTag.UI, "AutoISF settings export failed", e)
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Derived column values — public so the dialog's on-screen table uses the exact same logic
    // -----------------------------------------------------------------------------------------------

    // "...for early/mild fast rise <value> ..." — the fast-rise-specific ratio itself (e.g. 0.507), as
    // opposed to the "microBolus * <factor>" multiplier below, which is a coarser single-digit fallback.
    private val fastRiseFullRegex = Regex("""fast\s*rise\s*([0-9]+\.[0-9]+)""", RegexOption.IGNORE_CASE)

    // Same reason-text pattern used for the graph's fast-rise SMB label (see PrepareTreatmentsDataWorker.kt):
    // "<threshold>  = microBolus  * <factor> ; microBolus = <result>".
    private val fastRiseFactorRegex = Regex("""=\s*microBolus\s*\*\s*([0-9.]+)\s*;""")

    /** Fast-rise value from the nearest APSResult within 15 min. Prefers the full ratio following
     *  "fast rise" (e.g. 0.507 -> "507", decimal point and leading zeros dropped);
     *  falls back to the coarser single-digit "microBolus * factor" (e.g. 0.4 -> "4") if the full value
     *  isn't present in that branch's reason text; "--" if neither fired. */
    fun exactFastRiseStr(timestamp: Long, apsResults: List<APSResult>): String {
        val nearest = apsResults.minByOrNull { kotlin.math.abs(it.date - timestamp) } ?: return "--"
        if (kotlin.math.abs(nearest.date - timestamp) >= TimeUnit.MINUTES.toMillis(15)) return "--"
        val full = fastRiseFullRegex.find(nearest.reason)?.groupValues?.get(1)?.toDoubleOrNull()
        if (full != null) return String.format(Locale.US, "%.3f", full).replace(".", "").trimStart('0').ifEmpty { "0" }
        val factor = fastRiseFactorRegex.find(nearest.reason)?.groupValues?.get(1)?.toDoubleOrNull() ?: return "--"
        return Math.round(factor * 10).toString()
    }

    // Step counts get written into DetermineBasalAutoISF.kt's reason text as "StepsXM: <value> ;"
    // (also accepts "stepsXmin is <value>", matching a second phrasing observed in synced data).
    // On a client build (aapsclient/aapsclient2), NS device-status sync only creates local AIV +
    // APSResult records — it never populates the local StepsCount table — so the on-device step
    // columns are otherwise always empty there even though the master's real step data reached the
    // client fine, just embedded in this text rather than a structured field.
    private fun stepsRegex(label: String) = Regex("""\b${label}min\s+is\s+([0-9]+)\b|\b${label}M\s*[:=]\s*([0-9]+)\b""", RegexOption.IGNORE_CASE)
    private val steps5Regex = stepsRegex("[Ss]teps5")
    private val steps15Regex = stepsRegex("[Ss]teps15")
    private val steps30Regex = stepsRegex("[Ss]teps30")
    private val steps60Regex = stepsRegex("[Ss]teps60")
    private val steps180Regex = stepsRegex("[Ss]teps180")

    /** Step count parsed from the nearest APSResult's reason text within 15 min, or null if no
     *  match/no result close enough. Used as a fallback when the local StepsCount lookup is empty. */
    private fun stepsFromReason(timestamp: Long, apsResults: List<APSResult>, regex: Regex): Int? {
        val nearest = apsResults.minByOrNull { kotlin.math.abs(it.date - timestamp) } ?: return null
        if (kotlin.math.abs(nearest.date - timestamp) >= TimeUnit.MINUTES.toMillis(15)) return null
        val m = regex.find(nearest.reason) ?: return null
        return (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
    }

    /** SC's stepXmin if a local record is close enough, else the reason-text fallback.
     *  `bucketMinutes` selects the window: 5, 15, 30, 60 or 180. */
    fun stepsValue(sc: SC?, timestamp: Long, apsResults: List<APSResult>, bucketMinutes: Int): Int? {
        val (field, regex) = when (bucketMinutes) {
            5    -> SC::steps5min to steps5Regex
            15   -> SC::steps15min to steps15Regex
            30   -> SC::steps30min to steps30Regex
            60   -> SC::steps60min to steps60Regex
            else -> SC::steps180min to steps180Regex
        }
        return sc?.let(field) ?: stepsFromReason(timestamp, apsResults, regex)
    }

    /** Nearest StepsCount record within 15 min of `timestamp`, or null if none close enough. */
    fun stepsAt(timestamp: Long, stepsCountList: List<SC>): SC? {
        val nearest = stepsCountList.minByOrNull { kotlin.math.abs(it.timestamp - timestamp) } ?: return null
        if (kotlin.math.abs(nearest.timestamp - timestamp) >= TimeUnit.MINUTES.toMillis(15)) return null
        return nearest
    }

    /** IOB change over the 5 min preceding this record. Looks back in the FULL record set, so the
     *  value is correct even when the display is filtered to SMB-only rows. "--" if no record sits
     *  near 5 min before this one. */
    fun iob5MinChangeStr(r: AIV, allRecords: List<AIV>): String {
        val target = r.timestamp - 5 * 60_000L
        val prior = allRecords.minByOrNull { kotlin.math.abs(it.timestamp - target) } ?: return "--"
        if (kotlin.math.abs(prior.timestamp - target) > 3 * 60_000L) return "--"
        return df2.format(r.iob - prior.iob)
    }

    /** Raw (unsmoothed) glucose change over [minutesBack] minutes, in mmol — this record's glucose
     *  minus the glucose of the nearest record about [minutesBack] min earlier. This is the signal
     *  the dosing code's rawDelta5MinMgdl() gate reads, as opposed to the smoothed AAPS delta. Uses
     *  the full record set so filtering rows never distorts it; "--" if no record sits near the
     *  look-back point. */
    fun rawDeltaStr(r: AIV, allRecords: List<AIV>, minutesBack: Int): String {
        val target = r.timestamp - minutesBack * 60_000L
        val tolMs = if (minutesBack >= 15) 4 * 60_000L else 3 * 60_000L
        val prior = allRecords.minByOrNull { kotlin.math.abs(it.timestamp - target) } ?: return "--"
        if (kotlin.math.abs(prior.timestamp - target) > tolMs) return "--"
        return df2.format((r.glucose - prior.glucose) / MGDL_TO_MMOL)
    }

    /** Scheduled profile basal rate (U/hr) at this record's time, or "--" if unresolvable. */
    fun basalStr(r: AIV): String =
        profileFunction.getProfile(r.timestamp)?.getBasal(r.timestamp)?.let { df2.format(it) } ?: "--"
}
