package app.aaps.core.objects.autoisf

import app.aaps.core.data.model.AIV
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.LiveSteps
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The exported AutoISF files (csv and txt) in the 3426 app's columns, names and order (2026-11-20, per explicit request), so
 * files from v3 and v4 phones read the same way. Values in mmol/L as there, numbers always with a point.
 * Columns v4 cannot fill here are "--": the UKF2 and UKF3 series (deltaAcceU3, ukf3RawBGL, RawUKF3_5, RawUKF3_15, ukf2RawBGL,
 * RawUKF2_5, RawUKF2_15, HP3), and COBt and carbAbs where no carb absorption is worked out (a client).
 */
val AUTO_ISF_V3_EXPORT_HEADER = listOf(
    "Time", "BGL", "Target", "Final", "acce", "bg", "pp", "dura", "UAMci", "SMB", "FastRise", "SmbRatio", "MildBst", "SMBi5", "iobTH",
    "acWt", "ppWt", "Lslope", "acceBG", "deltaAcce", "deltaAcceUkf", "deltaAcceU3", "Delta", "SDelta", "LDelta", "rawBGL", "rawD1",
    "rawD5", "rawD15", "ukfRawBGL", "RawUKF5", "RawUKF15", "ukf3RawBGL", "RawUKF3_5", "RawUKF3_15", "ukf2RawBGL", "RawUKF2_5",
    "RawUKF2_15", "Int5", "Req", "TBR", "IOB", "IOBd5", "Basal", "COB", "COBt", "carbAbs", "HP", "HP2", "HP3", "targetBgOffset",
    "offsetSoZeroSMB", "LowBG", "S5", "S15", "S30", "S60", "S180", "MJ", "Notes", "ukf1Acce", "ukf1AcceISF"
)

private const val MGDL_PER_MMOL = 18.0182

private val mildBstExportRegex = Regex("""MildBst:\s*([0-9.]+)""")
private val targetBgOffsetRegex = Regex("""targetBgOffset:\s*([0-9.]+)""")
private val ukf1AcceRegex = Regex("""ukf1Acce:\s*(-?[0-9.]+)""")
private val ukf1AcceIsfRegex = Regex("""ukf1AcceISF:\s*(-?[0-9.]+)""")
private val lowBgRecentRegex = Regex("""LowBGrecent:\s*([YN])""")
private val offsetSoZeroRegex = Regex("""offsetSoZeroSMB:\s*(true|false)""")
private val insulinReqRegex = Regex("""insulinReq\s+(-?[0-9.]+)""")
private val lineBreaks = Regex("[\\r\\n]+")

/** [value] with exactly [decimals] decimals and a point, whatever the phone's language (csv-safe). */
fun fixedDecimals(value: Double, decimals: Int): String {
    if (!value.isFinite()) return "--"
    val scaled = (abs(value) * 10.0.pow(decimals)).roundToLong()
    val digits = scaled.toString().padStart(decimals + 1, '0')
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return if (decimals == 0) sign + digits else sign + digits.dropLast(decimals) + "." + digits.takeLast(decimals)
}

private fun f1(v: Double) = fixedDecimals(v, 1)
private fun f2(v: Double) = fixedDecimals(v, 2)
private fun mmol(mgdl: Double) = mgdl / MGDL_PER_MMOL

/**
 * The raw (noise) change at [timestamp], as the 3426 export's rawD1/rawD5/rawD15, in mg/dL: over 1 minute scaled to 5, over
 * 5 minutes as it is, over 15 minutes scaled to 5. [raw] is (time, raw value) newest first.
 */
fun rawDeltaMgdl(timestamp: Long, raw: List<Pair<Long, Double?>>, minutesBack: Int): Double? {
    val inWindow = raw.filter { it.first in (timestamp - (minutesBack + 2) * 60_000L)..timestamp }
    if (inWindow.size < 2) return null
    val newest = inWindow.first()
    val newestValue = newest.second ?: return null
    if (minutesBack <= 1) {
        val prior = inWindow[1]
        val priorValue = prior.second ?: return null
        val minutes = (newest.first - prior.first) / 60_000.0
        if (minutes <= 0.0) return null
        return (newestValue - priorValue) / minutes * 5.0
    }
    val ref = inWindow.minByOrNull { abs(it.first - (timestamp - minutesBack * 60_000L)) } ?: return null
    if (ref.first == newest.first) return null
    val refValue = ref.second ?: return null
    if (minutesBack <= 5) return newestValue - refValue
    val minutes = (newest.first - ref.first) / 60_000.0
    if (minutes <= 0.0) return null
    return (newestValue - refValue) / minutes * 5.0
}

