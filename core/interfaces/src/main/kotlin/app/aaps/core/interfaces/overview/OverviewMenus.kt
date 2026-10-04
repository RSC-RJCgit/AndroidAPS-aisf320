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
        // UAM Carb Impact (uci) -- deviation-derived carbs-equivalent, grams/5min, from the AIV table.
        // Appended at the end for the same ordinal-stability reason as RAW_BG_SMOOTHED above (same
        // migration-reset caveat applies).
        UAM_CARB_IMPACT,
        // Combined Carbs -- carbAbsorptionSeries + uamCarbImpactSeries summed at matching bucket
        // timestamps. Same ordinal-stability/migration-reset notes as above.
        COMBINED_CARBS,
        // Per-AIV-cycle lines: SMB delivered (0..1U), acce ISF weight (0..1), pp ISF weight (0..0.20), profile
        // basal (0..30% of max IOB). Since 2026-10-04 they are always drawn in graph5's top band and these menu
        // entries are hidden (kept so saved arrays stay the same size/order). Appended at the end for ordinal
        // stability; OverviewMenusImpl.loadGraphConfig() pads older saved configs instead of resetting them.
        SMB_DEL,
        ACCE_WT,
        PP_WT,
        PROFILE_BASAL,
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
