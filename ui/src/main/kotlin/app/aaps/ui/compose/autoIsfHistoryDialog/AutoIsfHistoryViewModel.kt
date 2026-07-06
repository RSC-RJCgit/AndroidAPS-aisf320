package app.aaps.ui.compose.autoIsfHistoryDialog

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.AIV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.storage.Storage
import app.aaps.core.interfaces.utils.DateUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.SimpleDateFormat
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
    private val profileUtil: ProfileUtil,
    private val fileListProvider: FileListProvider,
    private val storage: Storage,
    private val aapsLogger: AAPSLogger,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AutoIsfHistoryUiState())
    val uiState: StateFlow<AutoIsfHistoryUiState> = _uiState.asStateFlow()

    sealed class SideEffect {
        data class ExportCompleted(val fileName: String) : SideEffect()
        data class ExportFailed(val message: String) : SideEffect()
    }

    private val _sideEffect = MutableSharedFlow<SideEffect>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val sideEffect: SharedFlow<SideEffect> = _sideEffect.asSharedFlow()

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

            // Automatic CSV export on open, matching the source dialog's behavior — no separate
            // export button. Exported even when empty (header-only file) so the "nothing to show"
            // case still leaves a record of when the table was opened.
            exportCsv(records, apsResults, stepsCountList, isMmol, now)
        }
    }

    private suspend fun exportCsv(records: List<AIV>, apsResults: List<APSResult>, stepsCountList: List<SC>, isMmol: Boolean, now: Long) {
        withContext(Dispatchers.IO) {
            try {
                val fileName = "AutoISF_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now) + ".csv"
                val dir = fileListProvider.ensureExportDirExists() ?: throw java.io.FileNotFoundException("Export directory not accessible")
                val newFile = dir.createFile("text/csv", fileName) ?: throw java.io.FileNotFoundException("Could not create export file")
                val csv = buildCsv(records, apsResults, stepsCountList, isMmol)
                storage.putFileContents(context.contentResolver, newFile, csv)
                aapsLogger.debug(LTag.UI, "AutoISF history exported to $fileName")
                _sideEffect.emit(SideEffect.ExportCompleted(fileName))
            } catch (e: Exception) {
                aapsLogger.error(LTag.UI, "AutoISF CSV export failed", e)
                _sideEffect.emit(SideEffect.ExportFailed(e.message ?: "export failed"))
            }
        }
    }

    // Mirrors the table's column set, but always writes the raw formatted value (never "--")
    // even for neutral/zero cells — more useful for spreadsheet/graphing post-processing than the
    // table's "--" placeholders.
    private fun buildCsv(records: List<AIV>, apsResults: List<APSResult>, stepsCountList: List<SC>, isMmol: Boolean): String {
        val sb = StringBuilder("Time,BGL,Final,acce,bg,pp,dura,SMB,FastRise,iobTH,acceBG,Delta,SDelta,Req,TBR,S5,S15,S30,S60,S180\n")
        for (r in records) {
            val sc = stepsAt(r.timestamp, stepsCountList)
            sb.append(dateUtil.timeString(r.timestamp)).append(',')
                .append(formatBg(r.glucose, isMmol)).append(',')
                .append(df2.format(r.finalIsf)).append(',')
                .append(df2.format(r.acceIsf)).append(',')
                .append(df2.format(r.bgIsf)).append(',')
                .append(df2.format(r.ppIsf)).append(',')
                .append(df2.format(r.duraIsf)).append(',')
                .append(df2.format(r.smbDelivered)).append(',')
                .append(fastRiseStr(r.timestamp, apsResults)).append(',')
                .append(df2.format(r.iobThEffective)).append(',')
                .append(df2.format(r.bgAcceleration)).append(',')
                .append(formatBg(r.delta, isMmol)).append(',')
                .append(formatBg(r.shortAvgDelta, isMmol)).append(',')
                .append(df2.format(r.insulinReq)).append(',')
                .append(df2.format(r.tbrRate)).append(',')
                .append(sc?.steps5min ?: "").append(',')
                .append(sc?.steps15min ?: "").append(',')
                .append(sc?.steps30min ?: "").append(',')
                .append(sc?.steps60min ?: "").append(',')
                .append(sc?.steps180min ?: "").append('\n')
        }
        return sb.toString()
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
