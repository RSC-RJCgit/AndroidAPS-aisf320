package app.aaps.ui.dialogs

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.AIV
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.NoteTimestampAllocator
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.smoothing.UnscentedKalmanFilterPlugin
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
    private val config: Config,
    private val aapsLogger: AAPSLogger,
    private val iobCobCalculator: IobCobCalculator,
    private val ukfSmoothing: UnscentedKalmanFilterPlugin
) {

    private val df1 = DecimalFormat("0.0")
    private val df2 = DecimalFormat("0.00")

    /** Records the final AIV/combined export outcomes as short CarePortal notes. AVLs/AVLf = AIV
     *  local write, AVCs/AVCf = AIV cloud upload (both pre-existing). ACEs/ACEf = combined export
     *  (buildCombinedExport(), added 2026-08-17 after a real 5-day silent EACCES failure on a device's
     *  output/ folder went unnoticed -- that function previously only logged errors internally). */
    fun addExportCarePortalNote(note: String) {
        require(note in setOf("AVLs", "AVLf", "AVCs", "AVCf", "ACEs", "ACEf"))
        val therapyEvent = TE(
            timestamp = NoteTimestampAllocator.next(dateUtil.now()),
            duration = TimeUnit.MINUTES.toMillis(1),
            type = TE.Type.NOTE,
            note = note,
            glucoseUnit = profileFunction.getUnits()
        )
        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = therapyEvent,
            action = Action.CAREPORTAL,
            source = Sources.Automation,
            note = "AIV export result",
            listValues = listOf(ValueWithUnit.SimpleString(note))
        ).subscribe({}, { error -> aapsLogger.error(LTag.CORE, "Failed to add AIV CarePortal note $note", error) })
    }

    companion object {
        private const val MGDL_TO_MMOL = 18.0182
        const val WINDOW_HOURS = 6L

        // combined<Name>.txt covers a wider trailing window than the 6h table export so it reads as
        // one continuous history rather than needing several 6h exports stitched together.
        private const val COMBINED_WINDOW_HOURS = 30L
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
        return writeExport(records, apsResults, stepsCounts, smbBoluses, carePortalNotesFrom(from), rawReadings, now)
    }

    /** All CarePortal NOTE events, newest first. The 24-hour lead-in preserves MJ state at the
     * beginning of an AIV window; notes are only displayed when they fall in a row's own minute. */
    fun carePortalNotesFrom(from: Long): List<TE> =
        persistenceLayer.getTherapyEventDataFromTime(from - TimeUnit.HOURS.toMillis(24), TE.Type.NOTE, ascending = false)

    /** MJ-only compatibility query retained for graph/history callers. */
    fun mjNotesFrom(from: Long): List<TE> =
        carePortalNotesFrom(from)
            .filter { val t = it.note ?: ""; t == "MJ" || t == "MJ2" || t == "MJ3" || t == "MoreMJ" || t == "A1" || t == "NOMJremains" || t.startsWith("MJoff") }

    // -----------------------------------------------------------------------------------------------
    // File export
    // -----------------------------------------------------------------------------------------------

    val exportHeaders = listOf(
        "Time", "BGL", "Target", "Final", "acce", "bg", "pp", "dura", "UAMci", "SMB", "FastRise", "SmbRatio", "SMBi5", "iobTH", "acWt", "ppWt", "Lslope",
        "acceBG",
        // delta_accl trio: the plain dosing value (already logged to RT.reason every cycle -- see
        // deltaAcceStr()'s doc comment) plus two new UKF-domain analogs (see deltaAcceUkfStr()/
        // deltaAcceU3Str()'s own comments for why UKF1's/UKF3's own 5-min/15-min deltas stand in for
        // Delta/SDelta).
        "deltaAcce", "deltaAcceUkf", "deltaAcceU3",
        "Delta", "SDelta", "LDelta", "rawBGL", "rawD1", "rawD5", "rawD15", "ukfRawBGL", "RawUKF5", "RawUKF15",
        // UKF3 (LibreSpecial-from-UKF1) BGL/delta trio -- next to the UKF1 trio above, same
        // recomputed-from-rawReadings basis (see computeUkf3RawMgdl()'s doc comment), no persisted field.
        "ukf3RawBGL", "RawUKF3_5", "RawUKF3_15",
        "Int5", "Req", "TBR", "IOB", "IOBd5", "Basal", "COB", "COBt", "carbAbs", "HP", "HP2",
        // HP3: same formula/COB-gating as HP2, base-glucose + delta5 swapped for UKF3's own values --
        // see hp3Str()'s doc comment. Mirrors PrepareBgDataWorker.kt's graph-annotation HP3 exactly.
        "HP3",
        "LowBG", "S5", "S15", "S30", "S60", "S180", "MJ", "Notes"
    )

    /** One record's export fields, in the same order as [exportHeaders], shared by both the CSV
     *  and the plain-text table export so the two stay in sync automatically. `allRecords` is the
     *  full (unfiltered) set, used for the IOB-5-min-change look-back. `ukf3RawMgdl` is the
     *  once-per-export UKF3 series from [computeUkf3RawMgdl], shared by every row (feeds hp3Str/
     *  ukf3BglStr/ukf3DeltaStr). */
    private fun exportFields(r: AIV, apsResults: List<APSResult>, stepsCountList: List<SC>, allRecords: List<AIV>, smbBoluses: List<BS>, carePortalNotes: List<TE>, rawReadings: List<GV>, cobTByTimestamp: Map<Long, Double>, ukf3RawMgdl: List<Pair<Long, Double>>): List<String> {
        val sc = stepsAt(r.timestamp, stepsCountList)
        return listOf(
            dateUtil.timeString(r.timestamp),
            df1.format(r.glucose / MGDL_TO_MMOL),
            targetStr(r.timestamp, apsResults),
            df2.format(r.finalIsf),
            df2.format(r.acceIsf),
            df2.format(r.bgIsf),
            df2.format(r.ppIsf),
            df2.format(r.duraIsf),
            df2.format(r.uamCarbImpact),
            df2.format(r.smbDelivered),
            exactFastRiseStr(r.timestamp, apsResults),
            df2.format(r.smbDeliveryRatio),
            smbInterval5SecStr(r.timestamp, smbBoluses),
            df2.format(r.iobThEffective),
            df2.format(r.acceIsfWeight),
            ppWeightStr(r.timestamp, apsResults),
            df2.format(r.fslCalSlope),
            df2.format(r.bgAcceleration),
            deltaAcceStr(r.timestamp, apsResults),
            deltaAcceUkfStr(r, allRecords),
            deltaAcceU3Str(r, ukf3RawMgdl),
            df2.format(r.delta / MGDL_TO_MMOL),
            df2.format(r.shortAvgDelta / MGDL_TO_MMOL),
            df2.format(r.longAvgDelta / MGDL_TO_MMOL),
            rawBglStr(r.timestamp, rawReadings),
            rawDeltaStr(r.timestamp, rawReadings, 1),
            rawDeltaStr(r.timestamp, rawReadings, 5),
            rawDeltaStr(r.timestamp, rawReadings, 15),
            if (r.ukfRawBgl > 0.0) df1.format(r.ukfRawBgl / MGDL_TO_MMOL) else "--",
            ukfDeltaStr(r, allRecords, 5),
            ukfDeltaStr(r, allRecords, 15),
            ukf3BglStr(r, ukf3RawMgdl),
            ukf3DeltaStr(r, ukf3RawMgdl, 5),
            ukf3DeltaStr(r, ukf3RawMgdl, 15),
            readingIntervalStr(r.timestamp, rawReadings),
            df2.format(r.insulinReq),
            df2.format(r.tbrRate),
            df2.format(r.iob),
            iob5MinChangeStr(r, allRecords),
            basalStr(r),
            cobStr(r),
            cobTStr(r, cobTByTimestamp),
            carbAbsStr(r),
            hpStr(r, rawReadings),
            hp2Str(r, allRecords, cobTByTimestamp),
            hp3Str(r, ukf3RawMgdl, cobTByTimestamp),
            lowBgRecentStr(r.timestamp, apsResults),
            stepsValue(sc, r.timestamp, apsResults, 5)?.toString() ?: "",
            stepsValue(sc, r.timestamp, apsResults, 15)?.toString() ?: "",
            stepsValue(sc, r.timestamp, apsResults, 30)?.toString() ?: "",
            stepsValue(sc, r.timestamp, apsResults, 60)?.toString() ?: "",
            stepsValue(sc, r.timestamp, apsResults, 180)?.toString() ?: "",
            mjStateStr(r.timestamp, carePortalNotes),
            carePortalNotesStr(r.timestamp, carePortalNotes)
        )
    }

    /** Writes AutoISF_<stamp>.csv, AutoISF_<stamp>.txt and AutoISF_settings_<stamp>.txt into the
     *  aapsLogs directory, returning whichever of the three were written successfully (empty list on
     *  total failure). `records` is the set to export (also used as the IOB-5-min look-back set), so
     *  callers should pass the full unfiltered window. Runs on the caller's thread. */
    fun writeExport(records: List<AIV>, apsResults: List<APSResult>, stepsCountList: List<SC>, smbBoluses: List<BS>, carePortalNotes: List<TE>, rawReadings: List<GV>, now: Long): List<File> {
        val written = mutableListOf<File>()
        try {
            fileListProvider.ensureAapsLogsDirExists()
            val patientName = preferences.get(StringKey.GeneralPatientName).trim()
            val dir = resolveExportDir(patientName)
            val baseStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
            val stamp = if (patientName.isNotEmpty()) "${patientName}_$baseStamp" else baseStamp
            val cobTByTimestamp = calculatedCobT(records)
            val ukf3RawMgdl = computeUkf3RawMgdl(rawReadings)
            val rows = records.map { exportFields(it, apsResults, stepsCountList, records, smbBoluses, carePortalNotes, rawReadings, cobTByTimestamp, ukf3RawMgdl) }

            val csvFile = File(dir, "AutoISF_$stamp.csv")
            csvFile.bufferedWriter().use { writer ->
                writer.write(exportHeaders.joinToString(",") { csvCell(it) } + "\n")
                for (row in rows) writer.write(row.joinToString(",") { csvCell(it) } + "\n")
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

    /** Reuses GeneralPatientName (same value the logs cloud export already scopes by) rather than a
     *  separate new setting — if you've already set different patient names per device to distinguish
     *  the logs cloud folder, this scopes AIV exports the same way for free, both the folder and the
     *  filename. Empty (default) falls back to the original unscoped aapsLogs folder, unchanged. Shared
     *  by writeExport() and buildCombinedExport() so both always agree on where files actually live. */
    private fun resolveExportDir(patientName: String): File =
        if (patientName.isNotEmpty()) {
            File(fileListProvider.aapsLogsPath, patientName).also { it.mkdirs() }
        } else {
            fileListProvider.aapsLogsPath
        }

    /** Rebuilds "combined<PatientName>.txt" (or "combined.txt" unscoped) in resolveExportDir()'s own
     *  "output" subfolder, copies the newest settings export there under an undated stable filename,
     *  and writes a dated combined copy alongside it (see below) -- from a single fresh
     *  [COMBINED_WINDOW_HOURS]-hour DB query, the same way [exportLast6Hours] builds the regular 6h
     *  table, rather than concatenating several previously-written 6h .txt files. That earlier
     *  concatenation approach had a real bug: [exportTableAsText]/[formatTableText] pads each column to
     *  the widest value IN THAT CALL's own rows, so pasting several independently-padded chunks together
     *  could silently drift out of alignment between chunks. A single direct query has no such seam, and
     *  it also means a missed automatic export no longer leaves a gap in the combined file.
     *  No-data (empty records) stays silent -- that's not a failure, just nothing to write yet. A real
     *  failure now fires an ACEf CarePortal note (added 2026-08-17); a completed write fires ACEs. Before
     *  this, both were log-only (matching writeExport()'s own pattern) -- a real EACCES on a device's
     *  output/ folder went completely unnoticed for 5 days as a result; see addExportCarePortalNote()'s
     *  own doc comment.
     *
     *  Dated copy: also written to aapsLogs/<PatientName>datedAIV/combined<PatientName><yyyyMMdd>.txt
     *  (fileListProvider.aapsLogsPath, NOT resolveExportDir()'s possibly-nested per-patient dir) --
     *  same content, so a same-day rerun (dialog reopened, or the next KeepAliveWorker cycle) overwrites
     *  that day's dated file rather than accumulating duplicates. */
    fun buildCombinedExport(now: Long) {
        try {
            val patientName = preferences.get(StringKey.GeneralPatientName).trim()
            val outputDir = File(resolveExportDir(patientName), "output").also { it.mkdirs() }
            writeStableSettings(outputDir, patientName, now)
            val from = now - TimeUnit.HOURS.toMillis(COMBINED_WINDOW_HOURS)
            val records = persistenceLayer.getAutoIsfValuesFromTimeToTime(from, now).sortedByDescending { it.timestamp }
            if (records.isEmpty()) return
            val apsResults = persistenceLayer.getApsResults(from, now)
            val stepsCounts = persistenceLayer.getStepsCountFromTimeToTime(from, now)
            val smbBoluses = persistenceLayer.getBolusesFromTimeToTime(from, now, ascending = false).filter { it.type == BS.Type.SMB }
            val rawReadings = persistenceLayer.getBgReadingsDataFromTimeToTime(from - 20 * 60_000L, now, ascending = false)
            val cobTByTimestamp = calculatedCobT(records)
            val ukf3RawMgdl = computeUkf3RawMgdl(rawReadings)
            val rows = records.map { exportFields(it, apsResults, stepsCounts, records, smbBoluses, carePortalNotesFrom(from), rawReadings, cobTByTimestamp, ukf3RawMgdl) }
            val text = formatTableText(rows)

            val outFile = File(outputDir, "combined$patientName.txt")
            outFile.writeText(text)
            aapsLogger.debug(LTag.UI, "AutoISF combined export rebuilt at ${outFile.absolutePath} from ${records.size} row(s)")

            val dateStamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now))
            val datedDir = File(fileListProvider.aapsLogsPath, "${patientName}datedAIV").also { it.mkdirs() }
            File(datedDir, "combined$patientName$dateStamp.txt").writeText(text)
            addExportCarePortalNote("ACEs")
        } catch (e: Exception) {
            aapsLogger.error(LTag.UI, "AutoISF combined export failed", e)
            addExportCarePortalNote("ACEf")
        }
    }

    /** Column-aligned plain-text rendering of [exportHeaders] + `rows`, columns padded to the widest
     *  value within THIS call's own data. Shared by [exportTableAsText] and [buildCombinedExport] so
     *  both stay in sync automatically; kept as one call per file (rather than, say, concatenating
     *  several calls' output) is what keeps columns aligned -- padding is only comparable within a
     *  single call's own width computation. */
    private fun formatTableText(rows: List<List<String>>): String {
        val widths = exportHeaders.indices.map { i ->
            maxOf(exportHeaders[i].length, rows.maxOfOrNull { it[i].length } ?: 0)
        }
        val smbColumn = exportHeaders.indexOf("SMB")
        val sb = StringBuilder()
        sb.append(exportHeaders.mapIndexed { i, h -> h.padEnd(widths[i]) }.joinToString("  ").trimEnd()).append("\n")
        for (row in rows) {
            val line = row.mapIndexed { i, v -> v.padEnd(widths[i]) }.joinToString("  ").trimEnd()
            val hasSmb = smbColumn >= 0 && (row.getOrNull(smbColumn)?.toDoubleOrNull() ?: 0.0) > 0.0
            sb.append(line).apply { if (hasSmb) append("  SMB") }.append("\n")
        }
        return sb.toString()
    }

    /** Same table data as the CSV, in a human-readable, column-aligned plain-text file — for
     *  viewing directly on a phone/PC without needing a spreadsheet app. Returns the file on success,
     *  null on failure. */
    private fun exportTableAsText(dir: File, stamp: String, rows: List<List<String>>): File? {
        return try {
            val file = File(dir, "AutoISF_$stamp.txt")
            file.writeText(formatTableText(rows))
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
            file.writeText(settingsText(System.currentTimeMillis()))
            aapsLogger.debug(LTag.UI, "AutoISF settings exported to ${file.absolutePath}")
            file
        } catch (e: Exception) {
            aapsLogger.error(LTag.UI, "AutoISF settings export failed", e)
            null
        }
    }

    private fun writeStableSettings(outputDir: File, patientName: String, now: Long) {
        val stableName = if (patientName.isNotEmpty()) "AutoISF_settings_$patientName.txt" else "AutoISF_settings.txt"
        val stableSettings = File(outputDir, stableName)
        stableSettings.writeText(settingsText(now))
        aapsLogger.debug(LTag.UI, "Current AutoISF settings written to ${stableSettings.absolutePath}")
    }

    /** Pump writes local authoritative values; Client writes only the last mirrored Pump snapshot. */
    private fun settingsText(now: Long): String {
        if (config.AAPSCLIENT) {
            val snapshot = preferences.get(StringNonKey.MirroredAutoIsfSettings)
            val mirroredAt = preferences.get(LongNonKey.MirroredAutoIsfSettingsTimestamp)
            return if (snapshot.isBlank() || mirroredAt == 0L) {
                "main_phone_snapshot_time = UNAVAILABLE\nsource = PUMP_MIRROR_UNAVAILABLE\n"
            } else {
                "main_phone_snapshot_time = ${dateUtil.dateAndTimeString(mirroredAt)}\n" +
                    "source = PUMP_MIRROR\n$snapshot\n"
            }
        }

        val lines = mutableListOf<String>()
        lines.add("main_phone_snapshot_time = ${dateUtil.dateAndTimeString(now)}")
        lines.add("source = MAIN_PHONE_LOCAL")
        lines.add("configuration_flavor = ${config.FLAVOR}")
        lines.add("configuration_version = ${config.VERSION_NAME}")
        lines.add("configuration_application_id = ${config.APPLICATION_ID}")
        lines.add("configuration_profile = ${profileFunction.getProfileName()}")
        BooleanKey.entries.filter { it.name.contains("AutoIsf") }.forEach { lines.add("${it.key} = ${preferences.get(it)}") }
        IntKey.entries.filter { it.name.contains("AutoIsf") }.forEach { lines.add("${it.key} = ${preferences.get(it)}") }
        DoubleKey.entries.filter { it.name.contains("AutoIsf") }.forEach { lines.add("${it.key} = ${df2.format(preferences.get(it))}") }
        UnitDoubleKey.entries.filter { it.name.contains("AutoIsf") }.forEach { lines.add("${it.key} = ${df2.format(preferences.get(it))}") }
        StringKey.entries.filter { it.name.contains("AutoIsf") }.forEach { lines.add("${it.key} = ${preferences.get(it)}") }
        lines.add("${DoubleKey.FslCalSlope.key} = ${df2.format(preferences.get(DoubleKey.FslCalSlope))}")
        lines.add("${DoubleKey.FslCalOffset.key} = ${df2.format(preferences.get(DoubleKey.FslCalOffset))}")
        return lines.take(6).joinToString("\n") + "\n" + lines.drop(6).sorted().joinToString("\n") + "\n"
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

    /** Actual APS target for this AIV cycle, displayed/exported in mmol/L. */
    fun targetStr(timestamp: Long, apsResults: List<APSResult>): String {
        val nearest = apsResults.minByOrNull { kotlin.math.abs(it.date - timestamp) } ?: return "--"
        if (kotlin.math.abs(nearest.date - timestamp) >= TimeUnit.MINUTES.toMillis(15)) return "--"
        if (nearest.targetBG <= 0.0) return "--"
        return df1.format(nearest.targetBG / MGDL_TO_MMOL)
    }

    /** "LowBGrecent: Y|N ;" written into RT.reason every cycle (OpenAPSAutoISFPlugin.kt) -- the LowBG
     *  automation state, i.e. whether a recent low is still flagged. Same reason-text mechanism as
     *  ppWeight/AcceIsfWeight below rather than a persisted AIV field. Shown for EVERY row, not just
     *  rows where the recent-low rebound guard actually reduced an SMB, so a low can be reviewed
     *  afterwards to see whether the guard was armed in the run-up -- which is what validates it.
     *  "--" when the marker is absent (rows from before this existed, or a build without it). */
    private val lowBgRecentRegex = Regex("""LowBGrecent:\s*([YN])""")

    fun lowBgRecentStr(timestamp: Long, apsResults: List<APSResult>): String {
        val nearest = apsResults.minByOrNull { kotlin.math.abs(it.date - timestamp) } ?: return "--"
        if (kotlin.math.abs(nearest.date - timestamp) >= TimeUnit.MINUTES.toMillis(15)) return "--"
        return lowBgRecentRegex.find(nearest.reason)?.groupValues?.get(1) ?: "--"
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

    // "delta_accl: <value> ;" written into RT.reason every determineBasal cycle (DetermineBasalAutoISF.kt):
    // 100 * round((Delta - SDelta) / |SDelta|, 2) -- a discrete BG-acceleration proxy, the immediate
    // delta's deviation from the short-average delta, normalised against the short-average delta's own
    // magnitude and expressed as roughly a whole-number percentage. Same nearest-APSResult reason-text
    // pattern as ppWeightRegex above.
    private val deltaAcclRegex = Regex("""delta_accl:\s*(-?[0-9.]+)""")

    /** delta_accl value from the nearest APSResult within 15 min -- exactly what dosing already computed
     *  and logged that cycle, no separate calculation here. "--" if none close enough or no match in the
     *  reason text (e.g. rows from before delta_accl was added to RT.reason). */
    fun deltaAcceStr(timestamp: Long, apsResults: List<APSResult>): String {
        val nearest = apsResults.minByOrNull { kotlin.math.abs(it.date - timestamp) } ?: return "--"
        if (kotlin.math.abs(nearest.date - timestamp) >= TimeUnit.MINUTES.toMillis(15)) return "--"
        val value = deltaAcclRegex.find(nearest.reason)?.groupValues?.get(1)?.toDoubleOrNull() ?: return "--"
        return df1.format(value)
    }

    /** Same shape as deltaAcceStr's formula (100 * (fast - slow) / |slow|), but built from UKF-domain
     *  values instead of dosing's own Delta/SDelta: UKF1's own 5-min delta stands in for Delta (both
     *  roughly "most recent ~5min move"), UKF1's own 15-min delta stands in for SDelta (both roughly a
     *  short trailing-average move) -- the closest available UKF1 analogs, since UKF1 has no separate
     *  ~5-17.5min-average signal the way AAPS's real SDelta does. Unit-invariant (a ratio of two
     *  same-unit deltas), so using ukfDeltaMmol's mmol values rather than dosing's internal mg/dl ones
     *  changes nothing. Rounds directly to a whole-number-ish percentage rather than replicating
     *  delta_accl's own round(,2)-then-x100 two-step (mathematically equivalent to one decimal here, and
     *  this is a new column, not a reproduction of an existing persisted/logged value the way hp3Str is).
     *  "--" if either UKF1 delta is unavailable (same conditions ukfDeltaStr returns "--" for) or the
     *  15-min delta is exactly 0 (undefined ratio). */
    fun deltaAcceUkfStr(r: AIV, allRecords: List<AIV>): String {
        val fast = ukfDeltaMmol(r, allRecords, 5) ?: return "--"
        val slow = ukfDeltaMmol(r, allRecords, 15) ?: return "--"
        if (slow == 0.0) return "--"
        return df1.format((fast - slow) / kotlin.math.abs(slow) * 100.0)
    }

    /** Same as deltaAcceUkfStr, but against [computeUkf3RawMgdl]'s UKF3 series instead of UKF1's. */
    fun deltaAcceU3Str(r: AIV, ukf3RawMgdl: List<Pair<Long, Double>>): String {
        val fast = ukf3DeltaMmol(r, ukf3RawMgdl, 5) ?: return "--"
        val slow = ukf3DeltaMmol(r, ukf3RawMgdl, 15) ?: return "--"
        if (slow == 0.0) return "--"
        return df1.format((fast - slow) / kotlin.math.abs(slow) * 100.0)
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

    /** Same idea as rawDeltaStr's 5-min/15-min windows above, but differenced from the persisted
     *  UKF-smoothed raw value (r.ukfRawBgl, computed once per cycle in OpenAPSAutoISFPlugin.kt --
     *  see computeUkfRawBgl()) instead of the raw noise field directly: steadier, since differencing an
     *  already-smoothed trace doesn't amplify noise the way differencing two raw points does.
     *  Deliberately ADDITIONAL columns, not a replacement for rawDeltaStr's rΔ5/rΔ15 above -- those
     *  intentionally mirror the exact raw signal the live dosing gates read (an earlier version of THIS
     *  file differenced a smoothed series once before and reverted it, for exactly that reason -- see
     *  rawDeltaStr's own comment). No 1-min variant: AIV rows land roughly every ~5min (one per
     *  calculation cycle), too coarse for a meaningful 1-min delta. `allRecords` is the full (unfiltered)
     *  set, same convention as iob5MinChangeStr. "--" if no record sits within 3 min of the target time,
     *  or ukfRawBgl wasn't computed for either row (0.0, e.g. rows from before this feature existed). */
    fun ukfDeltaStr(r: AIV, allRecords: List<AIV>, minutesBack: Int): String =
        ukfDeltaMmol(r, allRecords, minutesBack)?.let { df2.format(it) } ?: "--"

    /** Same computation as ukfDeltaStr above, returning the raw mmol/5min Double instead of a
     *  formatted string, for use in hp2Str's own arithmetic -- mirrors rawDelta5Mmol's relationship
     *  to rawDeltaStr for the same reason (kept separate rather than parsing ukfDeltaStr's own
     *  "--"-or-formatted-number output back into a Double). Null under the same conditions
     *  ukfDeltaStr returns "--". */
    private fun ukfDeltaMmol(r: AIV, allRecords: List<AIV>, minutesBack: Int): Double? {
        if (r.ukfRawBgl <= 0.0) return null
        val target = r.timestamp - minutesBack * 60_000L
        val prior = allRecords.minByOrNull { kotlin.math.abs(it.timestamp - target) } ?: return null
        if (kotlin.math.abs(prior.timestamp - target) > 3 * 60_000L || prior.ukfRawBgl <= 0.0) return null
        val actualMin = (r.timestamp - prior.timestamp) / 60_000.0
        if (actualMin <= 0.0) return null
        return (r.ukfRawBgl - prior.ukfRawBgl) / actualMin * 5.0 / MGDL_TO_MMOL
    }

    /** UKF3 (LibreSpecial-from-UKF1) raw-mgdl series, ascending time order, for the whole export
     *  window in one pass -- mirrors PrepareBgDataWorker.kt's own `ukf3RawMgdl` computation exactly
     *  (UKF1's smoothForDisplay() output, then the LibreSpecial EMA formula run fresh against it; same
     *  math as NsIncomingDataProcessor.kt's live fsl_exp1 branch, kept in sync by hand -- see that
     *  worker's own comment). No persisted field (unlike UKF1's r.ukfRawBgl): recomputed here from
     *  `rawReadings` every export call instead, same "recompute from raw data" convention rawDeltaStr/
     *  hpStr already use rather than adding a new DB migration for a third UKF variant. Computed ONCE
     *  per export call (not per row) and threaded through exportFields/hp3Str/ukf3BglStr/ukf3DeltaStr,
     *  same convention as cobTByTimestamp. Deliberately uses Constants.MMOLL_TO_MGDL (18.0) for the
     *  offset-unit conversion below, NOT this file's own MGDL_TO_MMOL (18.0182) -- matching
     *  PrepareBgDataWorker.kt's exact arithmetic is the point; every OTHER conversion in this file still
     *  uses the local constant as before. Empty if `rawReadings` has no usable noise values. */
    fun computeUkf3RawMgdl(rawReadings: List<GV>): List<Pair<Long, Double>> {
        val newestFirstRaw = rawReadings.filter { it.noise != null }.sortedByDescending { it.timestamp }
        if (newestFirstRaw.isEmpty()) return emptyList()
        val ukf1SmoothedMgdl = ukfSmoothing.smoothForDisplay(newestFirstRaw.map { it.timestamp to it.noise!! })
        val applyCalibration = preferences.get(BooleanKey.Ukf3ApplyLibreCalibration)
        val slope = preferences.get(DoubleKey.FslCalSlope)
        val offset = preferences.get(DoubleKey.FslCalOffset)
        val factor = preferences.get(DoubleKey.FslSmoothAlpha)
        val maxGap = preferences.get(IntKey.FslMaxSmoothGap).toDouble()
        val unitFactor = if (profileFunction.getUnits() == GlucoseUnit.MMOL) Constants.MMOLL_TO_MGDL else 1.0
        var lastSmooth = 0.0
        var lastTimeRaw = 0L
        return newestFirstRaw.zip(ukf1SmoothedMgdl).asReversed().map { (reading, ukf1Mgdl) ->
            val calibrated = if (applyCalibration) max(40.0, ukf1Mgdl * slope + offset * unitFactor) else ukf1Mgdl
            val elapsedMinutes = (reading.timestamp - lastTimeRaw) / 60000.0
            val effectiveAlpha = min(1.0, factor + (1.0 - factor) * ((max(0.0, elapsedMinutes - 1.0) / (maxGap - 1.0)).pow(2.0)))
            val smooth = if (lastSmooth > 0.0) lastSmooth + effectiveAlpha * (calibrated - lastSmooth) else calibrated
            lastSmooth = smooth
            lastTimeRaw = reading.timestamp
            reading.timestamp to smooth
        }
    }

    /** Nearest [computeUkf3RawMgdl] entry within 3 min of `timestamp`, or null if none close enough --
     *  same tolerance/nearest-match convention as ukfDeltaMmol's own prior-row lookup. */
    private fun ukf3ValueNear(ukf3RawMgdl: List<Pair<Long, Double>>, timestamp: Long): Double? {
        val nearest = ukf3RawMgdl.minByOrNull { kotlin.math.abs(it.first - timestamp) } ?: return null
        if (kotlin.math.abs(nearest.first - timestamp) > 3 * 60_000L) return null
        return nearest.second
    }

    /** UKF3 BGL (mmol) at this row's timestamp -- same "--"-on-no-nearby-point fallback as
     *  ukfRawBGL's own column. */
    fun ukf3BglStr(r: AIV, ukf3RawMgdl: List<Pair<Long, Double>>): String =
        ukf3ValueNear(ukf3RawMgdl, r.timestamp)?.let { df1.format(it / MGDL_TO_MMOL) } ?: "--"

    /** Same windowed-delta shape as ukfDeltaMmol (nearest match at `timestamp` and at `timestamp -
     *  minutesBack`, normalised to a per-5min rate for windows over 5 min), but against
     *  [computeUkf3RawMgdl]'s series instead of the persisted r.ukfRawBgl chain. Null under the same
     *  no-nearby-point conditions ukfDeltaMmol returns null for. */
    private fun ukf3DeltaMmol(r: AIV, ukf3RawMgdl: List<Pair<Long, Double>>, minutesBack: Int): Double? {
        val nowEntry = ukf3RawMgdl.minByOrNull { kotlin.math.abs(it.first - r.timestamp) } ?: return null
        if (kotlin.math.abs(nowEntry.first - r.timestamp) > 3 * 60_000L) return null
        val target = r.timestamp - minutesBack * 60_000L
        val prior = ukf3RawMgdl.minByOrNull { kotlin.math.abs(it.first - target) } ?: return null
        if (kotlin.math.abs(prior.first - target) > 3 * 60_000L || prior.first == nowEntry.first) return null
        val actualMin = (nowEntry.first - prior.first) / 60_000.0
        if (actualMin <= 0.0) return null
        return (nowEntry.second - prior.second) / actualMin * 5.0 / MGDL_TO_MMOL
    }

    fun ukf3DeltaStr(r: AIV, ukf3RawMgdl: List<Pair<Long, Double>>, minutesBack: Int): String =
        ukf3DeltaMmol(r, ukf3RawMgdl, minutesBack)?.let { df2.format(it) } ?: "--"

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
    /** CarePortal notes created during this AIV row's displayed calendar minute (not a 60s window
     *  starting from the row's own precise timestamp -- see below for why that distinction is real).
     *  Window is floored to the minute boundary containing `timestamp`, so it matches whatever the
     *  displayed "HH:mm" label actually covers regardless of the row's own save-time offset into
     *  that minute.
     *
     *  Found 2026-08-17 by a real miss: three automations (PP50Off/ActivityOff/BasalUp) fired within
     *  ~90s of each other, all inside the same displayed minute, but NONE showed up in the AIV
     *  table's Notes column even though they were all visible in the app's own Treatments tab. Root
     *  cause: the old filter was `it.timestamp >= timestamp && it.timestamp < timestamp + 1min`
     *  using the AIV row's own exact persisted timestamp as the window START -- if the row was saved
     *  partway into its minute (e.g. :49:47), any note that fired earlier in that same displayed
     *  minute (:49:05, :49:30) sat *before* the row's own timestamp and was silently excluded by the
     *  `>=` lower bound, even though both are "12:49 PM" to anyone reading the table. */
    fun carePortalNotesStr(timestamp: Long, notes: List<TE>): String {
        val minuteMs = TimeUnit.MINUTES.toMillis(1)
        val minuteStart = timestamp - (timestamp % minuteMs)
        val minuteEnd = minuteStart + minuteMs
        return notes.asSequence()
            .filter { it.timestamp >= minuteStart && it.timestamp < minuteEnd }
            .mapNotNull { it.note?.trim()?.takeIf(String::isNotEmpty) }
            .map { it.replace(Regex("[\\r\\n]+"), " ") }
            .distinct()
            .toList()
            .asReversed()
            .joinToString("|")
    }

    /** RFC-4180-compatible CSV cell escaping for arbitrary CarePortal note text. */
    private fun csvCell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' })
            "\"${value.replace("\"", "\"\"")}\""
        else value
    fun mjStateStr(timestamp: Long, mjNotes: List<TE>): String {
        val latest = mjNotes.firstOrNull {
            val note = it.note ?: ""
            it.timestamp <= timestamp && (note == "MJ" || note == "MJ2" || note == "MJ3" ||
                note == "MoreMJ" || note == "A1" || note == "NOMJremains" || note.startsWith("MJoff"))
        } ?: return "NOM"
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

    /** Historically-accurate COB (grams) at [timestamp] — the record's own COB at ITS timestamp (not
     *  today's live COB, which would be wrong for older rows). 0.0 if no autosens data covers that time.
     *  Shared by hpStr() (feeds the HP formula) and cobStr() (the table's own visible COB column) so both
     *  read the identical source. */
    private fun historicalCob(timestamp: Long): Double = iobCobCalculator.ads.getAutosensDataAtTime(timestamp)?.cob ?: 0.0

    /** Formatted COB (grams) for the table's own visible column — same historicalCob() source hpStr()
     *  computes internally, exposed directly so COB can be filtered/analyzed on its own (e.g. "was there
     *  COB onboard when HP predicted X") without having to reverse-engineer it out of the HP number. */
    fun cobStr(r: AIV): String = df1.format(historicalCob(r.timestamp))

    /** Historically-accurate empirical carb absorption rate (g/5min) at [timestamp] -- the SAME source
     *  (autosensData.this5MinAbsorption, oref1 model) PrepareIobAutosensGraphDataWorker.kt EMA-smooths
     *  into the orange "carbs" graph line and sums with the (scaled) UAM line into Combined Carbs. NOT
     *  smoothed here (this is the raw per-bucket rate, not the graph's EMA-smoothed value) -- exposed so
     *  a real meal's actual peak magnitude can be compared directly against UAMci's peak from the same
     *  export, instead of guessing the Combined Carbs scaling factors blind. 0.0 if no autosens data
     *  covers that time (same fallback as historicalCob). */
    fun carbAbsStr(r: AIV): String = df2.format(iobCobCalculator.ads.getAutosensDataAtTime(r.timestamp)?.this5MinAbsorption ?: 0.0)

    /** Historical interpreted total COB for display/export analysis only; never used by dosing. */
    fun calculatedCobT(records: List<AIV>): Map<Long, Double> {
        if (records.isEmpty()) return emptyMap()
        val result = records.associate { it.timestamp to historicalCob(it.timestamp) }.toMutableMap()
        val positive = records.sortedBy { it.timestamp }.mapNotNull { r ->
            val carbAbs = iobCobCalculator.ads.getAutosensDataAtTime(r.timestamp)?.this5MinAbsorption ?: 0.0
            val excess = (r.uamCarbImpact - carbAbs).coerceAtLeast(0.0)
            if (excess > 0.05) r.timestamp to excess else null
        }

        val episodes = mutableListOf<MutableList<Pair<Long, Double>>>()
        for (point in positive) {
            val current = episodes.lastOrNull()
            if (current == null || point.first - current.last().first > 15 * 60_000L)
                episodes.add(mutableListOf(point))
            else
                current.add(point)
        }
        for (episode in episodes) {
            var remainingExtra = 0.0
            for (i in episode.indices.reversed()) {
                val minutes = if (i < episode.lastIndex)
                    ((episode[i + 1].first - episode[i].first) / 60_000.0).coerceIn(0.0, 5.0)
                else 1.0
                remainingExtra += episode[i].second * minutes / 5.0
                val timestamp = episode[i].first
                result[timestamp] = (result[timestamp] ?: 0.0) + remainingExtra
            }
        }
        return result
    }

    fun cobTStr(r: AIV, cobTByTimestamp: Map<Long, Double>): String =
        df1.format(cobTByTimestamp[r.timestamp] ?: historicalCob(r.timestamp))

    /** Hypo-prediction: (BGL[mmol] - IOB) + 0.25*SDelta[mmol] + 0.25*LibreDelta5[mmol] + COB/12 — same
     *  formula as the graph rows (PrepareBgDataWorker.kt), but historically accurate here: COB comes from
     *  historicalCob(r.timestamp), the record's own COB at ITS timestamp (not today's live COB, which
     *  would be wrong for older rows). "--" if the raw-Libre-delta window doesn't have enough data at this
     *  row's timestamp. `rawReadings` must be newest-first. No steps term: a multi-day accuracy check
     *  (COB/no-COB x falling/rising, episode-clustered across ~10 days) showed subtracting Steps60/750 +
     *  Steps30/750 didn't improve correlation and made HP chronically predict lower than reality — AutoISF
     *  already cuts SMB size directly on high recent steps, so the low this term anticipated had usually
     *  already been headed off by the reduced dosing. */
    fun hpStr(r: AIV, rawReadings: List<GV>): String {
        val libreDelta5Mmol = rawDelta5Mmol(r.timestamp, rawReadings) ?: return "--"
        val bglMmol = r.glucose / MGDL_TO_MMOL
        val sdeltaMmol = r.shortAvgDelta / MGDL_TO_MMOL
        val cob = historicalCob(r.timestamp)
        val hp = (bglMmol - r.iob) + 0.25 * sdeltaMmol + 0.25 * libreDelta5Mmol + cob / 12.0
        return df1.format(hp)
    }

    /** Second hypo-prediction variant, trying UKF's smoothed raw delta instead of the raw Libre delta,
     *  and heavier weights on both delta terms (0.5 vs hpStr's 0.25) -- an experiment to compare
     *  against hpStr's column side by side, NOT a replacement (hpStr is untouched above). Formula:
     *  (BGL[mmol] - IOB) + 0.5*SDelta[mmol] + 0.5*UKFRawDelta5[mmol] - COBt/12 (COBt term only applied
     *  when [cobtPenaltyGated] says the trend actually supports it -- see that function's own doc
     *  comment for why: an ungated -COBt/12 term made HP2 read as a false hypo alarm during ordinary
     *  post-meal rises, since COBt is LARGEST exactly when BG is still climbing). COBt is the
     *  comparison-only interpreted total from calculatedCobT(); HP1 and dosing remain unchanged.
     *  "--" if ukfRawBgl wasn't computed for this row or the row
     *  5min back (same conditions ukfDeltaStr/ukfDeltaMmol return null/"--" for). */
    fun hp2Str(r: AIV, allRecords: List<AIV>, cobTByTimestamp: Map<Long, Double>): String {
        val ukfDelta5Mmol = ukfDeltaMmol(r, allRecords, 5) ?: return "--"
        val bglMmol = r.glucose / MGDL_TO_MMOL
        val sdeltaMmol = r.shortAvgDelta / MGDL_TO_MMOL
        val ldeltaMmol = r.longAvgDelta / MGDL_TO_MMOL
        val cobT = cobTByTimestamp[r.timestamp] ?: historicalCob(r.timestamp)
        val cobtTerm = if (cobtPenaltyGated(r.bgAcceleration, sdeltaMmol, ldeltaMmol, r.insulinReq)) cobT / 12.0 else 0.0
        val hp2 = (bglMmol - r.iob) + 0.5 * sdeltaMmol + 0.5 * ukfDelta5Mmol - cobtTerm
        return df1.format(hp2)
    }

    /** Gates hp2Str's -COBt/12 penalty to only apply when the BG trend actually supports treating
     *  outstanding carbs as a hypo-risk-reducing factor: decelerating (bgAcceleration < 0) AND both the
     *  short (~5-17.5min) and long (~17.5-42.5min) average deltas are flat-or-falling (< 0.1 mmol/5min).
     *  Verified against real device data (2026-08-08 meal episode, aiv_Regan exports): ungated, HP2 read
     *  as low as 2.9 while BG was actually still climbing toward a peak 2h later -- a false hypo alarm
     *  driven entirely by COBt being largest exactly when a meal is still absorbing (i.e. BG still
     *  rising). Gated, those same rows correctly fall back to no COBt penalty (HP2 tracks HP closely);
     *  at the one genuine low in that dataset, the gate ALSO stayed closed (acceleration had already
     *  flipped positive coming out of the trough) but HP2 still landed within 0.1 of HP anyway, since
     *  dropping the penalty (not flipping its sign) never made HP2 read LOWER than the ungated version --
     *  the gate was only ever needed to suppress false rises, not to enhance real lows.
     *  Extended after a second real-data pass (aiv_Regan, 2026-08-10): the original clause alone missed
     *  272 rows where BG was genuinely falling (SDelta<0 and LDelta<0) but acceleration hadn't yet gone
     *  negative (a "falling, decelerating-positively" case) -- COBt stayed applied there when it shouldn't.
     *  Added an OR-branch admitting sdeltaMmol<0 && ldeltaMmol<0 outright, regardless of acceleration.
     *  That broadened OR would also have caught rows where the loop was still actively dosing (insulinReq
     *  > 0), so both branches are now additionally gated on insulinReq<=0.0 -- of the 349 rows the old
     *  single clause included, 131 still had Req>0 (discounting COBt while still actively requesting
     *  insulin), which this closes. */
    private fun cobtPenaltyGated(bgAcceleration: Double, sdeltaMmol: Double, ldeltaMmol: Double, insulinReq: Double): Boolean =
        ((bgAcceleration < 0 && sdeltaMmol < 0.1 && ldeltaMmol < 0.1) || (sdeltaMmol < 0 && ldeltaMmol < 0)) && insulinReq <= 0.0

    /** Third hypo-prediction variant: same formula/COB-gating as hp2Str, but BOTH the base-glucose term
     *  and the delta5 term are swapped to UKF3's own values ([computeUkf3RawMgdl]) instead of the live
     *  dosing BGL / UKF1-raw-delta5 -- mirrors PrepareBgDataWorker.kt's graph-annotation HP3 exactly
     *  (same weights, same cobtPenaltyGated() gate), just historically accurate here (COB from
     *  historicalCob/cobTByTimestamp at the record's own timestamp, not today's live COB) the same way
     *  hp2Str already is relative to its own live graph counterpart. "--" if UKF3's series doesn't have
     *  a point within 3 min of this row, or one ~5min back. */
    fun hp3Str(r: AIV, ukf3RawMgdl: List<Pair<Long, Double>>, cobTByTimestamp: Map<Long, Double>): String {
        val ukf3NowMgdl = ukf3ValueNear(ukf3RawMgdl, r.timestamp) ?: return "--"
        val ukf3Delta5Mmol = ukf3DeltaMmol(r, ukf3RawMgdl, 5) ?: return "--"
        val ukf3BglMmol = ukf3NowMgdl / MGDL_TO_MMOL
        val sdeltaMmol = r.shortAvgDelta / MGDL_TO_MMOL
        val ldeltaMmol = r.longAvgDelta / MGDL_TO_MMOL
        val cobT = cobTByTimestamp[r.timestamp] ?: historicalCob(r.timestamp)
        val cobtTerm = if (cobtPenaltyGated(r.bgAcceleration, sdeltaMmol, ldeltaMmol, r.insulinReq)) cobT / 12.0 else 0.0
        val hp3 = (ukf3BglMmol - r.iob) + 0.5 * sdeltaMmol + 0.5 * ukf3Delta5Mmol - cobtTerm
        return df1.format(hp3)
    }
}
