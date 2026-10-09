package app.aaps.plugins.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
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
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.receivers.Intents
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.source.XDripSource
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.DoubleNonKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.objects.workflow.MetroWorkerCreator
import app.aaps.core.ui.compose.icons.IcXDrip
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.ui.compose.preference.libreSpecialSettings
import app.aaps.core.utils.calibratedLibre
import app.aaps.core.utils.libreSpecial
import app.aaps.core.utils.receivers.DataInbox
import app.aaps.core.utils.receivers.Inbox
import app.aaps.plugins.source.compose.BgSourceComposeContent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlin.math.abs
import kotlin.math.round

@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(400)
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<XDripSource>())
@Inject
class XdripSourcePlugin(
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    preferences: Preferences,
    config: Config,
    notificationManager: NotificationManager
) : AbstractBgSourceWithSensorInsertLogPlugin(
    pluginDescription = PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .composeContent { plugin ->
            BgSourceComposeContent(
                title = rh.gs(R.string.source_xdrip)
            )
        }
        .icon(IcXDrip)
        .pluginName(TextRef.AndroidRes(R.string.source_xdrip))
        .preferencesVisibleInSimpleMode(false)
        .description(TextRef.AndroidRes(R.string.description_source_xdrip)),
    aapsLogger = aapsLogger,
    rh = rh,
    preferences = preferences,
    notificationManager = notificationManager
), BgSource, XDripSource {

    override var sensorBatteryLevel = -1

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "bg_source_with_sensor_settings",
        title = pluginDescription.pluginName!!,
        items = listOf(
            BooleanKey.BgSourceUploadToNs,
            BooleanKey.BgSourceCreateSensorChange,
            libreSpecialSettings("xdrip_libre_special_settings")
        ),
        icon = pluginDescription.icon
    )

    // cannot be inner class because of needed injection

    @AssistedInject
    class XdripSourceWorker(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        aapsLogger: AAPSLogger,
        fabricPrivacy: FabricPrivacy,
        private val xdripSourcePlugin: XdripSourcePlugin,
        private val persistenceLayer: PersistenceLayer,
        private val preferences: Preferences,
        private val dateUtil: DateUtil,
        private val dataInbox: DataInbox,
        private val profileFunction: ProfileFunction
    ) : LoggingWorker(context, params, Dispatchers.IO, aapsLogger, fabricPrivacy) {

        /**
         * Metro builds this worker. The parameter names must match [MetroWorkerCreator],
         * because Metro matches assisted parameters by name and not only by type.
         */
        @AssistedFactory
        fun interface Factory : MetroWorkerCreator {

            override fun create(context: Context, params: WorkerParameters): XdripSourceWorker
        }

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
            // Drain first, unconditionally: drain() clears DataInbox's pending-work gate, so every
            // enqueued worker MUST reach it. If we returned early (plugin disabled) before draining,
            // the gate would stay set and silently block all future enqueues for this slot until
            // the process restarts. Bundles drained while disabled are intentionally discarded.
            val bundles = dataInbox.drain(XdripInbox)
            if (!xdripSourcePlugin.isEnabled()) return Result.success(workDataOf("Result" to "Plugin not enabled"))
            if (bundles.isEmpty()) return Result.success(workDataOf("Result" to "no data"))

            var hadFailure = false
            for ((index, bundle) in bundles.withIndex()) {
                try {
                    processBundle(bundle)
                } catch (e: CancellationException) {
                    // WorkManager stopped this run (e.g. chain overload / run-attempt limit). The
                    // coroutine contract requires CancellationException to propagate, otherwise the
                    // loop keeps fighting a cancelled Job and spams failures for every remaining
                    // bundle. drain() already removed the whole batch, so re-queue this bundle plus
                    // everything not yet processed before propagating — otherwise those readings are
                    // lost. requeue/enqueue are non-suspending, so they complete despite cancellation.
                    dataInbox.requeue(XdripInbox, bundles.subList(index, bundles.size))
                    throw e
                } catch (e: Exception) {
                    // processBundle early-returns on malformed-bundle conditions; anything that
                    // reaches the catch is a real exception (typically a DB write). Surface as
                    // failure so WorkInfo reflects the truth.
                    aapsLogger.error(LTag.BGSOURCE, "Failed processing xDrip bundle", e)
                    hadFailure = true
                }
            }
            return if (hadFailure) Result.failure(workDataOf("Error" to "one or more bundles failed")) else Result.success()
        }

        private suspend fun processBundle(bundle: Bundle) {
            aapsLogger.debug(LTag.BGSOURCE, "Received xDrip data: $bundle")
            // The second Nightscout is the only glucose source while that switch is on.
            // A full app and a client both use it. Storing this packet would put xDrip back on top.
            if (preferences.get(BooleanKey.NsClientSecondaryEnabled) &&
                preferences.get(BooleanKey.NsClientBgFromLiveSite)
            ) {
                aapsLogger.debug(LTag.BGSOURCE, "xDrip skipped: BG comes from the Live Nightscout connection")
                return
            }
            val timestamp = bundle.getLong(Intents.EXTRA_TIMESTAMP, 0)
            val sourceCgm = bundle.getString(Intents.XDRIP_DATA_SOURCE) ?: ""
            var value = bundle.getDouble(Intents.EXTRA_BG_ESTIMATE, 0.0)
            var raw = bundle.getDouble(Intents.EXTRA_RAW, 0.0)
            var noise: Double? = null
            var smoothed: Double? = null
            if (applyLibreSlope(raw, sourceCgm)) {
                val slope = preferences.get(DoubleKey.FslCalSlope)
                val offset = preferences.get(DoubleKey.FslCalOffset)
                val alpha = preferences.get(DoubleKey.FslSmoothAlpha)
                val unitFactor = if (profileFunction.getUnits() == GlucoseUnit.MMOL) Constants.MMOLL_TO_MGDL else 1.0
                // A relay often sends no raw field. The estimate is that raw reading.
                val sensor = if (raw == 0.0) value else raw
                val calibrated = calibratedLibre(sensor, slope, offset, unitFactor)
                val lastSmooth = preferences.get(DoubleNonKey.FslLastSmooth)
                val lastTime = preferences.get(LongNonKey.FslSmoothLastTimeRaw)
                val elapsed = if (lastTime < 0L) 0.0 else (timestamp - lastTime) / 60000.0
                val smooth = libreSpecial(calibrated, lastSmooth, elapsed, alpha)
                aapsLogger.debug(LTag.BGSOURCE, "Libre slope applied: slope=$slope offset=$offset value=$smooth")
                noise = sensor
                raw = calibrated
                value = smooth
                smoothed = smooth
            } else {
                value = round(value)
                raw = round(raw)
            }
            val glucoseValues = mutableListOf<GV>()
            glucoseValues += GV(
                timestamp = timestamp,
                value = value,
                raw = raw,
                noise = noise,
                trendArrow = TrendArrow.fromString(bundle.getString(Intents.EXTRA_BG_SLOPE_NAME)),
                sourceSensor = SourceSensor.fromString(sourceCgm)
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
                    newSensorStartTime < lastStoredSensorStartTime                 -> {
                    aapsLogger.debug(LTag.BGSOURCE, "Sensor start time is older than last stored time, skipping update.")
                    null
                }

                else                                                               -> newSensorStartTime
            }
            // Always update glucoseValues, but use the decided sensorStartTime
            if (glucoseValues[0].timestamp > 0 && glucoseValues[0].value > 0.0) {
                smoothed?.let {
                    preferences.put(DoubleNonKey.FslLastSmooth, it)
                    preferences.put(LongNonKey.FslSmoothLastTimeRaw, timestamp)
                }
                persistenceLayer.insertCgmSourceData(Sources.Xdrip, glucoseValues, emptyList(), finalSensorStartTime)
            } else {
                aapsLogger.warn(LTag.BGSOURCE, "Skipping xDrip bundle: missing glucoseValue")
                return
            }
            xdripSourcePlugin.sensorBatteryLevel = bundle.getInt(Intents.EXTRA_SENSOR_BATTERY, -1)
        }

        // Apply Libre slope is enough on its own. A relay source name is often a device serial, not Libre2.
        // With the switch off, the old packets still apply: no raw value, and Libre 2, Libre 3, or G7.
        private fun applyLibreSlope(raw: Double, sourceCgm: String): Boolean {
            if (preferences.get(BooleanKey.FslApplySmoothing)) return true
            if (raw != 0.0) return false
            return sourceCgm == "Libre2" || sourceCgm == "Libre2 Native" || sourceCgm == "Libre3" || sourceCgm == "G7"
        }
    }
}

object XdripInbox : Inbox<Bundle>("xdrip-bg", XdripSourcePlugin.XdripSourceWorker::class.java)
