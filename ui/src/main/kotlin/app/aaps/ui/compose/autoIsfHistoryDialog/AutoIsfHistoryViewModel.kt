package app.aaps.ui.compose.autoIsfHistoryDialog

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.AIV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.DateUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs

/** Backing ViewModel for [AutoIsfHistoryScreen] — ports the source fork's AutoISFHistoryDialog
 *  (Fragment/View based) formatting and dominant-ISF-adaptation coloring logic to Compose.
 *  Queries a fixed 4-hour lookback window, same as the source dialog. */
@HiltViewModel
@Stable
class AutoIsfHistoryViewModel @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val profileUtil: ProfileUtil
) : ViewModel() {

    private val _uiState = MutableStateFlow(AutoIsfHistoryUiState())
    val uiState: StateFlow<AutoIsfHistoryUiState> = _uiState.asStateFlow()

    private val df2 = DecimalFormat("0.00")

    init {
        loadHistory()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            val now = dateUtil.now()
            val fourHoursAgo = now - TimeUnit.HOURS.toMillis(4)
            val records = persistenceLayer.getAutoIsfValuesFromTimeToTime(fourHoursAgo, now).sortedByDescending { it.timestamp }
            val apsResults = persistenceLayer.getApsResults(fourHoursAgo, now)
            val stepsCountList = persistenceLayer.getStepsCountFromTimeToTime(fourHoursAgo, now)
            val isMmol = profileUtil.units == GlucoseUnit.MMOL
            val rows = records.map { toRow(it, apsResults, stepsCountList, isMmol) }
            _uiState.update { it.copy(rows = rows, isLoading = false) }
        }
    }

    private fun toRow(r: AIV, apsResults: List<APSResult>, stepsCountList: List<SC>, isMmol: Boolean): AutoIsfRow {
        val sc = stepsAt(r.timestamp, stepsCountList)
        val dominant = dominantIsfType(r)
        return AutoIsfRow(
            time = dateUtil.timeString(r.timestamp),
            bgl = formatBg(r.glucose, isMmol),
            finalRatio = df2.format(r.finalIsf),
            finalDominant = dominant,
            acce = adjStr(r.acceIsf),
            bg = adjStr(r.bgIsf),
            pp = adjStr(r.ppIsf),
            dura = adjStr(r.duraIsf),
            smb = insulinStr(r.smbDelivered),
            smbDominant = if (r.smbDelivered == 0.0) DominantIsfType.NONE else dominant,
            fastRise = fastRiseStr(r.timestamp, apsResults),
            iobTh = df2.format(r.iobThEffective),
            bgAcceleration = df2.format(r.bgAcceleration),
            delta = formatBg(r.delta, isMmol),
            shortAvgDelta = formatBg(r.shortAvgDelta, isMmol),
            insulinReq = insulinStr(r.insulinReq),
            tbrRate = insulinStr(r.tbrRate),
            steps5min = sc?.steps5min?.toString() ?: "--",
            steps15min = sc?.steps15min?.toString() ?: "--",
            steps30min = sc?.steps30min?.toString() ?: "--",
            steps60min = sc?.steps60min?.toString() ?: "--",
            steps180min = sc?.steps180min?.toString() ?: "--"
        )
    }

    // BGL/Delta/SDelta are stored in mg/dL on AIV; convert to the user's configured display unit
    // rather than assuming mmol/L (unlike the source dialog, which always divided by a fixed
    // mg/dL-to-mmol/L constant).
    private fun formatBg(mgdl: Double, isMmol: Boolean): String {
        val value = profileUtil.fromMgdlToUnits(mgdl)
        return if (isMmol) DecimalFormat("0.0").format(value) else DecimalFormat("0").format(value)
    }

    /** Show "--" for neutral (1.0) adjustment values, matching the source dialog's display. */
    private fun adjStr(v: Double): String = if (v == 1.0) "--" else df2.format(v)

    /** Show "--" for zero insulin values. */
    private fun insulinStr(v: Double): String = if (v == 0.0) "--" else df2.format(v)

    /** Which AutoISF adaptation type (acce/bg/pp/dura) deviates furthest from neutral (1.0), or
     *  NONE if all deviations are within 1% of neutral. */
    private fun dominantIsfType(r: AIV): DominantIsfType {
        val acce = abs(r.acceIsf - 1.0)
        val bg = abs(r.bgIsf - 1.0)
        val pp = abs(r.ppIsf - 1.0)
        val dura = abs(r.duraIsf - 1.0)
        val maxDev = maxOf(maxOf(acce, bg), maxOf(pp, dura))
        if (maxDev <= 0.01) return DominantIsfType.NONE
        return when {
            acce >= maxDev -> DominantIsfType.ACCE
            bg >= maxDev   -> DominantIsfType.BG
            pp >= maxDev   -> DominantIsfType.PP
            else           -> DominantIsfType.DURA
        }
    }

    // Nearest StepsCount record within 15 min of `timestamp`, or null if none close enough.
    private fun stepsAt(timestamp: Long, stepsCountList: List<SC>): SC? {
        val nearest = stepsCountList.minByOrNull { abs(it.timestamp - timestamp) } ?: return null
        if (abs(nearest.timestamp - timestamp) >= TimeUnit.MINUTES.toMillis(15)) return null
        return nearest
    }

    // "...for early/mild fast rise <value> ..." — the fast-rise-specific ratio itself (e.g. 0.507), as
    // opposed to the "microBolus * <factor>" multiplier below, which is a coarser single-digit fallback.
    private val fastRiseFullRegex = Regex("""fast\s*rise\s*([0-9]+\.[0-9]+)""", RegexOption.IGNORE_CASE)

    // Same reason-text pattern used for the graph's fast-rise SMB label:
    // "<threshold>  = microBolus  * <factor> ; microBolus = <result>".
    private val fastRiseFactorRegex = Regex("""=\s*microBolus\s*\*\s*([0-9.]+)\s*;""")

    /** Fast-rise value from the nearest APSResult within 15 min. Prefers the full ratio following
     *  "fast rise" (e.g. 0.507 -> "0507", decimal point dropped but leading digit kept — 4 characters);
     *  falls back to the coarser single-digit "microBolus * factor" (e.g. 0.4 -> "4") if the full value
     *  isn't present in that branch's reason text; "--" if neither fired. */
    private fun fastRiseStr(timestamp: Long, apsResults: List<APSResult>): String {
        val nearest = apsResults.minByOrNull { abs(it.date - timestamp) } ?: return "--"
        if (abs(nearest.date - timestamp) >= TimeUnit.MINUTES.toMillis(15)) return "--"
        val full = fastRiseFullRegex.find(nearest.reason)?.groupValues?.get(1)?.toDoubleOrNull()
        if (full != null) return String.format(Locale.US, "%.3f", full).replace(".", "")
        val factor = fastRiseFactorRegex.find(nearest.reason)?.groupValues?.get(1)?.toDoubleOrNull() ?: return "--"
        return Math.round(factor * 10).toString()
    }
}
