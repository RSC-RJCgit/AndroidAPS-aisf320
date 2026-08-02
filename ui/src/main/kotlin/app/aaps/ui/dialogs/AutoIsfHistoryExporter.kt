package app.aaps.ui.dialogs

import app.aaps.core.data.model.AIV
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
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
import kotlin.math.roundToInt

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
    private val aapsLogger: AAPSLogger,
    private val iobCobCalculator: IobCobCalculator
) {

    private val df1 = DecimalFormat("0.0")
    private val df2 = DecimalFormat("0.00")

    companion object {
        private const val MGDL_TO_MMOL = 18.0182
        const val WINDOW_HOURS = 6L
    }

    /** Queries the last [WINDOW_HOURS] hours and writes the CSV + text + settings files, returning
     *  them so callers with cloud access (e.g. KeepAliveWorker, in the app module — this ui-module
     *  class has no CloudStorageManager dependency) can additionally upload them. Used by the
     *  background worker; the dialog uses [writeExport] directly with data it has already loaded. */
    fun exportLast6Hours(now: Long): List<File> {
        val from = now - TimeUnit.HOURS.toMillis(WINDOW_HOURS)
        val records = persistenceLayer.getAutoIsfValuesFromTimeToTime(from, now).sortedByDescending { it.timestamp }
        val apsResults = persistenceLayer.getApsResults(from, now)
        val stepsCounts = persistenceLayer.getStepsCountFromTimeToTime(from, now)
        val smbBoluses = persistenceLayer.getBolusesFromTimeToTime(from, now, ascending = false).filter { it.type == BS.Type.SMB }
        // 20-min lead-in so the oldest rows still have their raw-delta look-backs available
        val rawReadings = persistenceLayer.getBgReadingsDataFromTimeToTime(from - 20 * 60_000L, now, ascending = false)
        return writeExport(records, apsResults, stepsCounts, smbBoluses, mjNotesFrom(from), rawReadings, now)
    }

    /** MJ-lifecycle careportal notes (newest-first), across ALL sources — native automation, code, or
     *  manual — since they're all TE.Type.NOTE rows. Used to derive the per-row MJ state. Filters to
     *  just the notes that mark an MJ transition: "MJ"->MJa, "MJ2", "MJ3", "MJoff*"/"A1"->NOM.
     *  Queries 24h BEFORE [from] (not just the display window): the last MJ transition — e.g. the
     *  midnight MJoff — is often older than the 6h table window, and without it every row would
     *  wrongly fall back to the no-note default. Same 24h lookback as the graph annotation. */
    fun mjNotesFrom(from: Long): List<TE> =
        persistenceLayer.getTherapyEventDataFromTime(from - TimeUnit.HOURS.toMillis(24), TE.Type.NOTE, ascending = false)
            .filter { val t = it.note ?: ""; t == "MJ" || t == "MJ2" || t == "MJ3" || t == "MoreMJ" || t == "A1" || t == "NOMJremains" || t.startsWith("MJoff") }

    // -----------------------------------------------------------------------------------------------
    // File export
    // -----------------------------------------------------------------------------------------------

    val exportHeaders = listOf(
        "Time", "BGL", "Final", "acce", "bg", "pp", "dura", "SMB", "FastRise", "SmbRatio", "SMBi5", "iobTH", "acWt", "ppWt", "Lslope",
        "acceBG", "Delta", "SDelta", "rawBGL", "rawD1", "rawD5", "rawD15", "Int5", "Req", "TBR", "IOB", "IOBd5", "Basal", "HP", "S5", "S15", "S30", "S60", "S180", "MJ"
    )

    /** One record's export fields, in the same order as [exportHeaders], shared by both the CSV
     *  and the plain-text table export so the two stay in sync automatically. `allRecords` is the
     *  full (unfiltered) set, used for the IOB-5-min-change look-back. */
    private fun exportFields(r: AIV, apsResults: List<APSResult>, stepsCountList: List<SC>, allRecords: List<AIV>, smbBoluses: List<BS>, mjNotes: List<TE>, rawReadings: List<GV>): List<String> {
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
            smbInterval5SecStr(r.timestamp, smbBoluses),
            df2.format(r.iobThEffective),
            df2.format(r.acceIsfWeight),
            ppWeightStr(r.timestamp, apsResults),
            df2.format(r.fslCalSlope),
            df2.format(r.bgAcceleration),
            df2.format(r.delta / MGDL_TO_MMOL),
            df2.format(r.shortAvgDelta / MGDL_TO_MMOL),
            rawBglStr(r.timestamp, rawReadings),
            rawDeltaStr(r.timestamp, rawReadings, 1),
            rawDeltaStr(r.timestamp, rawReadings, 5),
            rawDeltaStr(r.timestamp, rawReadings, 15),
            readingIntervalStr(r.timestamp, rawReadings),
            df2.format(r.insulinReq),
            df2.format(r.tbrRate),
            df2.format(r.iob),
            iob5MinChangeStr(r, allRecords),
            basalStr(r),
            hpStr(r, rawReadings, sc, apsResults),
            stepsValue(sc, r.timestamp, apsResults, 5)?.toString() ?: "",
            stepsValue(sc, r.timestamp, apsResults, 15)?.toString() ?: "",
            stepsValue(sc, r.timestamp, apsResults, 30)?.toString() ?: "",
            stepsValue(sc, r.timestamp, apsResults, 60)?.toString() ?: "",
            stepsValue(sc, r.timestamp, apsResults, 180)?.toString() ?: "",
            mjStateStr(r.timestamp, mjNotes)
        )
    }

    /** Writes AutoISF_<stamp>.csv, AutoISF_<stamp>.txt and AutoISF_settings_<stamp>.txt into the
     *  aapsLogs directory, returning whichever of the three were written successfully (empty list on
     *  total failure). `records` is the set to export (also used as the IOB-5-min look-back set), so
     *  callers should pass the full unfiltered window. Runs on the caller's thread. */
    fun writeExport(records: List<AIV>, apsResults: List<APSResult>, stepsCountList: List<SC>, smbBoluses: List<BS>, mjNotes: List<TE>, rawReadings: List<GV>, now: Long): List<File> {
        val written = mutableListOf<File>()
        try {
            fileListProvider.ensureAapsLogsDirExists()
            // Reuses GeneralPatientName (same value the logs cloud export already scopes by) rather
            // than a separate new setting — if you've already set different patient names per device to
            // distinguish the logs cloud folder, this scopes AIV exports the same way for free, both
            // the folder and the filename. Empty (default) falls back to the original unscoped
            // folder/filename, unchanged.
            val patientName = preferences.get(StringKey.GeneralPatientName).trim()
            val dir = if (patientName.isNotEmpty()) {
                File(fileListProvider.aapsLogsPath, patientName).also { it.mkdirs() }
            } else {
                fileListProvider.aapsLogsPath
            }
            val baseStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
            val stamp = if (patientName.isNotEmpty()) "${patientName}_$baseStamp" else baseStamp
            val rows = records.map { exportFields(it, apsResults, stepsCountList, records, smbBoluses, mjNotes, rawReadings) }

            val csvFile = File(dir, "AutoISF_$stamp.csv")
            csvFile.bufferedWriter().use { writer ->
                writer.write(exportHeaders.joinToString(",") + "\n")
                for (row in rows) writer.write(row.joinToString(",") + "\n")
            }
            aapsLogger.debug(LTag.UI, "AutoISF history exported to ${csvFile.absolutePath}")
            written.add(csvFile)

            exportTableAsText(dir, stamp, rows)?.let { written.add(it) }
            exportSettingsText(dir, stamp)?.let { written.add(it) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.UI, "AutoISF CSV export failed", e)
        }
        return written
    }

    /** Same table data as the CSV, in a human-readable, column-aligned plain-text file — for
     *  viewing directly on a phone/PC without needing a spreadsheet app. Returns the file on success,
     *  null on failure. */
    private fun exportTableAsText(dir: File, stamp: String, rows: List<List<String>>): File? {
        return try {
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
            file
        } catch (e: Exception) {
            aapsLogger.error(LTag.UI, "AutoISF text export failed", e)
            null
        }
    }

    /** Plain-text dump of just the AutoISF-specific preference values (not the full app settings
     *  list), one "key = value" line each (key is the short preference-key string, not the CamelCase
     *  enum name), sorted alphabetically by key, written alongside the CSV so a reviewer knows what
     *  AutoISF settings were in effect for that export. Double/UnitDouble values are rounded to 2
     *  decimals for readability. Returns the file on success, null on failure. */
    private fun exportSettingsText(dir: File, stamp: String): File? {
        return try {
            val file = File(dir, "AutoISF_settings_$stamp.txt")
            val lines = mutableListOf<String>()
            BooleanKey.entries.filter { it.name.contains("AutoIsf") }.forEach { lines.add("${it.key} = ${preferences.get(it)}") }
            IntKey.entries.filter { it.name.contains("AutoIsf") }.forEach { lines.add("${it.key} = ${preferences.get(it)}") }
            DoubleKey.entries.filter { it.name.contains("AutoIsf") }.forEach { lines.add("${it.key} = ${df2.format(preferences.get(it))}") }
            UnitDoubleKey.entries.filter { it.name.contains("AutoIsf") }.forEach { lines.add("${it.key} = ${df2.format(preferences.get(it))}") }
            StringKey.entries.filter { it.name.contains("AutoIsf") }.forEach { lines.add("${it.key} = ${preferences.get(it)}") }
            file.bufferedWriter().use { writer ->
                for (line in lines.sorted()) writer.write("$line\n")
            }
            aapsLogger.debug(LTag.UI, "AutoISF settings exported to ${file.absolutePath}")
            file
        } catch (e: Exception) {
            aapsLogger.error(LTag.UI, "AutoISF settings export failed", e)
            null
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

    // "pp_ISF_weight is <value> ;;" written into RT.reason every determineBasal cycle (see
    // DetermineBasalAutoISF.kt) — the same synced RT.reason text NSDeviceStatusHandler.kt already
    // regex-parses to reconstruct acWt (AcceIsfWeight) on a client build, so this is exactly as
    // reliable there as acWt is; no separate persisted AIV field needed.
    private val ppWeightRegex = Regex("""pp_ISF_weight\s+is\s+([0-9.]+)""", RegexOption.IGNORE_CASE)

    /** ppISFwt value from the nearest APSResult within 15 min (same nearest-match pattern as
     *  exactFastRiseStr/stepsFromReason above). "--" if none close enough or no match in the reason text. */
    fun ppWeightStr(timestamp: Long, apsResults: List<APSResult>): String {
        val nearest = apsResults.minByOrNull { kotlin.math.abs(it.date - timestamp) } ?: return "--"
        if (kotlin.math.abs(nearest.date - timestamp) >= TimeUnit.MINUTES.toMillis(15)) return "--"
        val value = ppWeightRegex.find(nearest.reason)?.groupValues?.get(1)?.toDoubleOrNull() ?: return "--"
        return df2.format(value)
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

    /** Raw Libre delta at [timestamp] over the [minutesBack]-min window, in mmol — computed from the
     *  BG readings' NOISE field (the raw native Libre signal), NOT the smoothed AIV glucose, so these
     *  columns show exactly what the dosing gates and the graph "L1=" annotation read. (An earlier
     *  version differenced the AIV glucose — the smoothed series — which could even disagree in SIGN
     *  with the gates near a turn; that was wrong and is why this reads raw now.)
     *  - 1-min mirrors rawDelta1MinMgdl(): the two newest readings at/before the row, scaled to a
     *    per-5-min rate ((n-p)/gap ×5).
     *  - 5-min mirrors rawDelta5MinMgdl(): newest minus the reading nearest 5 min back (plain
     *    difference — a 5-min window IS the per-5-min rate).
     *  - 15-min follows the same nearest-reference pattern, normalised to a per-5-min rate so it
     *    stays on the shared mmol/5min scale.
     *  `rawReadings` must be newest-first. "--" when readings or their noise values are missing. */
    fun rawDeltaStr(timestamp: Long, rawReadings: List<GV>, minutesBack: Int): String {
        // Same window sizes as the gate functions: 3 min for the 1-min delta, window+2 otherwise.
        val inWindow = rawReadings.filter { it.timestamp in (timestamp - (minutesBack + 2) * 60_000L)..timestamp }
        if (inWindow.size < 2) return "--"
        val newest = inWindow.first()
        val n = newest.noise ?: return "--"
        if (minutesBack <= 1) {
            val p = inWindow[1].noise ?: return "--"
            val mins = (newest.timestamp - inWindow[1].timestamp) / 60_000.0
            if (mins <= 0.0) return "--"
            return df2.format((n - p) / mins * 5.0 / MGDL_TO_MMOL)
        }
        val target = timestamp - minutesBack * 60_000L
        val ref = inWindow.minByOrNull { kotlin.math.abs(it.timestamp - target) } ?: return "--"
        if (ref.timestamp == newest.timestamp) return "--"
        val refNoise = ref.noise ?: return "--"
        return if (minutesBack <= 5)
            df2.format((n - refNoise) / MGDL_TO_MMOL)
        else {
            val actualMin = (newest.timestamp - ref.timestamp) / 60_000.0
            if (actualMin <= 0.0) return "--"
            df2.format((n - refNoise) / actualMin * 5.0 / MGDL_TO_MMOL)
        }
    }

    /** Raw Libre BGL (mmol) at [timestamp] — the raw NOISE field itself, i.e. the same underlying
     *  reading rΔ1/5/15 are differenced from. Lets the raw value be eyeballed directly (e.g. "raw was
     *  6.1 five min ago, now 6.4") instead of only inferred from its deltas. Same 3-min window/newest
     *  lookup as rawDeltaStr's 1-min case. "--" if no reading in range or its noise value is missing.
     *  `rawReadings` must be newest-first. */
    fun rawBglStr(timestamp: Long, rawReadings: List<GV>): String {
        val inWindow = rawReadings.filter { it.timestamp in (timestamp - 3 * 60_000L)..timestamp }
        val newest = inWindow.firstOrNull() ?: return "--"
        val n = newest.noise ?: return "--"
        return df1.format(n / MGDL_TO_MMOL)
    }

    /** Average gap in SECONDS between BG/Libre readings in the 5 min BEFORE this record's timestamp —
     *  same units/style as smbInterval5SecStr's Sint, so a Libre reporting every ~60s shows ~60, not a
     *  flat "1.00" minutes that hides the actual jitter. "--" if fewer than 2 readings fell in that
     *  window. `rawReadings` must be newest-first. */
    fun readingIntervalStr(timestamp: Long, rawReadings: List<GV>): String {
        val windowStart = timestamp - 5 * 60_000L
        val inWindow = rawReadings.filter { it.timestamp in windowStart..timestamp }
        if (inWindow.size < 2) return "--"
        val spanSec = (inWindow.maxOf { it.timestamp } - inWindow.minOf { it.timestamp }).toDouble() / 1000.0
        return (spanSec / (inWindow.size - 1)).roundToInt().toString()
    }

    /** Average gap in seconds between SMBs delivered in the 5 min BEFORE this record's timestamp —
     *  the same "SMBint5" the dosing code computes live (for its rapid-stacking guard). "--" if fewer
     *  than 2 SMBs fell in that window. `smbBoluses` is the full window's SMB list (newest-first). */
    fun smbInterval5SecStr(timestamp: Long, smbBoluses: List<BS>): String {
        val windowStart = timestamp - 5 * 60_000L
        val inWindow = smbBoluses.filter { it.timestamp in windowStart..timestamp }
        if (inWindow.size < 2) return "--"
        val spanSec = (inWindow.maxOf { it.timestamp } - inWindow.minOf { it.timestamp }).toDouble() / 1000.0
        return (spanSec / (inWindow.size - 1)).roundToInt().toString()
    }

    /** MJ automation state as of this row, from the most recent MJ-lifecycle note at or before the
     *  row's timestamp: "MJ"->MJa, "MJ2"->MJ2, "MJ3"->MJ3, "MJoff*"/"A1"->NOM. Defaults to "NOM"
     *  when no MJ note is found in the 24h lookback — the midnight MJoff check resets the state to
     *  NOMJremains, so a long note-less stretch means NOM, not unknown (matches the graph line).
     *  `mjNotes` must be pre-filtered (see mjNotesFrom) and newest-first. */
    fun mjStateStr(timestamp: Long, mjNotes: List<TE>): String {
        val latest = mjNotes.firstOrNull { it.timestamp <= timestamp } ?: return "NOM"
        val note = latest.note ?: return "NOM"
        return when {
            note == "MJ"  -> "MJa"
            note == "MJ2" -> "MJ2"
            note == "MJ3" -> "MJ3"
            note == "MoreMJ" -> "MJ3"   // MoreMJ advances NOMJremains -> MJ3 directly
            else          -> "NOM"   // "A1", "MJoff*", "NOMJremains" (native), or anything unexpected → not in an MJ state
        }
    }

    /** Scheduled profile basal rate (U/hr) at this record's time, or "--" if unresolvable. */
    fun basalStr(r: AIV): String =
        profileFunction.getProfile(r.timestamp)?.getBasal(r.timestamp)?.let { df2.format(it) } ?: "--"

    /** Same 5-min raw-Libre-delta window/logic as rawDeltaStr's minutesBack<=5 branch, but returning the
     *  raw mmol Double instead of a formatted string, for use in hpStr's own arithmetic. Kept separate
     *  from rawDeltaStr rather than refactoring it, to avoid touching that already-relied-upon function.
     *  `rawReadings` must be newest-first. Null when readings/noise are missing (same conditions as
     *  rawDeltaStr returning "--"). */
    private fun rawDelta5Mmol(timestamp: Long, rawReadings: List<GV>): Double? {
        val inWindow = rawReadings.filter { it.timestamp in (timestamp - 7 * 60_000L)..timestamp }
        if (inWindow.size < 2) return null
        val newest = inWindow.first()
        val n = newest.noise ?: return null
        val target = timestamp - 5 * 60_000L
        val ref = inWindow.minByOrNull { kotlin.math.abs(it.timestamp - target) } ?: return null
        if (ref.timestamp == newest.timestamp) return null
        val refNoise = ref.noise ?: return null
        return (n - refNoise) / MGDL_TO_MMOL
    }

    /** Hypo-prediction: (BGL[mmol] - IOB) + 0.25*SDelta[mmol] + 0.25*LibreDelta5[mmol] + COB/5 -
     *  Steps60/750 - Steps30/750 — same formula as the graph rows (PrepareBgDataWorker.kt), but
     *  historically accurate here: COB comes from iobCobCalculator.ads.getAutosensDataAtTime(r.timestamp),
     *  the record's own COB at ITS timestamp (not today's live COB, which would be wrong for older rows);
     *  Steps30/60 come from stepsValue(sc, ...) — same nearest-record-or-reason-text lookup already used
     *  for this row's own S30/S60 columns (0 if unavailable, rather than blanking the whole row). "--" if
     *  the raw-Libre-delta window doesn't have enough data at this row's timestamp. `rawReadings` must be
     *  newest-first. */
    fun hpStr(r: AIV, rawReadings: List<GV>, sc: SC?, apsResults: List<APSResult>): String {
        val libreDelta5Mmol = rawDelta5Mmol(r.timestamp, rawReadings) ?: return "--"
        val bglMmol = r.glucose / MGDL_TO_MMOL
        val sdeltaMmol = r.shortAvgDelta / MGDL_TO_MMOL
        val cob = iobCobCalculator.ads.getAutosensDataAtTime(r.timestamp)?.cob ?: 0.0
        val steps30 = stepsValue(sc, r.timestamp, apsResults, 30) ?: 0
        val steps60 = stepsValue(sc, r.timestamp, apsResults, 60) ?: 0
        val hp = (bglMmol - r.iob) + 0.25 * sdeltaMmol + 0.25 * libreDelta5Mmol + cob / 5.0 -
            steps60 / 750.0 - steps30 / 750.0
        return df1.format(hp)
    }
}