/** Mean seconds between the readings of the 5 minutes up to [timestamp] (the 3426 export's Int5). */
fun readingInterval5Sec(timestamp: Long, raw: List<Pair<Long, Double?>>): Int? {
    val inWindow = raw.filter { it.first in (timestamp - 5 * 60_000L)..timestamp }
    if (inWindow.size < 2) return null
    return ((inWindow.maxOf { it.first } - inWindow.minOf { it.first }) / 1000.0 / (inWindow.size - 1)).roundToInt()
}

/** The exported rows, newest first, in [AUTO_ISF_V3_EXPORT_HEADER]'s order. [this] is newest first. */
fun List<AIV>.autoIsfV3ExportRows(
    timeText: (Long) -> String,
    loopResults: List<LoopResultText> = emptyList(),
    rawReadings: List<Pair<Long, Double?>> = emptyList(),
    smbTimes: List<Long> = emptyList(),
    steps: List<SC> = emptyList(),
    fromLivePhone: Boolean = false,
    ownDevice: String = "",
    notes: List<Pair<Long, String>> = emptyList(),
    profileBasalAt: (Long) -> Double? = { null },
    autosensAt: ((Long) -> Pair<Double, Double>?)? = null
): List<List<String>> {
    // COB as the 3426 export: the loop's own, else the worked-out one
    val cobByTime = associate { row -> row.timestamp to (if (row.cob != 0.0) row.cob else autosensAt?.invoke(row.timestamp)?.first ?: 0.0) }
    val cobT = autosensAt?.let { at -> calculatedCobT(this) { t -> (cobByTime[t] ?: 0.0) to (at(t)?.second ?: 0.0) } } ?: emptyMap()
    return mapIndexed { index, row -> exportRow(index, row, timeText, loopResults, rawReadings, smbTimes, steps, fromLivePhone, ownDevice, notes, profileBasalAt, autosensAt, cobByTime, cobT) }
}

