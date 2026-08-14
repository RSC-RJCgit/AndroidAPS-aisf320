package app.aaps.plugins.sync.nsShared

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.FD
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.IDs
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.nsclient.StoreDataForDb
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileSource
import app.aaps.core.interfaces.profile.ProfileStore
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventNSClientNewLog
import app.aaps.core.interfaces.source.NSClientSource
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.LongKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.nssdk.localmodel.entry.NSSgvV3
import app.aaps.core.nssdk.localmodel.food.NSFood
import app.aaps.core.nssdk.localmodel.treatment.NSBolus
import app.aaps.core.nssdk.localmodel.treatment.NSBolusWizard
import app.aaps.core.nssdk.localmodel.treatment.NSCarbs
import app.aaps.core.nssdk.localmodel.treatment.NSEffectiveProfileSwitch
import app.aaps.core.nssdk.localmodel.treatment.NSExtendedBolus
import app.aaps.core.nssdk.localmodel.treatment.NSOfflineEvent
import app.aaps.core.nssdk.localmodel.treatment.NSProfileSwitch
import app.aaps.core.nssdk.localmodel.treatment.NSTemporaryBasal
import app.aaps.core.nssdk.localmodel.treatment.NSTemporaryTarget
import app.aaps.core.nssdk.localmodel.treatment.NSTherapyEvent
import app.aaps.core.nssdk.localmodel.treatment.NSTreatment
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.sync.nsclient.extensions.fromJson
import app.aaps.plugins.sync.nsclientV3.extensions.toBolus
import app.aaps.plugins.sync.nsclientV3.extensions.toBolusCalculatorResult
import app.aaps.plugins.sync.nsclientV3.extensions.toCarbs
import app.aaps.plugins.sync.nsclientV3.extensions.toEffectiveProfileSwitch
import app.aaps.plugins.sync.nsclientV3.extensions.toExtendedBolus
import app.aaps.plugins.sync.nsclientV3.extensions.toFood
import app.aaps.plugins.sync.nsclientV3.extensions.toGV
import app.aaps.plugins.sync.nsclientV3.extensions.toProfileSwitch
import app.aaps.plugins.sync.nsclientV3.extensions.toRunningMode
import app.aaps.plugins.sync.nsclientV3.extensions.toTemporaryBasal
import app.aaps.plugins.sync.nsclientV3.extensions.toTemporaryTarget
import app.aaps.plugins.sync.nsclientV3.extensions.toTherapyEvent
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

