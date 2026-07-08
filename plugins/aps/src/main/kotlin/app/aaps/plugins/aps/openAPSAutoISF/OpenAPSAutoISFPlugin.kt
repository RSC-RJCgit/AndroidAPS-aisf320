package app.aaps.plugins.aps.openAPSAutoISF

import android.content.Context
import android.content.Intent
import android.icu.util.Calendar
import androidx.collection.LongSparseArray
import androidx.collection.forEach
import androidx.core.net.toUri
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.AIV
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntentKey
import app.aaps.core.keys.LongKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.getPassedDurationToTimeInMinutes
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.objects.extensions.target
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.utils.MidnightUtils
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveIntentPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.core.validators.preferences.AdaptiveUnitPreference
import app.aaps.plugins.aps.OpenAPSFragment
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.aps.openAPSSMB.PhoneMovementDetector
import app.aaps.plugins.aps.openAPSSMB.StepService
import com.google.gson.Gson
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Singleton
open class OpenAPSAutoISFPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val constraintsChecker: ConstraintsChecker,
    rh: ResourceHelper,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val config: Config,
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val hardLimits: HardLimits,
    private val preferences: Preferences,
    protected val dateUtil: DateUtil,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val persistenceLayer: PersistenceLayer,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val bgQualityCheck: BgQualityCheck,
    private val uiInteraction: UiInteraction,
    private val determineBasalAutoISF: DetermineBasalAutoISF,
    private val profiler: Profiler,
    private val glucoseStatusCalculatorAutoIsf: GlucoseStatusCalculatorAutoIsf,
    private val apsResultProvider: Provider<APSResult>,
    private val tddCalculator: TddCalculator
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.APS)
        .fragmentClass(OpenAPSFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
        .pluginName(R.string.openaps_auto_isf)
        .shortName(R.string.autoisf_shortname)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .preferencesVisibleInSimpleMode(false)
        .showInList { config.APS && config.isEngineeringMode() && config.isDev() }
        .description(R.string.description_auto_isf),
    aapsLogger, rh
), APS, PluginConstraints {

    private var bgAcce: Double = 0.0  // <-- here
    private var steps180: Int = 0  // add this
    private var steps15: Int = 0  // add this
    private var steps5: Int = 0  // add this
    @Inject lateinit var automationStateService: AutomationStateInterface
    @Inject lateinit var smsCommunicator: SmsCommunicator

    // last values
    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.AUTO_ISF
    override var lastAPSResult: APSResult? = null
    private var consoleError = mutableListOf<String>()
    private var consoleLog = mutableListOf<String>()
    val autoIsfVersion = "3.2.0"
    val autoIsfWeights; get() = preferences.get(BooleanKey.ApsUseAutoIsfWeights)
    private val autoISF_max; get() = preferences.get(DoubleKey.ApsAutoIsfMax)
    private val autoISF_min; get() = preferences.get(DoubleKey.ApsAutoIsfMin)
    private val bgAccel_ISF_weight; get() = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
    private val bgBrake_ISF_weight; get() = preferences.get(DoubleKey.ApsAutoIsfBgBrakeWeight)
    private val pp_ISF_weight; get() = preferences.get(DoubleKey.ApsAutoIsfPpWeight)
    private val lower_ISFrange_weight; get() = preferences.get(DoubleKey.ApsAutoIsfLowBgWeight)
    private val higher_ISFrange_weight; get() = preferences.get(DoubleKey.ApsAutoIsfHighBgWeight)
    private val dura_ISF_weight; get() = preferences.get(DoubleKey.ApsAutoIsfDuraWeight)
    private val smb_delivery_ratio; get() = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatio)
    private val smb_delivery_ratio_min; get() = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioMin)
    private val smb_delivery_ratio_max; get() = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioMax)
    private val smb_delivery_ratio_bg_range
        get() = if (preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange) < 10.0) preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange) * GlucoseUnit.MMOLL_TO_MGDL else preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange)
    val smbMaxRangeExtension; get() = preferences.get(DoubleKey.ApsAutoIsfSmbMaxRangeExtension)
    private val enableSMB_EvenOn_OddOff_always; get() = preferences.get(BooleanKey.ApsAutoIsfSmbOnEvenTarget) // for profile target
    val iobThresholdPercent; get() = preferences.get(IntKey.ApsAutoIsfIobThPercent)
    private val exerciseMode; get() = SMBDefaults.exercise_mode
    private val highTemptargetRaisesSensitivity; get() = preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens)
    val mgdlHalfBasalExerciseTarget; get() = preferences.get(UnitDoubleKey.ApsAutoIsfHalfBasalExerciseTarget) * if (profileFunction.getUnits() == GlucoseUnit.MMOL) GlucoseUnit.MMOLL_TO_MGDL else 1.0
    val normalTarget = 100
    val calibrationDuration = preferences.get(IntKey.FslCalibrationDuration)
    private val minutesClass; get() = if (preferences.get(IntKey.ApsMaxSmbFrequency) == 1) 6L else 30L  // ga-zelle: later get correct 1 min CGM flag from glucoseStatus ? ... or from apsResults?
    private val disposable = CompositeDisposable()

    // create array for key AutoISF results with defaults
    var autoIsfValues = AIV(
        timestamp = 0L,
        acceIsf = 1.0,
        bgIsf = 1.0,
        ppIsf = 1.0,
        driftIsf = 1.0,
        duraIsf = 1.0,
        finalIsf = 1.0,
        iobThEffective = 0.0
    )

    // Activity detection (steps)
    private val recentSteps5Minutes; get() = StepService.getRecentStepCount5Min()
    private val recentSteps10Minutes; get() = StepService.getRecentStepCount10Min()
    private val recentSteps15Minutes; get() = StepService.getRecentStepCount15Min()
    private val recentSteps30Minutes; get() = StepService.getRecentStepCount30Min()
    private val recentSteps60Minutes; get() = StepService.getRecentStepCount60Min()
    private val phone_moved; get() = PhoneMovementDetector.phoneMoved()

    override fun onStart() {
        super.onStart()
        var count = 0
        val apsResults = persistenceLayer.getApsResults(dateUtil.now() - T.days(1).msecs(), dateUtil.now())
        apsResults.forEach {
            val glucose = it.glucoseStatus?.glucose ?: return@forEach
            val variableSens = it.variableSens ?: return@forEach
            val timestamp = it.date
            val key = timestamp - timestamp % T.mins(minutesClass).msecs() + glucose.toLong()
            if (variableSens > 0) autoIsfCache.put(key, variableSens)
            count++
        }
        aapsLogger.debug(LTag.APS, "Loaded $count variable sensitivity values from database")
    }

    // irrelevant here but gets called by other profile functions and must be TRUE; otherwise averageISF falls back to profile sens
    override fun supportsDynamicIsf() = true    //false //: Boolean = preferences.get(BooleanKey.ApsUseAutoIsf)

    override fun getIsfMgdl(profile: Profile, caller: String): Double? {
        val start = dateUtil.now()
        val multiplier = (profile as ProfileSealed.EPS).value.originalPercentage / 100.0
        val sensitivity = calculateVariableIsf(start)
        if (sensitivity.second == null && caller == "OpenAPSSMBPlugin")
            uiInteraction.addNotificationValidTo(
                Notification.DYN_ISF_FALLBACK, start,
                rh.gs(R.string.fallback_to_isf_no_tdd, sensitivity.first), Notification.INFO, dateUtil.now() + T.mins(1).msecs()
            )
        else
            uiInteraction.dismissNotification(Notification.DYN_ISF_FALLBACK)
        profiler.log(LTag.APS, String.format(Locale.getDefault(), "getIsfMgdl() %s %f %s %s", sensitivity.first, sensitivity.second, dateUtil.dateAndTimeAndSecondsString(start), caller), start)
        return sensitivity.second?.let { it * multiplier }
    }

    override fun getAverageIsfMgdl(timestamp: Long, caller: String): Double? {
        var count = 0
        var sum = 0.0
        val start = timestamp - T.hours(24).msecs()
        autoIsfCache.forEach { key, value ->
            if (key in start..timestamp) {
                count++
                sum += value
            }
        }
        val sensitivity = if (count == 0) null else sum / count
        aapsLogger.debug(LTag.APS, "getAverageIsfMgdl() $sensitivity from $count values ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $caller")
        return sensitivity
    }

    override fun getSensitivityOverviewString(): String? = null // placeholder for Auto ISF Detailed information for overview

    override fun specialEnableCondition(): Boolean {
        return config.isEngineeringMode() && config.isDev() &&
            try {
                activePlugin.activePump.pumpDescription.isTempBasalCapable
            } catch (_: Exception) {
                // may fail during initialization
                true
            }
    }
    /*fun isEven(value: Double): Boolean {
        return value % 1 == 0.0 && value.toInt() % 2 == 0
    }*/

    override fun specialShowInListCondition(): Boolean {
        try {
            val pump = activePlugin.activePump
            return pump.pumpDescription.isTempBasalCapable
        } catch (_: Exception) {
            return true
        }
    }

    override fun preprocessPreferences(preferenceFragment: PreferenceFragmentCompat) {
        super.preprocessPreferences(preferenceFragment)
        val smbAlwaysEnabled = preferences.get(BooleanKey.ApsUseSmbAlways)
        val advancedFiltering = activePlugin.activeBgSource.advancedFilteringSupported()
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbWithCob.key)?.isVisible = !smbAlwaysEnabled || !advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbWithLowTt.key)?.isVisible = !smbAlwaysEnabled || !advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbAfterCarbs.key)?.isVisible = !smbAlwaysEnabled || !advancedFiltering
    }

    private val autoIsfCache = LongSparseArray<Double>()

    @Synchronized
    private fun calculateVariableIsf(timestamp: Long): Pair<String, Double?> {
        val profile = profileFunction.getProfile(timestamp)
        if (profile == null) return Pair("OFF", null)
        val glucose = glucoseStatusProvider.glucoseStatusData?.glucose ?: return Pair("GLUC", null)
        // Round down to minutesClass min and use it as a key for caching
        // Add BG to key as it affects calculation
        val key = timestamp - timestamp % T.mins(minutesClass).msecs() + glucose.toLong()
        val sensitivity = autoISF(profile)
        if (sensitivity > 0) {
            // can default to 0, e.g. for the first 2-3 loops in a virgin setup
            aapsLogger.debug("calculateVariableIsf CALC ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $sensitivity")
            autoIsfCache.put(key, sensitivity)
            if (autoIsfCache.size() > 1000) autoIsfCache.clear()
        }
        // this return is mandatory, otherwise it messed up the AutoISF algo.
        return Pair("OFF", null)
    }

    // Not yet called anywhere; ready for later conditions that need to switch profile in code, without an automation.
    // Mirrors ActionProfileSwitch: no-ops if targetProfileName is already active or doesn't exist.
    private fun switchProfileIfNeeded(targetProfileName: String, durationInMinutes: Int = 0): Boolean {
        if (targetProfileName == profileFunction.getProfileName()) return false
        val profileStore = activePlugin.activeProfileSource.profile ?: return false
        if (profileStore.getSpecificProfile(targetProfileName) == null) return false
        return profileFunction.createProfileSwitch(
            profileStore = profileStore,
            profileName = targetProfileName,
            durationInMinutes = durationInMinutes,
            percentage = 100,
            timeShiftInHours = 0,
            timestamp = dateUtil.now(),
            action = Action.PROFILE_SWITCH,
            source = Sources.Automation,
            note = "AutoISF code-based profile switch",
            listValues = listOf(
                ValueWithUnit.SimpleString(targetProfileName),
                ValueWithUnit.Percent(100)
            )
        )
    }

    // Force a profile switch to the current profile at 100%, even when already on that named profile.
    // Needed to cancel a temporary % reduction (e.g. prepare50 sets profile to 50% for 360 min).
    // switchProfileIfNeeded() short-circuits when the name matches; this never short-circuits.
    private fun applyCurrentProfileAt100() {
        val profileStore = activePlugin.activeProfileSource.profile ?: return
        val profileName = profileFunction.getProfileName()
        profileFunction.createProfileSwitch(
            profileStore = profileStore,
            profileName = profileName,
            durationInMinutes = 0,
            percentage = 100,
            timeShiftInHours = 0,
            timestamp = dateUtil.now(),
            action = Action.PROFILE_SWITCH,
            source = Sources.Automation,
            note = "AutoISF: reset to 100%",
            listValues = listOf(
                ValueWithUnit.SimpleString(profileName),
                ValueWithUnit.Percent(100)
            )
        )
    }

    // Sets a temporary 50% profile for 360 min on the current named profile.
    // Mirrors "Start profile 50% for 360 min" action. Does not guard against an existing switch.
    private fun startProfile50For360() {
        val profileStore = activePlugin.activeProfileSource.profile ?: return
        val profileName = profileFunction.getProfileName()
        profileFunction.createProfileSwitch(
            profileStore = profileStore,
            profileName = profileName,
            durationInMinutes = 360,
            percentage = 50,
            timeShiftInHours = 0,
            timestamp = dateUtil.now(),
            action = Action.PROFILE_SWITCH,
            source = Sources.Automation,
            note = "AutoISF: prepare 50% for 360 min",
            listValues = listOf(ValueWithUnit.Percent(50), ValueWithUnit.Minute(360))
        )
    }

    // Raw CGM helpers — use gv.noise (Libre native signal), same source as graph "L=" annotation.
    private fun rawGlucoseMgdl(): Double? {
        val now = dateUtil.now()
        return persistenceLayer.getBgReadingsDataFromTimeToTime(now - 10 * 60 * 1000L, now, ascending = false).firstOrNull()?.noise
    }

    private fun rawDelta1MinMgdl(): Double? {
        val now = dateUtil.now()
        val r = persistenceLayer.getBgReadingsDataFromTimeToTime(now - 3 * 60 * 1000L, now, ascending = false)
        if (r.size < 2) return null
        val n = r[0].noise ?: return null
        val p = r[1].noise ?: return null
        val mins = (r[0].timestamp - r[1].timestamp) / 60_000.0
        if (mins <= 0) return null
        return (n - p) / mins * 5.0
    }

    private fun rawDelta5MinMgdl(): Double? {
        val now = dateUtil.now()
        val r = persistenceLayer.getBgReadingsDataFromTimeToTime(now - 7 * 60 * 1000L, now, ascending = false)
        if (r.size < 2) return null
        val newest = r[0].noise ?: return null
        val fiveMinAgo = now - 5 * 60 * 1000L
        val ref = r.minByOrNull { kotlin.math.abs(it.timestamp - fiveMinAgo) } ?: return null
        if (ref.timestamp == r[0].timestamp) return null
        return newest - (ref.noise ?: return null)
    }

    // True when the local clock is inside [startH:startM, endH:endM). Handles overnight ranges
    // (e.g. 22:00–01:00) by checking the complement and inverting.
    private fun isTimeBetween(startH: Int, startM: Int, endH: Int, endM: Int): Boolean {
        val cal = Calendar.getInstance()
        val nowMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMins = startH * 60 + startM
        val endMins   = endH * 60 + endM
        return if (startMins <= endMins) nowMins in startMins until endMins
        else nowMins >= startMins || nowMins < endMins   // overnight: wraps midnight
    }

    // Hours since the last recorded cannula/site change, or null if none found.
    private fun hoursSinceLastCannulaChange(): Double? {
        val last = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE) ?: return null
        return (dateUtil.now() - last.timestamp) / 3_600_000.0
    }

    // Not yet called anywhere; ready for later conditions that need "time since last bolus" in code.
    // Mirrors TriggerBolusAgo: returns null (not just a huge number) when no NORMAL bolus has ever been logged,
    // so callers must decide explicitly how to treat "no history yet" rather than it silently always-passing.
    private fun minutesSinceLastNormalBolus(): Int? {
        val lastBolusTime = persistenceLayer.getNewestBolusOfType(BS.Type.NORMAL)?.timestamp ?: return null
        return ((dateUtil.now() - lastBolusTime).toDouble() / (60 * 1000)).toInt()
    }

    // Not yet called anywhere; ready for later conditions. Mirrors ActionSetAcceWeight: same underlying
    // preference key ("bgAccel_ISF_weight") the DoubleKey.ApsAutoIsfBgAccelWeight getter already reads.
    private fun setBgAccelIsfWeight(weight: Double) {
        preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, weight)
    }

    // Returns the active TT's lowTarget in mg/dL, or null if no TT is active.
    private fun activeTtMgdl(): Double? = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.lowTarget

    // Cancels the current TT unconditionally. Mirrors ActionStopTempTarget.
    private fun cancelCurrentTempTarget() {
        disposable += persistenceLayer.cancelCurrentTemporaryTargetIfAny(
            timestamp = dateUtil.now(),
            action = Action.CANCEL_TT,
            source = Sources.Automation,
            note = "AutoISF code-based TT cancel",
            listValues = listOf()
        ).subscribe()
    }

    // Not yet called anywhere; ready for later conditions. Mirrors ActionStartTempTarget, including its
    // own built-in guard: no-ops if a temp target is already active rather than stacking/replacing it.
    private fun startTempTargetIfNeeded(targetMgdl: Double, durationInMinutes: Int): Boolean {
        if (persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now()) != null) return false
        val tt = TT(
            timestamp = dateUtil.now(),
            duration = TimeUnit.MINUTES.toMillis(durationInMinutes.toLong()),
            reason = TT.Reason.AUTOMATION,
            lowTarget = targetMgdl,
            highTarget = targetMgdl
        )
        disposable += persistenceLayer.insertAndCancelCurrentTemporaryTarget(
            temporaryTarget = tt,
            action = Action.TT,
            source = Sources.Automation,
            note = "AutoISF code-based temp target",
            listValues = listOf(
                ValueWithUnit.TETTReason(TT.Reason.AUTOMATION),
                ValueWithUnit.Mgdl(targetMgdl),
                ValueWithUnit.Minute(durationInMinutes)
            )
        ).subscribe()
        return true
    }

    // Not yet called anywhere; ready for later conditions. Mirrors ActionSendSMS.
    private fun sendSms(text: String): Boolean = smsCommunicator.sendNotificationToAllNumbers(text)

    // Mirrors ActionCarePortalEvent for a plain note. Default duration is 5 min (not the 30 min the
    // original ported automations used) per explicit preference.
    private fun addCarePortalNote(note: String, durationInMinutes: Int = 5) {
        val therapyEvent = TE(
            timestamp = dateUtil.now(),
            type = TE.Type.NOTE,
            glucoseUnit = profileFunction.getUnits()
        ).apply {
            this.note = note
            this.duration = TimeUnit.MINUTES.toMillis(durationInMinutes.toLong())
        }
        disposable += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = therapyEvent,
            action = Action.CAREPORTAL,
            source = Sources.Automation, // matches ActionCarePortalEvent, which your working automations use
            note = "AutoISF code-based note",
            listValues = listOf(ValueWithUnit.SimpleString(note))
        ).subscribe()
    }

    // Not yet called anywhere; ready for later conditions. Mirrors TriggerAutomationState: exact string
    // equality (state values are names, not numbers), gated the same way — false when states are disabled.
    // Not affected by the fuzzy-equals tolerance noted below, since that only applies to Double comparisons.
    private fun checkAutomationState(stateName: String, stateValue: String): Boolean {
        if (!preferences.get(BooleanKey.AutomationStatesEnabled)) return false
        return automationStateService.inState(stateName, stateValue)
    }

    // Not yet called anywhere; ready for later conditions. Mirrors ActionSetAutomationState: no-ops (rather
    // than throwing) when states are disabled or stateValue isn't a valid value for stateName — setState()
    // itself throws IllegalStateException for an unknown stateName/stateValue, same as the automation action.
    private fun setAutomationState(stateName: String, stateValue: String): Boolean {
        if (!preferences.get(BooleanKey.AutomationStatesEnabled)) return false
        automationStateService.setState(stateName, stateValue)
        return true
    }

    // Ready for later conditions that could otherwise re-fire every loop cycle (down to 1-minute cycles)
    // instead of a minimum interval. Mirrors AutomationEventObject's own repeatInterval/lastRun throttle.
    // `key` should be unique per ported condition (e.g. its old automation title).
    private val lastRunTimestamps = mutableMapOf<String, Long>()

    private fun readyToRun(key: String, minIntervalMinutes: Int): Boolean =
        (lastRunTimestamps[key] ?: 0L) <= dateUtil.now() - T.mins(minIntervalMinutes.toLong()).msecs()

    private fun markRun(key: String) {
        lastRunTimestamps[key] = dateUtil.now()
    }

    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator tempBasalFallback: $tempBasalFallback")
        lastAPSResult = null
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val profile = profileFunction.getProfile()
        val pump = activePlugin.activePump
        if (profile == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(app.aaps.core.ui.R.string.no_profile_set)))
            aapsLogger.debug(LTag.APS, rh.gs(app.aaps.core.ui.R.string.no_profile_set))
            return
        }
        if (!isEnabled()) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_disabled)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_disabled))
            return
        }
        if (glucoseStatus == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_no_glucose_data)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_no_glucose_data))
            return
        }

        val inputConstraints = ConstraintObject(0.0, aapsLogger) // fake. only for collecting all results

        if (!hardLimits.checkHardLimits(profile.dia, app.aaps.core.ui.R.string.profile_dia, hardLimits.minDia(), hardLimits.maxDia())) return
        if (!hardLimits.checkHardLimits(
                profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()),
                app.aaps.core.ui.R.string.profile_carbs_ratio_value,
                hardLimits.minIC(),
                hardLimits.maxIC()
            )
        ) return
        if (!hardLimits.checkHardLimits(profile.getIsfMgdl("OpenAPSAutoISFPlugin"), app.aaps.core.ui.R.string.profile_sensitivity_value, HardLimits.MIN_ISF, HardLimits.MAX_ISF)) return
        if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), app.aaps.core.ui.R.string.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return
        if (!hardLimits.checkHardLimits(pump.baseBasalRate, app.aaps.core.ui.R.string.current_basal_value, 0.01, hardLimits.maxBasal())) return

        // End of check, start gathering data

        val autoIsfMode = true  //supportsDynamicIsf()  // preferences.get(BooleanKey.ApsUseAutoIsf)
        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val advancedFiltering = constraintsChecker.isAdvancedFilteringEnabled().also { inputConstraints.copyReasons(it) }.value()

        val now = dateUtil.now()
        val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        val currentTemp = CurrentTemp(
            duration = tb?.plannedRemainingMinutes ?: 0,
            rate = tb?.convertedToAbsolute(now, profile) ?: 0.0,
            minutesrunning = tb?.getPassedDurationToTimeInMinutes(now)
        )
        var minBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetLowMgdl(), 0.1), app.aaps.core.ui.R.string.profile_low_target, HardLimits.LIMIT_MIN_BG[0], HardLimits.LIMIT_MIN_BG[1])
        var maxBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetHighMgdl(), 0.1), app.aaps.core.ui.R.string.profile_high_target, HardLimits.LIMIT_MAX_BG[0], HardLimits.LIMIT_MAX_BG[1])
        var targetBg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TARGET_BG[0], HardLimits.LIMIT_TARGET_BG[1])
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            minBg = hardLimits.verifyHardLimits(tempTarget.lowTarget, app.aaps.core.ui.R.string.temp_target_low_target, HardLimits.LIMIT_TEMP_MIN_BG[0], HardLimits.LIMIT_TEMP_MIN_BG[1])
            maxBg = hardLimits.verifyHardLimits(tempTarget.highTarget, app.aaps.core.ui.R.string.temp_target_high_target, HardLimits.LIMIT_TEMP_MAX_BG[0], HardLimits.LIMIT_TEMP_MAX_BG[1])
            targetBg = hardLimits.verifyHardLimits(tempTarget.target(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG[0], HardLimits.LIMIT_TEMP_TARGET_BG[1])
        }
        // for key AutoISF results assign defaults
        autoIsfValues = AIV(
            timestamp = now,
            acceIsf = 1.0,
            bgIsf = 1.0,
            ppIsf = 1.0,
            driftIsf = 1.0,
            duraIsf = 1.0,
            finalIsf = 1.0,
            iobThEffective = 0.0
        )

        var autosensResult = AutosensResult()
        var variableSensitivity = profile.getProfileIsfMgdl()
        val sens = profile.getIsfMgdl("OpenAPSAutoISFPlugin")

        if (constraintsChecker.isAutosensModeEnabled().value()) {
            val autosensData = iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSAutoISFPlugin")
            if (autosensData == null) {
                rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openaps_no_as_data)))
                return
            }
            autosensResult = autosensData.autosensResult
        } else autosensResult.sensResult = "autosens disabled"
        val iobArray = iobCobCalculator.calculateIobArrayForSMB(autosensResult, preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens), preferences.get(UnitDoubleKey.ApsAutoIsfHalfBasalExerciseTarget), isTempTarget)
        val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()
        val iobData = iobArray[0]
        val profile_percentage = if (profile is ProfileSealed.EPS) profile.value.originalPercentage else 100
        val splitBolusBlockUntil = preferences.get(LongKey.SplitBolusBlockSmbUntil)
        val splitBolusBlocking = splitBolusBlockUntil > dateUtil.now()
        val microBolusAllowed = if (splitBolusBlocking) {
            inputConstraints.copyReasons(ConstraintObject(false, aapsLogger).also { it.set(false, "Split bolus active — SMBs blocked until ${dateUtil.timeString(splitBolusBlockUntil)}", this) })
            false
        } else {
            constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).also { inputConstraints.copyReasons(it) }.value()
        }

        aapsLogger.debug(LTag.APS, "invoke found step counts 5m:$recentSteps5Minutes, 10m:$recentSteps10Minutes, 15m:$recentSteps15Minutes, 30m:$recentSteps30Minutes, 60m:$recentSteps60Minutes")
        consoleError.clear()
        consoleLog.clear()
        val calendar = Calendar.getInstance()
        val hour = max(1, calendar.get(Calendar.HOUR_OF_DAY))
        val activityRatio = activityMonitor(isTempTarget, glucoseStatus.glucose, targetBg, hour)
        val activityLog = if (consoleLog.size == 0) "Activity Monitor skipped" else consoleLog[0]
        consoleLog.clear()
        var stepActivityDetected = false
        var stepInactivityDetected = false
        if (activityRatio < 1) {
            stepActivityDetected = true
        } else if (activityRatio > 1) {
            stepInactivityDetected = true
        }
        preferences.put(BooleanKey.ActivityMonitorStepsActive, stepActivityDetected)
        preferences.put(BooleanKey.ActivityMonitorStepsInactive, stepInactivityDetected)
        if (autoIsfMode) {
            val graphActivity = 100 * iobCobCalculator.calculateFromTreatmentsAndTemps(dateUtil.now(), profile).activity
            variableSensitivity = autoISF(profile, graphActivity, iobData.activity * 100)
        }
        val lastAppStart = preferences.get(LongKey.AppStart)
        val elapsedTimeSinceLastStart = (dateUtil.now() - lastAppStart).milliseconds.inWholeMinutes
        val oapsProfile = OapsProfileAutoIsf(
            dia = 0.0, // not used
            min_5m_carbimpact = 0.0, // not used
            max_iob = constraintsChecker.getMaxIOBAllowed().also { inputConstraints.copyReasons(it) }.value(),
            max_daily_basal = profile.getMaxDailyBasal(),
            max_basal = constraintsChecker.getMaxBasalAllowed(profile).also { inputConstraints.copyReasons(it) }.value(),
            min_bg = minBg,
            max_bg = maxBg,
            target_bg = targetBg,
            carb_ratio = profile.getIc(),
            sens = sens,
            autosens_adjust_targets = false, // not used
            max_daily_safety_multiplier = preferences.get(DoubleKey.ApsMaxDailyMultiplier),
            current_basal_safety_multiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier),
            lgsThreshold = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt(),
            high_temptarget_raises_sensitivity = exerciseMode || highTemptargetRaisesSensitivity, //was false,
            low_temptarget_lowers_sensitivity = preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens), // was false,
            sensitivity_raises_target = preferences.get(BooleanKey.ApsSensitivityRaisesTarget),
            resistance_lowers_target = preferences.get(BooleanKey.ApsResistanceLowersTarget),
            adv_target_adjustments = SMBDefaults.adv_target_adjustments,
            exercise_mode = SMBDefaults.exercise_mode,
            half_basal_exercise_target = preferences.get(UnitDoubleKey.ApsAutoIsfHalfBasalExerciseTarget),
            // mod activity mode
            activity_detection = preferences.get(BooleanKey.ApsActivityDetection),
            recent_steps_5_minutes = recentSteps5Minutes,
            recent_steps_10_minutes = recentSteps10Minutes,
            recent_steps_15_minutes = recentSteps15Minutes,
            recent_steps_30_minutes = recentSteps30Minutes,
            recent_steps_60_minutes = recentSteps60Minutes,
            phone_moved = phone_moved,
            time_since_start = elapsedTimeSinceLastStart,
            now = calendar.get(Calendar.HOUR_OF_DAY),
            // end mod
            maxCOB = SMBDefaults.maxCOB,
            skip_neutral_temps = pump.setNeutralTempAtFullHour(),
            remainingCarbsCap = SMBDefaults.remainingCarbsCap,
            enableUAM = constraintsChecker.isUAMEnabled().also { inputConstraints.copyReasons(it) }.value(),
            A52_risk_enable = SMBDefaults.A52_risk_enable,
            SMBInterval = preferences.get(IntKey.ApsMaxSmbFrequency),
            enableSMB_with_COB = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithCob),
            enableSMB_with_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithLowTt),
            allowSMB_with_high_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithHighTt),
            enableSMB_always = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAlways) && advancedFiltering,
            enableSMB_after_carbs = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAfterCarbs) && advancedFiltering,
            maxSMBBasalMinutes = preferences.get(IntKey.ApsMaxMinutesOfBasalToLimitSmb),
            maxUAMSMBBasalMinutes = preferences.get(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb),
            bolus_increment = pump.pumpDescription.bolusStep,
            carbsReqThreshold = preferences.get(IntKey.ApsCarbsRequestThreshold),
            current_basal = activePlugin.activePump.baseBasalRate,
            temptargetSet = isTempTarget,
            autosens_max = preferences.get(DoubleKey.AutosensMax),
            out_units = if (profileFunction.getUnits() == GlucoseUnit.MMOL) "mmol/L" else "mg/dl",
            variable_sens = variableSensitivity,
            autoISF_version = autoIsfVersion,
            enable_autoISF = autoIsfWeights,
            autoISF_max = autoISF_max,
            autoISF_min = autoISF_min,
            bgAccel_ISF_weight = bgAccel_ISF_weight,
            bgBrake_ISF_weight = bgBrake_ISF_weight,
            pp_ISF_weight = pp_ISF_weight,
            lower_ISFrange_weight = lower_ISFrange_weight,
            higher_ISFrange_weight = higher_ISFrange_weight,
            dura_ISF_weight = dura_ISF_weight,
            smb_delivery_ratio = smb_delivery_ratio,
            smb_delivery_ratio_min = smb_delivery_ratio_min,
            smb_delivery_ratio_max = smb_delivery_ratio_max,
            smb_delivery_ratio_bg_range = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange),   //smb_delivery_ratio_bg_range was always in mg/dL
            smb_max_range_extension = smbMaxRangeExtension,
            enableSMB_EvenOn_OddOff_always = enableSMB_EvenOn_OddOff_always,
            iob_threshold_percent = iobThresholdPercent,
            profile_percentage = profile_percentage
        )
        var sensitivityRatio = 1.0
        // TODO eliminate
        val target_bg = (minBg + maxBg) / 2
        val exerciseModeActive = highTemptargetRaisesSensitivity && isTempTarget && target_bg > normalTarget
        val resistanceModeActive = oapsProfile.low_temptarget_lowers_sensitivity && isTempTarget && target_bg < normalTarget
        if (exerciseModeActive || resistanceModeActive || stepActivityDetected || stepInactivityDetected) {
            if (exerciseModeActive || resistanceModeActive) {
                // w/ target 100, temp target 110 = .89, 120 = 0.8, 140 = 0.67, 160 = .57, and 200 = .44
                // e.g.: Sensitivity ratio set to 0.8 based on temp target of 120; Adjusting basal from 1.65 to 1.35; ISF from 58.9 to 73.6
                //sensitivityRatio = 2/(2+(target_bg-normalTarget)/40);
                val resistanceMax = min(1.5, preferences.get(DoubleKey.AutosensMax))  // additional safety limit
                val c = (mgdlHalfBasalExerciseTarget - normalTarget)
                if (c * (c + target_bg - normalTarget) <= 0.0) {
                    sensitivityRatio = resistanceMax
                } else {
                    sensitivityRatio = c / (c + target_bg - normalTarget)
                    // limit sensitivityRatio to profile.autosens_max (1.2x by default)
                    sensitivityRatio = min(sensitivityRatio, resistanceMax)
                    sensitivityRatio = round(sensitivityRatio, 2)
                    //exerciseRatio = sensitivityRatio
                }
            } else {
                sensitivityRatio = activityRatio
            }
        }
        var iobTH_reduction_ratio = 1.0
        var use_iobTH = false
        if (iobThresholdPercent != 100) {
            iobTH_reduction_ratio = profile_percentage / 100.0 * sensitivityRatio
            use_iobTH = true
        }
        val iobTHtolerance = 130.0
        val iobTHvirtual = iobThresholdPercent * iobTHtolerance / 10000.0 * oapsProfile.max_iob * iobTH_reduction_ratio
        val loopWantedSmb = loop_smb(microBolusAllowed, oapsProfile, iobData.iob, use_iobTH, iobTHvirtual / iobTHtolerance * 100.0)
        (glucoseStatus as? GlucoseStatusAutoIsf)?.let {
            autoIsfValues.glucose = it.glucose
            autoIsfValues.delta = it.delta
            autoIsfValues.shortAvgDelta = it.shortAvgDelta
            autoIsfValues.bgAcceleration = it.bgAcceleration
        }
        val flatBGsDetected = bgQualityCheck.state == BgQualityCheck.State.FLAT
        val smbRatio = determine_varSMBratio(glucoseStatus.glucose.toInt(), target_bg, loopWantedSmb)

        // Code port of the "Test" automation (MJ=MJ4). Self-guarding: state change prevents re-fire.
        if (checkAutomationState("MJ", "MJ4")) {
            addCarePortalNote("A1")
            setAutomationState("MJ", "NOMJremains")
        }

        // Code port of the "Test2" automation (MJ=MJ5): also switches to Current ProfileReal for 30 min.
        if (checkAutomationState("MJ", "MJ5")) {
            addCarePortalNote("A1")
            switchProfileIfNeeded("Current ProfileReal", 30)
            setAutomationState("MJ", "NOMJremains")
        }

        // --- MJ2 old: advances MJ state from "MJ active" → MJ2 at 02:10–03:10 AM ---
        if (checkAutomationState("MJ", "MJ active") && isTimeBetween(2, 10, 3, 10)) {
            sendSms("MJ2")
            setAutomationState("MJ", "MJ2")
            addCarePortalNote("MJ2")
            setAutomationState("MJstate", "MJon")
        }

        // --- MJ3 old: advances MJ state from MJ2 → MJ3 at 01:05–02:05 AM ---
        if (checkAutomationState("MJ", "MJ2") && isTimeBetween(1, 5, 2, 5)) {
            sendSms("MJ3")
            setAutomationState("MJ", "MJ3")
            addCarePortalNote("MJ3")
            setAutomationState("MJstate", "MJon")
        }

        // --- MJoff old: exits MJ cycle when MJ3 active ---
        // Block 1: 12:00–21:04 with BGL >= 10.5 mmol. Block 2: midnight 00:00–01:00.
        if (checkAutomationState("MJ", "MJ3")) {
            val g = glucoseStatus.glucose
            val mjB1 = isTimeBetween(12, 0, 21, 4) && g >= 189.2   // 10.5 mmol
            val mjB2 = isTimeBetween(0, 0, 1, 0)
            val mjBlock = when { mjB1 -> "1"; mjB2 -> "2"; else -> null }
            if (mjBlock != null) {
                sendSms("MJoff [b$mjBlock]: g=${String.format("%.1f", g / 18.016)}")
                setAutomationState("MJ", "NOMJremains")
                addCarePortalNote("MJoff-$mjBlock")
                setAutomationState("MJstate", "MJoff")
            }
        }

        // --- prepare Set50%: replaces "prepare Set50%0.07 50%" automation ---
        // Precondition guard: profile_percentage == 100. Once the 50% profile switch fires,
        // profile_percentage becomes 50 on the next loop cycle and the block stops running.
        // All 4 blocks also check Profile pct = 100 in the original; the outer guard handles that.
        if (profile_percentage == 100) {
            val g   = glucoseStatus.glucose
            val d   = glucoseStatus.delta
            val sd  = glucoseStatus.shortAvgDelta
            val iob = iobData.iob
            val cob = mealData.mealCOB

            // Block 1 — dual delta confirmation, no carbs
            val p50b1 = g <= 99.1 /* 5.5 */ && d <= -4.50 /* -0.25 */ && sd <= -4.50 && cob == 0.0

            // Block 2 — exercise + IOB risk: moving with significant IOB and falling
            val p50b2 = g <= 99.1 /* 5.5 */ && recentSteps30Minutes >= 300 &&
                cob == 0.0 && iob >= 1.2 && d <= -1.80 /* -0.10 */

            // Block 3 — fallback: any slight fall below 5.0
            val p50b3 = g < 90.1 /* 5.0 */ && d <= -0.90 /* -0.05 */

            // Block 4 — pre-sleep: falling into sleep window at higher glucose
            val p50b4 = g <= 126.1 /* 7.0 */ && isTimeBetween(21, 0, 0, 0) &&
                sd <= -1.80 /* -0.10 */ && d <= -3.60 /* -0.20 */

            val p50block = when { p50b1->"1"; p50b2->"2"; p50b3->"3"; p50b4->"4"; else->null }
            if (p50block != null) {
                setBgAccelIsfWeight(0.07)
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 50)
                startProfile50For360()
                setAutomationState("LowBG", "50recent")
                sendSms("prepare Set50% [b$p50block]: g=${String.format("%.1f", g / 18.016)} d=${String.format("%.2f", d / 18.016)}")
                addCarePortalNote("Set50-$p50block")
                aapsLogger.debug(LTag.APS, "prepare50 block $p50block: g=${String.format("%.1f", g / 18.016)}mmol d=${String.format("%.2f", d / 18.016)} iob=${String.format("%.2f", iob)} cob=${cob.toInt()} steps30=$recentSteps30Minutes")
            }
        }

        // --- GentleHypoRiskOver4.5: escalates from prepare50 state (weight 0.07) to Skittles state (0.02) ---
        // Guard: acce weight 0.03–0.08 (only fires when prepare50 is active; Skittles weight 0.02 falls below).
        // 30-min throttle via readyToRun/markRun. Uses Raw CGM (gv.noise) for additional safety checks.
        if (readyToRun("GentleHypoRisk", 30)) {
            val acceW = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
            if (acceW > 0.03 && acceW <= 0.08 && isTimeBetween(7, 30, 22, 0)) {
                val g    = glucoseStatus.glucose
                val d    = glucoseStatus.delta
                val sd   = glucoseStatus.shortAvgDelta
                val rawG  = rawGlucoseMgdl()
                val rawD1 = rawDelta1MinMgdl()
                val rawD5 = rawDelta5MinMgdl()

                // Block 1: AAPS BGL ≤ 5.3 + raw BGL ≤ 4.0 + OR(raw ≤ 3.5, raw-1min < 0, raw-5min < 0)
                val rawLow = rawG != null && rawG <= 72.1 /* 4.0 mmol */
                val rawOr1 = (rawG != null && rawG <= 63.1 /* 3.5 */) ||
                    (rawD1 != null && rawD1 < 0.0) ||
                    (rawD5 != null && rawD5 < 0.0)
                val ghB1 = g <= 95.5 /* 5.3 */ && rawLow && rawOr1

                // Block 2: AAPS BGL ≤ 5.5 + profile=50% + delta ≤ -0.3 + sdelta ≤ -0.2 + raw fallback OR
                // Note: d ≤ -0.3 already implies d ≤ 0.0, so the OR's AAPS-delta arm is always satisfied.
                val rawOr2 = (rawG != null && rawG <= 63.1 /* 3.5 */) ||
                    d <= 0.0 ||
                    (rawD5 != null && rawD5 <= 0.0)
                val ghB2 = g <= 99.1 /* 5.5 */ && profile_percentage == 50 &&
                    d <= -5.40 /* -0.30 */ && sd <= -3.60 /* -0.20 */ && rawOr2

                val ghBlock = when { ghB1 -> "1"; ghB2 -> "2"; else -> null }
                if (ghBlock != null) {
                    setBgAccelIsfWeight(0.02)
                    preferences.put(IntKey.ApsAutoIsfIobThPercent, 50)
                    sendSms("GentleHypoRisk [b$ghBlock]: g=${String.format("%.1f", g / 18.016)} d=${String.format("%.2f", d / 18.016)}")
                    uiInteraction.addNotification(id = 9001, text = "GentleHypoRisk G5 [b$ghBlock]: g=${String.format("%.1f", g / 18.016)}mmol", level = Notification.URGENT)
                    setAutomationState("MJstate", "MJon")
                    setAutomationState("BGLstate", "BGLlastLOW")
                    markRun("GentleHypoRisk")
                    aapsLogger.debug(LTag.APS, "GentleHypoRisk block $ghBlock: g=${String.format("%.1f", g / 18.016)}mmol d=${String.format("%.2f", d / 18.016)} acceW=$acceW rawG=${rawG?.let { String.format("%.1f", it / 18.016) }} rawD1=${rawD1?.let { String.format("%.2f", it / 18.016) }} rawD5=${rawD5?.let { String.format("%.2f", it / 18.016) }}")
                }
            }
        }

        // --- PP50.Off: replaces "PP50.Off CurrProfReal 70_0.70 0.35" automation ---
        // Exits the 50% prepare state: restores full profile, resets acce weight, clears LowBG=50recent.
        // Guard: LowBG=50recent state must be set; firing sets LowBG=NO50rec so it won't re-trigger.
        if (checkAutomationState("LowBG", "50recent")) {
            val g   = glucoseStatus.glucose
            val d   = glucoseStatus.delta
            val sd  = glucoseStatus.shortAvgDelta
            val ld  = glucoseStatus.longAvgDelta
            val iob = iobData.iob
            val cob = mealData.mealCOB
            val cannulaH = hoursSinceLastCannulaChange() ?: 0.0
            val lastBolusMin = minutesSinceLastNormalBolus() ?: Int.MAX_VALUE
            val acceWeight = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)

            // Daytime window: 01:01 – 22:00. Overnight window: 22:00 – 01:00.
            val isDaytime  = isTimeBetween(1, 1, 22, 0)
            val isOvernight = isTimeBetween(22, 0, 1, 0)

            // Block 1 — core recovery: any stabilisation above 6.0 during daytime
            val p50b1 = isDaytime && g >= 108.1 /* 6.0 */ && d > -1.80 /* -0.10 */ &&
                sd > -3.60 /* -0.20 */ && ld > -7.21 /* -0.40 */

            // Block 2 — fast active rise, cannula verified, daytime
            val p50b2 = isDaytime && g >= 108.1 && d > 5.40 /* 0.30 */ &&
                sd > 5.40 && cannulaH >= 3.0

            // Block 3 — treated hypo: carbs active + recent bolus + positive trend, cannula verified, daytime
            val p50b3 = isDaytime && g >= 108.1 && cob > 0.0 && lastBolusMin <= 30 &&
                d > 0.90 /* 0.05 */ && sd > 0.90 && ld > 0.90 && cannulaH >= 3.0

            // Block 4 — IOB threat resolved: minimal IOB + positive trend + cannula verified, daytime
            val p50b4 = isDaytime && g >= 108.1 && iob <= 0.5 &&
                d > 0.90 && sd > 0.90 && ld > 0.90 && cannulaH >= 3.0

            // Block 5 — overnight: tighter glucose floor (6.5), positive trend
            val p50b5 = isOvernight && g >= 117.1 /* 6.5 */ && d > 0.90 && sd > 0.90 && ld > 0.90

            // Block 6 — acce weight gate: weight <= 0.1 means prepare50/alarm has fired; catch via weight flag
            val p50b6 = acceWeight <= 0.1 && g >= 99.1 /* 5.5 */ && d > 1.80 /* 0.10 */ && sd > 1.80

            val p50block = when { p50b1->"1"; p50b2->"2"; p50b3->"3"; p50b4->"4"; p50b5->"5"; p50b6->"6"; else->null }
            if (p50block != null) {
                applyCurrentProfileAt100()
                setBgAccelIsfWeight(0.50)
                setAutomationState("LowBG", "NO50rec")
                sendSms("PP50.Off [b$p50block]: g=${String.format("%.1f", g / 18.016)} d=${String.format("%.2f", d / 18.016)}")
                addCarePortalNote("50ff-$p50block")
                aapsLogger.debug(LTag.APS, "PP50.Off block $p50block: g=${String.format("%.1f", g / 18.016)}mmol d=${String.format("%.2f", d / 18.016)} iob=${String.format("%.2f", iob)} cob=${cob.toInt()} cannula=${String.format("%.1f", cannulaH)}h acce=$acceWeight")
            }
        }

        // --- Skittles hypo-risk: replaces SkittlesTT3CurrP02, SkittlesA3ok8.0,5.0,6.0, Skittles3ok2BG9.0 ---
        // Primary guard: startTempTargetIfNeeded() no-ops when a TT is already active, so the
        // 7 condition blocks are evaluated every loop cycle but actions only fire once per TT period.
        // All glucose/delta thresholds in mg/dL; originals in mmol/L noted in comments.
        run {
            val g   = glucoseStatus.glucose      // mg/dL
            val d   = glucoseStatus.delta        // mg/dL, 5-min
            val sd  = glucoseStatus.shortAvgDelta // mg/dL, 15-min avg
            val ld  = glucoseStatus.longAvgDelta  // mg/dL, 40-min avg
            val iob = iobData.iob
            val cob = mealData.mealCOB
            val lastBolusMin = minutesSinceLastNormalBolus() ?: Int.MAX_VALUE
            val gate = profile_percentage >= 65 && lastBolusMin >= 5

            // Block A — SkittlesTT3 #1: fallback at very low glucose (BGL/data issues tolerated)
            val blkA = g <= 81.1 /* 4.5 */ && d <= -0.9 /* -0.05 */ && iob >= -0.2 &&
                sd <= -0.9 && ld <= -0.9 && cob <= 15.0 && gate

            // Block B — SkittlesTT3 #2: moderate fall with active insulin; COB or 60-min bolus fallback
            val blkB = g <= 90.1 /* 5.0 */ && d <= -5.13 /* -0.285 */ && iob >= 0.3 &&
                sd <= -3.60 /* -0.20 */ && ld <= -3.60 && gate && (cob <= 15.0 || lastBolusMin >= 60)

            // Block C — SkittlesTT3 #3 (FIXED from OR(<=2.5,<=3.0)): emergency floor
            val blkC = g <= 63.1 /* 3.5 */ && d <= 0.0

            // Block D — SkittlesA3ok #1: rapid sustained fall at target glucose, high IOB
            val blkD = g <= 108.1 /* 6.0 */ && d <= -16.21 /* -0.90 */ && sd <= -16.21 && iob > 2.8 && gate

            // Block E — SkittlesA3ok #2: moderate multi-timeframe fall
            val blkE = g <= 117.1 /* 6.5 */ && d <= -9.01 /* -0.50 */ && sd <= -7.21 /* -0.40 */ &&
                ld <= -3.60 /* -0.20 */ && iob >= 1.5 && cob <= 15.0 && gate

            // Block F — SkittlesA3ok #3: very high IOB at higher glucose, steroids off
            val blkF = g <= 162.1 /* 9.0 */ && d <= -9.01 && sd <= -7.21 && ld <= -7.21 &&
                iob >= 2.9 && cob <= 15.0 && gate && checkAutomationState("Steroids", "Steroids Off")

            // Block G — Skittles3ok2BG9.0: tight multi-delta confirmation at higher glucose
            val blkG = g <= 171.2 /* 9.5 */ && d <= -19.82 /* -1.10 */ && sd <= -14.41 /* -0.80 */ &&
                ld <= -14.41 && iob >= 1.4 && cob <= 15.0 && gate

            val block = when { blkA -> "A"; blkB -> "B"; blkC -> "C"; blkD -> "D"; blkE -> "E"; blkF -> "F"; blkG -> "G"; else -> null }
            if (block != null && startTempTargetIfNeeded(102.7 /* 5.7 mmol */, 180)) {
                setBgAccelIsfWeight(0.02)
                applyCurrentProfileAt100()
                setAutomationState("LowBG", "50recent")
                sendSms("Skittles $block: hypo risk — TT 5.7 set")
                addCarePortalNote("TT3-$block")
                aapsLogger.debug(LTag.APS, "Skittles block $block: g=${String.format("%.1f", g / 18.016)}mmol d=${String.format("%.2f", d / 18.016)} iob=${String.format("%.2f", iob)} cob=${cob.toInt()} pct=$profile_percentage")
            }
        }

        // --- TT 5.7 reversal block: replaces TToff2/3/4/5 and HypoTTOff1 automations ---
        // Primary guard: activeTtMgdl() must be ~5.7 mmol/L. Once TT is cancelled the guard fails,
        // so these conditions self-prevent re-firing without needing readyToRun() throttle.
        // All glucose/delta thresholds in mg/dL; originals in mmol/L in comments.
        run {
            val ttMgdl = activeTtMgdl() ?: return@run
            if (kotlin.math.abs(ttMgdl - 102.7) > 1.8) return@run   // guard: only act on 5.7 mmol TT

            val g   = glucoseStatus.glucose
            val d   = glucoseStatus.delta
            val sd  = glucoseStatus.shortAvgDelta
            val ld  = glucoseStatus.longAvgDelta
            val iob = iobData.iob
            val cob = mealData.mealCOB

            // TToff2 — loosest: any stabilisation ≥ 6.0 (earliest exit)
            val off2 = g >= 108.1 /* 6.0 */ && d >= -4.50 /* -0.25 */ && sd >= -4.50

            // TToff3 — stagnation plateau: flat BGL, low IOB, trivial COB
            val off3 = cob <= 4.0 && g >= 81.1 /* 4.5 */ && iob <= 0.8 &&
                d  in -1.80 /* -0.10 */ .. 1.80 /* 0.10 */ &&
                sd in -1.80 .. 1.80 &&
                ld in -1.80 .. 1.80

            // TToff4 — confident recovery: fast active rise, not exercising
            val off4 = g >= 108.1 /* 6.0 */ && d >= 5.40 /* 0.30 */ && sd >= 3.60 /* 0.20 */ &&
                recentSteps30Minutes <= 300

            // TToff5 — gentle capped rise: rising but not overshooting
            val off5 = g > 108.1 /* 6.0 */ && d in 1.80 /* 0.10 */ .. 14.41 /* 0.80 */ && sd >= 1.80

            // HypoTTOff1 — early catch: rising fast while still ≤ 5.5, not exercising
            val off1 = g <= 99.1 /* 5.5 */ &&
                d  in 9.01 /* 0.50 */ .. 14.41 /* 0.80 */ &&
                sd in 0.90 /* 0.05 */ .. 14.41 &&
                ld in 0.90 .. 9.01 /* 0.50 */ &&
                recentSteps60Minutes <= 500 && recentSteps15Minutes <= 100 && recentSteps30Minutes <= 200

            val which = when { off2 -> "off2"; off3 -> "off3"; off4 -> "off4"; off5 -> "off5"; off1 -> "off1"; else -> null }
            if (which != null) {
                cancelCurrentTempTarget()
                setBgAccelIsfWeight(0.50)
                applyCurrentProfileAt100()
                setAutomationState("LowBG", "NO50rec")
                sendSms("TT 5.7 ended [$which]: g=${String.format("%.1f", g / 18.016)} d=${String.format("%.2f", d / 18.016)}")
                addCarePortalNote("TToff-$which")
                aapsLogger.debug(LTag.APS, "TT reversal [$which]: g=${String.format("%.1f", g / 18.016)}mmol d=${String.format("%.2f", d / 18.016)} sd=${String.format("%.2f", sd / 18.016)} iob=${String.format("%.2f", iob)} cob=${cob.toInt()}")
            }
        }

        // --- 50pc makes5.7: safety TT when on 50% profile and BGL dropping below 5.0 mmol ---
        // Precondition: no TT active. Self-guarding: TT set so activeTtMgdl() != null next cycle.
        if (profile_percentage == 50 && activeTtMgdl() == null) {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            if (g < 90.1 && d <= -0.9) {   // < 5.0 mmol, delta <= -0.05 mmol
                startTempTargetIfNeeded(102.7, 150)   // 5.7 mmol for 150 min
                sendSms("50pc makes5.7: g=${String.format("%.1f", g / 18.016)} d=${String.format("%.2f", d / 18.016)}")
                addCarePortalNote("50pcTT")
                aapsLogger.debug(LTag.APS, "50pc makes5.7: g=${String.format("%.1f", g / 18.016)}mmol d=${String.format("%.2f", d / 18.016)}")
            }
        }

        // --- carbsStopTT1ok4.4: cancel very-low TT (<=4.4 mmol) when carbs active and rising ---
        // Self-guarding: cancels TT so tt<=79.3 is false next cycle.
        run {
            val tt = activeTtMgdl()
            val g  = glucoseStatus.glucose
            val d  = glucoseStatus.delta
            val iob = iobData.iob
            if (tt != null && tt <= 79.3          // TT <= 4.4 mmol
                && mealData.mealCOB >= 10.0
                && d >= 3.6                        // Delta >= 0.2 mmol
                && iob <= 0.3
                && isTimeBetween(10, 0, 22, 0)
            ) {
                cancelCurrentTempTarget()
                setBgAccelIsfWeight(0.50)
                applyCurrentProfileAt100()
                sendSms("carbsStopTT1: g=${String.format("%.1f", g / 18.016)} d=${String.format("%.2f", d / 18.016)} iob=${String.format("%.2f", iob)}")
                addCarePortalNote("Coff4")
                aapsLogger.debug(LTag.APS, "carbsStopTT1: g=${String.format("%.1f", g / 18.016)}mmol tt=${String.format("%.1f", tt / 18.016)} d=${String.format("%.2f", d / 18.016)} iob=${String.format("%.2f", iob)}")
            }
        }

        // --- CarbsStopTT5.7: cancel 5.7 mmol TT when carbs up or recent bolus and BGL stable/rising ---
        // Self-guarding: cancels TT so TT range checks fail next cycle.
        run {
            val tt = activeTtMgdl()
            if (tt != null) {
                val g  = glucoseStatus.glucose
                val d  = glucoseStatus.delta
                val cob = mealData.mealCOB
                val iob = iobData.iob
                val lastBolusMin = minutesSinceLastNormalBolus() ?: Int.MAX_VALUE
                // Block 1: TT 4.6–5.9 mmol, COB>10, IOB>=2.2, BGL>=5.0, Delta>=0
                val cb1 = cob > 10.0 && tt <= 106.3 && tt >= 82.9 && iob >= 2.2 && g >= 90.1 && d >= 0.0
                // Block 2: TT 6.2–6.4 mmol, COB>10, BGL>=5.0, Delta>=0.05 mmol, IOB<=2.2
                val cb2 = cob > 10.0 && g >= 90.1 && d >= 0.9 && tt >= 111.7 && tt < 115.3 && iob <= 2.2
                // Block 3: bolus <=10 min ago, TT 5.7–5.8 mmol, BGL>=5.0, Delta>=0
                val cb3 = lastBolusMin <= 10 && tt >= 102.7 && tt <= 104.5 && g >= 90.1 && d >= 0.0
                val cBlock = when { cb1 -> "1"; cb2 -> "2"; cb3 -> "3"; else -> null }
                if (cBlock != null) {
                    cancelCurrentTempTarget()
                    applyCurrentProfileAt100()
                    setBgAccelIsfWeight(0.50)
                    setAutomationState("LowBG", "NO50rec")
                    sendSms("CarbsStopTT [b$cBlock]: g=${String.format("%.1f", g / 18.016)} tt=${String.format("%.1f", tt / 18.016)}")
                    addCarePortalNote("Coff2-$cBlock")
                    aapsLogger.debug(LTag.APS, "CarbsStopTT block $cBlock: g=${String.format("%.1f", g / 18.016)}mmol tt=${String.format("%.1f", tt / 18.016)} cob=${cob.toInt()} iob=${String.format("%.2f", iob)} d=${String.format("%.2f", d / 18.016)}")
                }
            }
        }

        // --- Usual2forTH70: restores iobTH=70 and acce weight=0.50 when BGL has recovered ---
        // Replaces "Usual2forTH70 CurrProfReal0.35" automation.
        // Guard: profile at 100% (not in 50% state), iobTH still reduced (<70), no TT, BGL >= 5.5mmol.
        // Self-guarding: sets iobTH=70 so condition iobThresholdPercent<70 fails next cycle.
        if (profile_percentage == 100
            && iobThresholdPercent < 70
            && activeTtMgdl() == null
            && glucoseStatus.glucose >= 99.1     // 5.5 mmol
            && checkAutomationState("Steroids", "Steroids Off")
        ) {
            val g   = glucoseStatus.glucose
            val sd  = glucoseStatus.shortAvgDelta
            val cob = mealData.mealCOB
            val steps60  = recentSteps60Minutes
            val steps180 = StepService.getRecentStepCount180Min()
            val iobTH = iobThresholdPercent
            // block 1: daytime 08:00–20:00, some activity, iobTH low or reduced
            val u2b1 = isTimeBetween(8, 0, 20, 0) &&
                (steps180 >= 10 || iobTH <= 19 || iobTH == 50) &&
                steps60 >= 50
            // block 2: day 09:01–20:00, iobTH at night/twilight level
            val u2b2 = isTimeBetween(9, 1, 20, 0) &&
                (iobTH <= 19 || iobTH == 50)
            // block 3: 05:01–20:00, waking/rising BGL
            val u2b3 = isTimeBetween(5, 1, 20, 0) &&
                (cob >= 10.0 || steps60 >= 100 || g >= 153.1 || sd >= 18.0)  // 8.5mmol / 1.0mmol
            val u2block = when { u2b1->"1"; u2b2->"2"; u2b3->"3"; else->null }
            if (u2block != null) {
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 70)
                setBgAccelIsfWeight(0.50)
                setAutomationState("LowBG", "NO50rec")
                applyCurrentProfileAt100()
                sendSms("Usual2forTH [b$u2block]: g=${String.format("%.1f", g / 18.016)} iobTH=$iobTH")
                addCarePortalNote("UsuIP-$u2block")
                aapsLogger.debug(LTag.APS, "Usual2forTH block $u2block: g=${String.format("%.1f", g / 18.016)}mmol iobTH=$iobTH steps60=$steps60 steps180=$steps180 cob=${cob.toInt()} sd=${String.format("%.2f", sd / 18.016)}")
            }
        }

        // --- CarbsTHoff: lowers iobTH to 50% when post-carb BGL is falling or mid-range anomaly ---
        if (profile_percentage == 100 && activeTtMgdl() == null
            && checkAutomationState("Steroids", "Steroids Off")) {
            val g   = glucoseStatus.glucose
            val d   = glucoseStatus.delta
            val sd  = glucoseStatus.shortAvgDelta
            val acceW = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
            val iobTH = iobThresholdPercent
            val lastBolusMin = minutesSinceLastNormalBolus() ?: Int.MAX_VALUE
            // Block 1: BGL falling (SDelta<=-0.1mmol, Delta<=-0.1mmol), 5.0–8.5mmol,
            //          bolus>=80min ago, either iobTH at normal (>=71) or deep-hypo acce (<=0.03)
            val ctB1 = sd <= -1.8 && d <= -1.8 && g > 90.1 && g <= 153.1
                && lastBolusMin >= 80
                && (iobTH >= 71 || acceW <= 0.03)
            // Block 2: BGL 9.5–11.0mmol, acce NOT exactly 0.50 (<=0.49 OR >=0.51),
            //          iobTH 71–96 (normal-ish but not over-boosted)
            val ctB2 = g <= 198.2 && (acceW <= 0.49 || acceW >= 0.51)
                && iobTH >= 71 && iobTH <= 96 && g >= 171.2
            val ctBlock = when { ctB1 -> "1"; ctB2 -> "2"; else -> null }
            if (ctBlock != null) {
                setBgAccelIsfWeight(0.50)
                switchProfileIfNeeded("Current ProfileReal", 30)
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 50)
                preferences.put(DoubleKey.ApsAutoIsfPpWeight, 0.08)
                setAutomationState("LowBG", "NO50rec")
                sendSms("CarbsTHoff [b$ctBlock]: g=${String.format("%.1f", g / 18.016)} iobTH=$iobTH")
                addCarePortalNote("COff1-$ctBlock")
            }
        }

        val gson = Gson()
        aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal AutoISF <<<")
        aapsLogger.debug(LTag.APS, "Glucose status:     $glucoseStatus")
        aapsLogger.debug(LTag.APS, "Current temp:       $currentTemp")
        aapsLogger.debug(LTag.APS, "IOB data:           ${gson.toJson(iobArray)}")
        aapsLogger.debug(LTag.APS, "Profile:            $oapsProfile")
        aapsLogger.debug(LTag.APS, "Autosens data:      $autosensResult")
        aapsLogger.debug(LTag.APS, "Meal data:          $mealData")
        aapsLogger.debug(LTag.APS, "MicroBolusAllowed:  $microBolusAllowed")
        aapsLogger.debug(LTag.APS, "flatBGsDetected:    $flatBGsDetected")
        aapsLogger.debug(LTag.APS, "AutoIsfMode:        $autoIsfMode")
        //aapsLogger.debug(LTag.APS, "AutoISF extras:     ${Json.encodeToString(OapsProfile.serializer(), oapsProfile)}")
        determineBasalAutoISF.determine_basal(
            glucose_status = glucoseStatus,
            currenttemp = currentTemp,
            iob_data_array = iobArray,
            profile = oapsProfile,
            autosens_data = autosensResult,
            meal_data = mealData,
            microBolusAllowed = microBolusAllowed,
            currentTime = now,
            flatBGsDetected = flatBGsDetected,
            autoIsfMode = autoIsfMode,
            loop_wanted_smb = loopWantedSmb,
            profile_percentage = profile_percentage,
            smb_ratio = smbRatio,
            smb_max_range_extension = smbMaxRangeExtension,
            iob_threshold_percent = iobThresholdPercent,
            activity_consoleLog = activityLog,
            auto_isf_consoleError = consoleError,
            auto_isf_consoleLog = consoleLog,
            bg_acce = bgAcce,
            steps180M = steps180,
            steps15M = steps15,
            steps5M = steps5
        ).also {
            val determineBasalResult = apsResultProvider.get().with(it)
            determineBasalResult.inputConstraints = inputConstraints
            determineBasalResult.autosensResult = autosensResult
            determineBasalResult.iobData = iobArray
            determineBasalResult.glucoseStatus = glucoseStatus
            determineBasalResult.currentTemp = currentTemp
            determineBasalResult.oapsProfileAutoIsf = oapsProfile
            determineBasalResult.mealData = mealData
            lastAPSResult = determineBasalResult
            lastAPSRun = now
            aapsLogger.debug(LTag.APS, "Result: $it")
            rxBus.send(EventAPSCalculationFinished())
        }

        autoIsfValues.timestamp = now
        lastAPSResult?.let { result ->
            autoIsfValues.insulinReq = result.json()?.optDouble("insulinReq", 0.0) ?: 0.0
            autoIsfValues.tbrRate    = result.rate
            autoIsfValues.smbDelivered = result.smb
            (result.rawData() as? RT)?.let { rt ->
                rt.autoIsfAcce  = autoIsfValues.acceIsf
                rt.autoIsfBg    = autoIsfValues.bgIsf
                rt.autoIsfPp    = autoIsfValues.ppIsf
                rt.autoIsfDura  = autoIsfValues.duraIsf
                rt.autoIsfFinal = autoIsfValues.finalIsf
            }
        }
        disposable += persistenceLayer.insertOrUpdateAutoIsfValues(autoIsfValues).subscribe()
        rxBus.send(EventOpenAPSUpdateGui())
    }

    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? = glucoseStatusCalculatorAutoIsf.getGlucoseStatusData(allowOldData)

    override fun isSuperBolusEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        value.set(false)
        return value
    }

    override fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val maxIobPref = preferences.get(DoubleKey.ApsSmbMaxIob)
            maxIob.setIfSmaller(maxIobPref, rh.gs(R.string.limiting_iob, maxIobPref, rh.gs(R.string.maxvalueinpreferences)), this)
            maxIob.setIfSmaller(hardLimits.maxIobSMB(), rh.gs(R.string.limiting_iob, hardLimits.maxIobSMB(), rh.gs(R.string.hardlimit)), this)
        }
        return maxIob
    }

    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        if (isEnabled()) {
            var maxBasal = preferences.get(DoubleKey.ApsMaxBasal)
            if (maxBasal < profile.getMaxDailyBasal()) {
                maxBasal = profile.getMaxDailyBasal()
                absoluteRate.addReason(rh.gs(R.string.increasing_max_basal), this)
            }
            absoluteRate.setIfSmaller(maxBasal, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxBasal, rh.gs(R.string.maxvalueinpreferences)), this)

            // Check percentRate but absolute rate too, because we know real current basal in pump
            val maxBasalMultiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier)
            val maxFromBasalMultiplier = floor(maxBasalMultiplier * profile.getBasal() * 100) / 100
            absoluteRate.setIfSmaller(
                maxFromBasalMultiplier,
                rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxFromBasalMultiplier, rh.gs(R.string.max_basal_multiplier)),
                this
            )
            val maxBasalFromDaily = preferences.get(DoubleKey.ApsMaxDailyMultiplier)
            val maxFromDaily = floor(profile.getMaxDailyBasal() * maxBasalFromDaily * 100) / 100
            absoluteRate.setIfSmaller(maxFromDaily, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxFromDaily, rh.gs(R.string.max_daily_basal_multiplier)), this)
        }
        return absoluteRate
    }

    override fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseSmb)
        if (!enabled) value.set(false, rh.gs(R.string.smb_disabled_in_preferences), this)
        return value
    }

    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseUam)
        if (!enabled) value.set(false, rh.gs(R.string.uam_disabled_in_preferences), this)
        return value
    }

    override fun isAutosensModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseAutosens)
        if (!enabled) value.set(false, rh.gs(R.string.autosens_disabled_in_preferences), this)
        return value
    }

    override fun configuration(): JSONObject =
        JSONObject()
            .put(BooleanKey.ApsUseDynamicSensitivity, preferences)
            .put(IntKey.ApsDynIsfAdjustmentFactor, preferences)

    override fun applyConfiguration(configuration: JSONObject) {
        configuration
            .store(BooleanKey.ApsUseDynamicSensitivity, preferences)
            .store(IntKey.ApsDynIsfAdjustmentFactor, preferences)
    }

    // Rounds value to 'digits' decimal places
    // different for negative numbers fun round(value: Double, digits: Int): Double = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    fun round(value: Double, digits: Int): Double {
        if (value.isNaN()) return Double.NaN
        val scale = 10.0.pow(digits.toDouble())
        return (value * scale).roundToInt() / scale
    }

    fun convert_bg(value: Double): String =
        profileUtil.fromMgdlToStringInUnits(value).replace("-0.0", "0.0")

    fun convert_isf(value: Double): String =
        String.format("%.1f", profileUtil.fromMgdlToUnits(value))

    fun convert_bg_to_units(value: Double, profile: OapsProfileAutoIsf): Double =
        if (profile.out_units == "mmol/L") value * Constants.MGDL_TO_MMOLL else value

    fun activityMonitor(isTempTarget: Boolean, bg: Double, target_bg: Double, now: Int): Double {
        if (preferences.get(BooleanKey.ActivityMonitorShowStepsFromSmartphone)) {
            val nowMillis = System.currentTimeMillis()
            val stepsCount = SC(
                duration = 0,
                timestamp = nowMillis,
                steps5min = recentSteps5Minutes,
                steps10min = recentSteps5Minutes + recentSteps10Minutes,
                steps15min = recentSteps5Minutes + recentSteps10Minutes + recentSteps15Minutes,
                steps30min = recentSteps30Minutes,
                steps60min = recentSteps60Minutes,
                steps180min = StepService.getRecentStepCount180Min(),
                device = "Smartphone"
            )
            disposable += persistenceLayer.insertOrUpdateStepsCount(stepsCount).subscribe()
        }

        val phoneMoved = PhoneMovementDetector.phoneMoved()
        val lastAppStart = preferences.get(LongKey.AppStart)
        //val elapsedTimeSinceLastStart = (dateUtil.now() - lastAppStart) / 60000
        val time_since_start = (dateUtil.now() - lastAppStart).milliseconds.inWholeMinutes
        val activityDetection = preferences.get(BooleanKey.ApsActivityDetection)
        val activity_scale_factor = preferences.get(DoubleKey.ActivityScaleFactor)              // profile.activity_scale_factor;
        val inactivity_scale_factor = preferences.get(DoubleKey.InactivityScaleFactor)          // profile.inactivity_scale_factor;
        var activityRatio = 1.0
        val ignore_inactivity_overnight = preferences.get(BooleanKey.ActivityMonitorOvernight)  // profile.ignore_inactivity_overnight;
        val inactivity_idle_start = preferences.get(IntKey.ActivityMonitorIdleStart)           // profile.inactivity_idle_start;
        val inactivity_idle_end = preferences.get(IntKey.ActivityMonitorIdleEnd)                // profile.inactivity_idle_end;

        val existSleepState = automationStateService.hasStateValues("Sleeping")
        val useSleepState = automationStateService.inState("Sleeping", "True")
        aapsLogger.debug(LTag.APS, "State json for Sleep mode: {\"Sleeping\":\"${automationStateService.getState("Sleeping")}\"}")
        // really still sleeping?
        if (useSleepState && (recentSteps5Minutes + recentSteps10Minutes + recentSteps15Minutes < recentSteps30Minutes) && now >= inactivity_idle_end) {
            automationStateService.setState("query_got_up", "query_it")
        }
        aapsLogger.debug(LTag.APS, "State json for got up query: {\"query_got_up\":\"${automationStateService.getState("query_got_up")}\"}")

        if (!activityDetection) {
            consoleLog.add("Activity monitor disabled in settings")
        } else if (isTempTarget) {
            consoleLog.add("Activity monitor disabled: tempTarget")
        } else if (!phoneMoved) {
            consoleLog.add("Activity monitor disabled: Phone seems not to be carried for the last 15m")
        } else {
            if (time_since_start < 60 && recentSteps60Minutes <= 200) {
                consoleLog.add("Activity monitor initialising for ${60 - time_since_start} more minutes: inactivity detection disabled")
            } else if (useSleepState && recentSteps60Minutes <= 200) {
                consoleLog.add("Activity monitor disabled inactivity detection: sleeping state")
            } else if (((inactivity_idle_start > inactivity_idle_end && (now >= inactivity_idle_start || now < inactivity_idle_end))  // includes midnight
                    || (now >= inactivity_idle_start && now < inactivity_idle_end))                                                       // excludes midnight
                && recentSteps60Minutes <= 200 && ignore_inactivity_overnight && !existSleepState
            ) {
                consoleLog.add("Activity monitor disabled inactivity detection: sleeping hours")
            } else if (recentSteps5Minutes > 300 || recentSteps10Minutes > 300 || recentSteps15Minutes > 300 || recentSteps30Minutes > 1500 || recentSteps60Minutes > 2500) {
                activityRatio = 1 - 0.3 * activity_scale_factor
                consoleLog.add("Activity monitor detected activity, sensitivity ratio: $activityRatio")
            } else if (recentSteps5Minutes > 200 || recentSteps10Minutes > 200 || recentSteps15Minutes > 200
                || recentSteps30Minutes > 500 || recentSteps60Minutes > 800
            ) {
                activityRatio = 1 - 0.15 * activity_scale_factor
                consoleLog.add("Activity monitor detected partial activity, sensitivity ratio: $activityRatio")
            } else if (bg < target_bg && recentSteps60Minutes <= 200) {
                consoleLog.add("Activity monitor disabled inactivity detection: bg < target")
            } else if (recentSteps60Minutes < 50) {
                activityRatio = 1 + 0.2 * inactivity_scale_factor
                consoleLog.add("Activity monitor detected inactivity, sensitivity ratio: $activityRatio")
            } else if (recentSteps60Minutes <= 200) {
                activityRatio = 1 + 0.1 * inactivity_scale_factor
                consoleLog.add("Activity monitor detected partial inactivity, sensitivity ratio: $activityRatio")
            } else {
                consoleLog.add("Activity monitor detected neutral state")  //, sensitivity ratio unchanged: $activityRatio")
            }
        }
        preferences.put(DoubleKey.ActivityMonitorRatio, activityRatio)
        var activityMsg = "Activity Monitor json: {\"activity_scale_factor\":$activity_scale_factor,\"inactivity_scale_factor\":$inactivity_scale_factor"
        activityMsg += ",\"recentSteps5Minutes\":$recentSteps5Minutes,\"recentSteps10Minutes\":$recentSteps10Minutes,\"recentSteps15Minutes\":$recentSteps15Minutes"
        activityMsg += ",\"recentSteps30Minutes\":$recentSteps30Minutes,\"recentSteps60Minutes\":$recentSteps60Minutes"
        activityMsg += ",\"phone_moved\":$phoneMoved,\"time_since_start\":$time_since_start,\"activity_detection\":$activityDetection"
        activityMsg += ",\"ignore_inactivity_overnight\":$ignore_inactivity_overnight,\"inactivity_idle_start\":$inactivity_idle_start,\"inactivity_idle_end\":$inactivity_idle_end}"
        aapsLogger.debug(LTag.APS, activityMsg)
        return activityRatio
    }

    fun autoISF(profile: Profile, currentActivity: Double = 0.0, smbActivity: Double = 0.0): Double {

        var steps180min = StepService.getRecentStepCount180Min()
        var steps15min = StepService.getRecentStepCount15Min()
        var steps5min = StepService.getRecentStepCount5Min()

        //var steps180 = steps180min  // add this
        //var steps15 = steps15min  // add this
        this.steps180 = steps180min
        this.steps15 = steps15min
        this.steps5 = steps5min
        val nowHour = LocalDateTime.now().hour
        consoleError.add("steps5min is ${recentSteps5Minutes} ;;")
        consoleError.add("steps15min is ${recentSteps15Minutes} ;;")
        consoleError.add("steps30min is ${recentSteps30Minutes} ;;")
        consoleError.add("steps60min is ${recentSteps60Minutes} ;;")
        consoleError.add("steps180min is ${steps180min} ;;")

        val sens = profile.getProfileIsfMgdl()
        val glucose_status = glucoseStatusProvider.glucoseStatusData as GlucoseStatusAutoIsf?

        val high_temptarget_raises_sensitivity = exerciseMode || highTemptargetRaisesSensitivity
        var target_bg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TARGET_BG[0], HardLimits.LIMIT_TARGET_BG[1])
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            target_bg = hardLimits.verifyHardLimits(tempTarget.target(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG[0], HardLimits.LIMIT_TEMP_TARGET_BG[1])
        }
        val activityRatio = preferences.get(DoubleKey.ActivityMonitorRatio)
        val stepActivityDetected = preferences.get(BooleanKey.ActivityMonitorStepsActive)
        val stepInactivityDetected = preferences.get(BooleanKey.ActivityMonitorStepsInactive)
        var sensitivityRatio = 1.0
        val exerciseModeActive = high_temptarget_raises_sensitivity && isTempTarget && target_bg > normalTarget
        val resistanceModeActive = preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens) && isTempTarget && target_bg < normalTarget

        if (exerciseModeActive || resistanceModeActive || stepActivityDetected || stepInactivityDetected) {
            //======================================

            val tdd7D = tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = true))?.data?.totalAmount
            val tdd1D = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = true))?.data?.totalAmount
            val tddLast4H = tddCalculator.calculateDaily(-4, 0)?.totalAmount
            val tddLast8to4H = tddCalculator.calculateDaily(-8, -4)?.totalAmount
            if (tdd7D != null && tdd7D > 0.0 && tdd1D != null &&
                tddLast4H != null && tddLast8to4H != null
            ) {
                val w8H = ((1.4 * tddLast4H) + (0.6 * tddLast8to4H)) * 3
                val blendedTDD = if (w8H < 0.75 * tdd7D) {
                    // Recent usage well below average — pull 7D toward recent reality before blending
                    val adj7D = w8H + ((w8H / tdd7D) * (tdd7D - w8H))
                    (adj7D * 0.34) + (tdd1D * 0.33) + (w8H * 0.33)
                } else {
                    (w8H * 0.33) + (tdd7D * 0.34) + (tdd1D * 0.33)
                }
                val tddRatio = blendedTDD / tdd7D
                consoleError.add(
                    "TDD ratio NOT USED ${Round.roundTo(tddRatio, 0.01)}" +
                        " (blended ${Round.roundTo(blendedTDD, 0.1)}U / 7D avg ${Round.roundTo(tdd7D, 0.1)}U," +
                        " W8H ${Round.roundTo(w8H, 0.1)}U)"
                )
            }
            //==========================================
            if (exerciseModeActive || resistanceModeActive) {

                // w/ target 100, temp target 110 = .89, 120 = 0.8, 140 = 0.67, 160 = .57, and 200 = .44
                // e.g.: Sensitivity ratio set to 0.8 based on temp target of 120; Adjusting basal from 1.65 to 1.35; ISF from 58.9 to 73.6
                //sensitivityRatio = 2/(2+(target_bg-normalTarget)/40);
                val resistanceMax = min(1.5, preferences.get(DoubleKey.AutosensMax))  // additional safety limit
                val c = (mgdlHalfBasalExerciseTarget - normalTarget)
                if (c * (c + target_bg - normalTarget) <= 0.0) {
                    sensitivityRatio = resistanceMax
                    // consoleError.add("Sensitivity decrease for temp target of $target_bg limited by Autosens_max; ")

                } else {
                    sensitivityRatio = c / (c + target_bg - normalTarget)
                    // limit sensitivityRatio to profile.autosens_max (1.2x by default)
                }
                sensitivityRatio = min(sensitivityRatio, resistanceMax)
                sensitivityRatio = round(sensitivityRatio, 2)
                consoleError.add("exerciseModeActive or resistanceModeActive sensitivityRatio: ${sensitivityRatio}")
            } else if (stepActivityDetected) {
                sensitivityRatio = activityRatio
                consoleError.add("stepActivityDetected : sensitivityRatio: ${activityRatio}")
            } else if (stepInactivityDetected) {
                sensitivityRatio = activityRatio
                consoleError.add("stepInactivityDetected : sensitivityRatio: ${activityRatio}")
            }
        } else {
            var autosensResult = AutosensResult()

            if (constraintsChecker.isAutosensModeEnabled().value()) {
                iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSAutoISFPlugin")?.also {
                    autosensResult = it.autosensResult
                }
                // Do NOT reset tddRatio here — autoISF() is called from both invoke() and
                // calculateVariableIsf(). Resetting here would overwrite the value stored
                // by the invoke() path before determine_basal() runs.
            } else {
                // When autosens is off, derive sensitivity ratio from blended TDD (Boost method).
                // Blends 8H-weighted, 7D, and 1D TDD; ratio = blendedTDD / tdd7D.
                // ratio > 1 = more insulin needed recently (resistance); < 1 = more sensitive.
                if (!preferences.get(BooleanKey.ApsAutoIsfTddSensitivity)) {
                    autosensResult.sensResult = "autosens disabled, TDD sensitivity off"
                    consoleError.add("TDD sensitivity: off")
                    determineBasalAutoISF.tddRatio = 1.0
                    determineBasalAutoISF.tdd7D = 0.0
                } else {
                    val tdd7D = tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = true))?.data?.totalAmount
                    val tdd1D = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = true))?.data?.totalAmount
                    val tddLast4H = tddCalculator.calculateDaily(-4, 0)?.totalAmount
                    val tddLast8to4H = tddCalculator.calculateDaily(-8, -4)?.totalAmount
                    if (tdd7D != null && tdd7D > 0.0 && tdd1D != null &&
                        tddLast4H != null && tddLast8to4H != null
                    ) {
                        val w8H = ((1.4 * tddLast4H) + (0.6 * tddLast8to4H)) * 3
                        val blendedTDD = if (w8H < 0.75 * tdd7D) {
                            // Recent usage well below average — pull 7D toward recent reality before blending
                            val adj7D = w8H + ((w8H / tdd7D) * (tdd7D - w8H))
                            (adj7D * 0.34) + (tdd1D * 0.33) + (w8H * 0.33)
                        } else {
                            (w8H * 0.33) + (tdd7D * 0.34) + (tdd1D * 0.33)
                        }
                        val tddRatio = blendedTDD / tdd7D
                        // Store tddRatio and tdd7D to DetermineBasalAutoISF via class-level properties (Option 3)
                        // sensitivityRatio is intentionally NOT used for TDDfactor
                        determineBasalAutoISF.tddRatio = tddRatio.coerceIn(0.70, 1.50)
                        determineBasalAutoISF.tdd7D = tdd7D
                        autosensResult.ratio = tddRatio.coerceIn(0.70, 1.50)
                        autosensResult.sensResult = "TDD ratio ${Round.roundTo(tddRatio, 0.01)}" +
                            " (blended ${Round.roundTo(blendedTDD, 0.1)}U / 7D avg ${Round.roundTo(tdd7D, 0.1)}U," +
                            " W8H ${Round.roundTo(w8H, 0.1)}U)"
                        aapsLogger.debug(LTag.APS, autosensResult.sensResult)
                        /*consoleError.add("TDD sensitivity: ${autosensResult.sensResult}  TDD ratio ${Round.roundTo(tddRatio, 0.01)}" +
                            " (blended ${Round.roundTo(blendedTDD, 0.1)}U / 7D avg ${Round.roundTo(tdd7D, 0.1)}U," +
                            " W8H ${Round.roundTo(w8H, 0.1)}U)")*/
                    } else {
                        val missing = listOfNotNull(
                            if (tdd7D == null || tdd7D <= 0.0) "7D" else null,
                            if (tdd1D == null) "1D" else null,
                            if (tddLast4H == null) "4H" else null,
                            if (tddLast8to4H == null) "8-4H" else null
                        ).joinToString()
                        autosensResult.sensResult = "autosens disabled, TDD unavailable (missing: $missing)"
                        aapsLogger.debug(LTag.APS, autosensResult.sensResult)
                        consoleError.add("TDD sensitivity: ${autosensResult.sensResult}")
                        // Reset tddRatio to neutral when TDD data unavailable
                        determineBasalAutoISF.tddRatio = 1.0
                        determineBasalAutoISF.tdd7D = 0.0
                    }
                } // end ApsAutoIsfTddSensitivity
            }
            sensitivityRatio = autosensResult.ratio
        }
        val calibrationMinutes = calibrationDuration - (dateUtil.now() - preferences.get(LongKey.FslCalibrationStart)) / 60000
        val calibrationStopsSMB = calibrationMinutes > 0 && !preferences.get(BooleanKey.FslCalibrationEnd)
        val maxIob = constraintsChecker.getMaxIOBAllowed().value()
        fun isEven(value: Double): Boolean =
            if (value % 1 == 0.0) value.toInt() % 2 == 0          // whole number: check integer
            else (value * 10).roundToInt() % 2 == 0                // decimal: check first decimal digit

        val maxIobIsEven = isEven(maxIob)
        var skipWeights = false
        var applyWeights = false
        if (calibrationStopsSMB) {
            consoleError.add("AutoISF weights calculated for display but not applied: calibrating")
        } else if (!autoIsfWeights || glucose_status == null) {
            /*    consoleError.add("AutoISF weights disabled in Preferences")
                skipWeights = true
            } else if (maxIobIsEven) {*/
            consoleError.add("AutoISF weights DISPLAY only: AutoISF weights disabled in Preferences")
            applyWeights = false
        } else {
            consoleError.add("AutoISF weights ACTIVE AutoISF weights enabled in Preferences")
            applyWeights = true
        }
        if (skipWeights) {
            consoleError.add("----------------------------------")
            consoleError.add("end AutoISF")
            consoleError.add("----------------------------------")
            return round(sens / sensitivityRatio, 1)
        }
        val autosensResult = AutosensResult()

        if (constraintsChecker.isAutosensModeEnabled().value()) {
            val autosensData = iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSAutoISFPlugin")
            if (autosensData == null) {
                rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openaps_no_as_data)))
                return sens
            }
            autosensData.autosensResult
        } else autosensResult.sensResult = "autosens disabled"

        val dura05: Double = glucose_status!!.duraISFminutes
        val avg05: Double = glucose_status.duraISFaverage
        val maxISFReduction: Double = autoISF_max
        var sens_modified = false
        var pp_ISF = 1.0
        var acce_ISF = 1.0
        var acce_weight = 1.0
        val bg_off = target_bg + 10.0 - glucose_status.glucose                      // move from central BG=100 to target+10 as virtual BG'=100

        // calculate acce_ISF from bg acceleration and adapt ISF accordingly
        val fit_corr: Double = glucose_status.corrSqu
        val bg_acce: Double = glucose_status.bgAcceleration
        bgAcce = bg_acce  // store for use in determine_basal
        //val nowHour = LocalDateTime.now().hour
        //consoleError.add("steps60min is ${recentSteps60Minutes} ;;")
        //consoleError.add("steps180min is ${steps180min} ;;")
        consoleError.add("nowHour is ${nowHour} ;;")
        //consoleError.add("nowDate is ${nowDate} ;;")
        consoleError.add("bg_acce: ${round(bg_acce, 2)} ;")
        //consoleError.add("steps30min is ${recentSteps30Minutes} ;;")
        consoleError.add("bgAccel_ISF_weight is ${round(bgAccel_ISF_weight, 4)} ;;")
        consoleError.add("pp_ISF_weight is ${round(pp_ISF_weight, 4)} ;;")
        consoleError.add("iobThresholdPercent is ${iobThresholdPercent} ;;")
        consoleError.add("insulin activity graph: ${round(currentActivity, 4)} ;;")
        //consoleError.add("steps30min is ${recentSteps30Minutes} ;;")
        //consoleError.add("bg_acce  is $bg_acce ;;")
        //consoleError.add("Parabola fit results were acceleration:${round(bg_acce, 2)}, correlation:$fit_corr, duration:${glucose_status.parabolaMinutes}m")

        //consoleError.add("Parabola fit results were acceleration:${round(bg_acce, 2)}, correlation:$fit_corr, duration:${glucose_status.parabolaMinutes}m")
        if (glucose_status.a2 != 0.0 && fit_corr >= 0.9) {
            var minmax_delta: Double = -glucose_status.a1 / 2 / glucose_status.a2 * 5      // back from 5min block to 1 min
            val minmax_value: Double = round(glucose_status.a0 - minmax_delta * minmax_delta / 25 * glucose_status.a2, 1)
            minmax_delta = round(minmax_delta, 1)
            if (minmax_delta > 0 && bg_acce < 0) {
                consoleError.add("Parabolic fit extrapolates a maximum of ${convert_bg(minmax_value)} in about $minmax_delta minutes")
            } else if (minmax_delta > 0 && bg_acce > 0.0) {

                consoleError.add("Parabolic fit extrapolates a minimum of ${convert_bg(minmax_value)} in about $minmax_delta minutes")
                if (minmax_delta <= 30 && minmax_value < target_bg) {   // start braking
                    acce_weight = -bgBrake_ISF_weight
                    consoleError.add("extrapolation below target soon: use bgBrake_ISF_weight instead")
                }
            }
        }
        if (fit_corr < 0.9) {
            consoleError.add("acce_ISF adaptation by-passed as correlation ${round(fit_corr, 3)} is too low")
        } else {
            val fit_share = 10 * (fit_corr - 0.9)                            // 0 at correlation 0.9, 1 at 1.00
            var cap_weight = 1.0                                             // full contribution above target
            if (acce_weight == 1.0 && glucose_status.glucose < target_bg) {  // below target acce goes towards target
                if (bg_acce > 0) {
                    if (bg_acce > 1) {
                        cap_weight = 0.5
                    }            // halve the effect below target
                    acce_weight = bgBrake_ISF_weight
                } else if (bg_acce < 0) {
                    acce_weight = bgAccel_ISF_weight
                }
            } else if (acce_weight == 1.0) {                                 // above target acce goes away from target
                if (bg_acce < 0.0) {
                    acce_weight = bgBrake_ISF_weight
                } else if (bg_acce > 0.0) {
                    acce_weight = bgAccel_ISF_weight
                }
            }
            acce_ISF = 1.0 + bg_acce * cap_weight * acce_weight * fit_share
            consoleError.add("acce_ISF adaptation is ${round(acce_ISF, 2)}")
            if (acce_ISF != 1.0) {
                sens_modified = true
            }
        }
        autoIsfValues.acceIsf = acce_ISF

        val bg_ISF = 1 + interpolate(100 - bg_off)
        consoleError.add("bg_ISF adaptation is ${round(bg_ISF, 2)}")
        autoIsfValues.bgIsf = bg_ISF
        var liftISF: Double
        var final_ISF: Double = 1.0
        if (bg_ISF < 1.0) {
            liftISF = min(bg_ISF, acce_ISF)
            if (acce_ISF > 1.0) {
                liftISF = bg_ISF * acce_ISF                                 // bg_ISF could become > 1 now
                consoleError.add("bg_ISF adaptation lifted to ${round(liftISF, 2)} as bg accelerates already")
            }
            final_ISF = withinISFlimits(liftISF, autoISF_min, maxISFReduction, sensitivityRatio, exerciseModeActive, resistanceModeActive, stepActivityDetected, stepInactivityDetected)
            if (applyWeights) {
                consoleError.add(
                    "AutoISF weights ACTIVE AutoISF weights enabled in Preferences " +
                        "ISF " + convert_isf(min(720.0, sens / final_ISF))
                )
            } else {
                consoleError.add(
                    "AutoISF weights DISPLAY only: AutoISF weights disabled in Preferences " +
                        "ISF " + convert_isf(min(720.0, sens / final_ISF))
                )
            }
            //if (applyWeights) consoleError.add("AutoISF weights ACTIVE: max_iob ${round(maxIob, 1)} is odd, " +
            //                                       "ISF " + convert_isf(min(720.0, sens / final_ISF)))
            if (applyWeights) return min(720.0, round(sens / final_ISF, 1))         // observe ISF maximum of 720(?)
        } else if (bg_ISF > 1.0) {
            sens_modified = true
        }

        val bg_delta = glucose_status.delta
        val deltaType = "pp"
        when {
            bg_off > 0.0                     -> {
                consoleError.add("${deltaType}_ISF adaptation by-passed as average glucose < ${convert_bg(target_bg)}+10")
            }

            glucose_status.shortAvgDelta < 0 -> {
                consoleError.add("${deltaType}_ISF adaptation by-passed as no rise or too short lived")
            }

            else                             -> {
                pp_ISF = 1.0 + max(0.0, bg_delta * pp_ISF_weight)
                consoleError.add("pp_ISF adaptation is ${round(pp_ISF, 2)}")
                if (pp_ISF != 1.0) {
                    sens_modified = true
                }

            }
        }
        autoIsfValues.ppIsf = pp_ISF

        var dura_ISF = 1.0
        val weightISF: Double = dura_ISF_weight
        when {
            dura05 < 10.0      -> {
                consoleError.add("dura_ISF by-passed; bg is only $dura05 m at level ${convert_bg(avg05)}")
            }

            avg05 <= target_bg -> {
                consoleError.add("dura_ISF by-passed; avg. glucose ${convert_bg(avg05)} below target ${convert_bg(target_bg)}")
            }

            else               -> {
                // fight the resistance at high levels
                val dura05Weight = dura05 / 60
                val avg05Weight = weightISF / target_bg
                dura_ISF += dura05Weight * avg05Weight * (avg05 - target_bg)
                sens_modified = true
                consoleError.add("dura_ISF adaptation is ${round(dura_ISF, 2)} because ISF ${convert_isf(sens)} did not do it for ${round(dura05, 1)}m")
            }
        }
        autoIsfValues.duraIsf = dura_ISF

        if (sens_modified) {
            liftISF = max(dura_ISF, max(bg_ISF, max(acce_ISF, pp_ISF)))
            if (acce_ISF < 1.0) {
                consoleError.add("strongest autoISF factor ${round(liftISF, 2)} weakened to ${round(liftISF * acce_ISF, 2)} as bg decelerates already")
                liftISF = liftISF * acce_ISF
            }
            final_ISF = withinISFlimits(liftISF, autoISF_min, maxISFReduction, sensitivityRatio, exerciseModeActive, resistanceModeActive, stepActivityDetected, stepInactivityDetected)
            if (applyWeights) {
                consoleError.add(
                    "AutoISF weights ACTIVE AutoISF weights enabled in Preferences " +
                        "ISF " + convert_isf(min(720.0, sens / final_ISF))
                )
            } else {
                consoleError.add(
                    "AutoISF weights DISPLAY only: AutoISF weights disabled in Preferences" +
                        "ISF " + convert_isf(min(720.0, sens / final_ISF))
                )
            }
            //if (applyWeights) consoleError.add("AutoISF weights ACTIVE: max_iob ${round(maxIob, 1)} is odd, " +
            //                                       "ISF " + convert_isf(sens / final_ISF))
            if (applyWeights) return round(sens / final_ISF, 1)
            return round(sens / sensitivityRatio, 1) // display only: weights calculated but not applied
        }
        if (applyWeights) {
            consoleError.add(
                "AutoISF weights ACTIVE AutoISF weights enabled in Preferences " +
                    "ISF (unchanged) " + convert_isf(sens / sensitivityRatio)
            )
        } else {
            consoleError.add(
                "AutoISF weights DISPLAY only: AutoISF weights disabled in Preferences" +
                    "ISF (unchanged) " + convert_isf(sens / sensitivityRatio)
            )
        }
        //if (applyWeights) consoleError.add("AutoISF weights ACTIVE: max_iob ${round(maxIob, 1)} is odd, " +
        //                                       "ISF (unchanged) " + convert_isf(sens / sensitivityRatio))
        consoleError.add("----------------------------------")
        consoleError.add("end AutoISF")
        consoleError.add("----------------------------------")
        return round(sens / sensitivityRatio, 1)     // nothing changed
    }

    fun interpolate(xdata: Double): Double {   // interpolate ISF behaviour based on polygons defining nonlinear functions defined by value pairs for ...
        //  ...             <----------------------  glucose  ---------------------->
        val polyX = arrayOf(50.0, 60.0, 80.0, 90.0, 100.0, 110.0, 150.0, 180.0, 200.0)
        val polyY = arrayOf(-0.5, -0.5, -0.3, -0.2, 0.0, 0.0, 0.5, 0.7, 0.7)
        val polymax: Int = polyX.size - 1
        var step = polyX[0]
        var sVal = polyY[0]
        var stepT = polyX[polymax]
        var sValold = polyY[polymax]

        var newVal = 1.0
        var lowVal = 1.0
        val topVal: Double
        val lowX: Double
        val topX: Double
        val myX: Double
        var lowLabl = step

        if (step > xdata) {
            // extrapolate backwards
            stepT = polyX[1]
            sValold = polyY[1]
            lowVal = sVal
            topVal = sValold
            lowX = step
            topX = stepT
            myX = xdata
            newVal = lowVal + (topVal - lowVal) / (topX - lowX) * (myX - lowX)
        } else if (stepT < xdata) {
            // extrapolate forwards
            step = polyX[polymax - 1]
            sVal = polyY[polymax - 1]
            lowVal = sVal
            topVal = sValold
            lowX = step
            topX = stepT
            myX = xdata
            newVal = lowVal + (topVal - lowVal) / (topX - lowX) * (myX - lowX)
        } else {
            // interpolate
            for (i: Int in 0..polymax) {
                step = polyX[i]
                sVal = polyY[i]
                if (step == xdata) {
                    newVal = sVal
                    break
                } else if (step > xdata) {
                    topVal = sVal
                    lowX = lowLabl
                    myX = xdata
                    topX = step
                    newVal = lowVal + (topVal - lowVal) / (topX - lowX) * (myX - lowX)
                    break
                }
                lowVal = sVal
                lowLabl = step
            }
        }
        newVal = if (xdata > 100) {
            newVal * higher_ISFrange_weight
        } else {
            newVal * lower_ISFrange_weight
        }
        return newVal
    }

    fun withinISFlimits(
        liftISF: Double, minISFReduction: Double, maxISFReduction: Double, sensitivityRatio: Double,
        exerciseModeActive: Boolean, resistanceModeActive: Boolean, stepActivityDetected: Boolean, stepInactivityDetected: Boolean
    ): Double {
        var liftISFlimited: Double = liftISF
        if (liftISF < minISFReduction) {
            consoleError.add("weakest autoISF factor ${round(liftISF, 2)} limited by autoISF_min $minISFReduction")
            liftISFlimited = minISFReduction
        } else if (liftISF > maxISFReduction) {
            consoleError.add("strongest autoISF factor ${round(liftISF, 2)} limited by autoISF_max $maxISFReduction")
            liftISFlimited = maxISFReduction
        }
        val finalISF: Double
        var originSens = ""
        when {
            exerciseModeActive     -> {
                finalISF = liftISFlimited * sensitivityRatio                //# on top of TT modification
                originSens = "including exercise mode impact"
            }

            resistanceModeActive   -> {
                finalISF = liftISFlimited * sensitivityRatio                //# on top of TT modification
                originSens = "including resistance mode impact"
            }

            stepActivityDetected   -> {
                finalISF = liftISFlimited * sensitivityRatio                //# on top of activity detection
                originSens = "including activity detection impact"
            }

            stepInactivityDetected -> {
                finalISF = liftISFlimited * sensitivityRatio                //# on top of inactivity detection
                originSens = "including inactivity detection impact"
            }

            liftISFlimited >= 1    -> {                                // can we evr get here?
                finalISF = max(liftISFlimited, sensitivityRatio)
                if (liftISFlimited < sensitivityRatio) {
                    originSens = "from low TT modifier"
                }
            }

            else                   -> {
                finalISF = min(liftISFlimited, sensitivityRatio)            // low TT lowers sensitivity dominates
            }
        }
        consoleError.add("final ISF factor is ${round(finalISF, 2)} " + originSens)
        consoleError.add("----------------------------------")
        consoleError.add("end AutoISF")
        consoleError.add("----------------------------------")
        autoIsfValues.finalIsf = finalISF
        return finalISF
    }

    fun loop_smb(microBolusAllowed: Boolean, profile: OapsProfileAutoIsf, iob_data_iob: Double, useIobTh: Boolean, iobThEffective: Double): String {
        val iobThUser = preferences.get(IntKey.ApsAutoIsfIobThPercent)
        if (useIobTh) {
            val iobThPercent: Double
            if (profile.max_iob < 0.001) {
                iobThPercent = 0.0
                consoleLog.add("User setting iobTH disabled in LGS mode")
            } else {
                iobThPercent = round(iobThEffective / profile.max_iob * 100.0, 0)
            }
            if (iobThPercent == iobThUser.toDouble()) {
                consoleLog.add("User setting iobTH=$iobThUser% not modulated")
            } else if (iobThPercent > 0.0) {
                consoleLog.add("User setting iobTH=$iobThUser% modulated to ${iobThPercent.toInt()}% or ${round(iobThEffective, 2)}U")
                consoleLog.add("  due to profile %, exercise mode or similar")
            }
        } else {
            consoleLog.add("User setting iobTH=100% disables iobTH method")
        }
        autoIsfValues.iobThEffective = if (useIobTh) iobThEffective else profile.max_iob

        if (!microBolusAllowed) {
            return "AAPS"                                                 // see message in enable_smb
        }

        if (preferences.get(BooleanKey.FslCalibrationTrigger)) {
            preferences.put(LongKey.FslCalibrationStart, dateUtil.now())
            preferences.put(BooleanKey.FslCalibrationTrigger, false)
            preferences.put(BooleanKey.FslCalibrationEnd, false)
        }
        val calibrationMinutes = calibrationDuration - (dateUtil.now() - preferences.get(LongKey.FslCalibrationStart)) / 60000
        val calibrationStopsSMB = calibrationMinutes > 0 && !preferences.get(BooleanKey.FslCalibrationEnd)
        var CalibrationMsg = "Calibration json: {\"calibrationStart\":${preferences.get(LongKey.FslCalibrationStart)},\"calibrationIgnore\":${preferences.get(BooleanKey.FslCalibrationEnd)}"
        CalibrationMsg += "}"
        aapsLogger.debug(LTag.APS, CalibrationMsg)
        if (calibrationStopsSMB) {
            consoleLog.add("SMB disabled while calibrating for another ${calibrationMinutes}m")
            return "blocked"
        } else if (enableSMB_EvenOn_OddOff_always) {
            //TODO: cleaner conversion back to original mmol/L if applicable
            var target = convert_bg_to_units(profile.target_bg, profile)
            // val msgType: String
            val evenTarget: Boolean
            val msgUnits: String
            val msgTail: String
            if (profile.out_units == "mmol/L") {
                evenTarget = round(target * 10.0, 0).toInt() % 2 == 0
                target = round(target, 1)
                msgUnits = "has"
                msgTail = "decimal"
            } else {
                evenTarget = round(target, 0).toInt() % 2 == 0
                target = round(target, 0)
                msgUnits = "is"
                msgTail = "number"
            }
            val msgEven: String = if (evenTarget) "even" else "odd"

            if (!evenTarget) {
                consoleLog.add("SMB disabled; current target $target $msgUnits $msgEven $msgTail")
                consoleLog.add("Loop allows minimal power")
                return "blocked"
            } else if (profile.max_iob == 0.0) {
                consoleLog.add("SMB disabled because of max_iob=0")
                return "blocked"
            } else if (useIobTh && iobThEffective < iob_data_iob) {
                consoleLog.add("SMB disabled by Full Loop logic: iob $iob_data_iob is above effective iobTH $iobThEffective")
                consoleLog.add("Loop power level temporarily capped")
                return "iobTH"
            } else {
                consoleLog.add("SMB enabled; current target $target $msgUnits $msgEven $msgTail")
                return if (profile.target_bg < 100) {     // indirect assessment; later set it in GUI
                    consoleLog.add("Loop allows maximum power")
                    "fullLoop"                                      // even number
                } else {
                    consoleLog.add("Loop allows medium power")
                    "enforced"                                      // even number
                }
            }
        }
        consoleLog.add("Loop allows AAPS power level")
        return "AAPS"                                                      // leave it to standard AAPS
    }

    fun determine_varSMBratio(bg: Int, target_bg: Double, loop_wanted_smb: String): Double {   // let SMB delivery ratio increase from min to max depending on how much bg exceeds target
        val fix_SMB: Double = smb_delivery_ratio
        val lower_SMB = min(smb_delivery_ratio_min, smb_delivery_ratio_max)
        val higher_SMB = max(smb_delivery_ratio_min, smb_delivery_ratio_max)
        val higher_bg = target_bg + smb_delivery_ratio_bg_range
        var new_SMB: Double = fix_SMB
        if (smb_delivery_ratio_bg_range > 0) {
            new_SMB = lower_SMB + (higher_SMB - lower_SMB) * (bg - target_bg) / smb_delivery_ratio_bg_range
            new_SMB = max(lower_SMB, min(higher_SMB, new_SMB))   // cap if outside target_bg--higher_bg
        }
        if (loop_wanted_smb == "fullLoop") {                                // go for max impact
            consoleLog.add("SMB delivery ratio set to ${round(max(fix_SMB, new_SMB), 2)} as max of fixed and interpolated values")
            return max(fix_SMB, new_SMB)
        }

        if (smb_delivery_ratio_bg_range == 0.0) {                     // deactivated in SMB extended menu
            consoleLog.add("SMB delivery ratio set to fixed value ${round(fix_SMB, 2)}")
            return fix_SMB
        }
        if (bg <= target_bg) {
            consoleLog.add("SMB delivery ratio limited by minimum value ${round(lower_SMB, 2)}")
            return lower_SMB
        }
        if (bg >= higher_bg) {
            consoleLog.add("SMB delivery ratio limited by maximum value ${round(higher_SMB, 2)}")
            return higher_SMB
        }
        consoleLog.add("SMB delivery ratio set to interpolated value ${round(new_SMB, 2)}")
        return new_SMB
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null &&
            requiredKey != "absorption_smb_advanced" &&
            requiredKey != "activity_monitor" &&
            requiredKey != "auto_isf_settings" &&
            requiredKey != "smb_delivery_settings" &&
            requiredKey != "Libre_special_settings"
        ) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "openapsautoisf_settings"
            title = rh.gs(R.string.openaps_auto_isf)
            initialExpandedChildrenCount = 0
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsMaxBasal, dialogMessage = R.string.openapsma_max_basal_summary, title = R.string.openapsma_max_basal_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsSmbMaxIob, dialogMessage = R.string.openapssmb_max_iob_summary, title = R.string.openapssmb_max_iob_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseAutosens, title = R.string.openapsama_use_autosens))
            //addPreference(AdaptiveUnitPreference(ctx = context, unitKey = UnitDoubleKey.ApsLgsThreshold, dialogMessage = R.string.lgs_threshold_summary, title = R.string.lgs_threshold_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSensitivityRaisesTarget, summary = R.string.sensitivity_raises_target_summary, title = R.string.sensitivity_raises_target_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsResistanceLowersTarget, summary = R.string.resistance_lowers_target_summary, title = R.string.resistance_lowers_target_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfHighTtRaisesSens, summary = R.string.high_temptarget_raises_sensitivity_summary, title = R.string.high_temptarget_raises_sensitivity_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfLowTtLowersSens, summary = R.string.low_temptarget_lowers_sensitivity_summary, title = R.string.low_temptarget_lowers_sensitivity_title))
            addPreference(AdaptiveUnitPreference(ctx = context, unitKey = UnitDoubleKey.ApsAutoIsfHalfBasalExerciseTarget, dialogMessage = R.string.half_basal_exercise_target_summary, title = R.string.half_basal_exercise_target_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmb, summary = R.string.enable_smb_summary, title = R.string.enable_smb))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbWithHighTt, summary = R.string.enable_smb_with_high_temp_target_summary, title = R.string.enable_smb_with_high_temp_target))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbAlways, summary = R.string.enable_smb_always_summary, title = R.string.enable_smb_always))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbWithCob, summary = R.string.enable_smb_with_cob_summary, title = R.string.enable_smb_with_cob))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbWithLowTt, summary = R.string.enable_smb_with_temp_target_summary, title = R.string.enable_smb_with_temp_target))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbAfterCarbs, summary = R.string.enable_smb_after_carbs_summary, title = R.string.enable_smb_after_carbs))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseUam, summary = R.string.enable_uam_summary, title = R.string.enable_uam))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsMaxSmbFrequency, title = R.string.smb_interval_summary))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsMaxMinutesOfBasalToLimitSmb, title = R.string.smb_max_minutes_summary))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsUamMaxMinutesOfBasalToLimitSmb, dialogMessage = R.string.uam_smb_max_minutes, title = R.string.uam_smb_max_minutes_summary))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsCarbsRequestThreshold, dialogMessage = R.string.carbs_req_threshold_summary, title = R.string.carbs_req_threshold))
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "absorption_smb_advanced"
                title = rh.gs(app.aaps.core.ui.R.string.advanced_settings_title)
                addPreference(
                    AdaptiveIntentPreference(
                        ctx = context,
                        intentKey = IntentKey.ApsLinkToDocs,
                        intent = Intent().apply { action = Intent.ACTION_VIEW; data = rh.gs(R.string.openapsama_link_to_preference_json_doc).toUri() },
                        summary = R.string.openapsama_link_to_preference_json_doc_txt
                    )
                )
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAlwaysUseShortDeltas, summary = R.string.always_use_short_avg_summary, title = R.string.always_use_short_avg))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsMaxDailyMultiplier, dialogMessage = R.string.openapsama_max_daily_safety_multiplier_summary, title = R.string.openapsama_max_daily_safety_multiplier))
                addPreference(
                    AdaptiveDoublePreference(
                        ctx = context,
                        doubleKey = DoubleKey.ApsMaxCurrentBasalMultiplier,
                        dialogMessage = R.string.openapsama_current_basal_safety_multiplier_summary,
                        title = R.string.openapsama_current_basal_safety_multiplier
                    )
                )
            })
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "activity_monitor"
                title = rh.gs(R.string.activity_monitor_title)
                summary = rh.gs(R.string.activity_monitor_summary)
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ActivityMonitorDetection, summary = R.string.activity_monitor_summary, title = R.string.activity_monitor_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ActivityScaleFactor, dialogMessage = R.string.activity_scale_factor_summary, title = R.string.activity_scale_factor_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.InactivityScaleFactor, dialogMessage = R.string.inactivity_scale_factor_summary, title = R.string.inactivity_scale_factor_title))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ActivityMonitorOvernight, summary = R.string.ignore_inactivity_overnight_summary, title = R.string.ignore_inactivity_overnight_title))
                addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ActivityMonitorIdleStart, summary = R.string.inactivity_idle_start_summary, title = R.string.inactivity_idle_start_title))
                addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ActivityMonitorIdleEnd, summary = R.string.inactivity_idle_end_summary, title = R.string.inactivity_idle_end_title))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ActivityMonitorShowStepsFromSmartphone, summary = R.string.steps_graph_from_smartphone_summary, title = R.string.steps_graph_from_smartphone_title))
            })
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "auto_isf_settings"
                title = rh.gs(R.string.autoISF_settings_title)
                summary = rh.gs(R.string.autoISF_settings_summary)
                addPreference(preferenceManager.createPreferenceScreen(context).apply {
                    key = "Libre_special_settings"
                    title = "Libre special settings"  //rh.gs(R.string.smb_delivery_settings_title)
                    summary = "Calibrate and smooth Juggluco raw data"  //rh.gs(R.string.smb_delivery_settings_summary)
                    addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.FslCalibrationTrigger, summary = R.string.calibration_stops_smb_summary, title = R.string.calibration_stops_smb_title))
                    addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.FslCalibrationEnd, summary = R.string.calibration_enable_smb_summary, title = R.string.calibration_enable_smb_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.FslCalOffset, dialogMessage = R.string.fslCal_Offset_summary, title = R.string.fslCal_Offset_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.FslCalSlope, dialogMessage = R.string.fslCal_Slope_summary, title = R.string.fslCal_Slope_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.FslSmoothAlpha, dialogMessage = R.string.fsl_exp1_factor_summary, title = R.string.fsl_exp1_factor_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.MaintenanceCleanupDays, dialogMessage = R.string.MaintenanceCleanupDays_summary, title = R.string.MaintenanceCleanupDays_title))
                })
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseAutoIsfWeights, summary = R.string.openapsama_enable_autoISF, title = R.string.openapsama_enable_autoISF))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfMin, dialogMessage = R.string.openapsama_autoISF_min_summary, title = R.string.openapsama_autoISF_min))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfMax, dialogMessage = R.string.openapsama_autoISF_max_summary, title = R.string.openapsama_autoISF_max))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfBgAccelWeight, dialogMessage = R.string.openapsama_bgAccel_ISF_weight_summary, title = R.string.openapsama_bgAccel_ISF_weight))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfBgBrakeWeight, dialogMessage = R.string.openapsama_bgBrake_ISF_weight_summary, title = R.string.openapsama_bgBrake_ISF_weight))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfLowBgWeight, dialogMessage = R.string.openapsama_lower_ISFrange_weight_summary, title = R.string.openapsama_lower_ISFrange_weight))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfHighBgWeight, dialogMessage = R.string.openapsama_higher_ISFrange_weight_summary, title = R.string.openapsama_higher_ISFrange_weight))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfPpWeight, dialogMessage = R.string.openapsama_pp_ISF_weight_summary, title = R.string.openapsama_pp_ISF_weight))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfDuraWeight, dialogMessage = R.string.openapsama_dura_ISF_weight_summary, title = R.string.openapsama_dura_ISF_weight))
                addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsAutoIsfIobThPercent, dialogMessage = R.string.openapsama_iob_threshold_percent_summary, title = R.string.openapsama_iob_threshold_percent))
                addPreference(preferenceManager.createPreferenceScreen(context).apply {
                    key = "smb_delivery_settings"
                    title = rh.gs(R.string.smb_delivery_settings_title)
                    summary = rh.gs(R.string.smb_delivery_settings_summary)
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfSmbDeliveryRatio, dialogMessage = R.string.openapsama_smb_delivery_ratio_summary, title = R.string.openapsama_smb_delivery_ratio))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfSmbDeliveryRatioMin, dialogMessage = R.string.openapsama_smb_delivery_ratio_min_summary, title = R.string.openapsama_smb_delivery_ratio_min))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfSmbDeliveryRatioMax, dialogMessage = R.string.openapsama_smb_delivery_ratio_max_summary, title = R.string.openapsama_smb_delivery_ratio_max))
                    addPreference(
                        AdaptiveDoublePreference(
                            ctx = context,
                            doubleKey = DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange,
                            dialogMessage = R.string.openapsama_smb_delivery_ratio_bg_range_summary,
                            title = R.string.openapsama_smb_delivery_ratio_bg_range
                        )
                    )
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfSmbMaxRangeExtension, dialogMessage = R.string.openapsama_smb_max_range_extension_summary, title = R.string.openapsama_smb_max_range_extension))
                    addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfSmbOnEvenTarget, summary = R.string.enableSMB_EvenOn_OddOff_always_summary, title = R.string.enableSMB_EvenOn_OddOff_always))
                    addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfSplitBolusEnabled, summary = R.string.split_bolus_enabled_summary, title = R.string.split_bolus_enabled_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsAutoIsfSplitBolusInterval, dialogMessage = R.string.split_bolus_interval_summary, title = R.string.split_bolus_interval_title))
                    addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfTddSensitivity, summary = R.string.autoisf_tdd_sensitivity_summary, title = R.string.autoisf_tdd_sensitivity))
                    addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfTddFactor, summary = R.string.autoisf_tdd_factor_summary, title = R.string.autoisf_tdd_factor))
                })
            })
        }
    }
}

/*
OpenAPSAutoISFPlugin.kt320TDD2AU320TDD2AU212
 */