package app.aaps.ui.compose.overview

import app.aaps.core.data.model.AIV
import app.aaps.core.data.model.LiveSteps
import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.overview.graph.DominantIsf
import app.aaps.core.interfaces.overview.graph.dominantIsf
import kotlin.math.abs

/** How far back the history table looks. */
const val AUTO_ISF_HISTORY_WINDOW_MS = 6L * 60L * 60L * 1000L

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
    val note: String,
    val smbFactor: AutoIsfFactor,
    val hasSmb: Boolean,
    val steps5: String,
    val steps15: String,
    val steps30: String,
    val steps60: String,
    val steps180: String
)

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

fun List<AIV>.autoIsfHistoryRows(
    timeText: (Long) -> String,
    glucoseText: (Double) -> String,
    deltaText: (Double) -> String,
    format2: (Double) -> String,
    steps: List<SC> = emptyList(),
    fromLivePhone: Boolean = false,
    ownDevice: String = ""
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
        note = row.note.ifBlank { "--" },
        smbFactor = if (row.smbDelivered == 0.0) AutoIsfFactor.NONE else factor,
        hasSmb = row.smbDelivered > 0.0,
        steps5 = sample?.steps5min?.toString() ?: "--",
        steps15 = sample?.steps15min?.toString() ?: "--",
        steps30 = sample?.steps30min?.toString() ?: "--",
        steps60 = sample?.steps60min?.toString() ?: "--",
        steps180 = sample?.steps180min?.toString() ?: "--"
    )
}
