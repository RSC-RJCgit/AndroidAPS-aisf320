package app.aaps.ui.compose.overview

import app.aaps.core.data.model.AIV
import app.aaps.core.interfaces.overview.graph.DominantIsf
import app.aaps.core.interfaces.overview.graph.dominantIsf

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
    val smb: String,
    val smbFactor: AutoIsfFactor,
    val hasSmb: Boolean
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

fun List<AIV>.autoIsfHistoryRows(
    timeText: (Long) -> String,
    glucoseText: (Double) -> String,
    deltaText: (Double) -> String,
    format2: (Double) -> String
): List<AutoIsfHistoryRow> = map { row ->
    val factor = dominantAutoIsfFactor(row.acceIsf, row.bgIsf, row.ppIsf, row.duraIsf)
    AutoIsfHistoryRow(
        time = timeText(row.timestamp),
        glucose = glucoseText(row.glucose),
        ukf = if (row.ukfRawBgl == 0.0) "--" else glucoseText(row.ukfRawBgl),
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
        smb = autoIsfAmountText(row.smbDelivered, format2),
        smbFactor = if (row.smbDelivered == 0.0) AutoIsfFactor.NONE else factor,
        hasSmb = row.smbDelivered > 0.0
    )
}
