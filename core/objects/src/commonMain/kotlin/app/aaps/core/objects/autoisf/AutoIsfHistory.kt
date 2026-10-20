package app.aaps.core.objects.autoisf

import app.aaps.core.data.model.AIV
import app.aaps.core.data.model.LiveSteps
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
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
import kotlin.math.abs

// The table is here, not in :ui, so the exported files have the same columns as the screen (2026-10-21, per explicit
// request: the export had only 13 of them).

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
    val steps180: String
)

/** Column names for the exported files, in the order of [cells]. Same columns as the screen. */
val AUTO_ISF_HISTORY_EXPORT_HEADER = listOf(
    "Time", "BGL", "UKF", "UKFd5", "UKFd15", "Final", "acce", "bg", "pp", "dura",
    "Accel", "Delta", "Short", "Long", "IOB", "IOd5", "IOBth", "SMB",
    "Target", "UCI", "SMBratio", "AccW", "ppW", "Slope", "COB", "TBR", "PBasal", "Notes", "MJ",
    "S5", "S15", "S30", "S60", "S180"
)

/** Every cell of the row after the time, in screen order. */
fun AutoIsfHistoryRow.scrollingCells(): List<String> = listOf(
    glucose, ukf, ukfDelta5, ukfDelta15, finalIsf, acceIsf, bgIsf, ppIsf, duraIsf,
    acceleration, delta, shortDelta, longDelta, iob, iob5, iobTh, smb,
    target, uam, smbRatio, acceWeight, ppWeight, slope, cob, basal, profileBasal, note, mj,
    steps5, steps15, steps30, steps60, steps180
)

/** The whole row, time first, for the exported files. */
fun AutoIsfHistoryRow.cells(): List<String> = listOf(time) + scrollingCells()

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

/** The notes written after the next older row and up to this row (rows newest first). Empty when there are none. */
internal fun notesForRow(rows: List<AIV>, index: Int, notes: List<Pair<Long, String>>): String {
    if (notes.isEmpty()) return ""
    val until = rows[index].timestamp
    val from = rows.getOrNull(index + 1)?.timestamp ?: (until - 5 * 60_000L)
    return notes.filter { it.first > from && it.first <= until }.joinToString(" ") { it.second }
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
    profileBasalAt: (Long) -> Double? = { null }
): List<AutoIsfHistoryRow> = mapIndexed { index, row ->
    val factor = dominantAutoIsfFactor(row.acceIsf, row.bgIsf, row.ppIsf, row.duraIsf)
    val sample = LiveSteps.sampleFor(row.timestamp, steps, fromLivePhone, ownDevice)
    AutoIsfHistoryRow(
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
        note = row.note.ifBlank { notesForRow(this, index, notes) }.ifBlank { "--" },
        mj = mjAt(row.timestamp, notes),
        smbFactor = if (row.smbDelivered == 0.0) AutoIsfFactor.NONE else factor,
        hasSmb = row.smbDelivered > 0.0,
        steps5 = sample?.steps5min?.toString() ?: "--",
        steps15 = sample?.steps15min?.toString() ?: "--",
        steps30 = sample?.steps30min?.toString() ?: "--",
        steps60 = sample?.steps60min?.toString() ?: "--",
        steps180 = sample?.steps180min?.toString() ?: "--"
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
    activePlugin: ActivePlugin
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
    return aivRows.autoIsfHistoryRows(
        profileBasalAt = { profileBasal[it] },
        timeText = { dateUtil.timeString(it) },
        glucoseText = { profileUtil.fromMgdlToStringInUnits(it, units) },
        deltaText = { profileUtil.fromMgdlToSignedStringInUnits(it, units) },
        format2 = { decimalFormatter.to2Decimal(it) },
        steps = steps,
        notes = notes,
        fromLivePhone = fromLivePhone,
        ownDevice = "openaps://${config.deviceModelForUpload}"
    )
}