private fun List<AIV>.exportRow(
    index: Int,
    row: AIV,
    timeText: (Long) -> String,
    loopResults: List<LoopResultText>,
    rawReadings: List<Pair<Long, Double?>>,
    smbTimes: List<Long>,
    steps: List<SC>,
    fromLivePhone: Boolean,
    ownDevice: String,
    notes: List<Pair<Long, String>>,
    profileBasalAt: (Long) -> Double?,
    autosensAt: ((Long) -> Pair<Double, Double>?)?,
    cobByTime: Map<Long, Double>,
    cobT: Map<Long, Double>,
): List<String> {
    val loop = nearestLoopResult(loopResults, row.timestamp)
    val reason = loop?.reason
    fun reasonNumber(regex: Regex, format: (Double) -> String): String =
        reason?.let { regex.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }?.let(format) ?: "--"
    val cob = cobByTime[row.timestamp] ?: row.cob
    fun hp(delta5Mgdl: Double): String =
        f1(mmol(row.glucose) - row.iob + 0.25 * mmol(row.shortAvgDelta) + 0.25 * mmol(delta5Mgdl) + cob / 12.0)
    val sample = LiveSteps.sampleFor(row.timestamp, steps, fromLivePhone, ownDevice)
    val prior5 = minByOrNull { abs(it.timestamp - (row.timestamp - 5 * 60_000L)) }
        ?.takeIf { abs(it.timestamp - (row.timestamp - 5 * 60_000L)) <= 3 * 60_000L }
    val rowNotes = carePortalNotesForRow(this, index, notes)
    val loopTarget = loop?.targetBG ?: 0.0
    val ukf5 = ukfDeltaMgdl(this, index, 5)
    return listOf(
        timeText(row.timestamp),
        f1(mmol(row.glucose)),
        if (row.targetMgdl > 0.0) f1(mmol(row.targetMgdl)) else if (loopTarget > 0.0) f1(mmol(loopTarget)) else "--",
        f2(row.finalIsf), f2(row.acceIsf), f2(row.bgIsf), f2(row.ppIsf), f2(row.duraIsf),
        f2(row.uamCarbImpact),
        f2(row.smbDelivered),
        reason?.let { fastRiseText(it) } ?: "--",
        f2(row.smbDeliveryRatio),
        reasonNumber(mildBstExportRegex, ::f2),
        smbInterval5Sec(row.timestamp, smbTimes)?.toString() ?: "--",
        f2(row.iobThEffective), f2(row.acceIsfWeight), f2(row.ppIsfWeight), f2(row.fslCalSlope),
        f2(row.bgAcceleration),
        reason?.let { deltaAcceValue(it) }?.let(::f1)
            ?: if (row.shortAvgDelta == 0.0) "--" else f1((row.delta - row.shortAvgDelta) / abs(row.shortAvgDelta) * 100.0),
        deltaAcceUkfPercent(this, index)?.let(::f1) ?: "--",
        "--", // deltaAcceU3: the UKF3 series is not worked out here
        f2(mmol(row.delta)), f2(mmol(row.shortAvgDelta)), f2(mmol(row.longAvgDelta)),
        rawReadings.firstOrNull { it.first in (row.timestamp - 3 * 60_000L)..row.timestamp }?.second?.let { f1(mmol(it)) } ?: "--",
        rawDeltaMgdl(row.timestamp, rawReadings, 1)?.let { f2(mmol(it)) } ?: "--",
        rawDeltaMgdl(row.timestamp, rawReadings, 5)?.let { f2(mmol(it)) } ?: "--",
        rawDeltaMgdl(row.timestamp, rawReadings, 15)?.let { f2(mmol(it)) } ?: "--",
        if (row.ukfRawBgl > 0.0) f1(mmol(row.ukfRawBgl)) else "--",
        ukf5?.let { f2(mmol(it)) } ?: "--",
        ukfDeltaMgdl(this, index, 15)?.let { f2(mmol(it)) } ?: "--",
        "--", "--", "--", // ukf3RawBGL, RawUKF3_5, RawUKF3_15
        "--", "--", "--", // ukf2RawBGL, RawUKF2_5, RawUKF2_15
        readingInterval5Sec(row.timestamp, rawReadings)?.toString() ?: "--",
        (loop?.insulinReq ?: reason?.let { insulinReqRegex.find(it)?.groupValues?.get(1)?.toDoubleOrNull() })?.let(::f2) ?: "--",
        loop?.rate?.let(::f2) ?: "--",
        f2(row.iob),
        prior5?.let { f2(row.iob - it.iob) } ?: "--",
        profileBasalAt(row.timestamp)?.let(::f2) ?: "--",
        f1(cob),
        if (autosensAt == null) "--" else f1(cobT[row.timestamp] ?: cob),
        autosensAt?.invoke(row.timestamp)?.second?.let(::f2) ?: "--",
        rawDelta5Mgdl(row.timestamp, rawReadings)?.let(::hp) ?: "--",
        ukf5?.let(::hp) ?: "--",
        "--", // HP3: needs the UKF3 series
        reasonNumber(targetBgOffsetRegex, ::f1),
        reason?.let { if (it.contains("offsetSoZeroSMBcleared")) "false" else offsetSoZeroRegex.find(it)?.groupValues?.get(1) } ?: "--",
        reason?.let { lowBgRecentRegex.find(it)?.groupValues?.get(1) } ?: "--",
        sample?.steps5min?.toString() ?: "",
        sample?.steps15min?.toString() ?: "",
        sample?.steps30min?.toString() ?: "",
        sample?.steps60min?.toString() ?: "",
        sample?.steps180min?.toString() ?: "",
        mjAt(row.timestamp, notes),
        row.note.ifBlank { rowNotes.joinToString(" ") { it.second } }.replace(lineBreaks, " "),
        reasonNumber(ukf1AcceRegex, ::f2),
        reasonNumber(ukf1AcceIsfRegex, ::f2),
    )
}

/** The exported rows as csv, v3's header first. */
fun autoIsfV3ExportCsv(rows: List<List<String>>): String =
    (listOf(AUTO_ISF_V3_EXPORT_HEADER) + rows).joinToString("\n") { line -> line.joinToString(",") { csvCell(it) } }

