package app.aaps.plugins.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.receivers.Intents
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.source.XDripSource
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.LongKey
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import app.aaps.plugins.smoothing.UnscentedKalmanFilterPlugin
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

@Singleton
class XdripSourcePlugin @Inject constructor(
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    private val config: Config
) : AbstractBgSourceWithSensorInsertLogPlugin(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .fragmentClass(BGSourceFragment::class.java.name)
        .pluginIcon((app.aaps.core.objects.R.drawable.ic_blooddrop_48))
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .pluginName(R.string.source_xdrip)
        .preferencesVisibleInSimpleMode(false)
        .description(R.string.description_source_xdrip),
    aapsLogger, rh
), BgSource, XDripSource {

    // A follower (aapsclient/aapsclient2) takes BG only from Nightscout. Force the xDrip source
    // permanently disabled there so a stale "xDrip selected" state can never make the worker
    // ingest broadcasts alongside NS (which caused duplicate BG readings).
    override fun specialEnableCondition(): Boolean = !config.AAPSCLIENT

    @VisibleForTesting
    var advancedFiltering = false
    override var sensorBatteryLevel = -1

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        super.addPreferenceScreen(preferenceManager, parent, context, requiredKey)
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
        }
    }

    override fun advancedFilteringSupported(): Boolean = advancedFiltering

    //private fun detectSource(glucoseValue: GV) {
    //    aapsLogger.debug(LTag.BGSOURCE, "Libre reading coming from source ${glucoseValue.sourceSensor}")
    @VisibleForTesting
    fun detectSource(glucoseValue: GV) {
        aapsLogger.debug(LTag.BGSOURCE, "Libre reading coming from source ${glucoseValue.sourceSensor}")
        advancedFiltering = arrayOf(
            SourceSensor.DEXCOM_NATIVE_UNKNOWN,
            SourceSensor.DEXCOM_G6_NATIVE,
            SourceSensor.DEXCOM_G7_NATIVE,
            SourceSensor.DEXCOM_G6_NATIVE_XDRIP,
            SourceSensor.DEXCOM_G7_NATIVE_XDRIP,
            SourceSensor.DEXCOM_G7_XDRIP,
            SourceSensor.LIBRE_2,
            SourceSensor.LIBRE_2_NATIVE,
            SourceSensor.LIBRE_3,
        ).any { it == glucoseValue.sourceSensor }
    }

    // cannot be inner class because of needed injection
    class XdripSourceWorker(
        context: Context,
        params: WorkerParameters
    ) : LoggingWorker(context, params, Dispatchers.IO) {

        @Inject lateinit var xdripSourcePlugin: XdripSourcePlugin
        @Inject lateinit var persistenceLayer: PersistenceLayer
        @Inject lateinit var dateUtil: DateUtil
        @Inject lateinit var dataWorkerStorage: DataWorkerStorage
        //@Inject lateinit var uel: UserEntryLogger
        @Inject lateinit var preferences: Preferences
        @Inject lateinit var profileUtil: ProfileUtil
        @Inject lateinit var automationStateService: AutomationStateInterface
        @Inject lateinit var ukfSmoothing: UnscentedKalmanFilterPlugin

        fun getSensorStartTime(bundle: Bundle): Long? {
            val now = dateUtil.now()
            var sensorStartTime: Long? = if (preferences.get(BooleanKey.BgSourceCreateSensorChange)) {
                bundle.getLong(Intents.EXTRA_SENSOR_STARTED_AT, 0)
            } else {
                null
            }
            // check start time validity
            sensorStartTime?.let {
                if (abs(it - now) > T.months(1).msecs() || it > now) sensorStartTime = null
            }
            return sensorStartTime
        }

        @SuppressLint("CheckResult")
        override suspend fun doWorkAndLog(): Result {
            var ret = Result.success()

            if (!xdripSourcePlugin.isEnabled()) return Result.success(workDataOf("Result" to "Plugin not enabled"))
            val bundle = dataWorkerStorage.pickupBundle(inputData.getLong(DataWorkerStorage.STORE_KEY, -1))
                ?: return Result.failure(workDataOf("Error" to "missing input data"))

            aapsLogger.debug(LTag.BGSOURCE, "Received xDrip data: $bundle")
            val glucoseValues = mutableListOf<GV>()
            var extraBgEstimate = bundle.getDouble(Intents.EXTRA_BG_ESTIMATE, 0.0)          //round()
            var extraRaw = bundle.getDouble(Intents.EXTRA_RAW, 0.0)                         //round()
            val offset = preferences.get(DoubleKey.FslCalOffset)
            val slope = preferences.get(DoubleKey.FslCalSlope)
            val factor = preferences.get(DoubleKey.FslSmoothAlpha)
            val lastSmooth = preferences.get(DoubleKey.FslLastSmooth)
            val lastTimeRaw = preferences.get(LongKey.FslSmoothLastTimeRaw)
            val thisTimeRaw = bundle.getLong(Intents.EXTRA_TIMESTAMP, 0)
            val elapsedMinutes = (thisTimeRaw - lastTimeRaw) / 60000.0
            var smooth = extraBgEstimate
            if (preferences.get(BooleanKey.FslCalibrationTrigger)) {
                preferences.put(LongKey.FslCalibrationStart, dateUtil.now())
                preferences.put(BooleanKey.FslCalibrationTrigger, false)
                preferences.put(BooleanKey.FslCalibrationEnd, false)
            }
            val calibrationDuration = preferences.get(IntKey.FslCalibrationDuration)
            val calibrationMinutes = calibrationDuration - (dateUtil.now() - preferences.get(LongKey.FslCalibrationStart)) / 60000
            val calibrationStopsSMB = calibrationMinutes > 0 && !preferences.get(BooleanKey.FslCalibrationEnd)
            if (calibrationStopsSMB) {
                 aapsLogger.debug(LTag.BGSOURCE, "Sensor calibrating for another ${calibrationMinutes}m")
            }
            val sourceCGM = bundle.getString(Intents.XDRIP_DATA_SOURCE) ?: ""
            val fslApply = preferences.get(BooleanKey.FslApplySmoothing)
            val isAutoLibreSource = extraRaw == 0.0 && (sourceCGM == "Libre2" || sourceCGM == "Libre2 Native" || sourceCGM == "Libre3" || sourceCGM == "G7")
            if (fslApply || isAutoLibreSource) {
                // If extraRaw is 0 (xDrip Libre, no separate raw), treat the estimate as raw so calibration has something to work with.
                // If extraRaw is non-zero (Juggluco etc.), use the sensor raw directly for calibration.
                if (extraRaw == 0.0) extraRaw = extraBgEstimate
                extraBgEstimate = max(40.0, extraRaw * slope + offset * (if (profileUtil.units == GlucoseUnit.MMOL) Constants.MMOLL_TO_MGDL else 1.0))
                if (preferences.get(BooleanKey.FslUseUkfSmoothing) && !preferences.get(BooleanKey.FslUseUkfLibreSpecialSmoothing)) {
                    // UnscentedKalmanFilterPlugin.smoothRawRealtime() -- incremental, own persisted
                    // state (see that function's doc comment), replaces the fsl_exp1 EMA below
                    // entirely when this toggle is on. Same calibrated extraBgEstimate input either
                    // way; mirrors the NS ingestion wiring in NsIncomingDataProcessor.kt.
                    smooth = ukfSmoothing.smoothRawRealtime(thisTimeRaw, extraBgEstimate)
                    // LibreSpecial shadow: completes the live pair (after UKFset1's own shadow call in
                    // the else branch below), added 2026-08-16 (UKF3426 branch). Same "call for its side
                    // effect, discard the return value" idiom -- keeps LibreSpecial's own EMA/history
                    // current even while UKFset1 is the actual live/dosing source. Never assigned to
                    // smooth; dosing stays on UKFset1 exactly as before.
                    ukfSmoothing.smoothLibreSpecialShadow(
                        thisTimeRaw, extraBgEstimate, factor,
                        preferences.get(IntKey.FslMaxSmoothGap).toDouble(),
                        if (sourceCGM == "G7") 5.0 else 1.0
                    )
                    preferences.put(DoubleKey.FslLastRaw, extraBgEstimate)
                    aapsLogger.debug(LTag.BGSOURCE, "FSL xDrip calibration (UKF): raw=$extraRaw calibrated=$extraBgEstimate smooth=$smooth")
                } else {
                    val maxGap = preferences.get(IntKey.FslMaxSmoothGap).toDouble()
                    val cgmDelta = if (sourceCGM == "G7") 5.0 else 1.0
                    val effectiveAlpha = if (calibrationDuration - calibrationMinutes < 2 && !preferences.get(BooleanKey.FslCalibrationEnd)) 1.0
                        else min(1.0, factor + (1.0 - factor) * ((max(0.0, elapsedMinutes - cgmDelta) / (maxGap - cgmDelta)).pow(2.0)))
                    // Original LibreSpecial exponential smoothing.
                    val libreSpecial = if (lastSmooth > 0.0)
                        lastSmooth + effectiveAlpha * (extraBgEstimate - lastSmooth)
                    else extraBgEstimate
                    // UKFset2 comparison: run LibreSpecial's EMA through the full-history UKF so its
                    // separate graph history can be retested, but deliberately keep the live
                    // main-BG/dosing value on plain LibreSpecial during this comparison. Unconditional
                    // as of 2026-08-15 (UKF3426 branch) -- was gated on FslUseUkfLibreSpecialSmoothing,
                    // which meant ukf_librespecial_refined_history went stale the moment that toggle
                    // was off; now always kept current so UKF2's own delta5/delta15 (see
                    // DeltaCalculator.calculateDeltasGeneric()) stay meaningful regardless of the
                    // display toggle. Real always-on cost: one more smoothForDisplay() batch pass over
                    // up to 120 points, every reading, even when nobody's looking at the UKF2 graph.
                    ukfSmoothing.smoothLibreSpecialRealtime(thisTimeRaw, libreSpecial)
                    // UKFset1 shadow: same "call for its side effect, discard the return value" idiom
                    // as the UKF2 line above, added 2026-08-15 (UKF3426 branch) so UKFset1's own Kalman
                    // state/history (ukf_rawrt_* -- see UnscentedKalmanFilterPlugin.rawRealtimeHistory())
                    // stays warm and current even while LibreSpecial is the actual live/dosing source.
                    // Same calibrated extraBgEstimate input the live branch above uses -- keeps this
                    // apples-to-apples with what UKFset1 would compute if it WERE live right now. Never
                    // assigned to smooth; dosing stays on LibreSpecial exactly as before.
                    ukfSmoothing.smoothRawRealtime(thisTimeRaw, extraBgEstimate)
                    smooth = libreSpecial
                    preferences.put(DoubleKey.FslLastRaw, extraBgEstimate)
                    preferences.put(DoubleKey.FslLastSmooth, libreSpecial)
                    preferences.put(LongKey.FslSmoothLastTimeRaw, thisTimeRaw)
                    val calibrationMsg = "Calibration json: {\"calibration_offset\":$offset,\"calibration_slope\":$slope," +
                        "\"smoothFactor\":$factor,\"effectiveAlpha\":$effectiveAlpha," +
                        "\"calibrationStart\":${preferences.get(LongKey.FslCalibrationStart)}," +
                        "\"calibrationIgnore\":${preferences.get(BooleanKey.FslCalibrationEnd)}}"
                    aapsLogger.debug(LTag.BGSOURCE, calibrationMsg)
                }
            }
            glucoseValues += GV(
                timestamp = thisTimeRaw,        // bundle.getLong(Intents.EXTRA_TIMESTAMP, 0),
                value = smooth,                 //round(),   // round(extraBgEstimate), //round(bundle.getDouble(Intents.EXTRA_BG_ESTIMATE, 0.0)),
                raw = extraBgEstimate,          //round(),   // round(bundle.getDouble(Intents.EXTRA_RAW, 0.0)),
                noise = extraRaw,               //round(),   // piggy pack; raw can also be extracted from Juggluco export or above debug
                trendArrow = TrendArrow.fromString(bundle.getString(Intents.EXTRA_BG_SLOPE_NAME)),
                sourceSensor = SourceSensor.fromString(bundle.getString(Intents.XDRIP_DATA_SOURCE) ?: "")
            )
            val newSensorStartTime = getSensorStartTime(bundle)
            // Retrieve last stored sensorStartTime from the database
            val lastTherapyEvent = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)
            val lastStoredSensorStartTime = lastTherapyEvent?.timestamp
            // Decide whether to update sensorStartTime or keep the last stored one
            val finalSensorStartTime = when {
                lastStoredSensorStartTime != null && newSensorStartTime != null &&
                    abs(newSensorStartTime - lastStoredSensorStartTime) <= 300_000 -> {
                    aapsLogger.debug(LTag.BGSOURCE, "Sensor start time is within 5 minutes range, skipping update.")
                    null
                }
                lastStoredSensorStartTime != null && newSensorStartTime != null &&
                    newSensorStartTime < lastStoredSensorStartTime -> {
                    aapsLogger.debug(LTag.BGSOURCE, "Sensor start time is older than last stored time, skipping update.")
                    null
                }
                else -> newSensorStartTime
            }
            // Always update glucoseValues, but use the decided sensorStartTime
            if (glucoseValues[0].timestamp > 0 && glucoseValues[0].value > 0.0)
                persistenceLayer.insertCgmSourceData(Sources.Xdrip, glucoseValues, emptyList(), finalSensorStartTime)
                    .doOnError { ret = Result.failure(workDataOf("Error" to it.toString())) }
                    .blockingGet()
                    .also { savedValues -> savedValues.all().forEach { xdripSourcePlugin.detectSource(it) } }
            else return Result.failure(workDataOf("Error" to "missing glucoseValue"))
            xdripSourcePlugin.sensorBatteryLevel = bundle.getInt(Intents.EXTRA_SENSOR_BATTERY, -1)
            return ret
        }
    }
}
