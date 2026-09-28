package app.aaps.implementation.maintenance

import android.os.Build
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.AIV
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.LoggerUtils
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.smoothing.DisplayRawSmoothing
import app.aaps.core.interfaces.userEntry.UserEntryPresentationHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.maintenance.cloud.CloudConstants
import app.aaps.implementation.maintenance.cloud.CloudStorageManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Writes the AutoISF history files the backup script copies.
 * They go in Documents/AAPS/aapsLogs/<patient>_<phone model>/.
 * The same six-hour export that writes the short log-folder copy calls this.
 */
@SingleIn(AppScope::class)
@Inject
class AutoIsfAapsLogsExporter(
    private val dateUtil: DateUtil,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val preferences: Preferences,
    private val config: Config,
    private val virtualPump: VirtualPump,
    private val iobCobCalculator: IobCobCalculator,
    private val displayRawSmoothing: DisplayRawSmoothing,
    private val fileListProvider: FileListProvider,
    private val loggerUtils: LoggerUtils,
    private val aapsLogger: AAPSLogger,
    private val decimalFormatter: DecimalFormatter,
    private val cloudStorageManager: CloudStorageManager,
    private val userEntryPresentationHelper: UserEntryPresentationHelper,
) {

    private val symbols = DecimalFormatSymbols(Locale.US)
    private val df1 = DecimalFormat("0.0", symbols)
    private val df2 = DecimalFormat("0.00", symbols)

    suspend fun write(trigger: String, now: Long = dateUtil.now()) {
        try {
            val root = fileListProvider.ensureAapsLogsDirExists()
            val patientName = exportScopeName(preferences.get(StringKey.GeneralPatientName), Build.MODEL)
            val dir = if (patientName.isNotEmpty()) File(root, patientName).also { it.mkdirs() } else root
            if (!dir.isDirectory) {
                aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=AAPSLOGS result=FAILURE reason=no directory")
                return
            }
            val from = now - TimeUnit.HOURS.toMillis(WINDOW_HOURS)
            val records = persistenceLayer.getAutoIsfValuesFromTimeToTime(from, now).sortedByDescending { it.timestamp }
            val apsResults = persistenceLayer.getApsResults(from, now)
            val steps = persistenceLayer.getStepsCountFromTimeToTime(from, now)
            val smbBoluses = persistenceLayer.getBolusesFromTimeToTime(from, now, ascending = false)
                .filter { it.type == BS.Type.SMB }
            val rawReadings = persistenceLayer.getBgReadingsDataFromTimeToTime(from - 20 * 60_000L, now, ascending = false)
            val notes = persistenceLayer.getTherapyEventDataFromTime(from - TimeUnit.HOURS.toMillis(24), TE.Type.NOTE, ascending = false)
            val baseStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
            val ukf3 = computeUkf3RawMgdl(rawReadings)
            val cobT = calculatedCobT(records)
            val rows = records.map { exportFields(it, apsResults, steps, records, smbBoluses, notes, rawReadings, cobT, ukf3) }
            val files = listOf(
                File(dir, exportNamedFile("AutoISF", patientName, baseStamp, "csv")) to csvText(rows),
                File(dir, exportNamedFile("AutoISF", patientName, baseStamp, "txt")) to tableText(rows),
                File(dir, exportNamedFile("AutoISF_settings", patientName, baseStamp, "txt")) to settingsText(now),
                File(dir, userEntriesName(patientName, now)) to userEntriesText(now),
                File(dir, exportNamedFile("UKFcheck", patientName, baseStamp, "txt")) to ukfCheckText(),
            )
            var written = 0
            val writtenFiles = mutableListOf<File>()
            for ((file, text) in files) {
                try {
                    file.writeText(text)
                    written++
                    writtenFiles += file
                } catch (e: Exception) {
                    aapsLogger.error(LTag.CORE, "Export $trigger could not write ${file.name}", e)
                }
            }
            aapsLogger.info(
                LTag.CORE,
                "EXPORT_STATUS trigger=$trigger component=AAPSLOGS result=${if (written == files.size) "SUCCESS" else "FAILURE"} files=$written/${files.size} dir=${dir.absolutePath}"
            )
            upload(trigger, patientName, writtenFiles)
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=AAPSLOGS result=FAILURE", e)
        }
    }

    private fun userEntriesName(patientName: String, now: Long): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date(now))
        return exportNamedFile("UserEntries_30h", patientName, stamp, "txt")
    }

    private suspend fun userEntriesText(now: Long): String {
        val entries = persistenceLayer.getUserEntryFilteredDataFromTime(now - USER_ENTRIES_EXPORT_WINDOW_MS)
        return userEntryPresentationHelper.userEntriesToCsv(entries)
    }

    private suspend fun upload(trigger: String, patientName: String, files: List<File>) {
        if (files.isEmpty() || !cloudStorageManager.isCloudStorageActive()) return
        val provider = cloudStorageManager.getActiveProvider() ?: return
        val path = if (patientName.isNotEmpty()) "${CloudConstants.CLOUD_PATH_AIV}_$patientName" else CloudConstants.CLOUD_PATH_AIV
        var uploaded = 0
        for (file in files) {
            val mime = if (file.name.endsWith(".csv")) "text/csv" else "text/plain"
            val id = provider.uploadFileToPath(file.name, file.readBytes(), mime, path)
                ?: provider.uploadFile(file.name, file.readBytes(), mime)
            if (id != null) uploaded++
            else aapsLogger.error(LTag.CORE, "Export $trigger cloud upload failed for ${file.name}")
        }
        aapsLogger.info(
            LTag.CORE,
            "EXPORT_STATUS trigger=$trigger component=AAPSLOGS_CLOUD result=${if (uploaded == files.size) "SUCCESS" else "FAILURE"} files=$uploaded/${files.size}"
        )
    }

    private fun csvText(rows: List<List<String>>): String {
        val lines = listOf(EXPORT_HEADERS) + rows
        return lines.joinToString("\n") { line -> line.joinToString(",") { csvCell(it) } }
    }

    private fun tableText(rows: List<List<String>>): String {
        val widths = EXPORT_HEADERS.indices.map { i ->
            max(EXPORT_HEADERS[i].length, rows.maxOfOrNull { it[i].length } ?: 0)
        }
        val smbColumn = EXPORT_HEADERS.indexOf("SMB")
        val sb = StringBuilder()
        sb.append(EXPORT_HEADERS.mapIndexed { i, h -> h.padEnd(widths[i]) }.joinToString("  ").trimEnd()).append('\n')
        for (row in rows) {
            val line = row.mapIndexed { i, v -> v.padEnd(widths[i]) }.joinToString("  ").trimEnd()
            val hasSmb = smbColumn >= 0 && (row.getOrNull(smbColumn)?.toDoubleOrNull() ?: 0.0) > 0.0
            sb.append(line)
            if (hasSmb) sb.append("  SMB")
            sb.append('\n')
        }
        return sb.toString()
    }

    private suspend fun settingsText(now: Long): String {
        val lines = mutableListOf<String>()
        lines += "main_phone_snapshot_time = ${dateUtil.dateAndTimeString(now)}"
        lines += "source = MAIN_PHONE_LOCAL"
        lines += "configuration_flavor = ${config.FLAVOR}"
        lines += "configuration_version = ${config.VERSION_NAME}"
        lines += "configuration_application_id = ${config.APPLICATION_ID}"
        lines += "configuration_profile = ${profileFunction.getProfileName()}"
        val settings = mutableListOf<String>()
        for (key in BooleanKey.entries) if (isAutoIsfKey(key.key)) settings += "${key.key} = ${preferences.get(key)}"
        for (key in IntKey.entries) if (isAutoIsfKey(key.key)) settings += "${key.key} = ${preferences.get(key)}"
        for (key in DoubleKey.entries) if (isAutoIsfKey(key.key)) settings += "${key.key} = ${decimalFormatter.to2Decimal(preferences.get(key))}"
        for (key in StringKey.entries) if (isAutoIsfKey(key.key)) settings += "${key.key} = ${preferences.get(key)}"
        settings += "${DoubleKey.FslCalSlope.key} = ${decimalFormatter.to2Decimal(preferences.get(DoubleKey.FslCalSlope))}"
        settings += "${DoubleKey.FslCalOffset.key} = ${decimalFormatter.to2Decimal(preferences.get(DoubleKey.FslCalOffset))}"
        return lines.joinToString("\n") + "\n" + settings.sorted().joinToString("\n") + "\n"
    }

    private fun isAutoIsfKey(key: String): Boolean = key.contains("autoisf", ignoreCase = true)

    private fun ukfCheckText(): String {
        val patterns = listOf("DropDetected[", "AccelSignDisagreement[", "LibreVsSet1Race[", "Metrics:")
        val sb = StringBuilder()
        try {
            val dir = File(loggerUtils.logDirectory)
            val live = dir.listFiles()?.filter { it.name == "AndroidAPS.log" }?.maxByOrNull { it.lastModified() }
            if (live != null) {
                live.useLines { lines -> appendMatches(sb, live.name, lines, patterns) }
            }
            val zips = dir.listFiles()?.filter { it.name.endsWith(".log.zip") }?.sortedByDescending { it.lastModified() }?.take(6).orEmpty()
            for (zip in zips) {
                ZipInputStream(zip.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) appendMatches(sb, zip.name, zis.bufferedReader().lineSequence(), patterns)
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            aapsLogger.debug(LTag.CORE, "UKFcheck read skipped: ${e.message}")
            return "UKFcheck read failed: ${e.message}\n"
        }
        if (sb.isEmpty()) return "No DropDetected[/AccelSignDisagreement[/LibreVsSet1Race[/Metrics: lines found in this window.\n"
        return sb.toString()
    }

    private fun appendMatches(sb: StringBuilder, sourceName: String, lines: Sequence<String>, patterns: List<String>) {
        val matches = lines.filter { line -> patterns.any { line.contains(it) } }.toList()
        if (matches.isEmpty()) return
        sb.append("===== ").append(sourceName).append(" =====\n")
        matches.forEach { sb.append(it).append('\n') }
        sb.append('\n')
    }

    private fun exportFields(
        row: AIV,
        apsResults: List<APSResult>,
        stepsCountList: List<SC>,
        allRecords: List<AIV>,
        smbBoluses: List<BS>,
        notes: List<TE>,
        rawReadings: List<GV>,
        cobTByTimestamp: Map<Long, Double>,
        ukf3RawMgdl: List<Pair<Long, Double>>,
    ): List<String> {
        val sc = stepsAt(row.timestamp, stepsCountList)
        return listOf(
            dateUtil.timeString(row.timestamp),
            df1.format(row.glucose / MGDL_TO_MMOL),
            targetStr(row, apsResults),
            df2.format(row.finalIsf),
            df2.format(row.acceIsf),
            df2.format(row.bgIsf),
            df2.format(row.ppIsf),
            df2.format(row.duraIsf),
            df2.format(row.uamCarbImpact),
            df2.format(row.smbDelivered),
            exactFastRiseStr(row.timestamp, apsResults),
            df2.format(row.smbDeliveryRatio),
            reasonNumber(row.timestamp, apsResults, mildBstRegex, df2),
            smbInterval5SecStr(row.timestamp, smbBoluses),
            df2.format(row.iobThEffective),
            df2.format(row.acceIsfWeight),
            df2.format(row.ppIsfWeight),
            df2.format(row.fslCalSlope),
            df2.format(row.bgAcceleration),
            deltaAcceStr(row, apsResults),
            deltaAcceUkfStr(row, allRecords),
            deltaAcceU3Str(row, ukf3RawMgdl),
            df2.format(row.delta / MGDL_TO_MMOL),
            df2.format(row.shortAvgDelta / MGDL_TO_MMOL),
            df2.format(row.longAvgDelta / MGDL_TO_MMOL),
            rawBglStr(row.timestamp, rawReadings),
            rawDeltaStr(row.timestamp, rawReadings, 1),
            rawDeltaStr(row.timestamp, rawReadings, 5),
            rawDeltaStr(row.timestamp, rawReadings, 15),
            if (row.ukfRawBgl > 0.0) df1.format(row.ukfRawBgl / MGDL_TO_MMOL) else "--",
            ukfDeltaStr(row, allRecords, 5),
            ukfDeltaStr(row, allRecords, 15),
            ukf3BglStr(row, ukf3RawMgdl),
            ukf3DeltaStr(row, ukf3RawMgdl, 5),
            ukf3DeltaStr(row, ukf3RawMgdl, 15),
            "--",
            "--",
            "--",
            readingIntervalStr(row.timestamp, rawReadings),
            insulinReqStr(row.timestamp, apsResults),
            tbrStr(row.timestamp, apsResults),
            df2.format(row.iob),
            iob5MinChangeStr(row, allRecords),
            basalStr(row),
            cobStr(row),
            cobTStr(row, cobTByTimestamp),
            carbAbsStr(row),
            hpStr(row, rawReadings),
            hp2Str(row, allRecords),
            hp3Str(row, ukf3RawMgdl),
            reasonNumber(row.timestamp, apsResults, targetBgOffsetRegex, df1),
            offsetSoZeroSmbStr(row.timestamp, apsResults),
            lowBgStr(row.timestamp, apsResults),
            stepsValue(sc, row.timestamp, apsResults, 5)?.toString() ?: "",
            stepsValue(sc, row.timestamp, apsResults, 15)?.toString() ?: "",
            stepsValue(sc, row.timestamp, apsResults, 30)?.toString() ?: "",
            stepsValue(sc, row.timestamp, apsResults, 60)?.toString() ?: "",
            stepsValue(sc, row.timestamp, apsResults, 180)?.toString() ?: "",
            mjStateStr(row.timestamp, notes),
            notesText(row, notes, allRecords, rawReadings),
            reasonNumber(row.timestamp, apsResults, ukf1AcceRegex, df2),
            reasonNumber(row.timestamp, apsResults, ukf1AcceIsfRegex, df2),
        )
    }

    private fun targetStr(row: AIV, apsResults: List<APSResult>): String {
        if (row.targetMgdl > 0.0) return df1.format(row.targetMgdl / MGDL_TO_MMOL)
        val nearest = nearestAps(row.timestamp, apsResults) ?: return "--"
        if (nearest.targetBG <= 0.0) return "--"
        return df1.format(nearest.targetBG / MGDL_TO_MMOL)
    }

    private fun insulinReqStr(timestamp: Long, apsResults: List<APSResult>): String {
        val reason = nearestAps(timestamp, apsResults)?.reason ?: return "--"
        val value = insulinReqRegex.find(reason)?.groupValues?.get(1)?.toDoubleOrNull() ?: return "--"
        return df2.format(value)
    }

    private fun tbrStr(timestamp: Long, apsResults: List<APSResult>): String {
        val nearest = nearestAps(timestamp, apsResults) ?: return "--"
        return df2.format(nearest.rate)
    }

    private fun exactFastRiseStr(timestamp: Long, apsResults: List<APSResult>): String {
        val reason = nearestAps(timestamp, apsResults)?.reason ?: return "--"
        if (reason.contains("fast-rise caps skipped", ignoreCase = true)) return "--"
        if (reason.contains("fast-rise caps tapered", ignoreCase = true)) return "--"
        val full = fastRiseFullRegex.find(reason)?.groupValues?.get(1)?.toDoubleOrNull()
        if (full != null) return String.format(Locale.US, "%.3f", full).replace(".", "").trimStart('0').ifEmpty { "0" }
        val factor = fastRiseFactorRegex.find(reason)?.groupValues?.get(1)?.toDoubleOrNull() ?: return "--"
        return (factor * 10).roundToInt().toString()
    }

    private fun deltaAcceStr(row: AIV, apsResults: List<APSResult>): String {
        val fromReason = reasonNumber(row.timestamp, apsResults, deltaAcclRegex, df1)
        if (fromReason != "--") return fromReason
        if (row.shortAvgDelta == 0.0) return "--"
        return df1.format((row.delta - row.shortAvgDelta) / abs(row.shortAvgDelta) * 100.0)
    }

    private fun reasonNumber(timestamp: Long, apsResults: List<APSResult>, regex: Regex, format: DecimalFormat): String {
        val reason = nearestAps(timestamp, apsResults)?.reason ?: return "--"
        val value = regex.find(reason)?.groupValues?.get(1)?.toDoubleOrNull() ?: return "--"
        return format.format(value)
    }

    private fun lowBgStr(timestamp: Long, apsResults: List<APSResult>): String {
        val reason = nearestAps(timestamp, apsResults)?.reason ?: return "--"
        return lowBgRecentRegex.find(reason)?.groupValues?.get(1) ?: "--"
    }

    private fun offsetSoZeroSmbStr(timestamp: Long, apsResults: List<APSResult>): String {
        val reason = nearestAps(timestamp, apsResults)?.reason ?: return "--"
        if (reason.contains("offsetSoZeroSMBcleared")) return "false"
        return offsetSoZeroRegex.find(reason)?.groupValues?.get(1) ?: "--"
    }

    private fun nearestAps(timestamp: Long, apsResults: List<APSResult>): APSResult? {
        val nearest = apsResults.minByOrNull { abs(it.date - timestamp) } ?: return null
        if (abs(nearest.date - timestamp) >= TimeUnit.MINUTES.toMillis(15)) return null
        return nearest
    }

    private fun stepsRegex(label: String) = Regex("""\b${label}min\s+is\s+([0-9]+)\b|\b${label}M\s*[:=]\s*([0-9]+)\b""", RegexOption.IGNORE_CASE)

    private fun stepsFromReason(timestamp: Long, apsResults: List<APSResult>, regex: Regex): Int? {
        val candidates = apsResults.filter { abs(it.date - timestamp) < TimeUnit.MINUTES.toMillis(15) }
            .sortedBy { abs(it.date - timestamp) }
        for (candidate in candidates) {
            val match = regex.find(candidate.reason) ?: continue
            return (match.groupValues[1].ifEmpty { match.groupValues[2] }).toIntOrNull()
        }
        return null
    }

    private fun stepsValue(sc: SC?, timestamp: Long, apsResults: List<APSResult>, bucketMinutes: Int): Int? {
        val (field, regex) = when (bucketMinutes) {
            5 -> SC::steps5min to stepsRegex("[Ss]teps5")
            15 -> SC::steps15min to stepsRegex("[Ss]teps15")
            30 -> SC::steps30min to stepsRegex("[Ss]teps30")
            60 -> SC::steps60min to stepsRegex("[Ss]teps60")
            else -> SC::steps180min to stepsRegex("[Ss]teps180")
        }
        return sc?.let(field) ?: stepsFromReason(timestamp, apsResults, regex)
    }

    private fun stepsAt(timestamp: Long, stepsCountList: List<SC>): SC? {
        val nearest = stepsCountList.minByOrNull { abs(it.timestamp - timestamp) } ?: return null
        if (abs(nearest.timestamp - timestamp) >= TimeUnit.MINUTES.toMillis(15)) return null
        return nearest
    }

    private fun iob5MinChangeStr(row: AIV, allRecords: List<AIV>): String {
        val target = row.timestamp - 5 * 60_000L
        val prior = allRecords.minByOrNull { abs(it.timestamp - target) } ?: return "--"
        if (abs(prior.timestamp - target) > 3 * 60_000L) return "--"
        return df2.format(row.iob - prior.iob)
    }

    private fun rawDeltaStr(timestamp: Long, rawReadings: List<GV>, minutesBack: Int): String {
        val inWindow = rawReadings.filter { it.timestamp in (timestamp - (minutesBack + 2) * 60_000L)..timestamp }
        if (inWindow.size < 2) return "--"
        val newest = inWindow.first()
        val newestNoise = newest.noise ?: return "--"
        if (minutesBack <= 1) {
            val priorNoise = inWindow[1].noise ?: return "--"
            val mins = (newest.timestamp - inWindow[1].timestamp) / 60_000.0
            if (mins <= 0.0) return "--"
            return df2.format((newestNoise - priorNoise) / mins * 5.0 / MGDL_TO_MMOL)
        }
        val target = timestamp - minutesBack * 60_000L
        val ref = inWindow.minByOrNull { abs(it.timestamp - target) } ?: return "--"
        if (ref.timestamp == newest.timestamp) return "--"
        val refNoise = ref.noise ?: return "--"
        return if (minutesBack <= 5) df2.format((newestNoise - refNoise) / MGDL_TO_MMOL)
        else {
            val actualMin = (newest.timestamp - ref.timestamp) / 60_000.0
            if (actualMin <= 0.0) return "--"
            df2.format((newestNoise - refNoise) / actualMin * 5.0 / MGDL_TO_MMOL)
        }
    }

    private fun rawBglStr(timestamp: Long, rawReadings: List<GV>): String {
        val inWindow = rawReadings.filter { it.timestamp in (timestamp - 3 * 60_000L)..timestamp }
        val noise = inWindow.firstOrNull()?.noise ?: return "--"
        return df1.format(noise / MGDL_TO_MMOL)
    }

    private fun ukfDeltaStr(row: AIV, allRecords: List<AIV>, minutesBack: Int): String =
        ukfDeltaMmol(row, allRecords, minutesBack)?.let { df2.format(it) } ?: "--"

    private fun ukfDeltaMmol(row: AIV, allRecords: List<AIV>, minutesBack: Int): Double? {
        if (row.ukfRawBgl <= 0.0) return null
        val target = row.timestamp - minutesBack * 60_000L
        val prior = allRecords.minByOrNull { abs(it.timestamp - target) } ?: return null
        if (abs(prior.timestamp - target) > 3 * 60_000L || prior.ukfRawBgl <= 0.0) return null
        val actualMin = (row.timestamp - prior.timestamp) / 60_000.0
        if (actualMin <= 0.0) return null
        return (row.ukfRawBgl - prior.ukfRawBgl) / actualMin * 5.0 / MGDL_TO_MMOL
    }

    private fun deltaAcceUkfStr(row: AIV, allRecords: List<AIV>): String {
        val fast = ukfDeltaMmol(row, allRecords, 5) ?: return "--"
        val slow = ukfDeltaMmol(row, allRecords, 15) ?: return "--"
        if (slow == 0.0) return "--"
        return df1.format((fast - slow) / abs(slow) * 100.0)
    }

    private fun computeUkf3RawMgdl(rawReadings: List<GV>): List<Pair<Long, Double>> {
        val newestFirst = rawReadings.filter { it.noise != null }.sortedByDescending { it.timestamp }
        if (newestFirst.isEmpty()) return emptyList()
        val smoothed = try {
            displayRawSmoothing.smoothForDisplay(newestFirst.map { it.timestamp to it.noise!! })
        } catch (e: Exception) {
            aapsLogger.debug(LTag.CORE, "UKF3 smooth skipped: ${e.message}")
            return emptyList()
        }
        if (smoothed.size != newestFirst.size) return emptyList()
        val applyCalibration = preferences.get(BooleanKey.FslApplySmoothing)
        val slope = preferences.get(DoubleKey.FslCalSlope)
        val offset = preferences.get(DoubleKey.FslCalOffset)
        val factor = preferences.get(DoubleKey.FslSmoothAlpha)
        val maxGap = preferences.get(IntNonKey.FslMaxSmoothGap).toDouble()
        val unitFactor = if (profileFunction.getUnits() == GlucoseUnit.MMOL) Constants.MMOLL_TO_MGDL else 1.0
        var lastSmooth = 0.0
        var lastTimeRaw = 0L
        return newestFirst.zip(smoothed).asReversed().map { (reading, ukf1Mgdl) ->
            val calibrated = if (applyCalibration) max(40.0, ukf1Mgdl * slope + offset * unitFactor) else ukf1Mgdl
            val elapsedMinutes = (reading.timestamp - lastTimeRaw) / 60000.0
            val gap = max(maxGap - 1.0, 1.0)
            val effectiveAlpha = min(1.0, factor + (1.0 - factor) * ((max(0.0, elapsedMinutes - 1.0) / gap).pow(2.0)))
            val smooth = if (lastSmooth > 0.0) lastSmooth + effectiveAlpha * (calibrated - lastSmooth) else calibrated
            lastSmooth = smooth
            lastTimeRaw = reading.timestamp
            reading.timestamp to smooth
        }
    }

    private fun ukf3ValueNear(series: List<Pair<Long, Double>>, timestamp: Long): Double? {
        val nearest = series.minByOrNull { abs(it.first - timestamp) } ?: return null
        if (abs(nearest.first - timestamp) > 3 * 60_000L) return null
        return nearest.second
    }

    private fun ukf3BglStr(row: AIV, series: List<Pair<Long, Double>>): String =
        ukf3ValueNear(series, row.timestamp)?.let { df1.format(it / MGDL_TO_MMOL) } ?: "--"

    private fun ukf3DeltaMmol(row: AIV, series: List<Pair<Long, Double>>, minutesBack: Int): Double? {
        val nowEntry = series.minByOrNull { abs(it.first - row.timestamp) } ?: return null
        if (abs(nowEntry.first - row.timestamp) > 3 * 60_000L) return null
        val target = row.timestamp - minutesBack * 60_000L
        val prior = series.minByOrNull { abs(it.first - target) } ?: return null
        if (abs(prior.first - target) > 3 * 60_000L || prior.first == nowEntry.first) return null
        val actualMin = (nowEntry.first - prior.first) / 60_000.0
        if (actualMin <= 0.0) return null
        return (nowEntry.second - prior.second) / actualMin * 5.0 / MGDL_TO_MMOL
    }

    private fun ukf3DeltaStr(row: AIV, series: List<Pair<Long, Double>>, minutesBack: Int): String =
        ukf3DeltaMmol(row, series, minutesBack)?.let { df2.format(it) } ?: "--"

    private fun deltaAcceU3Str(row: AIV, series: List<Pair<Long, Double>>): String {
        val fast = ukf3DeltaMmol(row, series, 5) ?: return "--"
        val slow = ukf3DeltaMmol(row, series, 15) ?: return "--"
        if (slow == 0.0) return "--"
        return df1.format((fast - slow) / abs(slow) * 100.0)
    }

    private fun readingIntervalStr(timestamp: Long, rawReadings: List<GV>): String {
        val inWindow = rawReadings.filter { it.timestamp in (timestamp - 5 * 60_000L)..timestamp }
        if (inWindow.size < 2) return "--"
        val spanSec = (inWindow.maxOf { it.timestamp } - inWindow.minOf { it.timestamp }).toDouble() / 1000.0
        return (spanSec / (inWindow.size - 1)).roundToInt().toString()
    }

    private fun smbInterval5SecStr(timestamp: Long, smbBoluses: List<BS>): String {
        val inWindow = smbBoluses.filter { it.timestamp in (timestamp - 5 * 60_000L)..timestamp }
        if (inWindow.size < 2) return "--"
        val spanSec = (inWindow.maxOf { it.timestamp } - inWindow.minOf { it.timestamp }).toDouble() / 1000.0
        return (spanSec / (inWindow.size - 1)).roundToInt().toString()
    }

    private fun carePortalNotesStr(timestamp: Long, notes: List<TE>, allRecords: List<AIV>): String {
        val minuteMs = TimeUnit.MINUTES.toMillis(1)
        val minuteStart = timestamp - (timestamp % minuteMs)
        val minuteEnd = minuteStart + minuteMs
        val exact = notes.filter { it.timestamp >= minuteStart && it.timestamp < minuteEnd }
        val minutesWithRows = allRecords.mapTo(HashSet()) { it.timestamp - (it.timestamp % minuteMs) }
        val orphanToleranceMs = TimeUnit.MINUTES.toMillis(10)
        val fallback = notes.filter { note ->
            val noteMinute = note.timestamp - (note.timestamp % minuteMs)
            noteMinute !in minutesWithRows &&
                abs(note.timestamp - timestamp) <= orphanToleranceMs &&
                allRecords.minByOrNull { abs(it.timestamp - note.timestamp) }?.timestamp == timestamp
        }
        return (exact + fallback).asSequence()
            .mapNotNull { it.note?.trim()?.takeIf(String::isNotEmpty) }
            .map { it.replace(Regex("[\\r\\n]+"), " ") }
            .distinct()
            .toList()
            .asReversed()
            .joinToString("|")
    }

    private fun notesText(row: AIV, notes: List<TE>, allRecords: List<AIV>, rawReadings: List<GV>): String {
        val base = carePortalNotesStr(row.timestamp, notes, allRecords)
        val stored = row.note.trim()
        val withStored = when {
            stored.isEmpty() -> base
            base.isEmpty() -> stored
            base.contains(stored) -> base
            else -> "$base|$stored"
        }
        val tag = rawMissingTag(row.timestamp, rawReadings)
        return when {
            tag.isEmpty() -> withStored
            withStored.isEmpty() -> tag
            else -> "$withStored|$tag"
        }
    }

    private fun rawMissingTag(timestamp: Long, rawReadings: List<GV>): String {
        if (!virtualPump.isEnabled() || config.AAPSCLIENT) return ""
        val noise = rawReadings.firstOrNull { it.timestamp in (timestamp - 3 * 60_000L)..timestamp }?.noise
        return if (noise == null || noise <= 10.0) "RawMiss" else ""
    }

    private fun mjStateStr(timestamp: Long, notes: List<TE>): String {
        val latest = notes.firstOrNull {
            val note = it.note ?: ""
            it.timestamp <= timestamp && (
                note == "MJ" || note == "MJ active" || note == "MJ2" || note == "MJ3" ||
                    note == "MoreMJ" || note == "A1" || note == "NOMJremains" || note.startsWith("MJoff")
                )
        } ?: return "NOM"
        val note = latest.note ?: return "NOM"
        return when {
            note == "MJ" || note == "MJ active" -> "MJa"
            note == "MJ2" -> "MJ2"
            note == "MJ3" || note == "MoreMJ" -> "MJ3"
            else -> "NOM"
        }
    }

    private fun basalStr(row: AIV): String = df2.format(row.basal)

    private fun cobStr(row: AIV): String = df1.format(cobAt(row))

    private fun cobAt(row: AIV): Double {
        if (row.cob != 0.0) return row.cob
        return iobCobCalculator.ads.getAutosensDataAtTime(row.timestamp)?.cob ?: 0.0
    }

    private fun carbAbsStr(row: AIV): String =
        df2.format(iobCobCalculator.ads.getAutosensDataAtTime(row.timestamp)?.this5MinAbsorption ?: 0.0)

    private fun calculatedCobT(records: List<AIV>): Map<Long, Double> {
        if (records.isEmpty()) return emptyMap()
        val result = records.associate { it.timestamp to cobAt(it) }.toMutableMap()
        val positive = records.sortedBy { it.timestamp }.mapNotNull { row ->
            val carbAbs = iobCobCalculator.ads.getAutosensDataAtTime(row.timestamp)?.this5MinAbsorption ?: 0.0
            val excess = (row.uamCarbImpact - carbAbs).coerceAtLeast(0.0)
            if (excess > 0.05) row.timestamp to excess else null
        }
        val episodes = mutableListOf<MutableList<Pair<Long, Double>>>()
        for (point in positive) {
            val current = episodes.lastOrNull()
            if (current == null || point.first - current.last().first > 15 * 60_000L) episodes.add(mutableListOf(point))
            else current.add(point)
        }
        for (episode in episodes) {
            var remainingExtra = 0.0
            for (i in episode.indices.reversed()) {
                val minutes = if (i < episode.lastIndex) ((episode[i + 1].first - episode[i].first) / 60_000.0).coerceIn(0.0, 5.0) else 1.0
                remainingExtra += episode[i].second * minutes / 5.0
                val timestamp = episode[i].first
                result[timestamp] = (result[timestamp] ?: 0.0) + remainingExtra
            }
        }
        return result
    }

    private fun cobTStr(row: AIV, cobTByTimestamp: Map<Long, Double>): String =
        df1.format(cobTByTimestamp[row.timestamp] ?: cobAt(row))

    private fun rawDelta5Mmol(timestamp: Long, rawReadings: List<GV>): Double? {
        val inWindow = rawReadings.filter { it.timestamp in (timestamp - 7 * 60_000L)..timestamp }
        if (inWindow.size < 2) return null
        val newest = inWindow.first()
        val newestNoise = newest.noise ?: return null
        val target = timestamp - 5 * 60_000L
        val ref = inWindow.minByOrNull { abs(it.timestamp - target) } ?: return null
        if (ref.timestamp == newest.timestamp) return null
        val refNoise = ref.noise ?: return null
        return (newestNoise - refNoise) / MGDL_TO_MMOL
    }

    private fun hpStr(row: AIV, rawReadings: List<GV>): String {
        val libreDelta5 = rawDelta5Mmol(row.timestamp, rawReadings) ?: return "--"
        val hp = (row.glucose / MGDL_TO_MMOL - row.iob) + 0.25 * (row.shortAvgDelta / MGDL_TO_MMOL) + 0.25 * libreDelta5 + cobAt(row) / 12.0
        return df1.format(hp)
    }

    private fun hp2Str(row: AIV, allRecords: List<AIV>): String {
        val ukfDelta5 = ukfDeltaMmol(row, allRecords, 5) ?: return "--"
        val hp = (row.glucose / MGDL_TO_MMOL - row.iob) + 0.25 * (row.shortAvgDelta / MGDL_TO_MMOL) + 0.25 * ukfDelta5 + cobAt(row) / 12.0
        return df1.format(hp)
    }

    private fun hp3Str(row: AIV, series: List<Pair<Long, Double>>): String {
        val ukf3Now = ukf3ValueNear(series, row.timestamp) ?: return "--"
        val ukf3Delta5 = ukf3DeltaMmol(row, series, 5) ?: return "--"
        val hp = (ukf3Now / MGDL_TO_MMOL - row.iob) + 0.25 * (row.shortAvgDelta / MGDL_TO_MMOL) + 0.25 * ukf3Delta5 + cobAt(row) / 12.0
        return df1.format(hp)
    }

    private fun csvCell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"${value.replace("\"", "\"\"")}\""
        else value

    companion object {

        private const val MGDL_TO_MMOL = 18.0182
        private const val WINDOW_HOURS = 6L

        private val fastRiseFullRegex = Regex("""fast\s*rise\s*([0-9]+\.[0-9]+)""", RegexOption.IGNORE_CASE)
        private val fastRiseFactorRegex = Regex("""=\s*microBolus\s*\*\s*([0-9.]+)\s*;""")
        private val mildBstRegex = Regex("""MildBst:\s*([0-9.]+)""")
        private val targetBgOffsetRegex = Regex("""targetBgOffset:\s*([0-9.]+)""")
        private val deltaAcclRegex = Regex("""delta_accl:\s*(-?[0-9.]+)""")
        private val ukf1AcceRegex = Regex("""ukf1Acce:\s*(-?[0-9.]+)""")
        private val ukf1AcceIsfRegex = Regex("""ukf1AcceISF:\s*(-?[0-9.]+)""")
        private val lowBgRecentRegex = Regex("""LowBGrecent:\s*([YN])""")
        private val offsetSoZeroRegex = Regex("""offsetSoZeroSMB:\s*(true|false)""")
        private val insulinReqRegex = Regex("""insulinReq\s+(-?[0-9.]+)""")

        val EXPORT_HEADERS = listOf(
            "Time", "BGL", "Target", "Final", "acce", "bg", "pp", "dura", "UAMci", "SMB", "FastRise", "SmbRatio", "MildBst", "SMBi5", "iobTH", "acWt", "ppWt", "Lslope",
            "acceBG", "deltaAcce", "deltaAcceUkf", "deltaAcceU3",
            "Delta", "SDelta", "LDelta", "rawBGL", "rawD1", "rawD5", "rawD15", "ukfRawBGL", "RawUKF5", "RawUKF15",
            "ukf3RawBGL", "RawUKF3_5", "RawUKF3_15",
            "ukf2RawBGL", "RawUKF2_5", "RawUKF2_15",
            "Int5", "Req", "TBR", "IOB", "IOBd5", "Basal", "COB", "COBt", "carbAbs", "HP", "HP2", "HP3",
            "targetBgOffset", "offsetSoZeroSMB", "LowBG", "S5", "S15", "S30", "S60", "S180", "MJ", "Notes",
            "ukf1Acce", "ukf1AcceISF"
        )
    }
}