/** The exported rows as the 3426 app's padded text table; a row with an SMB ends with "SMB". */
fun autoIsfV3ExportText(rows: List<List<String>>): String {
    val widths = AUTO_ISF_V3_EXPORT_HEADER.indices.map { i -> max(AUTO_ISF_V3_EXPORT_HEADER[i].length, rows.maxOfOrNull { it.getOrNull(i)?.length ?: 0 } ?: 0) }
    val smbColumn = AUTO_ISF_V3_EXPORT_HEADER.indexOf("SMB")
    val sb = StringBuilder()
    sb.append(AUTO_ISF_V3_EXPORT_HEADER.mapIndexed { i, h -> h.padEnd(widths[i]) }.joinToString("  ").trimEnd()).append('\n')
    for (row in rows) {
        sb.append(row.mapIndexed { i, v -> v.padEnd(widths.getOrElse(i) { v.length }) }.joinToString("  ").trimEnd())
        if ((row.getOrNull(smbColumn)?.toDoubleOrNull() ?: 0.0) > 0.0) sb.append("  SMB")
        sb.append('\n')
    }
    return sb.toString()
}

private fun csvCell(value: String): String =
    if (value.contains(',') || value.contains('"') || value.contains('\n')) "\"" + value.replace("\"", "\"\"") + "\"" else value

/**
 * The exported rows of the last [AUTO_ISF_HISTORY_WINDOW_MS], newest first, in the 3426 app's columns
 * ([AUTO_ISF_V3_EXPORT_HEADER]). A client reads the loop phone's results and raw readings as synced; it works out no carb
 * absorption, so COBt and carbAbs stay "--" there. Without [iobCobCalculator] they are "--" too.
 */
suspend fun loadAutoIsfV3ExportRows(
    now: Long,
    persistenceLayer: PersistenceLayer,
    profileFunction: ProfileFunction,
    dateUtil: DateUtil,
    config: Config,
    preferences: Preferences,
    activePlugin: ActivePlugin,
    iobCobCalculator: IobCobCalculator?
): List<List<String>> {
    val from = now - AUTO_ISF_HISTORY_WINDOW_MS
    val fromLivePhone = config.AAPSCLIENT || config.APS &&
        preferences.get(BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual) &&
        activePlugin.activePump.selectedActivePump() is VirtualPump
    val records = persistenceLayer.getAutoIsfValuesFromTimeToTime(from, now).sortedByDescending { it.timestamp }
    val notes = persistenceLayer.getTherapyEventDataFromTime(from - 24L * 60L * 60L * 1000L, TE.Type.NOTE, true)
        .filter { it.isValid && !it.note.isNullOrBlank() }
        .map { it.timestamp to it.note.orEmpty().trim() }
    val profileBasal = records.associate { it.timestamp to profileFunction.getProfile(it.timestamp)?.getBasal(it.timestamp) }
    val rawReadings = persistenceLayer.getBgReadingsDataFromTimeToTime(from - 20 * 60_000L, now, false)
        .map { it.timestamp to it.noise?.takeIf { n -> n > 10.0 } }
    val smbTimes = persistenceLayer.getBolusesFromTimeToTime(from - 10 * 60_000L, now, true)
        .filter { it.isValid && it.type == BS.Type.SMB && it.amount > 0.0 }.map { it.timestamp }
    val loopResults = persistenceLayer.getApsResults(from - 15 * 60_000L, now).map { result ->
        val insulinReq = runCatching { result.json()?.get("insulinReq")?.jsonPrimitive?.doubleOrNull }.getOrNull()
        LoopResultText(result.date, result.reason, insulinReq, result.targetBG, result.rate)
    }
    val ads = iobCobCalculator?.ads?.takeIf { !config.AAPSCLIENT }
    val autosensAt: ((Long) -> Pair<Double, Double>?)? = ads?.let { store ->
        { time: Long -> store.getAutosensDataAtTime(time)?.let { it.cob to it.this5MinAbsorption } }
    }
    return records.autoIsfV3ExportRows(
        timeText = { dateUtil.timeString(it) },
        loopResults = loopResults,
        rawReadings = rawReadings,
        smbTimes = smbTimes,
        steps = persistenceLayer.getStepsCountFromTimeToTime(from, now),
        fromLivePhone = fromLivePhone,
        ownDevice = "openaps://${config.deviceModelForUpload}",
        notes = notes,
        profileBasalAt = { profileBasal[it] },
        autosensAt = autosensAt
    )
}
