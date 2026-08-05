package app.aaps.core.interfaces.overview

import android.widget.Button
import android.widget.ImageButton

interface OverviewMenus {
    enum class CharType {
        PRE,
        BG_PARAB,
        TREAT,
        BAS,
        ABS,
        IOB,
        COB,
        IOB_TH,
        DEV,
        BGI,
        SEN,
        VAR_SEN,
        ACT,
        CARB_ABS,
        DEVSLOPE,
        HR,
        STEPS,
        FIN_ISF,
        ACC_ISF,
        BG_ISF,
        PP_ISF,
        DUR_ISF,
        RAW_BG,
        // UKF-smoothed trace of the same raw/noise values as RAW_BG — independently selectable, own
        // checkbox next to RAW_BG in the chart menu. Appended at the end (not inserted) so existing
        // saved graph configs' CharType ordinals don't shift; NOTE: adding any new CharType still
        // trips OverviewMenusImpl.loadGraphConfig()'s "reset when new CharType added" size-mismatch
        // guard, resetting per-graph chart selections to defaults once on first load after this change.
        RAW_BG_SMOOTHED,
        // UAM Carb Impact (uci) -- deviation-derived BG-impact rate, mg/dL per 5min, from the AIV table.
        // Appended at the end for the same ordinal-stability reason as RAW_BG_SMOOTHED above (same
        // migration-reset caveat applies).
        UAM_CARB_IMPACT,
    }

    val setting: List<Array<Boolean>>
    fun loadGraphConfig()
    fun setupChartMenu(chartButton: ImageButton, scaleButton: Button)
    fun enabledTypes(graph: Int): String
    fun isEnabledIn(type: CharType): Int
    fun scaleString(rangeToDisplay: Int): String
    fun isActiveCharTypeData(graph: Int, m: Int): Boolean
    fun setPredictionsEnabled(enabled: Boolean)
}
