package app.aaps.core.objects.autoisf

import app.aaps.core.data.model.AIV
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.LiveSteps
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.graph.DominantIsf
import app.aaps.core.interfaces.overview.graph.dominantIsf
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.notes.NoteSmsTexts
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.roundToInt

// The screen and exported files share these rows. Full SMS text is shown only on the phone that has it.

/** How far back the history table looks. */
const val AUTO_ISF_HISTORY_WINDOW_MS = 6L * 60L * 60L * 1000L

/** How far back an MJ note is looked for, before the first row. Same as the graph 1 MJ= text. */
private const val MJ_NOTE_WINDOW_MS = 24L * 60L * 60L * 1000L

/**
 * Which AutoISF factor moved the ISF the most.
 * [NONE] means every factor stayed within 0.01 of 1.0.
 */
enum class AutoIsfFactor {
    ACCE,
    BG,
    PP,
    DURA,
    NONE
}

/**
 * One row of the history table. Text is already in the user's glucose units.
 * [hasSmb] keeps the SMB filter working after a zero SMB has been shown as "--".
 */
data class AutoIsfHistoryRow(
    val time: String,
    val glucose: String,
    val ukf: String,
    val ukfDelta5: String,
    val ukfDelta15: String,
    val finalIsf: String,
    val finalFactor: AutoIsfFactor,
    val acceIsf: String,
    val bgIsf: String,
    val ppIsf: String,
    val duraIsf: String,
    val acceleration: String,
    val delta: String,
    val shortDelta: String,
    val longDelta: String,
    val iob: String,
    val iob5: String,
    val iobTh: String,
    val smb: String,
    val target: String,
    val uam: String,
    val smbRatio: String,
    val acceWeight: String,
    val ppWeight: String,
    val slope: String,
    val cob: String,
    val basal: String,
    val profileBasal: String,
    val note: String,
    /** MJ state at this time, from the newest MJ note: MJa, MJ2, MJ3 or NOM. */
    val mj: String,
    val smbFactor: AutoIsfFactor,
    val hasSmb: Boolean,
    val steps5: String,
    val steps15: String,
    val steps30: String,
    val steps60: String,
    val steps180: String,
    val fullNote: String = "",
    /** Manual, calculator, carb-split and protein/fat boluses since the row before (2026-11-15, per explicit request). */
    val bolus: String = "--",
    val hasBolus: Boolean = false,
    // The columns below are on the screen only, so the table matches the 3426 app's (2026-11-20, per explicit request). The
    // exported files keep their columns. "--" when the source is missing (see [loadAutoIsfHistoryRows]).
    /** Hypo prediction with the raw (Libre) 5 minute change, in the user's units; see [hypoPredictionMgdl]. */
    val hp: String = "--",
    /** Hypo prediction with the UKF 5 minute change, in the user's units; see [hypoPredictionMgdl]. */
    val hp2: String = "--",
    /** Fast-rise SMB factor from the loop's reason text; see [fastRiseText]. */
    val fastRise: String = "--",
    /** Mild boost base from the loop's reason text. */
    val mildBst: String = "--",
    /** Mean seconds between the SMBs of the last 5 minutes; see [smbInterval5Sec]. */
    val smbI5: String = "--",
    /** Change in BG acceleration (delta_accl) from the loop's reason text. */
    val deltaAcce: String = "--",
    /** UKF 5 minute change against the 15 minute one, in %; see [deltaAcceUkfPercent]. */
    val deltaAcceUkf: String = "--",
    /** Insulin required from the loop's result. */
    val insulinReq: String = "--",
    /** Carbs on board including unannounced carbs; see [calculatedCobT]. Not on a client. */
    val cobT: String = "--",
    /** Carb absorption per 5 minutes. Not on a client. */
    val carbAbs: String = "--"
)

/** What the table reads from one stored loop result: its time, its reason text and its insulin required. */
data class LoopResultText(
    val date: Long,
    val reason: String,
    val insulinReq: Double?,
    /** The loop's target in mg/dL, 0 when none (for the exported files). */
    val targetBG: Double = 0.0,
    /** The temp basal rate the loop asked for, U/h (for the exported files). */
    val rate: Double? = null
)