@Singleton
class NsIncomingDataProcessor @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val nsClientSource: NSClientSource,
    private val preferences: Preferences,
    private val rxBus: RxBus,
    private val dateUtil: DateUtil,
    private val profileUtil: ProfileUtil,
    private val activePlugin: ActivePlugin,
    private val storeDataForDb: StoreDataForDb,
    private val config: Config,
    private val profileStoreProvider: Provider<ProfileStore>,
    private val profileSource: ProfileSource,
    private val uiInteraction: UiInteraction,
    private val ukfSmoothing: app.aaps.plugins.smoothing.UnscentedKalmanFilterPlugin
) {

    private fun toGv(jsonObject: JSONObject): GV? {
        val sgv = NSSgv(jsonObject)
        return GV(
            timestamp = sgv.mills ?: return null,
            value = sgv.mgdl?.toDouble() ?: return null,
            noise = sgv.unfiltered?.toDouble(),
            raw = sgv.filtered?.toDouble(),
            trendArrow = TrendArrow.fromString(sgv.direction),
            ids = IDs(nightscoutId = sgv.id),
            sourceSensor = SourceSensor.fromString(sgv.device)
        )
    }

    /**
     * Preprocess list of SGVs
     *
     * @return true if there was an accepted SGV
     */
    fun processSgvs(sgvs: Any, doFullSync: Boolean): Boolean {
        // Objective0
        preferences.put(BooleanNonKey.ObjectivesBgIsAvailableInNs, true)

        if (!nsClientSource.isEnabled() && !preferences.get(BooleanKey.NsClientAcceptCgmData) && !doFullSync) return false

        var latestDateInReceivedData: Long = 0
        aapsLogger.debug(LTag.NSCLIENT, "Received NS Data: $sgvs")
        val glucoseValues = mutableListOf<GV>()

        if (sgvs is JSONArray) { // V1 client
            for (i in 0 until sgvs.length()) {
                val sgv = toGv(sgvs.getJSONObject(i)) ?: continue
                // allow 1 min in the future
                if (sgv.timestamp < dateUtil.now() + T.mins(1).msecs() && sgv.timestamp > latestDateInReceivedData) {
                    latestDateInReceivedData = sgv.timestamp
                    glucoseValues += sgv
                } else
                    aapsLogger.debug(LTag.NSCLIENT, "Ignoring record with wrong timestamp: $sgv")
            }
        } else if (sgvs is List<*>) { // V3 client

            for (i in 0 until sgvs.size) {
                val sgv = (sgvs[i] as NSSgvV3).toGV()
                if (sgv.timestamp < dateUtil.now() + T.mins(1).msecs() && sgv.timestamp > latestDateInReceivedData) {
                    latestDateInReceivedData = sgv.timestamp
                    glucoseValues += sgv
                } else
                    aapsLogger.debug(LTag.NSCLIENT, "Ignoring record with wrong timestamp: $sgv")
            }
        }
        if (glucoseValues.isNotEmpty()) {
            // Apply FSL calibration + smoothing if enabled (e.g. Juggluco uploading raw data via NS)
            if (preferences.get(BooleanKey.FslApplySmoothing)) {
                val slope = preferences.get(DoubleKey.FslCalSlope)
                val offset = preferences.get(DoubleKey.FslCalOffset)
                val factor = preferences.get(DoubleKey.FslSmoothAlpha)
                val maxGap = preferences.get(IntKey.FslMaxSmoothGap).toDouble()
                val useRawUkf = preferences.get(BooleanKey.FslUseUkfSmoothing) && !preferences.get(BooleanKey.FslUseUkfLibreSpecialSmoothing)
                val useLibreSpecialUkf = preferences.get(BooleanKey.FslUseUkfLibreSpecialSmoothing)
                val unitFactor = if (profileUtil.units == GlucoseUnit.MMOL) Constants.MMOLL_TO_MGDL else 1.0
                glucoseValues.sortBy { it.timestamp }
                for (gv in glucoseValues) {
                    val calibrated = max(40.0, gv.value * slope + offset * unitFactor)
                    val smooth: Double
                    if (useRawUkf) {
                        // UnscentedKalmanFilterPlugin.smoothRawRealtime() -- incremental, own persisted
                        // state (see that function's doc comment), replaces the fsl_exp1 EMA below
                        // entirely when this toggle is on. Same calibrated-value input either way.
                        smooth = ukfSmoothing.smoothRawRealtime(gv.timestamp, calibrated)
                        aapsLogger.debug(LTag.NSCLIENT, "FSL NS calibration (UKF): raw=${gv.value} calibrated=$calibrated smooth=$smooth")
                    } else {
                        val lastSmooth = preferences.get(DoubleKey.FslLastSmooth)
                        val lastTimeRaw = preferences.get(LongKey.FslSmoothLastTimeRaw)
                        val elapsedMinutes = (gv.timestamp - lastTimeRaw) / 60000.0
                        val effectiveAlpha = min(1.0, factor + (1.0 - factor) * ((max(0.0, elapsedMinutes - 1.0) / (maxGap - 1.0)).pow(2.0)))
                        val libreSpecial = if (lastSmooth > 0.0) lastSmooth + effectiveAlpha * (calibrated - lastSmooth) else calibrated
                        // TEMP DISABLED 2026-08-14 (aisf321_58x) -- UKF2 (smoothLibreSpecialRealtime)
                        // taken out of the live pipeline pending retest; falls through to plain
                        // libreSpecial unconditionally even if useLibreSpecialUkf is still true. To
                        // re-enable, restore the commented condition below (same change needed in
                        // XdripSourcePlugin.kt's mirrored block).
                        // smooth = if (useLibreSpecialUkf)
                        //     ukfSmoothing.smoothLibreSpecialRealtime(gv.timestamp, libreSpecial)
                        // else libreSpecial
                        smooth = libreSpecial
                        preferences.put(DoubleKey.FslLastSmooth, libreSpecial)
                        preferences.put(LongKey.FslSmoothLastTimeRaw, gv.timestamp)
                        aapsLogger.debug(LTag.NSCLIENT, "FSL NS calibration: raw=${gv.value} calibrated=$calibrated libreSpecial=$libreSpecial smooth=$smooth alpha=$effectiveAlpha")
                    }
                    gv.noise = gv.value     // preserve pre-calibration mgdl as raw reference
                    gv.raw = calibrated     // calibrated but unsmoothed
                    gv.value = smooth       // final smoothed value
                }
            }
            activePlugin.activeNsClient?.updateLatestBgReceivedIfNewer(latestDateInReceivedData)
            // Was that sgv more less 5 mins ago ?
            if (T.msecs(dateUtil.now() - latestDateInReceivedData).mins() < 5L) {
                rxBus.send(EventDismissNotification(Notification.NS_ALARM))
                rxBus.send(EventDismissNotification(Notification.NS_URGENT_ALARM))
            }
            storeDataForDb.addToGlucoseValues(glucoseValues)
        }
        return glucoseValues.isNotEmpty()
    }

    /**
     * Preprocess list of treatments
     *
     * @return true if there was an accepted treatment
     */
    fun processTreatments(treatments: List<NSTreatment>, doFullSync: Boolean): Boolean {
        try {
            var latestDateInReceivedData: Long = 0
            for (treatment in treatments) {
                aapsLogger.debug(LTag.NSCLIENT, "Received NS treatment: $treatment")
                val date = treatment.date ?: continue
                if (date > latestDateInReceivedData) latestDateInReceivedData = date

                when (treatment) {
                    is NSBolus                  ->
                        if (preferences.get(BooleanKey.NsClientAcceptInsulin) || config.AAPSCLIENT || doFullSync) {
                            val bolus = treatment.toBolus()
                            if (bolus.type == BS.Type.SMB && preferences.get(BooleanKey.NsClientAcceptInsulinExcludeSmb) && !config.AAPSCLIENT && !doFullSync)
                                aapsLogger.debug(LTag.NSCLIENT, "Skipping SMB bolus (excluded by setting): $treatment")
                            else
                                storeDataForDb.addToBoluses(bolus)
                        }

                    is NSCarbs                  ->
                        if (preferences.get(BooleanKey.NsClientAcceptCarbs) || config.AAPSCLIENT || doFullSync)
                            storeDataForDb.addToCarbs(treatment.toCarbs())

                    is NSTemporaryTarget        ->
                        if (preferences.get(BooleanKey.NsClientAcceptTempTarget) || config.AAPSCLIENT || doFullSync) {
                            if (treatment.duration > 0L) {
                                // not ending event
                                if (treatment.targetBottomAsMgdl() < Constants.MIN_TT_MGDL
                                    || treatment.targetBottomAsMgdl() > Constants.MAX_TT_MGDL
                                    || treatment.targetTopAsMgdl() < Constants.MIN_TT_MGDL
                                    || treatment.targetTopAsMgdl() > Constants.MAX_TT_MGDL
                                    || treatment.targetBottomAsMgdl() > treatment.targetTopAsMgdl()
                                ) {
                                    aapsLogger.debug(LTag.NSCLIENT, "Ignored TemporaryTarget $treatment")
                                    continue
                                }
                            }
                            storeDataForDb.addToTemporaryTargets(treatment.toTemporaryTarget())
                        }

                    is NSTemporaryBasal         ->
                        if (preferences.get(BooleanKey.NsClientAcceptTbrEb) || config.AAPSCLIENT || doFullSync)
                            storeDataForDb.addToTemporaryBasals(treatment.toTemporaryBasal())

                    is NSEffectiveProfileSwitch ->
                        if (preferences.get(BooleanKey.NsClientAcceptProfileSwitch) || config.AAPSCLIENT || doFullSync) {
                            treatment.toEffectiveProfileSwitch(dateUtil)?.let { effectiveProfileSwitch ->
                                storeDataForDb.addToEffectiveProfileSwitches(effectiveProfileSwitch)
                            }
                        }

                    is NSProfileSwitch          ->
                        if (preferences.get(BooleanKey.NsClientAcceptProfileSwitch) || config.AAPSCLIENT || doFullSync) {
                            treatment.toProfileSwitch(activePlugin, dateUtil)?.let { profileSwitch ->
                                storeDataForDb.addToProfileSwitches(profileSwitch)
                            }
                        }

                    is NSBolusWizard            ->
                        treatment.toBolusCalculatorResult()?.let { bolusCalculatorResult ->
                            storeDataForDb.addToBolusCalculatorResults(bolusCalculatorResult)
                        }

                    is NSTherapyEvent           ->
                        if (preferences.get(BooleanKey.NsClientAcceptTherapyEvent) || config.AAPSCLIENT || doFullSync)
                            treatment.toTherapyEvent().let { therapyEvent ->
                                storeDataForDb.addToTherapyEvents(therapyEvent)
                                if (therapyEvent.type == TE.Type.ANNOUNCEMENT &&
                                    preferences.get(BooleanKey.NsClientNotificationsFromAnnouncements) &&
                                    therapyEvent.timestamp + T.mins(60).msecs() > dateUtil.now()
                                )
                                    uiInteraction.addNotificationWithAction(
                                        id = Notification.NS_ANNOUNCEMENT,
                                        text = therapyEvent.note ?: "",
                                        level = Notification.ANNOUNCEMENT,
                                        buttonText = app.aaps.core.ui.R.string.snooze,
                                        action = { },
                                        validityCheck = null,
                                        soundId = app.aaps.core.ui.R.raw.alarm,
                                        validTo = dateUtil.now() + T.mins(60).msecs()
                                    )
                            }

                    is NSOfflineEvent           ->
                        if (preferences.get(BooleanKey.NsClientAcceptRunningMode) || config.AAPSCLIENT || doFullSync)
                            treatment.toRunningMode().let { runningMode ->
                                storeDataForDb.addToRunningModes(runningMode)
                            }

                    is NSExtendedBolus          ->
                        if (preferences.get(BooleanKey.NsClientAcceptTbrEb) || config.AAPSCLIENT || doFullSync)
                            treatment.toExtendedBolus().let { extendedBolus ->
                                storeDataForDb.addToExtendedBoluses(extendedBolus)
                            }
                }
            }
            if (latestDateInReceivedData > 0)
                activePlugin.activeNsClient?.updateLatestTreatmentReceivedIfNewer(latestDateInReceivedData)
            return latestDateInReceivedData > 0
        } catch (error: Exception) {
            aapsLogger.error("Error: ", error)
            rxBus.send(EventNSClientNewLog("◄ ERROR", error.localizedMessage))
        }
        return false
    }

    fun processFood(data: Any) {
        aapsLogger.debug(LTag.NSCLIENT, "Received Food Data: $data")

        try {
            val foods = mutableListOf<FD>()
            if (data is JSONArray) {
                for (index in 0 until data.length()) {
                    val jsonFood: JSONObject = data.getJSONObject(index)

                    if (JsonHelper.safeGetString(jsonFood, "type") != "food") continue

                    when (JsonHelper.safeGetString(jsonFood, "action")) {
                        "remove" -> {
                            val delFood = FD(
                                name = "",
                                portion = 0.0,
                                carbs = 0,
                                isValid = false
                            ).also { it.ids.nightscoutId = JsonHelper.safeGetString(jsonFood, "_id") }
                            foods += delFood
                        }

                        else     -> {
                            val food = FD.fromJson(jsonFood)
                            if (food != null) foods += food
                            else aapsLogger.error(LTag.NSCLIENT, "Error parsing food", jsonFood.toString())
                        }
                    }
                }
            } else if (data is List<*>) {
                for (i in 0 until data.size)
                    foods += (data[i] as NSFood).toFood()
            }
            storeDataForDb.addToFoods(foods)
        } catch (error: Exception) {
            aapsLogger.error("Error: ", error)
            rxBus.send(EventNSClientNewLog("◄ ERROR", error.localizedMessage))
        }
    }

    fun processProfile(profileJson: JSONObject, doFullSync: Boolean) {
        if (preferences.get(BooleanKey.NsClientAcceptProfileStore) || config.AAPSCLIENT || doFullSync) {
            val store = profileStoreProvider.get().with(profileJson)
            val createdAt = store.getStartDate()
            val lastLocalChange = preferences.get(LongNonKey.LocalProfileLastChange)
            aapsLogger.debug(LTag.PROFILE, "Received profileStore: createdAt: $createdAt Local last modification: $lastLocalChange")
            if (createdAt > lastLocalChange || createdAt % 1000 == 0L) { // whole second means edited in NS
                profileSource.loadFromStore(store)
                activePlugin.activeNsClient?.dataSyncSelector?.profileReceived(store.getStartDate())
                aapsLogger.debug(LTag.PROFILE, "Received profileStore: $profileJson")
            }
        }
    }
}