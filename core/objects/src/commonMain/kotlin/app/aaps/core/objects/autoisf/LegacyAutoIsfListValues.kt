package app.aaps.core.objects.autoisf

import app.aaps.core.keys.StringKey
import app.aaps.core.keys.readTier
import app.aaps.core.keys.tierSummary

// Keys as written by the V3 loop phone before the direct-list report was added.
private val legacyListKeys = mapOf(
    "5.006" to "autoisf_old_sensor_adj_enabled",
    "5.008" to "autoisf_boost_automations_enabled",
    "5.012" to "autoisf_pp_isf_weight_normal",
    "5.016" to "autoisf_bgaccel_isf_weight_normal",
    "5.022" to "autoisf_dura_isf_weight_normal",
    "5.026" to "autoisf_libre_slope_orig",
    "5.032" to "autoisf_libre_offset_orig",
    "5.036" to "autoisf_smb_offset_override",
    "5.046" to "boluswizard_percentage",
    "5.052" to "autoisf_mild_boost_ratio",
    "5.056" to "autoisf_pp_isf_weight_high",
    "5.062" to "autoisf_bgaccel_isf_weight_high",
    "5.068" to "higher_ISFrange_weight",
    "5.074" to "insulin_oref_peak",
    "5.08" to "autoISF_max_low",
    "5.086" to "autoISF_max",
    "5.092" to "autoisf_tod_offset_0002",
    "5.098" to "autoisf_tod_offset_0204",
    "5.104" to "autoisf_tod_offset_0406",
    "5.11" to "autoisf_tod_offset_0609",
    "5.116" to "autoisf_tod_offset_0912",
    "5.122" to "autoisf_tod_offset_1218",
    "5.128" to "autoisf_tod_offset_1822",
    "5.134" to "autoisf_tod_offset_2200",
    "5.138" to "show_carb_model_curve",
    "5.142" to "show_graph5",
    "5.148" to "autoisf_standard_profile_name",
    "5.15" to "autoisf_low_profile_name",
    "5.152" to "fsl_use_ukf_smoothing",
    "5.156" to "autoisf_sensor_age_code_enabled",
    "5.162" to "autoisf_steroid_110_profile_name",
    "5.164" to "autoisf_mj_kotlin_buttons_enabled",
    "5.166" to "autoisf_steroid_kotlin_button_enabled",
    "5.168" to "autoisf_steroid_130_profile_name",
    "5.17" to "autoisf_steroid_150_profile_name",
    "5.172" to "autoisf_steroid_190_profile_name",
    "5.174" to "autoisf_steroid_250_profile_name",
    "5.176" to "autoisf_steroid_100_profile_name",
    "5.182" to "boost_scale_value",
    "5.186" to "boost_bolus_cap",
    "5.19" to "boost_max_iob_percent",
    "5.194" to "autoisf_uam_boost_enabled",
    "5.196" to "autoisf_use_ukf1_for_dosing",
    "5.198" to "automation_coded_locations_enabled",
    "5.204" to "automation_location_sms_device_model",
    "5.206" to "autoisf_use_live_steps_on_virtual",
    "5.208" to "autoisf_adb_connect_port",
    "5.21" to "autoisf_profile_batch_auto_enabled",
    "5.212" to "autoisf_profile_batch_revert_enabled",
    "5.214" to "autoisf_profile_batch_revert_c_enabled",
    "5.226" to "autoisf_fast_rise_enabled",
    "5.228" to "autoisf_low_rebound_guard_enabled",
    "5.23" to "autoisf_uam_boost_unrestricted_enabled",
    "5.232" to "show_insulin_totals_row",
    "5.234" to "autoisf_loop_every_minute",
    "5.236" to "autoisf_use_live_mj_state_on_virtual",
)