private const val LOOP_RESULT_REACH_MS = 15 * 60_000L

/** The loop result nearest [timestamp], within 15 minutes, as the 3426 app picks it. */
internal fun nearestLoopResult(results: List<LoopResultText>, timestamp: Long): LoopResultText? =
    results.minByOrNull { abs(it.date - timestamp) }?.takeIf { abs(it.date - timestamp) < LOOP_RESULT_REACH_MS }

private val fastRiseFullRegex = Regex("""fast\s*rise\s*([0-9]+\.[0-9]+)""", RegexOption.IGNORE_CASE)
private val fastRiseFactorRegex = Regex("""=\s*microBolus\s*\*\s*([0-9.]+)\s*;""")
private val mildBstRegex = Regex("""MildBst:\s*([0-9.]+)""")
private val deltaAcclRegex = Regex("""delta_accl:\s*(-?[0-9.]+)""")

/**
 * The fast-rise SMB factor in the 3426 app's form: the full ratio without its point ("fast rise 0.750" gives 750), else the
 * "microBolus * 0.9" factor times 10 (9). "--" when the caps were skipped or tapered, since they then did not cap the SMB.
 */
fun fastRiseText(reason: String): String {
    if (reason.contains("fast-rise caps skipped", ignoreCase = true)) return "--"
    if (reason.contains("fast-rise caps tapered", ignoreCase = true)) return "--"
    fastRiseFullRegex.find(reason)?.groupValues?.get(1)?.let { full ->
        val (whole, frac) = full.split('.')
        return (whole + (frac + "000").take(3)).trimStart('0').ifEmpty { "0" }
    }
    val factor = fastRiseFactorRegex.find(reason)?.groupValues?.get(1)?.toDoubleOrNull() ?: return "--"
    return (factor * 10).roundToInt().toString()
}

fun mildBstValue(reason: String): Double? = mildBstRegex.find(reason)?.groupValues?.get(1)?.toDoubleOrNull()

fun deltaAcceValue(reason: String): Double? = deltaAcclRegex.find(reason)?.groupValues?.get(1)?.toDoubleOrNull()

/** Mean seconds between the SMBs given in the 5 minutes up to [timestamp], or null with fewer than two. */
fun smbInterval5Sec(timestamp: Long, smbTimes: List<Long>): Int? {
    val inWindow = smbTimes.filter { it in (timestamp - 5 * 60_000L)..timestamp }
    if (inWindow.size < 2) return null
    return ((inWindow.max() - inWindow.min()) / 1000.0 / (inWindow.size - 1)).roundToInt()
}

/** UKF change per 5 minutes over about [minutesBack] minutes, in mg/dL, or null without UKF values. */
fun ukfDeltaMgdl(rows: List<AIV>, index: Int, minutesBack: Int): Double? {
    val row = rows[index]
    if (row.ukfRawBgl <= 0.0) return null
    val target = row.timestamp - minutesBack * 60_000L
    val prior = rows.minByOrNull { abs(it.timestamp - target) } ?: return null
    if (abs(prior.timestamp - target) > 3 * 60_000L || prior.ukfRawBgl <= 0.0) return null
    val actualMin = (row.timestamp - prior.timestamp) / 60_000.0
    if (actualMin <= 0.0) return null
    return (row.ukfRawBgl - prior.ukfRawBgl) / actualMin * 5.0
}

/** The UKF 5 minute change against the 15 minute one, in % of the 15 minute one, as the 3426 app's dAccUkf. */
fun deltaAcceUkfPercent(rows: List<AIV>, index: Int): Double? {
    val fast = ukfDeltaMgdl(rows, index, 5) ?: return null
    val slow = ukfDeltaMgdl(rows, index, 15) ?: return null
    if (slow == 0.0) return null
    return (fast - slow) / abs(slow) * 100.0
}

/**
 * The raw (Libre noise) change over about 5 minutes at [timestamp], in mg/dL, as the 3426 app's HP column uses it. [raw] is
 * (time, raw value) newest first. Null when the readings or their raw values are missing.
 */
