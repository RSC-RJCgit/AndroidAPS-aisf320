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
    // Low-BG counterpart to ApsAutoIsfMax above — added as a plain settings-only value for now, not
    // yet wired into any calculation (no low-BG branch exists yet to pick this over autoISF_max).
    ApsAutoIsfMaxLow("autoISF_max_low", 1.0, 1.0, 3.0, defaultedBySM = true),
    ApsAutoIsfBgAccelWeight("bgAccel_ISF_weight", 0.0, 0.0, 1.0, defaultedBySM = true),
    // The user's own configured "normal" bgAccel_ISF_weight (currently 0.70) — what AcceUp0.5/
    // RecentPodOff restore ApsAutoIsfBgAccelWeight to once their own conditions clear.
    ApsAutoIsfBgAccelWeightNormal("autoisf_bgaccel_isf_weight_normal", 0.70, 0.0, 1.0, defaultedBySM = true),
    // The user's own configured "boosted" bgAccel_ISF_weight (default 0.95, matching the value this
    // replaces) — what OldPod2/RecentPod set ApsAutoIsfBgAccelWeight to while boosting, and what
    // AcceUp0.5 now targets too (not the resting baseline above). Was a hardcoded 0.95 literal in all 3
    // places; now a live, user-tunable preference instead.
    ApsAutoIsfBgAccelWeightHigh("autoisf_bgaccel_isf_weight_high", 0.95, 0.0, 1.0, defaultedBySM = true),
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
    // Fallback TDDfactor (scales max_iob/insulinReq) used only while ApsAutoIsfTddFactor is off — a
    // dedicated setting, split out from the old ApsAutoIsfSmbDeliveryRatioMin>0.8 piggyback so that
    // setting stays single-purpose. Default 1.0 (neutral, matches the original lower_SMB baseline);
    // range 0.8-1.2 matches the live tddRatio clamp used when ApsAutoIsfTddFactor is on.
    ApsAutoIsfTddFactorFallback("autoisf_tdd_factor_fallback", 1.0, 0.8, 1.2, defaultedBySM = true),
    ApsAutoIsfSmbMaxRangeExtension("openapsama_smb_max_range_extension", 1.0, 1.0, 5.0, defaultedBySM = true),
    // Simple flat BG-above-target offset (mmol/L) that fully replaces the complex varOffset derivation
    // when ApsAutoIsfSmbOffsetOverrideEnabled is on. Max 2.0 mmol (36 mg/dL) matches the original
    // varOffset hard clamp (min(36.0, varOffset)) in DetermineBasalAutoISF.kt, so this can never exceed
    // what the old mechanism itself was capable of.
    ApsAutoIsfSmbOffsetOverride("autoisf_smb_offset_override", 0.5, 0.0, 2.0, defaultedBySM = true, dependency = BooleanKey.ApsAutoIsfSmbOffsetOverrideEnabled),
    // Time-of-day varOffset nudge (mmol), one signed value per fixed window — applied ADDITIVELY on top
    // of whatever varOffset already is (ApsAutoIsfSmbOffsetOverride's own HARD value, or the normal
    // smb_delivery_ratio_max-based derivation), regardless of which mode is active. Default 0.0 = no
    // adjustment; TT-nudgeable +/-0.1 (see the *TT blocks in OpenAPSAutoISFPlugin.kt). Replaces the old
    // carbsReqThreshold-encoded offset1/2/3 + hardcoded nowHour mechanism in DetermineBasalAutoISF.kt.
    ApsAutoIsfTodOffset0002("autoisf_tod_offset_0002", 0.0, -2.0, 2.0, defaultedBySM = true),
    ApsAutoIsfTodOffset0204("autoisf_tod_offset_0204", 0.0, -2.0, 2.0, defaultedBySM = true),
    ApsAutoIsfTodOffset0406("autoisf_tod_offset_0406", 0.0, -2.0, 2.0, defaultedBySM = true),
    ApsAutoIsfTodOffset0609("autoisf_tod_offset_0609", 0.0, -2.0, 2.0, defaultedBySM = true),
    ApsAutoIsfTodOffset0912("autoisf_tod_offset_0912", 0.0, -2.0, 2.0, defaultedBySM = true),
    ApsAutoIsfTodOffset1218("autoisf_tod_offset_1218", 0.0, -2.0, 2.0, defaultedBySM = true),
    ApsAutoIsfTodOffset1822("autoisf_tod_offset_1822", 0.0, -2.0, 2.0, defaultedBySM = true),
    ApsAutoIsfTodOffset2200("autoisf_tod_offset_2200", 0.0, -2.0, 2.0, defaultedBySM = true),
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
    // Tier 3 "UAM Boost" numeric knobs (DetermineBasalAutoISF.kt), ported from Boost-in-AAPS_3.4's
    // OapsProfileBoost.boost_bolus/boost_maxIOB/boost_scale -- same defaults/ranges as that project's
    // own DoubleKey entries (boost_bolus_cap/boost_max_iob/boost_scale_value). "Uam" prefix to stay
    // distinct from ApsAutoIsfBoostAutomationsEnabled/ApsAutoIsfMildBoostRatio above (a different,
    // unrelated boost feature). Gated as a group by BooleanKey.ApsAutoIsfUamBoostEnabled.
    //
    // Boost_InsulinReq (the reference project's separate 0-100% GUI setting) deliberately has NO
    // DoubleKey of its own, unlike the three siblings above: 2026-08-18, changed to a pure
    // calculation (ApsAutoIsfSmbDeliveryRatio * 100) instead of an independent setting, so it always
    // stays in lockstep with the existing base SMB delivery ratio rather than needing its own upkeep
    // -- see OpenAPSAutoISFPlugin.kt's uamBoostInsulinReqPct computed property.
    ApsAutoIsfUamBoostMaxBolus("boost_bolus_cap", 2.5, 0.1, 10.0, defaultedBySM = true, dependency = BooleanKey.ApsAutoIsfUamBoostEnabled),
    // Percentage of profile.max_iob used as Tier 3's hard IOB ceiling. This deliberately uses a new
    // storage key: reinterpreting a legacy absolute value such as 1.0U as 1% would be unsafe.
    ApsAutoIsfUamBoostMaxIobPercent("boost_max_iob_percent", 10.0, 1.0, 100.0, defaultedBySM = true, dependency = BooleanKey.ApsAutoIsfUamBoostEnabled),
    // Raw, unscaled value -- DetermineBasalAutoISF.kt applies the live profile-percentage scaling
    // itself (* profile_percentage / 100.0), matching Boost-in-AAPS_3.4's own
    // "profile.boost_scale * (profileSwitch / 100.0)" -- so this setting is the pre-scaling baseline,
    // not the effective in-use value.
    ApsAutoIsfUamBoostScale("boost_scale_value", 1.0, 0.1, 3.0, defaultedBySM = true, dependency = BooleanKey.ApsAutoIsfUamBoostEnabled),
    FslCalOffset("fslCal_Offset", 0.0, -50.0, 50.0, defaultedBySM = true),      //dependency = BooleanKey.ApsCalibrationTrigger),
    FslCalSlope("fslCal_Slope", 1.0, 0.5, 1.5, defaultedBySM = true),           //dependency = BooleanKey.ApsCalibrationTrigger),
    FslSmoothAlpha("fsl_exp1_factor", 0.3, 0.1, 1.0, defaultedBySM = true),
    //FslSmoothCorrection("fsl_exp1_correction", 0.0, 0.0, 1.0, defaultedBySM = true),
    FslLastRaw("fsl_last_raw", -1.0, 40.0, 400.0, defaultedBySM = true),
    FslLastSmooth("fsl_last_smooth", -1.0, 40.0, 400.0, defaultedBySM = true),
    // Internal-only snapshot of the user's own FslCalSlope/FslCalOffset, taken the moment
    // OldSensorAdj first overrides them (11-15 elapsed-day aging-sensor compensation) so they can be restored
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
