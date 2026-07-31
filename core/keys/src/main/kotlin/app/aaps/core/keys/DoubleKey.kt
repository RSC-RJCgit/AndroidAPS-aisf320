package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey

enum class DoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val min: Double,
    override val max: Double,
    override val defaultedBySM: Boolean = false,
    override val calculatedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true
) : DoublePreferenceKey {

    OverviewInsulinButtonIncrement1("insulin_button_increment_1", 0.5, -5.0, 5.0, defaultedBySM = true, dependency = BooleanKey.OverviewShowInsulinButton),
    OverviewInsulinButtonIncrement2("insulin_button_increment_2", 1.0, -5.0, 5.0, defaultedBySM = true, dependency = BooleanKey.OverviewShowInsulinButton),
    OverviewInsulinButtonIncrement3("insulin_button_increment_3", 2.0, -5.0, 5.0, defaultedBySM = true, dependency = BooleanKey.OverviewShowInsulinButton),
    ActionsFillButton1("fill_button1", 0.3, 0.05, 20.0, defaultedBySM = true, hideParentScreenIfHidden = true),
    ActionsFillButton2("fill_button2", 0.0, 0.05, 20.0, defaultedBySM = true),
    ActionsFillButton3("fill_button3", 0.0, 0.05, 20.0, defaultedBySM = true),
    SafetyMaxBolus("treatmentssafety_maxbolus", 3.0, 0.1, 60.0),
    ApsMaxBasal("openapsma_max_basal", 1.0, 0.1, 25.0, defaultedBySM = true, calculatedBySM = true),
    ApsSmbMaxIob("openapsmb_max_iob", 3.0, 0.0, 70.0, defaultedBySM = true, calculatedBySM = true),
    ApsAmaMaxIob("openapsma_max_iob", 1.5, 0.0, 25.0, defaultedBySM = true, calculatedBySM = true),
    ApsMaxDailyMultiplier("openapsama_max_daily_safety_multiplier", 3.0, 1.0, 10.0, defaultedBySM = true),
    ApsMaxCurrentBasalMultiplier("openapsama_current_basal_safety_multiplier", 4.0, 1.0, 10.0, defaultedBySM = true),
    ApsAmaBolusSnoozeDivisor("bolussnooze_dia_divisor", 2.0, 1.0, 10.0, defaultedBySM = true),
    ApsAmaMin5MinCarbsImpact("openapsama_min_5m_carbimpact", 3.0, 1.0, 12.0, defaultedBySM = true),
    ApsSmbMin5MinCarbsImpact("openaps_smb_min_5m_carbimpact", 8.0, 1.0, 12.0, defaultedBySM = true),
    AbsorptionCutOff("absorption_cutoff", 6.0, 4.0, 10.0),
    AbsorptionMaxTime("absorption_maxtime", 6.0, 4.0, 10.0),
    AutosensMin("autosens_min", 0.7, 0.1, 1.0, defaultedBySM = true, hideParentScreenIfHidden = true),
    AutosensMax("autosens_max", 1.2, 0.5, 3.0, defaultedBySM = true),
    ApsAutoIsfMin("autoISF_min", 1.0, 0.3, 1.0, defaultedBySM = true),
    ApsAutoIsfMax("autoISF_max", 1.0, 1.0, 3.0, defaultedBySM = true),
    ApsAutoIsfBgAccelWeight("bgAccel_ISF_weight", 0.0, 0.0, 1.0, defaultedBySM = true),
    // The user's own configured "normal" bgAccel_ISF_weight (currently 0.70). See ApsAutoIsfPpWeightNormal
    // below for the reasoning; NOT yet wired into any restore call — see conversation before assuming
    // any of the existing hardcoded 0.70/0.71 boost-tier literals in OpenAPSAutoISFPlugin.kt should
    // read this back, since those looked like deliberate per-automation targets, not baseline restores.
    ApsAutoIsfBgAccelWeightNormal("autoisf_bgaccel_isf_weight_normal", 0.70, 0.0, 1.0, defaultedBySM = true),
    ApsAutoIsfBgBrakeWeight("bgBrake_ISF_weight", 0.0, 0.0, 1.0, defaultedBySM = true),
    ApsAutoIsfLowBgWeight("lower_ISFrange_weight", 0.0, 0.0, 2.0, defaultedBySM = true),
    ApsAutoIsfHighBgWeight("higher_ISFrange_weight", 0.0, 0.0, 2.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatioBgRange("openapsama_smb_delivery_ratio_bg_range", 0.0, 0.0, 100.0, defaultedBySM = true),
    ApsAutoIsfPpWeight("pp_ISF_weight", 0.0, 0.0, 0.15, defaultedBySM = true),
    // The user's own configured "normal" pp_ISF_weight (currently 0.08) — what the boost/recovery
    // automations restore ApsAutoIsfPpWeight to once a boost window ends. Same reasoning as
    // ApsAutoIsfSmbDeliveryBaseline: a live, user-tunable preference rather than a hardcoded literal.
    ApsAutoIsfPpWeightNormal("autoisf_pp_isf_weight_normal", 0.08, 0.0, 0.15, defaultedBySM = true),
    // The user's own configured "boosted" pp_ISF_weight (default 0.15, matching the value this replaces)
    // — what the fast-rise boost automations (BolusGiven, BolusGivenMild, High6PP, HighOldPod,
    // PodChangeHighPP130, OldPod2, RecentPod) set ApsAutoIsfPpWeight to while boosting. Was a hardcoded
    // 0.15 literal in all 7 places; now a live, user-tunable preference instead.
    ApsAutoIsfPpWeightHigh("autoisf_pp_isf_weight_high", 0.15, 0.0, 0.15, defaultedBySM = true),
    ApsAutoIsfDuraWeight("dura_ISF_weight", 0.0, 0.0, 3.0, defaultedBySM = true),
    // The user's own configured "normal" dura_ISF_weight (currently 1.2). Nothing in
    // OpenAPSAutoISFPlugin.kt currently writes ApsAutoIsfDuraWeight at all (read-only, via the
    // dura_ISF_weight getter) — this exists for parity with acce/pp's own *Normal keys, ready if a
    // restore-style write is ever added later.
    ApsAutoIsfDuraWeightNormal("autoisf_dura_isf_weight_normal", 1.2, 0.0, 3.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatio("openapsama_smb_delivery_ratio", 0.2, 0.1, 1.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatioMin("openapsama_smb_delivery_ratio_min", 0.5, 0.1, 1.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatioMax("openapsama_smb_delivery_ratio_max", 0.5, 0.5, 1.0, defaultedBySM = true),
    ApsAutoIsfSmbMaxRangeExtension("openapsama_smb_max_range_extension", 1.0, 1.0, 5.0, defaultedBySM = true),
    // Simple flat BG-above-target offset (mmol/L) that fully replaces the complex varOffset derivation
    // when ApsAutoIsfSmbOffsetOverrideEnabled is on. Max 2.0 mmol (36 mg/dL) matches the original
    // varOffset hard clamp (min(36.0, varOffset)) in DetermineBasalAutoISF.kt, so this can never exceed
    // what the old mechanism itself was capable of.
    ApsAutoIsfSmbOffsetOverride("autoisf_smb_offset_override", 0.5, 0.0, 2.0, defaultedBySM = true, dependency = BooleanKey.ApsAutoIsfSmbOffsetOverrideEnabled),
    // Resting SMB delivery ratio the custom automations (DelOff and the recovery/protective autos)
    // restore to once a boost window ends. Deliberately separate from ApsAutoIsfSmbDeliveryRatio, which
    // those same autos also write into transiently while a boost is active (bg3/mild write boosted
    // values there, then read this key back to know what to restore to) — reusing one preference for
    // both would lose the user's configured baseline the moment any boost overwrote it.
    ApsAutoIsfSmbDeliveryBaseline("autoisf_smb_delivery_baseline", 0.14, 0.1, 0.5, defaultedBySM = true),
    // Base ratio for BolusGivenMild's boost — its BGL tiers (<7.5mmol / <9.0mmol / else) apply as
    // relative bumps (+0.05 / +0.02 / +0) on top of this value, so raising or lowering it shifts all
    // three tiers together. BolusGiven bg3's own ("strong") boost ratio is derived from this value
    // + 0.03, not set independently — see ApsAutoIsfBoostAutomationsEnabled for the on/off toggle.
    ApsAutoIsfMildBoostRatio("autoisf_mild_boost_ratio", 0.20, 0.1, 0.5, defaultedBySM = true),
    FslCalOffset("fslCal_Offset", 0.0, -50.0, 50.0, defaultedBySM = true),      //dependency = BooleanKey.ApsCalibrationTrigger),
    FslCalSlope("fslCal_Slope", 1.0, 0.5, 1.5, defaultedBySM = true),           //dependency = BooleanKey.ApsCalibrationTrigger),
    FslSmoothAlpha("fsl_exp1_factor", 0.3, 0.1, 1.0, defaultedBySM = true),
    //FslSmoothCorrection("fsl_exp1_correction", 0.0, 0.0, 1.0, defaultedBySM = true),
    FslLastRaw("fsl_last_raw", -1.0, 40.0, 400.0, defaultedBySM = true),
    FslLastSmooth("fsl_last_smooth", -1.0, 40.0, 400.0, defaultedBySM = true),
    // Internal-only snapshot of the user's own FslCalSlope/FslCalOffset, taken the moment
    // OldSensorAdj first overrides them (12-15 day aging-sensor compensation) so they can be restored
    // exactly afterwards — same reasoning as ApsAutoIsfSmbDeliveryBaseline above: the live keys get
    // overwritten transiently, so the "what to revert to" value has to live somewhere else. Not shown
    // in any preference screen; defaults just mirror FslCalSlope/FslCalOffset's own defaults.
    ApsAutoIsfFslCalSlopeNormal("autoisf_fslcal_slope_normal", 1.0, 0.5, 1.5, defaultedBySM = true, exportable = false),
    ApsAutoIsfFslCalOffsetNormal("autoisf_fslcal_offset_normal", 0.0, -50.0, 50.0, defaultedBySM = true, exportable = false),
    // The user's own configured "normal" Libre cal slope (currently 0.72) — the base reference point
    // OldSensorAdj's tiered slopes are derived from (base - 0.02/0.04/0.07 for the D2/D1/D0 tiers),
    // rather than each tier being an independent hardcoded literal. Distinct from
    // ApsAutoIsfFslCalSlopeNormal above, which is a different, unrelated snapshot (what to restore
    // FslCalSlope to once OldSensorAdj's override window ends, not the base the tiers are computed from).
    ApsAutoIsfLibreSlopeOrig("autoisf_libre_slope_orig", 0.72, 0.5, 1.5, defaultedBySM = true),
    // The user's own configured "normal" Libre cal offset (currently 1.4) — the base reference point
    // OldSensorAdj's tiered offsets are derived from (base + 0.05/0.10/0.15 for the D2/D1/D0 tiers),
    // same reasoning/pattern as ApsAutoIsfLibreSlopeOrig above.
    ApsAutoIsfLibreOffsetOrig("autoisf_libre_offset_orig", 1.4, -50.0, 50.0, defaultedBySM = true),

    ActivityMonitorRatio("activity_ratio", 1.0, 0.0, 2.0, defaultedBySM = true),
    ActivityScaleFactor("activity_scale_factor", 1.0, 0.0, 1.5, defaultedBySM = true, dependency = BooleanKey.ActivityMonitorDetection),
    InactivityScaleFactor("inactivity_scale_factor", 1.0, 0.0, 1.5, defaultedBySM = true, dependency = BooleanKey.ActivityMonitorDetection),

}