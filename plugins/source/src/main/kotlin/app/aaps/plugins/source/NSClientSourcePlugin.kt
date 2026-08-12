package app.aaps.plugins.source

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.source.NSClientSource
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NSClientSourcePlugin @Inject constructor(
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    private val config: Config
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .fragmentClass(BGSourceFragment::class.java.name)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_nsclient_bg)
        .pluginName(R.string.ns_client_bg)
        .shortName(R.string.ns_client_bg_short)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .description(R.string.description_source_ns_client)
        .alwaysEnabled(config.AAPSCLIENT)
        .alwaysVisible(config.AAPSCLIENT)
        .setDefault(config.AAPSCLIENT),
    aapsLogger, rh
), BgSource, NSClientSource {

    @VisibleForTesting
    var lastBGTimeStamp: Long = 0

    @VisibleForTesting
    var isAdvancedFilteringEnabled = false

    override fun advancedFilteringSupported(): Boolean = isAdvancedFilteringEnabled

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "libre_special_settings"
            title = rh.gs(R.string.libre_special_settings)
            initialExpandedChildrenCount = 0
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.FslApplySmoothing, title = R.string.fsl_apply_smoothing_title, summary = R.string.fsl_apply_smoothing_summary))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.FslUseUkfSmoothing, mutuallyExclusiveKey = BooleanKey.FslUseUkfLibreSpecialSmoothing, title = R.string.fsl_use_ukf_smoothing_title, summary = R.string.fsl_use_ukf_smoothing_summary))
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = BooleanKey.FslUseUkfLibreSpecialSmoothing,
                    mutuallyExclusiveKey = BooleanKey.FslUseUkfSmoothing,
                    title = R.string.fsl_use_ukf_libre_special_smoothing_title,
                    summary = R.string.fsl_use_ukf_libre_special_smoothing_summary
                )
            )
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.FslCalOffset, title = R.string.fsl_cal_offset_title, dialogMessage = R.string.fsl_cal_offset_summary))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.FslCalSlope, title = R.string.fsl_cal_slope_title, dialogMessage = R.string.fsl_cal_slope_summary))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.FslSmoothAlpha, title = R.string.fsl_smooth_alpha_title, dialogMessage = R.string.fsl_smooth_alpha_summary))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.FslMaxSmoothGap, title = R.string.fsl_max_smooth_gap_title, summary = R.string.fsl_max_smooth_gap_summary))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.FslCalibrationTrigger, title = R.string.fsl_calibration_trigger_title, summary = R.string.fsl_calibration_trigger_summary))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.FslCalibrationEnd, title = R.string.fsl_calibration_end_title, summary = R.string.fsl_calibration_end_summary))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfOldSensorAdjEnabled, title = R.string.old_sensor_adj_enabled_title, summary = R.string.old_sensor_adj_enabled_summary))
        }
    }

    override fun detectSource(glucoseValue: GV) {
        if (glucoseValue.timestamp > lastBGTimeStamp) {
            isAdvancedFilteringEnabled = arrayOf(
                SourceSensor.DEXCOM_NATIVE_UNKNOWN,
                SourceSensor.DEXCOM_G6_NATIVE,
                SourceSensor.DEXCOM_G7_NATIVE,
                SourceSensor.DEXCOM_G6_NATIVE_XDRIP,
                SourceSensor.DEXCOM_G7_NATIVE_XDRIP,
            ).any { it == glucoseValue.sourceSensor }
            lastBGTimeStamp = glucoseValue.timestamp
        }
    }
}