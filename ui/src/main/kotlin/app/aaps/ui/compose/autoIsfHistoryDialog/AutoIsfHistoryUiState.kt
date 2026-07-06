package app.aaps.ui.compose.autoIsfHistoryDialog

import androidx.compose.runtime.Immutable

/** Which AutoISF adaptation factor deviates furthest from neutral (1.0) for a given row —
 *  drives the Final/SMB cell color. Resolved to an actual theme [androidx.compose.ui.graphics.Color]
 *  in the Composable via [app.aaps.core.ui.compose.GeneralColors], not here, since the ViewModel
 *  has no access to composition-local theme values. */
enum class DominantIsfType {
    ACCE, BG, PP, DURA, NONE
}

/** One formatted row of the AutoISF history table — see AutoIsfHistoryViewModel for the exact
 *  source-value mapping and formatting rules (ported from the source fork's AutoISFHistoryDialog). */
@Immutable
data class AutoIsfRow(
    val time: String,
    val bgl: String,
    val finalRatio: String,
    val finalDominant: DominantIsfType,
    val acce: String,
    val bg: String,
    val pp: String,
    val dura: String,
    val smb: String,
    val smbDominant: DominantIsfType,
    val fastRise: String,
    val iobTh: String,
    val bgAcceleration: String,
    val delta: String,
    val shortAvgDelta: String,
    val insulinReq: String,
    val tbrRate: String,
    val steps5min: String,
    val steps15min: String,
    val steps30min: String,
    val steps60min: String,
    val steps180min: String
)

@Immutable
data class AutoIsfHistoryUiState(
    val rows: List<AutoIsfRow> = emptyList(),
    val isLoading: Boolean = true
)