fun rawDelta5Mgdl(timestamp: Long, raw: List<Pair<Long, Double?>>): Double? {
    val inWindow = raw.filter { it.first in (timestamp - 7 * 60_000L)..timestamp }
    if (inWindow.size < 2) return null
    val newest = inWindow.first()
    val newestValue = newest.second ?: return null
    val ref = inWindow.minByOrNull { abs(it.first - (timestamp - 5 * 60_000L)) } ?: return null
    if (ref.first == newest.first) return null
    return newestValue - (ref.second ?: return null)
}

/**
 * Carbs on board including what unannounced carbs add (the 3426 app's COBt, for display only): the autosens COB at each row,
 * plus, within each run of rows where the UAM carb impact exceeds the modelled absorption, the excess still to come.
 * [autosensAt] gives (COB, absorption per 5 minutes) at a time.
 */
fun calculatedCobT(rows: List<AIV>, autosensAt: (Long) -> Pair<Double, Double>?): Map<Long, Double> {
    if (rows.isEmpty()) return emptyMap()
    val result = rows.associate { it.timestamp to (autosensAt(it.timestamp)?.first ?: 0.0) }.toMutableMap()
    val positive = rows.sortedBy { it.timestamp }.mapNotNull { r ->
        val excess = (r.uamCarbImpact - (autosensAt(r.timestamp)?.second ?: 0.0)).coerceAtLeast(0.0)
        if (excess > 0.05) r.timestamp to excess else null
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
            result[episode[i].first] = (result[episode[i].first] ?: 0.0) + remainingExtra
        }
    }
    return result
}

/** Column names for the exported files, in the order of [cells]. */
val AUTO_ISF_HISTORY_EXPORT_HEADER = listOf(
    "Time", "BGL", "UKF", "UKFd5", "UKFd15", "Final", "acce", "bg", "pp", "dura",
    "Accel", "Delta", "Short", "Long", "IOB", "IOd5", "IOBth", "SMB",
    "Target", "UCI", "SMBratio", "AccW", "ppW", "Slope", "COB", "TBR", "PBasal", "Notes", "MJ",
    "S5", "S15", "S30", "S60", "S180",
    // Last, so the columns of older exported files keep their places (2026-11-15)
    "Bolus"
)

/** Every cell of the row after the time, in screen order. */
fun AutoIsfHistoryRow.scrollingCells(): List<String> = listOf(
    glucose, ukf, ukfDelta5, ukfDelta15, finalIsf, acceIsf, bgIsf, ppIsf, duraIsf,
    acceleration, delta, shortDelta, longDelta, iob, iob5, iobTh, smb,
    target, uam, smbRatio, acceWeight, ppWeight, slope, cob, basal, profileBasal, note, mj,
    steps5, steps15, steps30, steps60, steps180
)

/** The whole row, time first, for the exported files. Bolus is last there, after the older columns. */
fun AutoIsfHistoryRow.cells(): List<String> = listOf(time) + scrollingCells() + bolus

/** The boluses that are not SMBs since the next older row and up to this row (rows newest first). */
private fun bolusesForRow(rows: List<AIV>, index: Int, boluses: List<Pair<Long, Double>>): Double {
    val until = rows[index].timestamp
    val from = rows.getOrNull(index + 1)?.timestamp ?: (until - 5 * 60_000L)
    return boluses.filter { it.first > from && it.first <= until }.sumOf { it.second }
}

/** Valid boluses that are not SMBs or priming, as (time, units), for [autoIsfHistoryRows]. */
fun List<BS>.nonSmbBoluses(): List<Pair<Long, Double>> =
    filter { it.isValid && it.amount > 0.0 && it.type != BS.Type.SMB && it.type != BS.Type.PRIMING }.map { it.timestamp to it.amount }

/**
 * The MJ state a care-portal note sets, or null when the note is not about MJ.
 * Used by the table and by the graph 1 MJ= text, so both read the notes the same way.
 */