fun legacyAutoIsfListValues(report: String?): Map<String, String> {
    if (report.isNullOrBlank()) return emptyMap()
    val legacy = report.lineSequence().mapNotNull { line ->
        val separator = line.indexOf(" = ")
        if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 3)
    }.toMap()
    if ("configuration_version" !in legacy) return emptyMap()

    return buildMap {
        put("unsupported:5.248", "true")
        put("unsupported:5.236", "true")
        for ((code, key) in legacyListKeys) {
            val value = legacy[key] ?: continue
            put(code, when (code) {
                "5.046", "5.19" -> "$value%"
                "5.074" -> "$value min"
                "5.186" -> "${value}U"
                "5.208" -> if ((value.toIntOrNull() ?: continue) > 0) "Port configured" else "Port not set"
                "5.234" -> when (value) {
                    "true" -> "every 1 min"
                    "false" -> "every 5 min"
                    else -> continue
                }
                else -> if (key in legacyToggleKeys) onOff(value) else value
            })
        }
        val base = legacy["autoisf_smb_delivery_baseline"]
        val mild = legacy["autoisf_mild_boost_ratio"]
        if (base != null && mild != null) put("5.002", "base=$base, mildBst=$mild")
        legacy["autoisf_apk_newest_nnn"]?.let { raw ->
            val newest = raw.toLongOrNull()?.takeIf { it > 0 }
            val text = if (newest == null) "Newest: not found yet" else "Newest: $newest"
            put("5.2", text)
            put("5.202", text)
        }
        autoIsfProfileRoleKeys.forEach { key -> legacy[key.key]?.let { put("role:${key.key}", it) } }
        legacy["configuration_profile"]?.let { running ->
            put("runningProfile", running)
            val standard = legacy[StringKey.ApsAutoIsfStandardProfileName.key]
            val low = legacy[StringKey.ApsAutoIsfLowProfileName.key]
            if (standard != null && low != null) {
                val standardRungs = standardTierRoleKeys.map { legacy[it.key] }
                val lowRungs = lowTierRoleKeys.map { legacy[it.key] }
                if (standardRungs.none { it == null } && lowRungs.none { it == null }) {
                    val tier = readTier(standard, low, running, standardRungs.filterNotNull(), lowRungs.filterNotNull())
                    put("tierNow", tierSummary(tier, standard, low, running))
                }
            }
        }
        val slope = legacy["fslCal_Slope"]
        val offset = legacy["fslCal_Offset"]
        val baseSlope = legacy["autoisf_libre_slope_orig"]
        val baseOffset = legacy["autoisf_libre_offset_orig"]
        val active = legacy["autoisf_old_sensor_adj_active"]
        if (slope != null && offset != null && baseSlope != null && baseOffset != null && active != null) {
            put("libreLive", "Live slope $slope, offset $offset. Baseline slope $baseSlope, offset $baseOffset. Sensor age tier ${onOff(active)}.")
        }
    }
}

private fun onOff(value: String): String = when (value) {
    "true" -> "ON"
    "false" -> "OFF"
    else -> value
}

private val legacyToggleKeys = setOf(
    "autoisf_old_sensor_adj_enabled",
    "autoisf_boost_automations_enabled",
    "show_carb_model_curve",
    "show_graph5",
    "fsl_use_ukf_smoothing",
    "autoisf_sensor_age_code_enabled",
    "autoisf_mj_kotlin_buttons_enabled",
    "autoisf_steroid_kotlin_button_enabled",
    "autoisf_uam_boost_enabled",
    "autoisf_use_ukf1_for_dosing",
    "automation_coded_locations_enabled",
    "autoisf_use_live_steps_on_virtual",
    "autoisf_use_live_mj_state_on_virtual",
    "autoisf_profile_batch_auto_enabled",
    "autoisf_profile_batch_revert_enabled",
    "autoisf_profile_batch_revert_c_enabled",
    "autoisf_fast_rise_enabled",
    "autoisf_low_rebound_guard_enabled",
    "autoisf_uam_boost_unrestricted_enabled",
    "show_insulin_totals_row",
)