fun mjStateFromNote(text: String): String? = when {
    text == "MJ" || text == "MJ active" || text == "MJsAc"                               -> "MJa"
    text == "MJ2" || text == "MJs2"                                                      -> "MJ2"
    text == "MJ3" || text == "MJs3" || text == "MoreMJ"                                  -> "MJ3"
    text == "A1" || text == "NOMJremains" || text == "MJsNO" || text.startsWith("MJoff") -> "NOM"
    else                                                                                 -> null
}

/** MJ state at [time]: the newest MJ note at or before it, within 24 hours. NOM when there is none. */
internal fun mjAt(time: Long, notes: List<Pair<Long, String>>): String =
    notes.filter { it.first <= time && it.first > time - MJ_NOTE_WINDOW_MS }
        .sortedByDescending { it.first }
        .firstNotNullOfOrNull { mjStateFromNote(it.second) } ?: "NOM"

/** The largest of the four factors, using the same order as the graph colours. */
fun dominantAutoIsfFactor(acce: Double, bg: Double, pp: Double, dura: Double): AutoIsfFactor =
    when (dominantIsf(acce, bg, pp, dura)) {
        DominantIsf.ACCE -> AutoIsfFactor.ACCE
        DominantIsf.BG   -> AutoIsfFactor.BG
        DominantIsf.PP   -> AutoIsfFactor.PP
        DominantIsf.DURA -> AutoIsfFactor.DURA
        DominantIsf.NONE -> AutoIsfFactor.NONE
    }

/** A factor of 1.0 did not change the ISF, so the cell shows a dash. */
fun autoIsfAdjustmentText(value: Double, format2: (Double) -> String): String =
    if (value == 1.0) "--" else format2(value)

/** A zero insulin amount is shown as a dash. */
fun autoIsfAmountText(value: Double, format2: (Double) -> String): String =
    if (value == 0.0) "--" else format2(value)

/** Change in IOB over about 5 minutes. "--" when no earlier row is close enough. */
fun iob5MinChangeText(rows: List<AIV>, index: Int, format2: (Double) -> String): String {
    val row = rows[index]
    val target = row.timestamp - 5 * 60_000L
    val prior = rows.minByOrNull { abs(it.timestamp - target) } ?: return "--"
    if (abs(prior.timestamp - target) > 3 * 60_000L) return "--"
    return format2(row.iob - prior.iob)
}

/** UKF change over about 5 or 15 minutes, in the same units as the other deltas. */
fun ukfDeltaText(rows: List<AIV>, index: Int, minutesBack: Int, deltaText: (Double) -> String): String {
    val row = rows[index]
    if (row.ukfRawBgl <= 0.0) return "--"
    val target = row.timestamp - minutesBack * 60_000L
    val prior = rows.minByOrNull { abs(it.timestamp - target) } ?: return "--"
    if (abs(prior.timestamp - target) > 3 * 60_000L || prior.ukfRawBgl <= 0.0) return "--"
    val actualMin = (row.timestamp - prior.timestamp) / 60_000.0
    if (actualMin <= 0.0) return "--"
    return deltaText((row.ukfRawBgl - prior.ukfRawBgl) / actualMin * 5.0)
}

/**
 * Hypo prediction for one row, in mg/dL (2026-11-20, per explicit request), the 3426 app's HP and HP2 formula:
 * (glucose - IOB) + 0.25 x short delta + 0.25 x [delta5Mgdl] + COB/12, from the row's own values, IOB in U and COB in g turned
 * into mg/dL. HP passes the raw (Libre) 5 minute change ([rawDelta5Mgdl]), HP2 the UKF one ([ukfDeltaMgdl]). COB is the loop
 * phone's, as stored with the row (also on a client).
 */
fun hypoPredictionMgdl(row: AIV, delta5Mgdl: Double): Double {
    val mgdlPerMmol = 18.0182
    return row.glucose - row.iob * mgdlPerMmol + 0.25 * row.shortAvgDelta + 0.25 * delta5Mgdl + row.cob / 12.0 * mgdlPerMmol
}

/** The notes written after the next older row and up to this row (rows newest first). */
internal fun carePortalNotesForRow(rows: List<AIV>, index: Int, notes: List<Pair<Long, String>>): List<Pair<Long, String>> {
    val until = rows[index].timestamp
    val from = rows.getOrNull(index + 1)?.timestamp ?: (until - 5 * 60_000L)
    return notes.filter { it.first > from && it.first <= until }
}

fun List<AIV>.autoIsfHistoryRows(
    timeText: (Long) -> String,
    glucoseText: (Double) -> String,
    deltaText: (Double) -> String,
    format2: (Double) -> String,
    steps: List<SC> = emptyList(),
    /** Care-portal notes (time, text). A row without its own note shows the ones written since the row before it. */
    notes: List<Pair<Long, String>> = emptyList(),
    fromLivePhone: Boolean = false,
    ownDevice: String = "",
    smsTexts: Map<Long, String> = emptyMap(),
    profileBasalAt: (Long) -> Double? = { null },
    /** Boluses that are not SMBs, as (time, units); see [nonSmbBoluses]. */
    boluses: List<Pair<Long, Double>> = emptyList(),
    // For the screen-only columns (2026-11-20); empty or null leaves them "--"
    format1: (Double) -> String = format2,
    /** Readings as (time, raw value), newest first, for HP. */
    rawReadings: List<Pair<Long, Double?>> = emptyList(),
    /** SMB times, for SMBi5. */
    smbTimes: List<Long> = emptyList(),
    /** Stored loop results, for FR, MildBst, dAcc and Req. */
    loopResults: List<LoopResultText> = emptyList(),
    /** (COB, absorption per 5 minutes) at a time, for COBt and carbAbs; null where none is worked out (a client). */
    autosensAt: ((Long) -> Pair<Double, Double>?)? = null
): List<AutoIsfHistoryRow> {
    val cobT = autosensAt?.let { calculatedCobT(this, it) } ?: emptyMap()
    return mapIndexed { index, row -> historyRow(index, row, timeText, glucoseText, deltaText, format2, steps, notes, fromLivePhone,
        ownDevice, smsTexts, profileBasalAt, boluses, format1, rawReadings, smbTimes, loopResults, autosensAt, cobT) }
}

private fun List<AIV>.historyRow(
    index: Int,
    row: AIV,
    timeText: (Long) -> String,
    glucoseText: (Double) -> String,
    deltaText: (Double) -> String,
    format2: (Double) -> String,
    steps: List<SC>,
    notes: List<Pair<Long, String>>,
    fromLivePhone: Boolean,
    ownDevice: String,
    smsTexts: Map<Long, String>,
    profileBasalAt: (Long) -> Double?,
    boluses: List<Pair<Long, Double>>,
    format1: (Double) -> String,
    rawReadings: List<Pair<Long, Double?>>,
    smbTimes: List<Long>,
    loopResults: List<LoopResultText>,
    autosensAt: ((Long) -> Pair<Double, Double>?)?,
    cobT: Map<Long, Double>,
): AutoIsfHistoryRow {
    val loop = nearestLoopResult(loopResults, row.timestamp)
    val autosens = autosensAt?.invoke(row.timestamp)
    val bolus = bolusesForRow(this, index, boluses)
    val factor = dominantAutoIsfFactor(row.acceIsf, row.bgIsf, row.ppIsf, row.duraIsf)
    val sample = LiveSteps.sampleFor(row.timestamp, steps, fromLivePhone, ownDevice)
    val rowNotes = carePortalNotesForRow(this, index, notes)
    return AutoIsfHistoryRow(
        time = timeText(row.timestamp),
        glucose = glucoseText(row.glucose),
        ukf = if (row.ukfRawBgl == 0.0) "--" else glucoseText(row.ukfRawBgl),
        ukfDelta5 = ukfDeltaText(this, index, 5, deltaText),
        ukfDelta15 = ukfDeltaText(this, index, 15, deltaText),
        finalIsf = format2(row.finalIsf),
        finalFactor = factor,
        acceIsf = autoIsfAdjustmentText(row.acceIsf, format2),
        bgIsf = autoIsfAdjustmentText(row.bgIsf, format2),
        ppIsf = autoIsfAdjustmentText(row.ppIsf, format2),
        duraIsf = autoIsfAdjustmentText(row.duraIsf, format2),
        acceleration = format2(row.bgAcceleration),
        delta = deltaText(row.delta),
        shortDelta = deltaText(row.shortAvgDelta),
        longDelta = deltaText(row.longAvgDelta),
        iob = autoIsfAmountText(row.iob, format2),
        iob5 = iob5MinChangeText(this, index, format2),
        iobTh = autoIsfAmountText(row.iobThEffective, format2),
        smb = autoIsfAmountText(row.smbDelivered, format2),
        target = autoIsfAmountText(row.targetMgdl, glucoseText),
        uam = autoIsfAmountText(row.uamCarbImpact, format2),
        smbRatio = autoIsfAmountText(row.smbDeliveryRatio, format2),
        acceWeight = autoIsfAmountText(row.acceIsfWeight, format2),
        ppWeight = autoIsfAmountText(row.ppIsfWeight, format2),
        slope = autoIsfAmountText(row.fslCalSlope, format2),
        cob = autoIsfAmountText(row.cob, format2),
        basal = autoIsfAmountText(row.basal, format2),
        profileBasal = profileBasalAt(row.timestamp)?.let { format2(it) } ?: "--",
        note = row.note.ifBlank { rowNotes.joinToString(" ") { it.second } }.ifBlank { "--" },
        mj = mjAt(row.timestamp, notes),
        smbFactor = if (row.smbDelivered == 0.0) AutoIsfFactor.NONE else factor,
        hasSmb = row.smbDelivered > 0.0,
        steps5 = sample?.steps5min?.toString() ?: "--",
        steps15 = sample?.steps15min?.toString() ?: "--",
        steps30 = sample?.steps30min?.toString() ?: "--",
        steps60 = sample?.steps60min?.toString() ?: "--",
        steps180 = sample?.steps180min?.toString() ?: "--",
        fullNote = rowNotes.asSequence().map { it.first }.distinct().mapNotNull { smsTexts[it] }.joinToString("\n"),
        bolus = autoIsfAmountText(bolus, format2),
        hasBolus = bolus > 0.0,
        hp = rawDelta5Mgdl(row.timestamp, rawReadings)?.let { glucoseText(hypoPredictionMgdl(row, it)) } ?: "--",
        hp2 = ukfDeltaMgdl(this, index, 5)?.let { glucoseText(hypoPredictionMgdl(row, it)) } ?: "--",
        fastRise = loop?.let { fastRiseText(it.reason) } ?: "--",
        mildBst = loop?.let { mildBstValue(it.reason) }?.let(format2) ?: "--",
        smbI5 = smbInterval5Sec(row.timestamp, smbTimes)?.toString() ?: "--",
        deltaAcce = loop?.let { deltaAcceValue(it.reason) }?.let(format1) ?: "--",
        deltaAcceUkf = deltaAcceUkfPercent(this, index)?.let(format1) ?: "--",
        insulinReq = loop?.insulinReq?.let { autoIsfAmountText(it, format2) } ?: "--",
        cobT = cobT[row.timestamp]?.let(format1) ?: "--",
        carbAbs = autosens?.second?.let(format2) ?: "--"
    )
}

/**
 * The table rows of the last [AUTO_ISF_HISTORY_WINDOW_MS], newest first, in the user's units.
 * The screen and the exported files both use this, so they show the same thing.
 */
suspend fun loadAutoIsfHistoryRows(
    now: Long,
    persistenceLayer: PersistenceLayer,
    profileFunction: ProfileFunction,
    profileUtil: ProfileUtil,
    dateUtil: DateUtil,
    decimalFormatter: DecimalFormatter,
    config: Config,
    preferences: Preferences,
    activePlugin: ActivePlugin,
    includeFullNotes: Boolean = false,
    /**
     * Also fill the screen-only columns (HP, HP2, FR, MildBst, SMBi5, dAcc, dAccUkf, Req, COBt, carbAbs; 2026-11-20). The exported
     * files do not show them, so they skip the extra reads. COBt and carbAbs need [iobCobCalculator] and are left out on a client,
     * which does not work out carb absorption itself.
     */
    includeScreenColumns: Boolean = false,
    iobCobCalculator: IobCobCalculator? = null
): List<AutoIsfHistoryRow> {
    val from = now - AUTO_ISF_HISTORY_WINDOW_MS
    val units = profileFunction.getUnits()
    // A client shows the loop phone's steps, which it keeps from the loop results (2026-10-21)
    val fromLivePhone = config.AAPSCLIENT || config.APS &&
        preferences.get(BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual) &&
        activePlugin.activePump.selectedActivePump() is VirtualPump
    val steps = persistenceLayer.getStepsCountFromTimeToTime(from, now)
    val aivRows = persistenceLayer.getAutoIsfValuesFromTimeToTime(from, now).sortedByDescending { it.timestamp }
    // The care-portal notes, for rows that carry none (a client's rows come from the loop phone's results), and for the
    // MJ column. They start a day earlier, so the first rows still find the MJ note that was in force.
    val notes = persistenceLayer.getTherapyEventDataFromTime(from - MJ_NOTE_WINDOW_MS, TE.Type.NOTE, true)
        .filter { it.isValid && !it.note.isNullOrBlank() }
        .map { it.timestamp to it.note.orEmpty().trim() }
    // Profile basal at each row's time (not the running/temp rate). Looked up here because the lookup is suspend.
    val profileBasal = aivRows.associate { it.timestamp to profileFunction.getProfile(it.timestamp)?.getBasal(it.timestamp) }
    val allBoluses = persistenceLayer.getBolusesFromTimeToTime(from - 10 * 60_000L, now, true)
    val boluses = allBoluses.nonSmbBoluses()
    // The screen-only columns' sources. A client keeps the loop phone's results from Nightscout, so the reason-text columns
    // work there too; it does not work out carb absorption, so COBt and carbAbs stay "--" there.
    val rawReadings = if (!includeScreenColumns) emptyList() else
        persistenceLayer.getBgReadingsDataFromTimeToTime(from - 10 * 60_000L, now, false).map { it.timestamp to it.noise?.takeIf { n -> n > 10.0 } }
    val smbTimes = if (!includeScreenColumns) emptyList() else
        allBoluses.filter { it.isValid && it.type == BS.Type.SMB && it.amount > 0.0 }.map { it.timestamp }
    val loopResults = if (!includeScreenColumns) emptyList() else
        persistenceLayer.getApsResults(from - 15 * 60_000L, now).map { result ->
            val insulinReq = runCatching { result.json()?.get("insulinReq")?.jsonPrimitive?.doubleOrNull }.getOrNull()
            LoopResultText(result.date, result.reason, insulinReq)
        }
    val ads = iobCobCalculator?.ads?.takeIf { includeScreenColumns && !config.AAPSCLIENT }
    val autosensAt: ((Long) -> Pair<Double, Double>?)? = ads?.let { store ->
        { time: Long -> store.getAutosensDataAtTime(time)?.let { it.cob to it.this5MinAbsorption } }
    }
    return aivRows.autoIsfHistoryRows(
        format1 = { decimalFormatter.to1Decimal(it) },
        rawReadings = rawReadings,
        smbTimes = smbTimes,
        loopResults = loopResults,
        autosensAt = autosensAt,
        boluses = boluses,
        profileBasalAt = { profileBasal[it] },
        timeText = { dateUtil.timeString(it) },
        glucoseText = { profileUtil.fromMgdlToStringInUnits(it, units) },
        deltaText = { profileUtil.fromMgdlToSignedStringInUnits(it, units) },
        format2 = { decimalFormatter.to2Decimal(it) },
        steps = steps,
        notes = notes,
        fromLivePhone = fromLivePhone,
        ownDevice = "openaps://${config.deviceModelForUpload}",
        smsTexts = if (includeFullNotes) NoteSmsTexts.all(preferences) else emptyMap()
    )
}
