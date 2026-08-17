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
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.notifications.NotificationUserMessage
import app.aaps.core.interfaces.protection.ExportPasswordDataStore
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.rx.events.EventAutoIsfDirectTtCode
import app.aaps.core.interfaces.rx.events.EventMjUserAction
import app.aaps.core.interfaces.rx.events.EventSteroidUserAction
import app.aaps.core.interfaces.rx.events.EventNewNotification
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.smsCommunicator.Sms
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.NoteTimestampAllocator
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.IntentKey
import app.aaps.core.keys.LongKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.asAnnouncement
import app.aaps.core.objects.extensions.asSettingsExport
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.getPassedDurationToTimeInMinutes
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.objects.extensions.target
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.objects.wizard.BolusWizard
import app.aaps.core.utils.MidnightUtils
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveIntentPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.core.validators.preferences.AdaptiveUnitPreference
import app.aaps.plugins.aps.OpenAPSFragment
import app.aaps.plugins.smoothing.UnscentedKalmanFilterPlugin
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.aps.openAPS.AccelerationCalculator
import app.aaps.plugins.aps.openAPS.DeltaCalculator
import app.aaps.plugins.aps.openAPSSMB.PhoneMovementDetector
import app.aaps.plugins.aps.openAPSSMB.StepService
import com.google.gson.Gson
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.schedulers.Schedulers
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
    private val tddCalculator: TddCalculator,
    private val context: Context,
    private val importExportPrefs: ImportExportPrefs,
    private val exportPasswordDataStore: ExportPasswordDataStore,
    private val ukfSmoothing: UnscentedKalmanFilterPlugin,
    private val deltaCalculator: DeltaCalculator,
    private val accelerationCalculator: AccelerationCalculator
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
    @Inject lateinit var receiverStatusStore: app.aaps.core.interfaces.receivers.ReceiverStatusStore
    @Inject lateinit var bolusWizardProvider: Provider<BolusWizard>
    @Inject lateinit var commandQueue: CommandQueue

    // last values
    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.AUTO_ISF
    override var lastAPSResult: APSResult? = null
    private var consoleError = mutableListOf<String>()
    private var consoleLog = mutableListOf<String>()
    // Persists across loop cycles (plugin is a singleton) so the "dura_ISF IOB taper" Script Debug
    // line always shows *something* -- last engagement time/IOB/factor if it's ever fired, "never" if
    // not -- rather than only appearing on the cycle it actually engages and being silent/absent the
    // rest of the time.
    private var lastDuraTaperTimestamp: Long = 0L
    private var lastDuraTaperInfo: String = ""
    // TodOffsetsZero's own sustained-duration tracking -- see that block's doc comment. 0L means "not
    // currently in a continuously-true streak"; set once when the streak starts, cleared the moment the
    // condition goes false again, so a flickering condition never accumulates partial credit.
    private var todOffsetNegClearSince: Long = 0L
    private var todOffsetPosClearSince: Long = 0L
    private var virtualPseudoWizardLastStatus: String = "never fired"
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
    private val mjUserActionDisposable = CompositeDisposable()
    @Volatile private var pendingDirectTtCode: Double? = null
    @Volatile private var directTtCodeMatchActive = false

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
        mjUserActionDisposable.clear()
        mjUserActionDisposable += rxBus
            .toObservable(EventMjUserAction::class.java)
            .observeOn(Schedulers.io())
            .subscribe({ handleDirectMjUserAction(it.action, it.directMenu) }) {
                aapsLogger.error(LTag.APS, "Direct MJ Kotlin button failed", it)
            }
        mjUserActionDisposable += rxBus
            .toObservable(EventSteroidUserAction::class.java)
            .observeOn(Schedulers.io())
            .subscribe({ handleDirectSteroidUserAction(it.action, it.directMenu) }) {
                aapsLogger.error(LTag.APS, "Direct Steroid Kotlin button failed", it)
            }
        mjUserActionDisposable += rxBus
            .toObservable(EventAutoIsfDirectTtCode::class.java)
            .subscribe({ event ->
                if (!config.AAPSCLIENT) {
                    pendingDirectTtCode = event.mmol
                    aapsLogger.info(LTag.APS, "Queued local AutoISF settings control ${event.mmol}")
                }
            }) {
                aapsLogger.error(LTag.APS, "Direct AutoISF settings control failed", it)
            }
    }

    override fun onStop() {
        mjUserActionDisposable.clear()
        super.onStop()
    }

    /**
     * Executes the direct Overview MJ buttons without AutomationEvent/Action objects. The state is
     * checked again here after confirmation so a stale visible button cannot run the wrong branch.
     */
    private fun handleDirectMjUserAction(action: EventMjUserAction.Action, directMenu: Boolean = false) {
        if ((!directMenu && !preferences.get(BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled)) ||
            (directMenu && config.AAPSCLIENT) ||
            !preferences.get(BooleanKey.AutomationStatesEnabled)
        ) return

        val nomjRemains = try {
            automationStateService.inState("MJ", "NOMJremains")
        } catch (e: IllegalStateException) {
            aapsLogger.error(LTag.APS, "Direct MJ button cannot read State MJ", e)
            return
        }
        val conditionStillMatches = directMenu ||
            (action == EventMjUserAction.Action.START && nomjRemains) ||
            (action == EventMjUserAction.Action.RESTORE && !nomjRemains)
        if (!conditionStillMatches) {
            aapsLogger.info(LTag.APS, "Direct MJ button ignored because State MJ changed before execution")
            rxBus.send(EventRefreshOverview("MJ Kotlin state changed", true))
            return
        }

        when (action) {
            EventMjUserAction.Action.START -> {
                sendSms("Injection MJ 0.35_.70")
                rxBus.send(EventNewNotification(NotificationUserMessage("MJ", Notification.URGENT)))
                addGraphAnnouncement("MJ")
                setBgAccelIsfWeight(0.35)
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 70)
                switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfLowProfileName))
                setAutomationState("MJ", "MJ active")
                addCarePortalNote("MJ active")
            }

            EventMjUserAction.Action.RESTORE -> {
                sendSms("MJ dose 4+ days old")
                switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfStandardProfileName))
                setBgAccelIsfWeight(0.50)
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 70)
                setAutomationState("MJ", "NOMJremains")
                addCarePortalNote("NOMJremains")
            }
        }
        rxBus.send(EventRefreshOverview("MJ Kotlin button", true))
    }

    /** Kotlin-only ports of the native Steroid 110% start and 110% -> 130% user actions. */
    private fun handleDirectSteroidUserAction(
        action: EventSteroidUserAction.Action = EventSteroidUserAction.Action.START_110,
        directMenu: Boolean = false
    ) {
        if ((!directMenu && !preferences.get(BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled)) ||
            (directMenu && config.AAPSCLIENT) ||
            !preferences.get(BooleanKey.AutomationStatesEnabled)
        ) return

        val steroidKotlinButtonsEnabled = preferences.get(BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled)
        val conditionStillMatches = try {
            when (action) {
                EventSteroidUserAction.Action.START_110 ->
                    directMenu ||
                        (isTimeBetween(6, 0, 0, 0) &&
                            automationStateService.inState("MJ", "NOMJremains") &&
                            automationStateService.inState("Steroids", "Steroids Off"))

                EventSteroidUserAction.Action.INCREASE_130 ->
                    automationStateService.inState("Steroids", "SteroidsON") &&
                        (profileFunction.getProfile() as? ProfileSealed.EPS)?.value?.originalPercentage == 100 &&
                        (!steroidKotlinButtonsEnabled || profileFunction.getProfileName() == "Steroid Profile110")

                EventSteroidUserAction.Action.INCREASE_150 ->
                    automationStateService.inState("Steroids", "SteroidsON") &&
                        automationStateService.inState("MJ", "NOMJremains") &&
                        (profileFunction.getProfile() as? ProfileSealed.EPS)?.value?.originalPercentage == 100 &&
                        (!steroidKotlinButtonsEnabled || profileFunction.getProfileName() == "Steroid Profile130")

                EventSteroidUserAction.Action.INCREASE_190 ->
                    automationStateService.inState("Steroids", "SteroidsON") &&
                        automationStateService.inState("MJ", "NOMJremains") &&
                        (profileFunction.getProfile() as? ProfileSealed.EPS)?.value?.originalPercentage == 100 &&
                        (!steroidKotlinButtonsEnabled || profileFunction.getProfileName() == "Steroid Profile150")

                EventSteroidUserAction.Action.INCREASE_250 ->
                    automationStateService.inState("Steroids", "SteroidsON") &&
                        automationStateService.inState("MJ", "NOMJremains") &&
                        (profileFunction.getProfile() as? ProfileSealed.EPS)?.value?.originalPercentage == 100 &&
                        (!steroidKotlinButtonsEnabled || profileFunction.getProfileName() == "Current Profile190Real")

                EventSteroidUserAction.Action.TURN_OFF ->
                    automationStateService.inState("Steroids", "SteroidsON") &&
                        automationStateService.inState("MJ", "NOMJremains") &&
                        (profileFunction.getProfile() as? ProfileSealed.EPS)?.value?.originalPercentage == 100
            }
        } catch (e: IllegalStateException) {
            aapsLogger.error(LTag.APS, "Direct Steroid button cannot read automation states", e)
            false
        }
        if (!conditionStillMatches) {
            aapsLogger.info(LTag.APS, "Direct Steroid button ignored because its conditions changed before execution")
            rxBus.send(EventRefreshOverview("Steroid Kotlin state changed", true))
            return
        }

        when (action) {
            EventSteroidUserAction.Action.START_110 -> {
                sendSms("Turn SteroidsON")
                setAutomationState("Steroids", "SteroidsON")
                switchProfileIfNeeded("Steroid Profile110")
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 71)
                setBgAccelIsfWeight(0.70)
                addCarePortalNote("SteroidsON")
            }

            EventSteroidUserAction.Action.INCREASE_130 -> {
                sendSms("Steroids 110% are ON.. press to increase? to 130")
                switchProfileIfNeeded("Steroid Profile130")
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 73)
                setBgAccelIsfWeight(0.70)
                addCarePortalNote("Steroids130")
            }

            EventSteroidUserAction.Action.INCREASE_150 -> {
                sendSms("Steroids 130% are ON.. press to increase? to 150")
                switchProfileIfNeeded("Steroid Profile150")
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 74)
                setBgAccelIsfWeight(0.70)
                addCarePortalNote("Steroids150")
            }

            EventSteroidUserAction.Action.INCREASE_190 -> {
                sendSms("Steroids 150% are ON.. press to increase? to 190")
                switchProfileIfNeeded("Current Profile190Real")
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 75)
                setBgAccelIsfWeight(0.70)
                addCarePortalNote("Steroids190")
            }

            EventSteroidUserAction.Action.INCREASE_250 -> {
                sendSms("Steroids 190% are ON.. press to increase? to 250")
                switchProfileIfNeeded("Current ProfileReal250")
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 76)
                setBgAccelIsfWeight(0.70)
                addCarePortalNote("Steroids250")
            }

            EventSteroidUserAction.Action.TURN_OFF -> {
                switchProfileIfNeeded("Current ProfileReal")
                sendSms("Steroids are ON.. press to turn OFF? 71_0.71")
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 71)
                setBgAccelIsfWeight(0.71)
                setAutomationState("Steroids", "Steroids Off")
                addCarePortalNote("SteroidsOff")
            }
        }
        rxBus.send(EventRefreshOverview("Steroid Kotlin button", true))
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
        // Use the ORIGINAL (base) profile name, not getProfileName(): the latter returns the
        // customized/decorated name (e.g. "Current Profile (50%)") when a percentage switch is
        // active, and createProfileSwitch can't resolve that name in the store — the 100% switch
        // then gets recorded but never takes effect, leaving the profile stuck at 50% (which made
        // PP50.Off re-fire endlessly). getOriginalProfileName() gives the undecorated base name.
        val profileName = profileFunction.getOriginalProfileName()
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

    // Temporary percentage-only profile boost for a fixed duration, at profileName (defaults to
    // whatever profile is currently active — pass an explicit name only when the original action
    // targeted a specific named profile, e.g. HighOldPod/BolusGiven71's Standard profile).
    // Replaces the former startProfile50For360/180, startProfile110For5/10, startProfile120For5,
    // startProfile130For60 — all identical apart from percentage/duration/profileName.
    // Default to the ORIGINAL (base) profile name, not getProfileName() — see applyCurrentProfileAt100:
    // the customized/decorated name (e.g. "... (50%)") can't be resolved by createProfileSwitch.
    private fun startProfilePercentFor(percentage: Int, durationMinutes: Int, profileName: String = profileFunction.getOriginalProfileName()) {
        val profileStore = activePlugin.activeProfileSource.profile ?: return
        profileFunction.createProfileSwitch(
            profileStore = profileStore,
            profileName = profileName,
            durationInMinutes = durationMinutes,
            percentage = percentage,
            timeShiftInHours = 0,
            timestamp = dateUtil.now(),
            action = Action.PROFILE_SWITCH,
            source = Sources.Automation,
            note = "AutoISF: $percentage% for $durationMinutes min",
            listValues = listOf(ValueWithUnit.Percent(percentage), ValueWithUnit.Minute(durationMinutes))
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

    // One UKF-smoothed view of the raw/noise signal for hypo checks and SMB confirmations. Computing all
    // values from the same smoothing pass keeps UKFraw glucose/delta1/delta5 internally consistent and matches the
    // ukfRaw graph line rather than mixing that line with unsmoothed raw trigger values.
    private data class UkfRawMetrics(
        val glucose: Double?,
        val delta1: Double?,
        val delta5: Double?
    )

    private fun ukfRawMetrics(): UkfRawMetrics {
        val now = dateUtil.now()
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(now - T.mins(60).msecs(), now, ascending = false)
            .filter { it.noise != null && it.noise!! > 10.0 }
            .sortedByDescending { it.timestamp }
        if (readings.isEmpty()) return UkfRawMetrics(null, null, null)

        // Always independent raw/noise UKF, regardless of whether live AAPS BGL is EMA, UKF1 or UKF2.
        val smoothed = ukfSmoothing.smoothForDisplay(readings.map { it.timestamp to it.noise!! })
        if (smoothed.isEmpty()) return UkfRawMetrics(null, null, null)
        val ukfG = smoothed[0]
        val ukfD1 = if (smoothed.size >= 2) {
            val minutes = (readings[0].timestamp - readings[1].timestamp) / 60_000.0
            if (minutes > 0.0) (smoothed[0] - smoothed[1]) / minutes * 5.0 else null
        } else null
        val fiveMinAgo = now - T.mins(5).msecs()
        val refIndex = readings.indices.minByOrNull { kotlin.math.abs(readings[it].timestamp - fiveMinAgo) }
        val ukfD5 = refIndex?.takeIf { it != 0 }?.let { ukfG - smoothed[it] }
        return UkfRawMetrics(ukfG, ukfD1, ukfD5)
    }

    // UKF2's own always-current persisted history (ukf_librespecial_refined_history via
    // libreSpecialPostUkfHistory() -- see that function's own call sites in
    // XdripSourcePlugin.kt/NsIncomingDataProcessor.kt for the 2026-08-15 change that made it update
    // unconditionally), newest-first as both calculateDeltasGeneric() and fitBestParabola() expect
    // (libreSpecialPostUkfHistory returns oldest-first). Shared by ukf2DeltaMetrics() and
    // ukf2AccelerationMetrics() below so the lookback/fetch isn't duplicated. lookbackMinutes should
    // cover whichever consumer's widest window -- 45min for deltas (17.5-42.5min longAvgDelta), 47min
    // for the parabola fit's own maxLookbackMinutes default.
    private fun ukf2RecentHistory(lookbackMinutes: Long): List<Pair<Long, Double>> {
        val now = dateUtil.now()
        return ukfSmoothing.libreSpecialPostUkfHistory(now - T.mins(lookbackMinutes).msecs(), now)
            .sortedByDescending { it.first }
    }

    // UKF2's own delta5/delta15, computed from its own history rather than whatever's actually
    // live/dosing. First proof-of-concept for the per-type delta/acceleration comparison work on the
    // UKF3426 branch -- see DeltaCalculator.calculateDeltasGeneric()'s own doc comment for the shared
    // math this reuses.
    private fun ukf2DeltaMetrics(): DeltaCalculator.DeltaResult =
        deltaCalculator.calculateDeltasGeneric(ukf2RecentHistory(45))

    // UKF2's own bgAcceleration/deltaPl/deltaPn, same idea as ukf2DeltaMetrics() above but via
    // AccelerationCalculator.fitBestParabola() -- see its own doc comment for the shared math (a
    // generalized port of GlucoseStatusCalculatorAutoIsf's parabola-fit section) this reuses. 47-min
    // lookback matches fitBestParabola's own maxLookbackMinutes default with no extra headroom needed
    // (the function itself stops scanning once it exceeds that window).
    private fun ukf2AccelerationMetrics(): AccelerationCalculator.ParabolaFitResult =
        accelerationCalculator.fitBestParabola(ukf2RecentHistory(47))

    // UKFset1's own shadow-persisted history (ukf_rawrt_output_history via rawRealtimeHistory() --
    // see that function's own doc comment in UnscentedKalmanFilterPlugin.kt, and the shadow-call sites
    // in XdripSourcePlugin.kt/NsIncomingDataProcessor.kt that keep it current even while LibreSpecial
    // is the actual live source). Same newest-first reversal as ukf2RecentHistory() above --
    // rawRealtimeHistory() also returns oldest-first.
    private fun ukfSet1RecentHistory(lookbackMinutes: Long): List<Pair<Long, Double>> {
        val now = dateUtil.now()
        return ukfSmoothing.rawRealtimeHistory(now - T.mins(lookbackMinutes).msecs(), now)
            .sortedByDescending { it.first }
    }

    // UKFset1's own delta5/delta15/delta30 and bgAcceleration/deltaPl/deltaPn, same idea as the UKF2
    // pair above but from its own shadow history. Second type in the per-type delta/acceleration
    // comparison work on the UKF3426 branch, after UKF2.
    private fun ukfSet1DeltaMetrics(): DeltaCalculator.DeltaResult =
        deltaCalculator.calculateDeltasGeneric(ukfSet1RecentHistory(45))

    private fun ukfSet1AccelerationMetrics(): AccelerationCalculator.ParabolaFitResult =
        accelerationCalculator.fitBestParabola(ukfSet1RecentHistory(47))

    // LibreSpecial's own shadow-persisted history (fsl_shadow_history via libreSpecialShadowHistory()
    // -- see that function's own doc comment in UnscentedKalmanFilterPlugin.kt, and the shadow-call
    // sites in XdripSourcePlugin.kt/NsIncomingDataProcessor.kt that keep it current even while UKFset1
    // is the actual live source). Completes the live pair, after UKFset1. Same newest-first reversal
    // as the other two RecentHistory functions above.
    private fun libreSpecialShadowRecentHistory(lookbackMinutes: Long): List<Pair<Long, Double>> {
        val now = dateUtil.now()
        return ukfSmoothing.libreSpecialShadowHistory(now - T.mins(lookbackMinutes).msecs(), now)
            .sortedByDescending { it.first }
    }

    private fun libreSpecialShadowDeltaMetrics(): DeltaCalculator.DeltaResult =
        deltaCalculator.calculateDeltasGeneric(libreSpecialShadowRecentHistory(45))

    private fun libreSpecialShadowAccelerationMetrics(): AccelerationCalculator.ParabolaFitResult =
        accelerationCalculator.fitBestParabola(libreSpecialShadowRecentHistory(47))

    // UKF1's own recent history: raw/noise readings run through smoothForDisplay() -- same math as the
    // UKF1 graph line and ukfRawMetrics() above, but the FULL series rather than just glucose/delta1/
    // delta5. Fully stateless -- smoothForDisplay() needs no persisted history of its own (unlike the
    // live-pair types above), just a window of raw readings fed fresh each call, so unlike UKF2/
    // UKFset1/LibreSpecial this needed no new persistence to build. Fourth type in the per-type
    // delta/acceleration comparison work on the UKF3426 branch, first of the two batch/stateless types.
    private fun ukf1RecentHistory(lookbackMinutes: Long): List<Pair<Long, Double>> {
        val now = dateUtil.now()
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(now - T.mins(lookbackMinutes).msecs(), now, ascending = false)
            .filter { it.noise != null && it.noise!! > 10.0 }
            .sortedByDescending { it.timestamp }
        if (readings.isEmpty()) return emptyList()
        val smoothed = ukfSmoothing.smoothForDisplay(readings.map { it.timestamp to it.noise!! })
        return readings.mapIndexed { i, reading -> reading.timestamp to smoothed[i] }
    }

    private fun ukf1DeltaMetrics(): DeltaCalculator.DeltaResult =
        deltaCalculator.calculateDeltasGeneric(ukf1RecentHistory(45))

    private fun ukf1AccelerationMetrics(): AccelerationCalculator.ParabolaFitResult =
        accelerationCalculator.fitBestParabola(ukf1RecentHistory(47))

    // UKF3's own recent history: UKF1's own smoothed output (from ukf1RecentHistory() above) run
    // through UnscentedKalmanFilterPlugin.computeUkf3Series()'s EMA pass -- see that function's own
    // doc comment. Also fully stateless. Fifth and final type in the per-type comparison work.
    private fun ukf3RecentHistory(lookbackMinutes: Long): List<Pair<Long, Double>> {
        val ukf1OldestFirst = ukf1RecentHistory(lookbackMinutes).asReversed()
        if (ukf1OldestFirst.isEmpty()) return emptyList()
        val unitFactor = if (profileFunction.getUnits() == GlucoseUnit.MMOL) Constants.MMOLL_TO_MGDL else 1.0
        return ukfSmoothing.computeUkf3Series(
            ukf1OldestFirst,
            preferences.get(BooleanKey.Ukf3ApplyLibreCalibration),
            preferences.get(DoubleKey.FslCalSlope),
            preferences.get(DoubleKey.FslCalOffset),
            unitFactor,
            preferences.get(DoubleKey.FslSmoothAlpha),
            preferences.get(IntKey.FslMaxSmoothGap).toDouble()
        ).sortedByDescending { it.first }
    }

    private fun ukf3DeltaMetrics(): DeltaCalculator.DeltaResult =
        deltaCalculator.calculateDeltasGeneric(ukf3RecentHistory(45))

    private fun ukf3AccelerationMetrics(): AccelerationCalculator.ParabolaFitResult =
        accelerationCalculator.fitBestParabola(ukf3RecentHistory(47))

    // Sign-disagreement detector for the per-type acceleration comparison work above: flags when a
    // type's own bgAcceleration disagrees in SIGN with the loop's real value, rather than requiring
    // eyeballing every Metrics log line by hand. Anchored on real UKF2 data from this branch's own
    // testing (2026-08-16): a clean sign flip (+3.632 vs -0.103) and a borderline near-zero one
    // (+0.025 vs -0.786) were both observed within 5 samples -- the 0.3 mg/dL/5min^2 floor on BOTH
    // sides is specifically there to exclude that near-zero case (neither value is a real trend
    // signal at that magnitude) while still catching genuine disagreements. WARN level (not debug, like
    // the routine Metrics lines) so it's easy to filter for and stands out on its own.
    private fun logAccelSignDisagreement(typeName: String, typeAccel: Double, loopAccel: Double?) {
        if (loopAccel == null) return
        val meaningfulThreshold = 0.3
        if (kotlin.math.abs(typeAccel) < meaningfulThreshold || kotlin.math.abs(loopAccel) < meaningfulThreshold) return
        if ((typeAccel > 0) != (loopAccel > 0)) {
            aapsLogger.warn(
                LTag.APS,
                "AccelSignDisagreement[$typeName]: type=${round(typeAccel, 3)} loop=${round(loopAccel, 3)} " +
                    "ratio=${round(kotlin.math.abs(typeAccel) / kotlin.math.abs(loopAccel), 1)}x"
            )
        }
    }

    // "Who detected the drop first" tracker: prompted by a real visual observation (2026-08-16) that
    // UKF1's graph line reacted to a drop before LibreSpecial's did -- logs the first time each type's
    // own delta5 crosses below a "genuine decline" threshold, and how far ahead/behind that is relative
    // to whichever type crossed first in the current episode. In-memory only (like lastRunTimestamps
    // elsewhere in this file) -- resets on app restart, fine for a diagnostic-only feature. Threshold
    // (-0.2mmol/5min) is a round, investigative choice, not anchored on specific real episode data like
    // the dosing-safety guards elsewhere in this file. Per-type crossing state resets the moment that
    // type's own delta5 recovers above threshold, so a sustained decline only logs once per type per
    // episode, and a later, separate decline logs again as a fresh episode.
    private val dropCrossedAt = mutableMapOf<String, Long>()

    private fun logDropDetection(typeName: String, delta5: Double) {
        val threshold = -3.6 /* -0.2 mmol/5min */
        val now = dateUtil.now()
        if (delta5 <= threshold) {
            if (!dropCrossedAt.containsKey(typeName)) {
                val earliestSoFar = dropCrossedAt.values.minOrNull()
                val leadLagText = if (earliestSoFar == null) "first"
                    else "${if (now >= earliestSoFar) "+" else ""}${round((now - earliestSoFar) / 1000.0, 1)}s vs first"
                dropCrossedAt[typeName] = now
                aapsLogger.warn(LTag.APS, "DropDetected[$typeName]: delta5=${round(delta5, 2)} ($leadLagText)")
            }
        } else {
            dropCrossedAt.remove(typeName)
        }
    }

    // Live HP2, matching AutoIsfHistoryExporter.hp2Str(): (BGL - IOB) + 0.5*SDelta
    // + 0.5*UKF raw delta5 - gated COBt/12. Null when UKF delta is unavailable.
    private fun hypoPrediction2Mmol(
        glucoseMgdl: Double,
        shortAvgDeltaMgdl: Double,
        longAvgDeltaMgdl: Double,
        iob: Double,
        cob: Double,
        bgAcceleration: Double,
        ukfRaw: UkfRawMetrics? = null
    ): Double? {
        val ukfDelta5Mgdl = (ukfRaw ?: ukfRawMetrics()).delta5 ?: return null
        val sdeltaMmol = shortAvgDeltaMgdl / 18.0182
        val ldeltaMmol = longAvgDeltaMgdl / 18.0182
        val cobPenaltyApplies =
            (((bgAcceleration < 0.0 && sdeltaMmol < 0.1 && ldeltaMmol < 0.1) ||
                (sdeltaMmol < 0.0 && ldeltaMmol < 0.0)) &&
                autoIsfValues.insulinReq <= 0.0)
        val cobtTerm = if (cobPenaltyApplies) liveCobT(cob) / 12.0 else 0.0
        return (glucoseMgdl / 18.0182 - iob) +
            0.5 * sdeltaMmol +
            0.5 * (ukfDelta5Mgdl / 18.0182) -
            cobtTerm
    }

    // Live HP1, matching AutoIsfHistoryExporter.hpStr(): (BGL - IOB) + 0.25*SDelta + 0.25*raw-Libre-delta5
    // + COB/12 (ungated, always additive -- unlike HP2's gated -COBt/12, COB here is treated as
    // straightforwardly protective, never a risk flag). rawDelta5MinMgdl() is the same raw/noise window
    // hpStr's rawDelta5Mmol reads, just "now"-anchored instead of an arbitrary export-row timestamp. Null
    // when that raw-delta window doesn't have enough data (same condition hpStr falls back to "--" for).
    private fun hypoPrediction1Mmol(glucoseMgdl: Double, iob: Double, shortAvgDeltaMgdl: Double, cob: Double): Double? {
        val libreDelta5Mgdl = rawDelta5MinMgdl() ?: return null
        val sdeltaMmol = shortAvgDeltaMgdl / 18.0182
        return (glucoseMgdl / 18.0182 - iob) +
            0.25 * sdeltaMmol +
            0.25 * (libreDelta5Mgdl / 18.0182) +
            cob / 12.0
    }

    /** Strict 90-minute BG coverage check for the Virtual-Pump pseudo-wizard experiment. */
    private fun allRecentBgAbove90Minutes(minimumMgdl: Double): Boolean {
        val now = dateUtil.now()
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(now - T.mins(90).msecs(), now, ascending = true)
        if (readings.size < 10) return false
        if (readings.first().timestamp > now - T.mins(85).msecs()) return false
        if (readings.last().timestamp < now - T.mins(10).msecs()) return false
        if (readings.zipWithNext().any { (a, b) -> b.timestamp - a.timestamp > T.mins(10).msecs() }) return false
        return readings.all { it.value > minimumMgdl }
    }

    /**
     * Minutes during the last 15 where the stored/current AutoISF factor was actively raising insulin.
     * Factors themselves are normally near 1, so the requested >7 and >4 thresholds are interpreted as
     * active minutes, not factor magnitudes. Each sample owns the interval since the preceding sample,
     * capped at five minutes; missing history therefore fails closed.
     */
    private fun recentAdaptationMinutes(selector: (AIV) -> Double): Double {
        val now = dateUtil.now()
        val start = now - T.mins(15).msecs()
        val records = (persistenceLayer.getAutoIsfValuesFromTimeToTime(start, now) + autoIsfValues)
            .filter { it.timestamp in start..now }
            .distinctBy { it.timestamp }
            .sortedBy { it.timestamp }
        if (records.size < 2 || records.first().timestamp > start + T.mins(5).msecs()) return 0.0
        var activeMinutes = 0.0
        records.zipWithNext().forEach { (previous, current) ->
            if (selector(current) > 1.0) {
                activeMinutes += ((current.timestamp - previous.timestamp) / 60_000.0).coerceIn(0.0, 5.0)
            }
        }
        return activeMinutes
    }

    /** Live counterpart of the export-only retrospective COBt value. */
    private fun liveCobT(currentCob: Double): Double {
        val latest = persistenceLayer.getAutoIsfValuesFromTime(dateUtil.now() - T.mins(10).msecs()).maxByOrNull { it.timestamp }
            ?: return currentCob
        val absorbed = iobCobCalculator.ads.getAutosensDataAtTime(latest.timestamp)?.this5MinAbsorption ?: 0.0
        val unexplainedRate = (latest.uamCarbImpact - absorbed).coerceAtLeast(0.0)
        return currentCob + unexplainedRate / 5.0
    }

    // Raw 15-min delta (.noise), normalised down to a per-5-min rate (÷3) so it stays on the same
    // mmol/5min scale as rawDelta5MinMgdl/rawDelta1MinMgdl — matches AutoIsfHistoryExporter's rΔ15
    // table column convention exactly, so a live gate reading this lines up with what's shown in the
    // AIV history table. Feeds the early-AM raw-rise guard's corroboration/divergence check.
    private fun rawDelta15MinMgdl(): Double? {
        val now = dateUtil.now()
        val r = persistenceLayer.getBgReadingsDataFromTimeToTime(now - 17 * 60 * 1000L, now, ascending = false)
        if (r.size < 2) return null
        val newest = r[0].noise ?: return null
        val fifteenMinAgo = now - 15 * 60 * 1000L
        val ref = r.minByOrNull { kotlin.math.abs(it.timestamp - fifteenMinAgo) } ?: return null
        if (ref.timestamp == r[0].timestamp) return null
        val diff = newest - (ref.noise ?: return null)
        return diff / 3.0
    }

    // AAPS-processed (gv.value, NOT raw noise) 1-min delta — mirrors PrepareBgDataWorker's
    // aapsOneMinuteDelta() (feeds the graph's "A1=" label). Distinct from rawDelta1MinMgdl(): .value
    // already has per-reading sensor-level processing applied (calibration/filtering), unlike .noise
    // (the raw native signal) — the two can genuinely disagree even at this short a window, not just
    // at the longer 5/15/40-min AAPS-average level. .value is non-nullable, unlike .noise.
    private fun aapsDelta1MinMgdl(): Double? {
        val now = dateUtil.now()
        val r = persistenceLayer.getBgReadingsDataFromTimeToTime(now - 3 * 60 * 1000L, now, ascending = false)
        if (r.size < 2) return null
        val mins = (r[0].timestamp - r[1].timestamp) / 60_000.0
        if (mins <= 0) return null
        return (r[0].value - r[1].value) / mins * 5.0
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

    // Hours since the last recorded sensor change, or null if none found. Matches TriggerSensorAge's
    // use of TE.Type.SENSOR_CHANGE.
    private fun hoursSinceLastSensorChange(): Double? {
        val last = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE) ?: return null
        return (dateUtil.now() - last.timestamp) / 3_600_000.0
    }

    // Whether raw Libre BGL has been observed over 12.0mmol within the last [hours]. false if never
    // observed (ApsAutoIsfLibreOver12Ts == 0). Shared by OldSensorAdj (24h) and the pod-boost/MJ/PPrevert
    // gates below (48h) -- same underlying tracked timestamp, just compared against different windows
    // depending on which automation is asking.
    private fun recentLibreOver12(hours: Int): Boolean {
        val ts = preferences.get(LongKey.ApsAutoIsfLibreOver12Ts)
        return ts != 0L && (dateUtil.now() - ts) <= T.hours(hours.toLong()).msecs()
    }

    // Not yet called anywhere; ready for later conditions that need "time since last bolus" in code.
    // Mirrors TriggerBolusAgo: returns null (not just a huge number) when no NORMAL bolus has ever been logged,
    // so callers must decide explicitly how to treat "no history yet" rather than it silently always-passing.
    private fun minutesSinceLastNormalBolus(): Int? {
        val lastBolusTime = persistenceLayer.getNewestBolusOfType(BS.Type.NORMAL)?.timestamp ?: return null
        return ((dateUtil.now() - lastBolusTime).toDouble() / (60 * 1000)).toInt()
    }

    private fun minutesSinceLastCarbs(): Int? {
        val lastCarbTime = persistenceLayer.getNewestCarbs()?.timestamp ?: return null
        return ((dateUtil.now() - lastCarbTime).toDouble() / (60 * 1000)).toInt()
    }

    // Average gap in seconds between SMBs delivered in the last 5 minutes. Returns a large sentinel
    // (so any "<= 70s" stacking guard is false) when fewer than 2 SMBs fell in the window — nothing
    // to measure. Used to block the delivery-ratio boosts while SMBs stack, and (in DetermineBasal)
    // to trim a rapidly-stacking SMB to 90%.
    private fun smbInterval5Sec(): Double {
        val now = dateUtil.now()
        val smbs = persistenceLayer.getBolusesFromTimeToTime(now - 5 * 60_000L, now, ascending = false)
            .filter { it.type == BS.Type.SMB }
        if (smbs.size < 2) return 9999.0
        val spanSec = (smbs.first().timestamp - smbs.last().timestamp).toDouble() / 1000.0
        return spanSec / (smbs.size - 1)
    }

    // Count of SMBs delivered in the last 5 minutes — same query as smbInterval5Sec(), just the raw
    // count instead of the derived average gap. Used alongside it for the hard-stacking delivery-ratio
    // reversion below (gap alone can be misleadingly low with only 2-3 SMBs; requiring a minimum count
    // too confirms genuinely sustained rapid delivery, not a coincidental close pair).
    private fun smbCount5Min(): Int {
        val now = dateUtil.now()
        return persistenceLayer.getBolusesFromTimeToTime(now - 5 * 60_000L, now, ascending = false)
            .count { it.type == BS.Type.SMB }
    }

    // Total UNITS of SMB delivered in the last 10 minutes -- deliberately a sum, not a count or an
    // interval, unlike the two helpers either side of it. The 6->7 Aug 2026 bursts showed why: both were
    // ~60s apart (so indistinguishable from normal 1-min-cadence operation by interval) and delivered
    // 1.50U and 1.10U respectively. Only the cumulative amount separates them from routine dosing --
    // measured across recent exports, the median 10-min total is 0.20U and the 90th percentile 0.60U, so
    // those bursts sit far out in the tail. Feeds the cumulative SMB cap in DetermineBasalAutoISF.kt.
    private fun smbSum10Min(): Double {
        val now = dateUtil.now()
        return persistenceLayer.getBolusesFromTimeToTime(now - 10 * 60_000L, now, ascending = false)
            .filter { it.type == BS.Type.SMB }
            .sumOf { it.amount }
    }

    // Total UNITS of SMB delivered in the last 30 minutes -- same shape as smbSum10Min(), wider window.
    // Used as OvernightDuraRescue's "no active stacking" gate: a rescue is meant for a plateaued high
    // with nothing currently being delivered for it, not for topping up a burst already in progress.
    // Also feeds determine_basal()'s own late-FastRise trim and 30-min cumulative SMB cap (see
    // DetermineBasalAutoISF.kt) -- reverted to SMB-only (was briefly changed to include normal boluses
    // too, which turned out to affect that always-on dosing math, not what was actually being asked for
    // -- see the SMB_STACK_TOTAL yellow-label reconstruction in PrepareTreatmentsDataWorker.kt instead).
    private fun smbSum30Min(): Double {
        val now = dateUtil.now()
        return persistenceLayer.getBolusesFromTimeToTime(now - 30 * 60_000L, now, ascending = false)
            .filter { it.type == BS.Type.SMB }
            .sumOf { it.amount }
    }

    // Count of SMBs delivered in the last 20 minutes. Deliberately a much longer window than
    // smbCount5Min() -- used by BolusGivenMildFailsafe to detect genuine delivery silence (sensor
    // dropout, pod disconnect, etc.), not just the normal gap between doses that a 5-min window
    // could mistake for suppression.
    private fun smbCount20Min(): Int {
        val now = dateUtil.now()
        return persistenceLayer.getBolusesFromTimeToTime(now - 20 * 60_000L, now, ascending = false)
            .count { it.type == BS.Type.SMB }
    }

    // Total IOB (bolus + temp-basal) at a timestamp — matches the loop's IOB and the "IOB change"
    // trigger. Basal contribution is included deliberately.
    private fun totalIobAt(time: Long): Double {
        val profile = profileFunction.getProfile(time) ?: return 0.0
        return iobCobCalculator.calculateFromTreatmentsAndTemps(time, profile).iob
    }

    // Not yet called anywhere; ready for later conditions. Mirrors ActionSetAcceWeight: same underlying
    // preference key ("bgAccel_ISF_weight") the DoubleKey.ApsAutoIsfBgAccelWeight getter already reads.
    private fun setBgAccelIsfWeight(weight: Double) {
        preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, weight)
    }

    // Not yet called anywhere; ready for later conditions. Mirrors ActionSetSmbDeliveryRatio: same
    // underlying preference key the smb_delivery_ratio getter above already reads.
    private fun setSmbDeliveryRatio(ratio: Double) {
        preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryRatio, ratio)
    }

    // Returns the active TT's lowTarget in mg/dL, or null if no TT is active.
    private fun activeTtMgdl(): Double? = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.lowTarget

    // Converts mmol/L to mg/dL using the app's actual conversion constant (Constants.MMOLL_TO_MGDL
    // = 18.0), NOT the 18.016 this file's comments otherwise use for display rounding. Use this
    // (not a hand-computed literal) wherever a value is being compared for near-equality against a
    // specific manually-set constant, e.g. identifying which of several closely-spaced TT presets is
    // active — that's where the 18.016-vs-18.0 gap can actually flip a match, unlike ordinary
    // glucose/delta thresholds where a ~0.1 mg/dL drift is inconsequential.
    private fun mmolToMgdl(mmol: Double): Double = mmol * Constants.MMOLL_TO_MGDL

    // True when the currently active TT is within toleranceMmol of targetMmol. Centralizes the
    // "identify which manually-set TT is active" pattern (5.7/5.8mmol reversal, 6.8mmol Activity,
    // 8.0mmol hyp) so the conversion constant and tolerance are correct and consistent everywhere.
    private fun activeTtNear(targetMmol: Double, toleranceMmol: Double): Boolean {
        directTtCodeMatchActive = pendingDirectTtCode?.let {
            kotlin.math.abs(it - targetMmol) <= toleranceMmol
        } == true
        if (directTtCodeMatchActive) return true
        val ttMgdl = activeTtMgdl() ?: return false
        return kotlin.math.abs(ttMgdl - mmolToMgdl(targetMmol)) <= mmolToMgdl(toleranceMmol)
    }

    // Mirrors the real automation engine's Comparator.Compare.check(obj1, obj2, tolerance) —
    // see plugins/automation/.../elements/Comparator.kt: every native trigger comparison (not just
    // equality) is fuzzed by a fixed 0.001 mg/dL tolerance, documented there as already validated
    // ("a prior half-step-based tolerance was reverted for not working reliably; flat 0.001 replaced
    // it"). Use this for any future near-equality check against a fixed mg/dL value that isn't the
    // TT-matching case activeTtNear() already covers.
    private fun fuzzyEquals(a: Double, b: Double, toleranceMgdl: Double = 0.001): Boolean =
        kotlin.math.abs(a - b) <= toleranceMgdl

    // Cancels the current TT unconditionally. Mirrors ActionStopTempTarget.
    private fun cancelCurrentTempTarget() {
        // A pump/Virtual popup command uses the same handler without creating a TT. In that path an
        // unrelated therapeutic TT must be left untouched.
        if (directTtCodeMatchActive) return
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

    // Sends directly to the numbers configured in the given per-automation StringKey (semicolon-separated,
    // same format as SmsAllowedNumbers), IN ADDITION TO the general broadcast sendSms() already sent —
    // e.g. so a caregiver who is excluded from routine automation SMS (SmsBroadcastExcludeNumbers)
    // still gets this specific targeted alert. No-op if the setting is empty. One StringKey per
    // automation that wants its own list (SmsBattAlertNumbers, SmsGentleHypoAlertNumbers, etc.).
    private fun sendSmsToNumbers(text: String, key: StringKey) {
        preferences.get(key).split(";")
            .map { it.replace("\\s+".toRegex(), "") }
            .filter { it.isNotEmpty() }
            .forEach { number -> smsCommunicator.sendSMS(Sms(number, text)) }
    }

    // Mirrors ActionCarePortalEvent for a plain note. Default duration is 5 min (not the 30 min the
    // original ported automations used) per explicit preference.
    // NoteTimestampAllocator.next() (not a bare dateUtil.now()) — insertPumpTherapyEventIfNewByTimestamp's
    // dedup check keys purely on (type, timestamp), not note text, so two different notes landing on the
    // same millisecond (very possible with ~20+ automations checked sequentially, synchronously, in the
    // same cycle) would otherwise silently drop whichever one loses the race, with no error anywhere.
    private fun addCarePortalNote(note: String, durationInMinutes: Int = 1) {
        val therapyEvent = TE(
            timestamp = NoteTimestampAllocator.next(dateUtil.now()),
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
        ).subscribe(
            { rxBus.send(EventRefreshOverview("AutoISF CarePortal note", true)) },
            { error -> aapsLogger.error(LTag.APS, "Failed to save AutoISF CarePortal note: $note", error) }
        )
    }
    private fun todOffsetCarePortalNote(value: Double): String {
        val roundedValue = round(value, 1).let { if (it == 0.0) 0.0 else it }
        val sign = if (roundedValue >= 0.0) "+" else ""
        return "T$sign$roundedValue"
    }

    private fun compactSettingNote(prefix: String, value: Double, decimals: Int, omitLeadingZero: Boolean = false): String {
        val formattedValue = String.format(Locale.US, "%.${decimals}f", value)
        var note = prefix + if (omitLeadingZero) formattedValue.removePrefix("0") else formattedValue
        while (note.length > 5 && note.endsWith("0") && note.contains(".")) note = note.dropLast(1)
        return note
    }



    // Mirrors ActionNotification's TherapyEvent handling exactly (confirmed on-device working:
    // shows on the BGL graph and in Treatments/Notes) — unlike addCarePortalNote()'s TE.Type.NOTE,
    // which does not render on the graph. TE.asAnnouncement() + Action.TREATMENT + the explicit
    // EventRefreshOverview send are all load-bearing; this isn't just insertPumpTherapyEventIfNewByTimestamp
    // with a different Type.
    private fun addGraphAnnouncement(note: String) {
        disposable += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = TE.asAnnouncement(note),
            timestamp = dateUtil.now(),
            action = Action.TREATMENT,
            source = Sources.Automation,
            note = note,
            listValues = listOf()
        ).subscribe()
        rxBus.send(EventRefreshOverview("AutoISF"))
    }

    // Faithful port of ActionSettingsExport.doAction() — reuses the exact same password/encryption
    // infrastructure (ImportExportPrefs, ExportPasswordDataStore) rather than reimplementing any of
    // it, so the exported settings backup stays encrypted exactly as it would via the real action.
    // `label` matches what the original action's "text" field would have been, e.g. "NewPod".
    private fun exportSettingsFor(label: String) {
        var exportResultMessage: String
        var announceAlert = false
        val notification: NotificationUserMessage

        if (exportPasswordDataStore.exportPasswordStoreEnabled()) {
            val (password, isExpired, isAboutToExpire) = exportPasswordDataStore.getPasswordFromDataStore(context)
            if (password.isNotEmpty() && !isExpired) {
                exportResultMessage = if (isAboutToExpire) {
                    rh.gs(app.aaps.core.ui.R.string.export_result_message_about_to_expire)
                } else {
                    rh.gs(app.aaps.core.ui.R.string.export_result_message_exported)
                }
                var localNotification = NotificationUserMessage(exportResultMessage, if (isAboutToExpire) Notification.LOW else Notification.INFO)
                if (!importExportPrefs.exportSharedPreferencesNonInteractive(context, password)) {
                    exportResultMessage = rh.gs(app.aaps.core.ui.R.string.export_result_message_failed)
                    localNotification = NotificationUserMessage(exportResultMessage, Notification.URGENT)
                    announceAlert = true
                }
                notification = localNotification
            } else {
                exportResultMessage = rh.gs(app.aaps.core.ui.R.string.export_result_message_expired)
                notification = NotificationUserMessage(exportResultMessage, Notification.URGENT)
                exportPasswordDataStore.clearPasswordDataStore(context)
                announceAlert = true
            }
        } else {
            exportResultMessage = rh.gs(app.aaps.core.ui.R.string.export_result_message_disabled)
            notification = NotificationUserMessage(exportResultMessage, Notification.URGENT)
        }
        rxBus.send(EventNewNotification(notification))

        val error = "$label: $exportResultMessage"
        disposable += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = TE.asSettingsExport(error = error),
            timestamp = dateUtil.now(),
            action = Action.EXPORT_SETTINGS,
            source = Sources.Automation,
            note = exportResultMessage,
            listValues = listOf()
        ).subscribe()

        if (announceAlert && preferences.get(BooleanKey.NsClientCreateAnnouncementsFromErrors) && config.APS) {
            val alert = "${rh.gs(app.aaps.core.ui.R.string.export_alert)}($label): $exportResultMessage"
            disposable += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                therapyEvent = TE.asAnnouncement(error = alert),
                timestamp = dateUtil.now(),
                action = Action.EXPORT_SETTINGS,
                source = Sources.Automation,
                note = exportResultMessage,
                listValues = listOf()
            ).subscribe()
        }

        rxBus.send(EventRefreshOverview("ExportSettingsPodActivation"))
    }

    // Not yet called anywhere; ready for later conditions. Mirrors TriggerAutomationState: exact string
    // equality (state values are names, not numbers), gated the same way — false when states are disabled.
    // Not affected by the fuzzy-equals tolerance noted below, since that only applies to Double comparisons.
    private fun checkAutomationState(stateName: String, stateValue: String): Boolean {
        if (!preferences.get(BooleanKey.AutomationStatesEnabled)) return false
        return automationStateService.inState(stateName, stateValue)
    }

    // True only when MJ is literally the "MJ active" state — the state right after the native button
    // press, before the timed native automations advance it to MJ2/MJ3. Deliberately narrower than
    // "any non-NOMJremains value": MJ2/MJ3 do NOT block here, only "MJ active" does.
    private fun mjActive(): Boolean = checkAutomationState("MJ", "MJ active")

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

    private fun directTtCodeForRunKey(key: String): Double? = when (key) {
        "SmbDeliveryDownTT" -> 5.002
        "SmbDeliveryUpTT" -> 5.004
        "SensorAgeToggleTT" -> 5.006
        "BoostToggleTT" -> 5.008
        "PpWeightDownTT" -> 5.012
        "PpWeightUpTT" -> 5.014
        "AcceWeightDownTT" -> 5.016
        "AcceWeightUpTT" -> 5.018
        "DuraWeightDownTT" -> 5.022
        "DuraWeightUpTT" -> 5.024
        "LibreSlopeDownTT" -> 5.026
        "LibreSlopeUpTT" -> 5.028
        "LibreOffsetDownTT" -> 5.032
        "LibreOffsetUpTT" -> 5.034
        "SmbOffsetDownTT" -> 5.036
        "SmbOffsetUpTT" -> 5.038
        "CleanGraphTT" -> 5.042
        "WizardPctDownTT" -> 5.046
        "WizardPctUpTT" -> 5.048
        "MildBoostDownTT" -> 5.052
        "MildBoostUpTT" -> 5.054
        "PpWeightHighDownTT" -> 5.056
        "PpWeightHighUpTT" -> 5.058
        "AcceWeightHighDownTT" -> 5.062
        "AcceWeightHighUpTT" -> 5.064
        "HigherIsfRangeWeightDownTT" -> 5.068
        "HigherIsfRangeWeightUpTT" -> 5.070
        "PeakInsulinTimeDownTT" -> 5.074
        "PeakInsulinTimeUpTT" -> 5.076
        "AutoIsfMaxLowDownTT" -> 5.080
        "AutoIsfMaxLowUpTT" -> 5.082
        "AutoIsfMaxNormalDownTT" -> 5.086
        "AutoIsfMaxNormalUpTT" -> 5.088
        "TodOffset0002DownTT" -> 5.092
        "TodOffset0002UpTT" -> 5.094
        "TodOffset0204DownTT" -> 5.098
        "TodOffset0204UpTT" -> 5.100
        "TodOffset0406DownTT" -> 5.104
        "TodOffset0406UpTT" -> 5.106
        "TodOffset0609DownTT" -> 5.110
        "TodOffset0609UpTT" -> 5.112
        "TodOffset0912DownTT" -> 5.116
        "TodOffset0912UpTT" -> 5.118
        "TodOffset1218DownTT" -> 5.122
        "TodOffset1218UpTT" -> 5.124
        "TodOffset1822DownTT" -> 5.128
        "TodOffset1822UpTT" -> 5.130
        "TodOffset2200DownTT" -> 5.134
        "TodOffset2200UpTT" -> 5.136
        "Graph2ToggleTT" -> 5.138
        "CloudLogsUploadTT" -> 5.140
        "Graph5ToggleTT" -> 5.142
        "MjStateNoMjTT" -> 5.144
        "MjStateMj3TT" -> 5.146
        "ProfileStandardTT" -> 5.148
        "ProfileLowTT" -> 5.150
        "LibreUkf1ToggleTT" -> 5.152
        // 5.154 (LibreUkf2ToggleTT) freed up 2026-08-15 -- UKFset2 toggling moved to list2's
        // "Graph: UKF2" entry (GraphToggleEntry.syncedLiveKey in OverviewFragment.kt), which wrote
        // FslUseUkfLibreSpecialSmoothing directly rather than via a TT code. That syncedLiveKey write
        // was itself removed 2026-08-16 (UKF3426 branch, see OverviewFragment.kt's own updated doc
        // comment) -- FslUseUkfLibreSpecialSmoothing currently has no toggle path anywhere in this file
        // or OverviewFragment.kt; 5.154 remains free if a plain toggle needs restoring.
        "SensorAgeCodeToggleTT" -> 5.156
        "AnyDeskRestartActionTT" -> 5.178
        "MjKotlinButtonsToggleTT" -> 5.164
        "SteroidKotlinButtonToggleTT" -> 5.166
        else -> null
    }

    private fun readyToRun(key: String, minIntervalMinutes: Int): Boolean =
        directTtCodeForRunKey(key)?.let { expected ->
            pendingDirectTtCode?.let { kotlin.math.abs(it - expected) <= 0.0000001 }
        } == true ||
            (lastRunTimestamps[key] ?: 0L) <= dateUtil.now() - T.mins(minIntervalMinutes.toLong()).msecs()

    private fun markRun(key: String) {
        if (directTtCodeMatchActive) {
            pendingDirectTtCode = null
            directTtCodeMatchActive = false
            rxBus.send(EventRefreshOverview("AutoISF direct settings control", true))
            return
        }
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

        // True while an OvernightDuraRescue temporary Standard-profile switch is still within its 60-min
        // window. OffHighProf/MJrecentCurrProfAcce/NightAcce's switch-to-Low actions check this and yield
        // rather than fight the rescue back down to Low mid-window. Computed once per cycle here since all
        // four sites need the same read.
        val rescueActive = preferences.get(LongKey.ApsAutoIsfOvernightRescueUntil) > dateUtil.now()

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
        // SMB-block window for the (separate, unrelated to BolusWizard's own split-bolus feature)
        // 50%-profile delayed-bolus mechanism. BolusWizard's own carb-split/protein/fat scheduling never
        // blocks SMBs (removed entirely — see BolusWizard.kt's scheduleReducedPartsSplitBolus), so there
        // is no longer a second window to combine here.
        val delayedBolusBlockUntil = preferences.get(LongKey.DelayedBolusBlockSmbUntil)
        val microBolusAllowed = if (delayedBolusBlockUntil > dateUtil.now()) {
            inputConstraints.copyReasons(ConstraintObject(false, aapsLogger).also { it.set(false, "Delayed bolus active — SMBs blocked until ${dateUtil.timeString(delayedBolusBlockUntil)}", this) })
            false
        } else {
            constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).also { inputConstraints.copyReasons(it) }.value()
        }

        aapsLogger.debug(LTag.APS, "invoke found step counts 5m:$recentSteps5Minutes, 10m:$recentSteps10Minutes, 15m:$recentSteps15Minutes, 30m:$recentSteps30Minutes, 60m:$recentSteps60Minutes")
        consoleError.clear()
        consoleLog.clear()
        val calendar = Calendar.getInstance()
        val hour = max(1, calendar.get(Calendar.HOUR_OF_DAY))
        val activityRatio = activityMonitor(isTempTarget, glucoseStatus.glucose, targetBg, hour, glucoseStatus.shortAvgDelta)
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
        var liveHp2: Double? = null
        if (autoIsfMode) {
            val graphActivity = 100 * iobCobCalculator.calculateFromTreatmentsAndTemps(dateUtil.now(), profile).activity
            liveHp2 = hypoPrediction2Mmol(
                glucoseStatus.glucose,
                glucoseStatus.shortAvgDelta,
                glucoseStatus.longAvgDelta,
                iobData.iob,
                mealData.mealCOB,
                bgAcce
            )
            variableSensitivity = autoISF(profile, graphActivity, iobData.activity * 100, iobData.iob, liveHp2)
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
            profile_percentage = profile_percentage,
            hypo_prediction_2 = liveHp2
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
            autoIsfValues.longAvgDelta = it.longAvgDelta
            autoIsfValues.bgAcceleration = it.bgAcceleration
        }
        val flatBGsDetected = bgQualityCheck.state == BgQualityCheck.State.FLAT
        val smbRatio = determine_varSMBratio(glucoseStatus.glucose.toInt(), target_bg, loopWantedSmb)

        // The real-pump phone owns the storage check. It publishes a plain Note so Nightscout can
        // relay the warning to the Virtual Pump phone without putting anything on the pump graph.
        // The Virtual Pump does not inspect its own storage here; it reacts once to each relayed Note
        // and adds the graph announcement locally. The real-phone latch re-arms only above 10.5 GiB.
        if (activePlugin.activePump !is VirtualPump) {
            val bytesPerGiB = 1024.0 * 1024.0 * 1024.0
            val freeBytes = context.filesDir.usableSpace
            val freeGiB = freeBytes / bytesPerGiB
            val notified = preferences.get(BooleanKey.ApsAutoIsfLowStorageNotified)
            when {
                freeBytes > 0L && freeGiB < 10.0 && !notified -> {
                    val note = String.format(Locale.US, "StLow %.1fGB free", freeGiB)
                    uiInteraction.addNotification(id = 9012, text = note, level = Notification.URGENT)
                    sendSms(note)
                    addCarePortalNote(note)
                    preferences.put(BooleanKey.ApsAutoIsfLowStorageNotified, true)
                }

                freeGiB >= 10.5 && notified ->
                    preferences.put(BooleanKey.ApsAutoIsfLowStorageNotified, false)
            }
        } else {
            val lastHandled = preferences.get(LongKey.ApsAutoIsfLowStorageNsNoteHandledAt)
            val searchFrom = if (lastHandled > 0L) lastHandled + 1L else dateUtil.now() - T.days(7).msecs()
            val receivedNote = persistenceLayer.getTherapyEventDataFromTime(searchFrom, TE.Type.NOTE, false)
                .filter {
                    val note = it.note.orEmpty()
                    it.isValid && it.timestamp > lastHandled && (note.startsWith("StLow ") || note.startsWith("StorageLow "))
                }
                .maxByOrNull { it.timestamp }
            receivedNote?.let {
                val note = it.note ?: return@let
                uiInteraction.addNotification(id = 9012, text = note, level = Notification.URGENT)
                sendSms(note)
                addGraphAnnouncement(note)
                preferences.put(LongKey.ApsAutoIsfLowStorageNsNoteHandledAt, it.timestamp)
            }
        }

        // Reverse-direction remote command: the sending device writes the exact Note "ADesk" to its NS;
        // this device's secondary-NS worker records that server revision, and this sends one
        // implicit broadcast per revision. Tasker's Intent Received profile owns the existing AnyDesk
        // restart task. Do not target Tasker's package: its dynamically registered receiver requires
        // an implicit broadcast. This path also runs on Virtual Pump for testing.
        run {
            val commandRevision = preferences.get(LongKey.ApsAutoIsfAnyDeskSecondaryCommandAt)
            val handledRevision = preferences.get(LongKey.ApsAutoIsfAnyDeskTaskerHandledAt)
            if (commandRevision > handledRevision) {
                context.sendBroadcast(
                    Intent("app.aaps.action.RESTART_ANYDESK")
                        .putExtra("source", "AAPS secondary NS Note")
                        .putExtra("command", "ADesk")
                        .putExtra("revision", commandRevision)
                )
                preferences.put(LongKey.ApsAutoIsfAnyDeskTaskerHandledAt, commandRevision)
                aapsLogger.info(LTag.APS, "ADesk secondary-NS command sent to Tasker")
                // Ack note back to Client: written to THIS device's own primary NS, which Client reads as
                // ITS secondary NS -- the reverse direction of the "ADesk" note above. Distinct text
                // ("ADeskAck" vs "ADesk") so Client's own secondary-NS acceptance logic
                // (LoadSecondaryBolusCarbsWorker.kt) can tell its own sent note apart from this
                // confirmation, and so the two show as visually distinguishable Notes on Client.
                addCarePortalNote("ADeskAck")
            }
        }

        if (preferences.get(BooleanKey.ApsAutoIsfCustomAutomationsEnabled)) {

        // Code port of the "Test" automation (MJ=MJ4). Self-guarding: state change prevents re-fire.
        // 5-min floor throttle (see readyToRun() usage note near its declaration).
        if (readyToRun("MJ4", 5) && checkAutomationState("MJ", "MJ4")) {
            addCarePortalNote("A1")
            setAutomationState("MJ", "NOMJremains")
            markRun("MJ4")
        }

        // Code port of the "Test2" automation (MJ=MJ5): also switches to Current ProfileReal for 30 min.
        if (readyToRun("MJ5", 5) && checkAutomationState("MJ", "MJ5")) {
            addCarePortalNote("A1")
            switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfStandardProfileName), 30)
            setAutomationState("MJ", "NOMJremains")
            markRun("MJ5")
        }

        // Code port of the "Test3" automation (MJ=MJ6): validates the state-check -> notification ->
        // state-set mechanics, including the graph-announcement fix (addGraphAnnouncement). Original
        // screenshot's "Min interval (min): 1" is below the 5-min floor now applied across all ported
        // automations that lack their own interval, so this uses 5 rather than 1.
        // Self-guarding: the state-set action moves MJ away from MJ6, so it fires once per cycle
        // that reaches MJ6.
        if (readyToRun("Test3", 5) && checkAutomationState("MJ", "MJ6")) {
            uiInteraction.addNotification(id = 9002, text = "_____Test3", level = Notification.URGENT)
            addGraphAnnouncement("_____Test3")
            // Test3 has no general-broadcast SMS of its own — this call exists purely to test the
            // targeted-numbers mechanism (SmsTest3Numbers) in isolation.
            sendSmsToNumbers("Test3", StringKey.SmsTest3Numbers)
            setAutomationState("MJ", "NOMJremains")
            markRun("Test3")
        }

        // --- MJ2 old: advances MJ state from "MJ active" → MJ2 at 02:10–03:10 AM ---
        if (readyToRun("MJ2old", 5) && checkAutomationState("MJ", "MJ active") && isTimeBetween(2, 10, 3, 10)) {
            sendSms("MJ2")
            setAutomationState("MJ", "MJ2")
            addCarePortalNote("MJ2")
            //setAutomationState("MJstate", "MJon")
            markRun("MJ2old")
        }

        // --- MJ3 old: advances MJ state from MJ2 → MJ3 at 01:05–02:05 AM ---
        if (readyToRun("MJ3old", 5) && checkAutomationState("MJ", "MJ2") && isTimeBetween(1, 5, 2, 5)) {
            sendSms("MJ3")
            setAutomationState("MJ", "MJ3")
            addCarePortalNote("MJ3")
            //setAutomationState("MJstate", "MJon")
            markRun("MJ3old")
        }

        // --- MJoff old: exits MJ cycle when MJ3 active ---
        // Block 1: 12:00–21:04 with BGL >= 10.5 mmol. Block 2: midnight 00:00–01:00.
        if (readyToRun("MJoff", 5) && checkAutomationState("MJ", "MJ3")) {
            val g = glucoseStatus.glucose
            val mjB1 = isTimeBetween(12, 0, 21, 4) && g >= 189.2   // 10.5 mmol
            val mjB2 = isTimeBetween(0, 0, 1, 0)
            val mjBlock = when { mjB1 -> "1"; mjB2 -> "2"; else -> null }
            if (mjBlock != null) {
                sendSms("MJoff [b$mjBlock]: g=${String.format("%.1f", g / 18.016)}")
                setAutomationState("MJ", "NOMJremains")
                addCarePortalNote("MJoff-$mjBlock")
                //setAutomationState("MJstate", "MJoff")
                markRun("MJoff")
            }
        }

        // --- prepare Set50%: replaces "prepare Set50%0.07 50%" automation ---
        // Precondition guard: profile_percentage == 100. Once the 50% profile switch fires,
        // profile_percentage becomes 50 on the next loop cycle and the block stops running.
        // All 4 blocks also check Profile pct = 100 in the original; the outer guard handles that.
        // 5-min floor throttle added on top of the precondition (see readyToRun() usage note).
        if (profile_percentage == 100 && readyToRun("PrepareSet50", 5)) {
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

            // Block 3 — fallback: a clear fall below 5.0 (delta ≤ -0.10, was -0.05,
            // widened to open a deadband against the TToff off3 plateau reversal)
            val p50b3 = g < 90.1 /* 5.0 */ && d <= -1.80 /* -0.10 */

            // Block 4 — pre-sleep: falling into sleep window at higher glucose
            val p50b4 = g <= 126.1 /* 7.0 */ && isTimeBetween(21, 0, 0, 0) &&
                sd <= -1.80 /* -0.10 */ && d <= -3.60 /* -0.20 */

            val p50block = when { p50b1->"1"; p50b2->"2"; p50b3->"3"; p50b4->"4"; else->null }
            if (p50block != null) {
                setBgAccelIsfWeight(0.07)
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 50)
                setSmbDeliveryRatio(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline))   // restore delivery baseline: hypo protection must not
                                            // keep BolusGiven's strengthened SMB delivery
                preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal))   // restore ppWeight baseline
                startProfilePercentFor(50, 360, preferences.get(StringKey.ApsAutoIsfLowProfileName))   // force onto the MJ/night profile, then hold 50% for 360 min as usual
                setAutomationState("LowBG", "50recent")
                sendSms("prepare Set50% [b$p50block]: g=${String.format("%.1f", g / 18.016)} d=${String.format("%.2f", d / 18.016)}")
                addCarePortalNote("Set50-$p50block")
                aapsLogger.debug(LTag.APS, "prepare50 block $p50block: g=${String.format("%.1f", g / 18.016)}mmol d=${String.format("%.2f", d / 18.016)} iob=${String.format("%.2f", iob)} cob=${cob.toInt()} steps30=$recentSteps30Minutes")
                markRun("PrepareSet50")
            }
        }

        // --- OldSensorAdj: Libre special settings for an aging sensor (12-15 days) — swings high/low
        // too easily and isn't compensated by the usual slope/offset calibration. Mirrored at the other
        // end of the sensor's life too (0-3 days): a brand-new sensor has its own settling/compression-
        // artifact inaccuracy, easing back toward normal as it moves away from insertion, same as it
        // eases away from the 12-15 day window as it ages — day 0 (<1 day, the WHOLE first day, no
        // separate <6h sub-tier) mirrors the MOST extreme 14-15 day tier (0.65/1.6), day 1 mirrors 13-14
        // (0.68/1.5), day 2 mirrors 12-13 (0.70/1.45). Tiered FslCalSlope/FslCalOffset override applies
        // by sensor age (no MJ/hypo-state or cannula-age gating).
        // The configured LibreSlope_orig/LibreOffset_orig pair is the explicit baseline. Once outside
        // the 0-3/12-15 day tiers, the live slope/offset are restored to that baseline even if the
        // oldSensorActive latch was lost during restart/import. No readyToRun throttle — deliberately
        // checked every cycle like DelOff, so day
        // transitions revert promptly; both branches are idempotent so re-checking is harmless.
        run {
            val sensorAgeCodeEnabled = preferences.get(BooleanKey.ApsAutoIsfSensorAgeCodeEnabled)
            val oldSensorActive = preferences.get(BooleanKey.ApsAutoIsfOldSensorAdjActive)
            if (!sensorAgeCodeEnabled) {
                // Restore the explicit Libre baseline even if the active latch was lost on restart.
                val baselineSlope = preferences.get(DoubleKey.ApsAutoIsfLibreSlopeOrig)
                val baselineOffset = preferences.get(DoubleKey.ApsAutoIsfLibreOffsetOrig)
                if (oldSensorActive ||
                    !fuzzyEquals(preferences.get(DoubleKey.FslCalSlope), baselineSlope) ||
                    !fuzzyEquals(preferences.get(DoubleKey.FslCalOffset), baselineOffset)
                ) {
                    preferences.put(DoubleKey.FslCalSlope, baselineSlope)
                    preferences.put(DoubleKey.FslCalOffset, baselineOffset)
                    preferences.put(BooleanKey.ApsAutoIsfOldSensorAdjActive, false)
                    addCarePortalNote("SensorAgeCodeOff")
                }
                return@run
            }

            // Track the last time raw Libre BGL was seen over 12.0mmol -- updated every cycle it's
            // observed (not a "since it started" latch like OldPodHighSinceTs above; just "when did this
            // last happen"). Gates OldSensorAdj below: the tier compensation is calibrated against
            // genuine high-BGL Libre-vs-reference divergence, so if that hasn't actually been seen
            // recently, the compensation is blocked and slope/offset revert to baseline instead.
            val rawGForOver12 = rawGlucoseMgdl()
            if (rawGForOver12 != null && rawGForOver12 > 216.2 /* 12.0 mmol raw */) {
                preferences.put(LongKey.ApsAutoIsfLibreOver12Ts, dateUtil.now())
            }
            val recentHighBglSeen = recentLibreOver12(24)

            // Missing sensor age is unknown, never day 0. A null age produces no tier below (other than
            // the NewDay2-by-pod-age fallback just below); if an override was active, the normal
            // restore branch runs rather than keeping/creating Day1.
            val sensorAgeDays = hoursSinceLastSensorChange()?.div(24.0)
            val oldSensorEnabled = preferences.get(BooleanKey.ApsAutoIsfOldSensorAdjEnabled)
            // Tier slopes/offsets are derived from the user's own base Libre slope/offset
            // (ApsAutoIsfLibreSlopeOrig currently 0.72, ApsAutoIsfLibreOffsetOrig currently 1.4) rather
            // than independent hardcoded literals: slope base-0.02/-0.04/-0.07 and offset base+0.05/
            // +0.10/+0.15 for the mild/medium/extreme tiers — so retuning the base shifts all tiers
            // together.
            val libreSlopeOrig = preferences.get(DoubleKey.ApsAutoIsfLibreSlopeOrig)
            val libreOffsetOrig = preferences.get(DoubleKey.ApsAutoIsfLibreOffsetOrig)
            // NewDay2 also fires on pod age alone (cannula age >60h, same threshold OldPod below uses
            // for "pod is old"), OR'd with the normal 1-2 day sensor-age window — same entry criteria as
            // every other tier here (oldSensorEnabled + recentHighBglSeen, checked below) otherwise. A
            // fresh sensor on an aging pod reads the same as an aging sensor for this purpose. Sensor-age
            // wins where it applies (NewDay1/NewDay3/the 11-15 day tiers are untouched by pod age); this
            // OR only widens the NewDay2 window, it never narrows the sensor-age-only tiers.
            val cannulaHoursForTier = hoursSinceLastCannulaChange()
            val newDay2ByPodAge = cannulaHoursForTier != null && cannulaHoursForTier > 60.0
            val oldSensorTier = when {
                sensorAgeDays != null && sensorAgeDays < 1.0                           -> Triple("NewDay1", libreSlopeOrig - 0.07, libreOffsetOrig + 0.15)
                (sensorAgeDays != null && sensorAgeDays >= 1.0 && sensorAgeDays < 2.0)
                    || newDay2ByPodAge                                                 -> Triple("NewDay2", libreSlopeOrig - 0.04, libreOffsetOrig + 0.10)
                sensorAgeDays != null && sensorAgeDays >= 2.0 && sensorAgeDays < 3.0    -> Triple("NewDay3", libreSlopeOrig - 0.02, libreOffsetOrig + 0.05)
                // Start calendar day 12 after 11 full days; use the same mild adjustment as day 13.
                sensorAgeDays != null && sensorAgeDays >= 11.0 && sensorAgeDays < 13.0  -> Triple("1", libreSlopeOrig - 0.02, libreOffsetOrig + 0.05)
                sensorAgeDays != null && sensorAgeDays >= 13.0 && sensorAgeDays < 14.0  -> Triple("2", libreSlopeOrig - 0.04, libreOffsetOrig + 0.10)
                sensorAgeDays != null && sensorAgeDays >= 14.0 && sensorAgeDays < 15.0  -> Triple("3", libreSlopeOrig - 0.07, libreOffsetOrig + 0.15)
                else -> null
            }
            val oldSensorActiveNow = preferences.get(BooleanKey.ApsAutoIsfOldSensorAdjActive)
            // Sensor-age calibration is otherwise independent of cannula/pod age (see NewDay2's pod-age
            // OR above for the one exception). It still requires recentHighBglSeen (see above); cannula
            // protections remain in their own pod automations.
            if (oldSensorEnabled && oldSensorTier != null && recentHighBglSeen) {
                if (!oldSensorActiveNow) {
                    preferences.put(DoubleKey.ApsAutoIsfFslCalSlopeNormal, preferences.get(DoubleKey.FslCalSlope))
                    preferences.put(DoubleKey.ApsAutoIsfFslCalOffsetNormal, preferences.get(DoubleKey.FslCalOffset))
                    preferences.put(BooleanKey.ApsAutoIsfOldSensorAdjActive, true)
                }
                val (tierLabel, slope, offset) = oldSensorTier
                if (!fuzzyEquals(preferences.get(DoubleKey.FslCalSlope), slope) || !fuzzyEquals(preferences.get(DoubleKey.FslCalOffset), offset)) {
                    preferences.put(DoubleKey.FslCalSlope, slope)
                    preferences.put(DoubleKey.FslCalOffset, offset)
                    addCarePortalNote("OldSensor$tierLabel")
                }
            } else {
                // At 3 full days and outside the old-sensor tiers, always restore the configured
                // Libre baseline. The old latch alone is insufficient because it can be lost while
                // the live day-3 value (for example 0.70) remains behind.
                val baselineMismatch =
                    !fuzzyEquals(preferences.get(DoubleKey.FslCalSlope), libreSlopeOrig) ||
                        !fuzzyEquals(preferences.get(DoubleKey.FslCalOffset), libreOffsetOrig)
                if (baselineMismatch) {
                    preferences.put(DoubleKey.FslCalSlope, libreSlopeOrig)
                    preferences.put(DoubleKey.FslCalOffset, libreOffsetOrig)
                }
                preferences.put(BooleanKey.ApsAutoIsfOldSensorAdjActive, false)
                if (oldSensorActiveNow || baselineMismatch) addCarePortalNote("OldSensorOff")
            }
        }

        // --- OldPod: notify (graph announcement + SMS) once a pod is over 60h old AND BGL has been
        // continuously over threshold for 2h+ — a sustained-high stretch this far into a pod's life
        // reads as the pod itself failing, not a dosing problem, so this only ever notifies, never
        // touches dosing. "High" is rBGL (raw Libre) > 11.0 mmol OR the AAPS-filtered BGL > 10.0 mmol —
        // an OR so either signal alone can start/hold the sustained-high episode; missing raw data just
        // falls back to the AAPS BGL leg rather than blocking the check. Because "high" is an OR, the
        // episode only RESETS once BOTH legs are simultaneously back at/below their own threshold
        // (De Morgan's law: !(A || B) == !A && !B) — one leg dropping alone isn't enough to clear it.
        // Uses addGraphAnnouncement()
        // (TE.asAnnouncement), not addCarePortalNote() — that's the same "notifications" mechanism used
        // elsewhere (settings-export alerts etc.), which renders on the MAIN graph rather than graph2
        // (where plain TE.Type.NOTE notes went). Fires once per pod (ApsAutoIsfOldPodNotified latch),
        // re-arming only once cannula age drops back under 60h (i.e. a new pod was actually inserted).
        // No readyToRun throttle — checked every cycle like DelOff/OldSensorAdj above, so the "since"
        // timestamp and the latch both react/reset promptly.
        run {
            val cannulaH = hoursSinceLastCannulaChange() ?: 0.0
            val podOld = cannulaH > 60.0
            val g = glucoseStatus.glucose
            val rawG = rawGlucoseMgdl()
            val highNow = (rawG != null && rawG > 198.2 /* 11.0 mmol raw */) || g > 180.2 /* 10.0 mmol */
            var highSinceTs = preferences.get(LongKey.ApsAutoIsfOldPodHighSinceTs)
            if (highNow) {
                if (highSinceTs == 0L) {
                    highSinceTs = dateUtil.now()
                    preferences.put(LongKey.ApsAutoIsfOldPodHighSinceTs, highSinceTs)
                }
            } else if (highSinceTs != 0L) {
                highSinceTs = 0L
                preferences.put(LongKey.ApsAutoIsfOldPodHighSinceTs, 0L)
            }
            val highSustained = highSinceTs != 0L && (dateUtil.now() - highSinceTs) >= T.hours(2).msecs()
            val notified = preferences.get(BooleanKey.ApsAutoIsfOldPodNotified)
            if (podOld && highSustained && !notified) {
                addGraphAnnouncement("______deadpod2hrs?")
                val deadPodSmsText = "OldPod: pod >60h, BGL >10.0 for 2h+ - change pod"
                sendSms(deadPodSmsText)
                sendSmsToNumbers(deadPodSmsText, StringKey.SmsDeadPodNumbers)
                preferences.put(BooleanKey.ApsAutoIsfOldPodNotified, true)
            } else if (!podOld && notified) {
                preferences.put(BooleanKey.ApsAutoIsfOldPodNotified, false)
            }
        }

        // --- SensorAgeToggleTT: manually setting a TT of 5.06 mmol is used as a remote toggle for the
        // OldSensorAdj enable switch (ApsAutoIsfOldSensorAdjEnabled) — not a real target. Flips the
        // switch, then immediately cancels the TT so it never actually affects dosing. activeTtNear()
        // (not a hand-rolled mg/dL literal) is deliberate here — see its own doc comment on why the
        // exact mmol->mg/dL conversion constant matters for matching a manually-set TT. Tight 0.001mmol
        // tolerance (not the old 0.02) — this now sits in a cluster with SmbDeliveryDownTT (5.02),
        // SmbDeliveryUpTT (5.04), and BoostToggleTT (5.08), all only 0.02mmol apart from their
        // neighbors, so a loose tolerance would make adjacent windows overlap. Small throttle purely as
        // a defense against double-toggling if the cancel hasn't fully propagated by the next cycle —
        // the cancel itself is what actually prevents re-firing in normal operation.
        if (readyToRun("SensorAgeToggleTT", 2) && activeTtNear(5.006, 0.0001)) {
            val newState = !preferences.get(BooleanKey.ApsAutoIsfOldSensorAdjEnabled)
            preferences.put(BooleanKey.ApsAutoIsfOldSensorAdjEnabled, newState)
            cancelCurrentTempTarget()
            sendSms("SensorAgeToggle: ${if (newState) "ON" else "OFF"}")
            addCarePortalNote("STg${if (newState) "On" else "Off"}")
            markRun("SensorAgeToggleTT")
        }

        // --- BoostToggleTT: manually setting a TT of 5.08 mmol is used as a remote toggle for
        // ApsAutoIsfBoostAutomationsEnabled (the combined master switch for BolusGiven bg1/2/3 and
        // BolusGivenMild) — not a real target. Same pattern and same tight 0.001mmol tolerance as
        // SensorAgeToggleTT above (see its comment for why).
        if (readyToRun("BoostToggleTT", 2) && activeTtNear(5.008, 0.0001)) {
            val newState = !preferences.get(BooleanKey.ApsAutoIsfBoostAutomationsEnabled)
            preferences.put(BooleanKey.ApsAutoIsfBoostAutomationsEnabled, newState)
            cancelCurrentTempTarget()
            sendSms("BoostToggle: ${if (newState) "ON" else "OFF"}")
            addCarePortalNote("BTg${if (newState) "On" else "Off"}")
            markRun("BoostToggleTT")
        }

        // --- SmbDeliveryDownTT: manually setting a TT of 5.02 mmol is used as a remote -0.01 nudge on
        // both SMB delivery settings (ApsAutoIsfSmbDeliveryBaseline, ApsAutoIsfMildBoostRatio) — not a
        // real target. Clamped to each key's own min (0.1 for both). Same activeTtNear()/cancel/notify
        // pattern as SensorAgeToggleTT/BoostToggleTT above. Tight 0.001mmol tolerance (not the usual
        // 0.02) — 5.02 and 5.04 (SmbDeliveryUpTT) are only 0.02mmol apart, so 0.02 tolerance would make
        // their windows overlap each other (and creep into 5.0's own "own boost TT" territory).
        if (readyToRun("SmbDeliveryDownTT", 2) && activeTtNear(5.002, 0.0001)) {
            val newBaseline = (preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline) - 0.01).coerceAtLeast(0.1)
            val newMildBoost = (preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio) - 0.01).coerceAtLeast(0.1)
            preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryBaseline, newBaseline)
            preferences.put(DoubleKey.ApsAutoIsfMildBoostRatio, newMildBoost)
            cancelCurrentTempTarget()
            sendSms("SmbDeliveryDown: baseline=${round(newBaseline, 2)} mildBoost=${round(newMildBoost, 2)}")
            addCarePortalNote(compactSettingNote("SB", newBaseline, 2, omitLeadingZero = true))
            addCarePortalNote(compactSettingNote("SM", newMildBoost, 2, omitLeadingZero = true))
            markRun("SmbDeliveryDownTT")
        }

        // --- SmbDeliveryUpTT: manually setting a TT of 5.04 mmol is used as a remote +0.01 nudge on
        // both SMB delivery settings, clamped to each key's own max (0.5 for both). Same pattern and
        // same tight 0.001mmol tolerance as SmbDeliveryDownTT above.
        if (readyToRun("SmbDeliveryUpTT", 2) && activeTtNear(5.004, 0.0001)) {
            val newBaseline = (preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline) + 0.01).coerceAtMost(0.5)
            val newMildBoost = (preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio) + 0.01).coerceAtMost(0.5)
            preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryBaseline, newBaseline)
            preferences.put(DoubleKey.ApsAutoIsfMildBoostRatio, newMildBoost)
            cancelCurrentTempTarget()
            sendSms("SmbDeliveryUp: baseline=${round(newBaseline, 2)} mildBoost=${round(newMildBoost, 2)}")
            addCarePortalNote(compactSettingNote("SB", newBaseline, 2, omitLeadingZero = true))
            addCarePortalNote(compactSettingNote("SM", newMildBoost, 2, omitLeadingZero = true))
            markRun("SmbDeliveryUpTT")
        }

        // --- PpWeightDownTT: manually setting a TT of 5.12 mmol is used as a remote -0.01 nudge on
        // ApsAutoIsfPpWeightNormal (ppISFwt_orig), clamped to its own min (0.0) — not a real target.
        // Same pattern/tight 0.001mmol tolerance as the SmbDelivery*/SensorAge/Boost toggle TTs above.
        if (readyToRun("PpWeightDownTT", 2) && activeTtNear(5.012, 0.0001)) {
            val currentPp = preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal)
            val currentLive = preferences.get(DoubleKey.ApsAutoIsfPpWeight)
            val newPp = (currentPp - 0.01).coerceAtLeast(0.0)
            preferences.put(DoubleKey.ApsAutoIsfPpWeightNormal, newPp)
            // If the live weight currently equals its own baseline (nothing has it diverged away right
            // now), nudge both together so the change is felt immediately — otherwise only the baseline
            // moves, leaving whatever's currently overriding it undisturbed until it next restores.
            if (fuzzyEquals(currentLive, currentPp)) preferences.put(DoubleKey.ApsAutoIsfPpWeight, (currentLive - 0.01).coerceAtLeast(0.0))
            cancelCurrentTempTarget()
            sendSms("PpWeightDown: ppISFwt_orig=${round(newPp, 2)}")
            addCarePortalNote(compactSettingNote("PP", newPp, 2, omitLeadingZero = true))
            markRun("PpWeightDownTT")
        }

        // --- PpWeightUpTT: manually setting a TT of 5.14 mmol is used as a remote +0.01 nudge on
        // ApsAutoIsfPpWeightNormal, clamped to its own max (0.15). Same pattern as PpWeightDownTT above.
        if (readyToRun("PpWeightUpTT", 2) && activeTtNear(5.014, 0.0001)) {
            val currentPp = preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal)
            val currentLive = preferences.get(DoubleKey.ApsAutoIsfPpWeight)
            val newPp = (currentPp + 0.01).coerceAtMost(0.15)
            preferences.put(DoubleKey.ApsAutoIsfPpWeightNormal, newPp)
            if (fuzzyEquals(currentLive, currentPp)) preferences.put(DoubleKey.ApsAutoIsfPpWeight, (currentLive + 0.01).coerceAtMost(0.15))
            cancelCurrentTempTarget()
            sendSms("PpWeightUp: ppISFwt_orig=${round(newPp, 2)}")
            addCarePortalNote(compactSettingNote("PP", newPp, 2, omitLeadingZero = true))
            markRun("PpWeightUpTT")
        }

        // --- PpWeightHighDownTT: manually setting a TT of 5.056 mmol is used as a remote -0.01 nudge on
        // ApsAutoIsfPpWeightHigh (ppISFwt_high — the boosted value the 7 fast-rise automations set,
        // previously a hardcoded 0.15 literal in each), clamped to its own min (0.0) — not a real target.
        // Same pattern/tight 0.0001mmol tolerance as the other settings-nudge TTs above. No dual-update:
        // unlike PpWeightNormal/PpWeight, this only ever gets written into the live weight transiently
        // when a boost automation actually fires, not continuously.
        if (readyToRun("PpWeightHighDownTT", 2) && activeTtNear(5.056, 0.0001)) {
            val currentPpHigh = preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh)
            val newPpHigh = (currentPpHigh - 0.01).coerceAtLeast(0.0)
            preferences.put(DoubleKey.ApsAutoIsfPpWeightHigh, newPpHigh)
            cancelCurrentTempTarget()
            sendSms("PpWeightHighDown: ppISFwt_high=${round(newPpHigh, 2)}")
            addCarePortalNote(compactSettingNote("PH", newPpHigh, 2, omitLeadingZero = true))
            markRun("PpWeightHighDownTT")
        }

        // --- PpWeightHighUpTT: manually setting a TT of 5.058 mmol is used as a remote +0.01 nudge on
        // ApsAutoIsfPpWeightHigh, clamped to its own max (0.15). Same pattern as PpWeightHighDownTT above.
        if (readyToRun("PpWeightHighUpTT", 2) && activeTtNear(5.058, 0.0001)) {
            val currentPpHigh = preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh)
            val newPpHigh = (currentPpHigh + 0.01).coerceAtMost(0.15)
            preferences.put(DoubleKey.ApsAutoIsfPpWeightHigh, newPpHigh)
            cancelCurrentTempTarget()
            sendSms("PpWeightHighUp: ppISFwt_high=${round(newPpHigh, 2)}")
            addCarePortalNote(compactSettingNote("PH", newPpHigh, 2, omitLeadingZero = true))
            markRun("PpWeightHighUpTT")
        }

        // --- PpWeightRevertUnder8_5: genuine automatic (not TT-triggered) counterpart to the boost
        // automations that set ApsAutoIsfPpWeight to 0.15 (BolusGiven, BolusGivenMild, High6PP,
        // HighOldPod, PodChangeHighPP130, OldPod2, RecentPod) — always-on, fires whenever the live PP
        // weight currently differs from its own baseline (ApsAutoIsfPpWeightNormal) AND either BG is
        // back under 8.5mmol OR recent activity is high enough — same recentSteps5Minutes/
        // recentSteps30Minutes thresholds (100/200) already used as the movement-guard BLOCKER in
        // BolusGiven/BolusGivenMild/bg3's own fire conditions, plus bg3's own extra recentSteps60Minutes
        // (300) gate, all reused here as POSITIVE triggers instead: active movement is independent
        // evidence the elevated PP weight is no longer needed. No profile%/TT preconditions: unlike the
        // 6 existing restore automations (Usual2forTH/CarbsTHoff/etc.), this is a direct rule — it can
        // revert PP even while a boost's own profile% duration is still running. 5-min throttle is a
        // defensive backstop only; the fuzzyEquals check already makes this a no-op once reverted.
        // Nested acce check: under the same trigger (BG<8.5mmol or high activity), also revert
        // ApsAutoIsfBgAccelWeight to its own baseline (ApsAutoIsfBgAccelWeightNormal) if it's currently
        // ABOVE baseline — deliberately one-directional (only reverts an elevated/boosted acce, never
        // touches a legitimately-reduced protective acce like NightAcce's 0.35 or GentleHypoRisk's
        // 0.07/0.02, which sit below baseline and are unrelated to this rule).
        if (readyToRun("PpWeightRevertUnder8_5", 5)) {
            val currentPp = preferences.get(DoubleKey.ApsAutoIsfPpWeight)
            val baselinePp = preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal)
            val currentAcce = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
            val baselineAcce = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal)
            val lowBg = glucoseStatus.glucose < 153.1 /* 8.5 mmol */
            val activeMovement = recentSteps5Minutes > 100 || recentSteps30Minutes > 200 || recentSteps60Minutes > 300
            // No genuine recent high (raw Libre >12.0mmol within 48h) is also grounds to revert -- the
            // elevated PP/acce weights are calibrated against a genuinely-occurring high, not a stale one.
            val noRecentHigh = !recentLibreOver12(48)
            val trigger = lowBg || activeMovement || noRecentHigh
            val ppNeedsRevert = !fuzzyEquals(currentPp, baselinePp) && trigger
            val acceNeedsRevert = currentAcce > baselineAcce && trigger
            if (ppNeedsRevert || acceNeedsRevert) {
                if (ppNeedsRevert) preferences.put(DoubleKey.ApsAutoIsfPpWeight, baselinePp)
                if (acceNeedsRevert) preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, baselineAcce)
                val reason = if (lowBg) "BG<8.5mmol" else if (activeMovement) "activity" else "noRecentHigh48h"
                val what = when {
                    ppNeedsRevert && acceNeedsRevert -> "ppISFwt=${round(baselinePp, 2)} acceISFwt=${round(baselineAcce, 2)}"
                    ppNeedsRevert                    -> "ppISFwt=${round(baselinePp, 2)}"
                    else                              -> "acceISFwt=${round(baselineAcce, 2)}"
                }
                sendSms("PpWeightRevert: $what ($reason)")
                val note = if (ppNeedsRevert && acceNeedsRevert) "PArv" else if (ppNeedsRevert) "PPrv" else "ACrv"
                addCarePortalNote(note)
                markRun("PpWeightRevertUnder8_5")
            }
        }

        // --- AcceWeightDownTT: manually setting a TT of 5.16 mmol is used as a remote -0.05 nudge on
        // ApsAutoIsfBgAccelWeightNormal (acceISFwt_orig), clamped to a min of 0.55 — not a real target.
        // Same pattern/tight 0.001mmol tolerance as the other settings-nudge TTs above.
        if (readyToRun("AcceWeightDownTT", 2) && activeTtNear(5.016, 0.0001)) {
            val currentAcce = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal)
            val currentLive = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
            val newAcce = (currentAcce - 0.05).coerceAtLeast(0.55)
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeightNormal, newAcce)
            if (fuzzyEquals(currentLive, currentAcce)) preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, (currentLive - 0.05).coerceAtLeast(0.55))
            cancelCurrentTempTarget()
            sendSms("AcceWeightDown: acceISFwt_orig=${round(newAcce, 2)}")
            addCarePortalNote(compactSettingNote("AC", newAcce, 2, omitLeadingZero = true))
            markRun("AcceWeightDownTT")
        }

        // --- AcceWeightUpTT: manually setting a TT of 5.18 mmol is used as a remote +0.05 nudge on
        // ApsAutoIsfBgAccelWeightNormal, clamped to a max of 1.00. Same pattern as AcceWeightDownTT above.
        if (readyToRun("AcceWeightUpTT", 2) && activeTtNear(5.018, 0.0001)) {
            val currentAcce = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal)
            val currentLive = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
            val newAcce = (currentAcce + 0.05).coerceAtMost(1.00)
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeightNormal, newAcce)
            if (fuzzyEquals(currentLive, currentAcce)) preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, (currentLive + 0.05).coerceAtMost(1.00))
            cancelCurrentTempTarget()
            sendSms("AcceWeightUp: acceISFwt_orig=${round(newAcce, 2)}")
            addCarePortalNote(compactSettingNote("AC", newAcce, 2, omitLeadingZero = true))
            markRun("AcceWeightUpTT")
        }

        // --- AcceWeightHighDownTT: manually setting a TT of 5.062 mmol is used as a remote -0.01 nudge
        // on ApsAutoIsfBgAccelWeightHigh (acceISFwt_high — the boosted value OldPod2/RecentPod/AcceUp0.5
        // set, previously a hardcoded 0.95 literal in each), clamped to its own min (0.0) — not a real
        // target. Same pattern/tight 0.0001mmol tolerance as the other settings-nudge TTs above. No
        // dual-update: unlike AcceWeightNormal/AcceWeight, this only ever gets written into the live
        // weight transiently when a boost automation actually fires, not continuously.
        if (readyToRun("AcceWeightHighDownTT", 2) && activeTtNear(5.062, 0.0001)) {
            val currentAcceHigh = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightHigh)
            val newAcceHigh = (currentAcceHigh - 0.01).coerceAtLeast(0.0)
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeightHigh, newAcceHigh)
            cancelCurrentTempTarget()
            sendSms("AcceWeightHighDown: acceISFwt_high=${round(newAcceHigh, 2)}")
            addCarePortalNote(compactSettingNote("AH", newAcceHigh, 2, omitLeadingZero = true))
            markRun("AcceWeightHighDownTT")
        }

        // --- AcceWeightHighUpTT: manually setting a TT of 5.064 mmol is used as a remote +0.01 nudge on
        // ApsAutoIsfBgAccelWeightHigh, clamped to its own max (1.00). Same pattern as
        // AcceWeightHighDownTT above.
        if (readyToRun("AcceWeightHighUpTT", 2) && activeTtNear(5.064, 0.0001)) {
            val currentAcceHigh = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightHigh)
            val newAcceHigh = (currentAcceHigh + 0.01).coerceAtMost(1.00)
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeightHigh, newAcceHigh)
            cancelCurrentTempTarget()
            sendSms("AcceWeightHighUp: acceISFwt_high=${round(newAcceHigh, 2)}")
            addCarePortalNote(compactSettingNote("AH", newAcceHigh, 2, omitLeadingZero = true))
            markRun("AcceWeightHighUpTT")
        }

        // --- HigherIsfRangeWeightDownTT: manually setting a TT of 5.068 mmol is used as a remote -0.1 nudge on
        // ApsAutoIsfHighBgWeight (higher_ISFrange_weight), clamped to a min of 0.0 — not a real target. Same
        // pattern/tight 0.0001mmol tolerance as the other settings-nudge TTs above.
        if (readyToRun("HigherIsfRangeWeightDownTT", 2) && activeTtNear(5.068, 0.0001)) {
            val currentHigh = preferences.get(DoubleKey.ApsAutoIsfHighBgWeight)
            val newHigh = (currentHigh - 0.1).coerceAtLeast(0.0)
            preferences.put(DoubleKey.ApsAutoIsfHighBgWeight, newHigh)
            cancelCurrentTempTarget()
            sendSms("HigherIsfRangeWeightDown: higher_ISFrange_weight=${round(newHigh, 2)}")
            addCarePortalNote(compactSettingNote("HI", newHigh, 1))
            markRun("HigherIsfRangeWeightDownTT")
        }

        // --- HigherIsfRangeWeightUpTT: manually setting a TT of 5.070 mmol is used as a remote +0.1 nudge on
        // ApsAutoIsfHighBgWeight, clamped to a max of 2.0. Same pattern as HigherIsfRangeWeightDownTT above.
        if (readyToRun("HigherIsfRangeWeightUpTT", 2) && activeTtNear(5.070, 0.0001)) {
            val currentHigh = preferences.get(DoubleKey.ApsAutoIsfHighBgWeight)
            val newHigh = (currentHigh + 0.1).coerceAtMost(2.0)
            preferences.put(DoubleKey.ApsAutoIsfHighBgWeight, newHigh)
            cancelCurrentTempTarget()
            sendSms("HigherIsfRangeWeightUp: higher_ISFrange_weight=${round(newHigh, 2)}")
            addCarePortalNote(compactSettingNote("HI", newHigh, 1))
            markRun("HigherIsfRangeWeightUpTT")
        }

        // --- PeakInsulinTimeDownTT: manually setting a TT of 5.074 mmol is used as a remote -5 nudge on
        // InsulinOrefPeak (peak insulin activity time, minutes), clamped to its own min (35) — not a real
        // target. Same pattern/tight 0.0001mmol tolerance as the other settings-nudge TTs above.
        if (readyToRun("PeakInsulinTimeDownTT", 2) && activeTtNear(5.074, 0.0001)) {
            val newPeak = (preferences.get(IntKey.InsulinOrefPeak) - 5).coerceAtLeast(35)
            preferences.put(IntKey.InsulinOrefPeak, newPeak)
            cancelCurrentTempTarget()
            sendSms("PeakInsulinTimeDown: insulinPeak=$newPeak")
            addCarePortalNote(String.format(Locale.US, "IP%03d", newPeak))
            markRun("PeakInsulinTimeDownTT")
        }

        // --- PeakInsulinTimeUpTT: manually setting a TT of 5.076 mmol is used as a remote +5 nudge on
        // InsulinOrefPeak, clamped to its own max (120). Same pattern as PeakInsulinTimeDownTT above.
        if (readyToRun("PeakInsulinTimeUpTT", 2) && activeTtNear(5.076, 0.0001)) {
            val newPeak = (preferences.get(IntKey.InsulinOrefPeak) + 5).coerceAtMost(120)
            preferences.put(IntKey.InsulinOrefPeak, newPeak)
            cancelCurrentTempTarget()
            sendSms("PeakInsulinTimeUp: insulinPeak=$newPeak")
            addCarePortalNote(String.format(Locale.US, "IP%03d", newPeak))
            markRun("PeakInsulinTimeUpTT")
        }

        // --- AutoIsfMaxLowDownTT: manually setting a TT of 5.080 mmol is used as a remote -0.1 nudge on
        // ApsAutoIsfMaxLow (autoISF_max_low — not yet wired into any calculation, see DoubleKey.kt),
        // clamped to a min of 1.0 — not a real target. Same pattern/tight 0.0001mmol tolerance as the
        // other settings-nudge TTs above.
        if (readyToRun("AutoIsfMaxLowDownTT", 2) && activeTtNear(5.080, 0.0001)) {
            val newMaxLow = (preferences.get(DoubleKey.ApsAutoIsfMaxLow) - 0.1).coerceAtLeast(1.0)
            preferences.put(DoubleKey.ApsAutoIsfMaxLow, newMaxLow)
            cancelCurrentTempTarget()
            sendSms("AutoIsfMaxLowDown: autoISF_max_low=${round(newMaxLow, 2)}")
            addCarePortalNote(compactSettingNote("ML", newMaxLow, 1))
            markRun("AutoIsfMaxLowDownTT")
        }

        // --- AutoIsfMaxLowUpTT: manually setting a TT of 5.082 mmol is used as a remote +0.1 nudge on
        // ApsAutoIsfMaxLow, clamped to a max of 3.0. Same pattern as AutoIsfMaxLowDownTT above.
        if (readyToRun("AutoIsfMaxLowUpTT", 2) && activeTtNear(5.082, 0.0001)) {
            val newMaxLow = (preferences.get(DoubleKey.ApsAutoIsfMaxLow) + 0.1).coerceAtMost(3.0)
            preferences.put(DoubleKey.ApsAutoIsfMaxLow, newMaxLow)
            cancelCurrentTempTarget()
            sendSms("AutoIsfMaxLowUp: autoISF_max_low=${round(newMaxLow, 2)}")
            addCarePortalNote(compactSettingNote("ML", newMaxLow, 1))
            markRun("AutoIsfMaxLowUpTT")
        }

        // --- AutoIsfMaxNormalDownTT: manually setting a TT of 5.086 mmol is used as a remote -0.1 nudge
        // on ApsAutoIsfMax (autoISF_max — the existing, already-live setting; "Normal" here just
        // distinguishes it from AutoIsfMaxLow above), clamped to a min of 1.0 — not a real target. Same
        // pattern/tight 0.0001mmol tolerance as the other settings-nudge TTs above.
        if (readyToRun("AutoIsfMaxNormalDownTT", 2) && activeTtNear(5.086, 0.0001)) {
            val newMax = (preferences.get(DoubleKey.ApsAutoIsfMax) - 0.1).coerceAtLeast(1.0)
            preferences.put(DoubleKey.ApsAutoIsfMax, newMax)
            cancelCurrentTempTarget()
            sendSms("AutoIsfMaxNormalDown: autoISF_max=${round(newMax, 2)}")
            addCarePortalNote(compactSettingNote("MN", newMax, 1))
            markRun("AutoIsfMaxNormalDownTT")
        }

        // --- AutoIsfMaxNormalUpTT: manually setting a TT of 5.088 mmol is used as a remote +0.1 nudge on
        // ApsAutoIsfMax, clamped to a max of 3.0. Same pattern as AutoIsfMaxNormalDownTT above.
        if (readyToRun("AutoIsfMaxNormalUpTT", 2) && activeTtNear(5.088, 0.0001)) {
            val newMax = (preferences.get(DoubleKey.ApsAutoIsfMax) + 0.1).coerceAtMost(3.0)
            preferences.put(DoubleKey.ApsAutoIsfMax, newMax)
            cancelCurrentTempTarget()
            sendSms("AutoIsfMaxNormalUp: autoISF_max=${round(newMax, 2)}")
            addCarePortalNote(compactSettingNote("MN", newMax, 1))
            markRun("AutoIsfMaxNormalUpTT")
        }

        // --- TodOffset*TT (8 pairs, T1-T8): manually setting a TT of 5.092-5.136 mmol is used as a
        // remote +/-0.1 nudge on one of the 8 fixed time-of-day varOffset windows (ApsAutoIsfTodOffset*,
        // see DetermineBasalAutoISF.kt), clamped to +/-2.0 — not a real target. T1=00-02h, T2=02-04h,
        // T3=04-06h, T4=06-09h, T5=09-12h, T6=12-18h, T7=18-22h, T8=22-00h. Same pattern/tight 0.0001mmol
        // tolerance as the other settings-nudge TTs above.
        if (readyToRun("TodOffset0002DownTT", 2) && activeTtNear(5.092, 0.0001)) {
            val newVal = (preferences.get(DoubleKey.ApsAutoIsfTodOffset0002) - 0.1).coerceAtLeast(-2.0)
            preferences.put(DoubleKey.ApsAutoIsfTodOffset0002, newVal)
            cancelCurrentTempTarget()
            sendSms("TodOffset0002Down: tod_offset_0002=${round(newVal, 2)}")
            addCarePortalNote(todOffsetCarePortalNote(newVal))
            markRun("TodOffset0002DownTT")
        }
        if (readyToRun("TodOffset0002UpTT", 2) && activeTtNear(5.094, 0.0001)) {
            val newVal = (preferences.get(DoubleKey.ApsAutoIsfTodOffset0002) + 0.1).coerceAtMost(2.0)
            preferences.put(DoubleKey.ApsAutoIsfTodOffset0002, newVal)
            cancelCurrentTempTarget()
            sendSms("TodOffset0002Up: tod_offset_0002=${round(newVal, 2)}")
            addCarePortalNote(todOffsetCarePortalNote(newVal))
            markRun("TodOffset0002UpTT")
        }
        if (readyToRun("TodOffset0204DownTT", 2) && activeTtNear(5.098, 0.0001)) {
            val newVal = (preferences.get(DoubleKey.ApsAutoIsfTodOffset0204) - 0.1).coerceAtLeast(-2.0)
            preferences.put(DoubleKey.ApsAutoIsfTodOffset0204, newVal)
            cancelCurrentTempTarget()
            sendSms("TodOffset0204Down: tod_offset_0204=${round(newVal, 2)}")
            addCarePortalNote(todOffsetCarePortalNote(newVal))
            markRun("TodOffset0204DownTT")
        }
        if (readyToRun("TodOffset0204UpTT", 2) && activeTtNear(5.100, 0.0001)) {
            val newVal = (preferences.get(DoubleKey.ApsAutoIsfTodOffset0204) + 0.1).coerceAtMost(2.0)
            preferences.put(DoubleKey.ApsAutoIsfTodOffset0204, newVal)
            cancelCurrentTempTarget()
            sendSms("TodOffset0204Up: tod_offset_0204=${round(newVal, 2)}")
            addCarePortalNote(todOffsetCarePortalNote(newVal))
            markRun("TodOffset0204UpTT")
        }
        if (readyToRun("TodOffset0406DownTT", 2) && activeTtNear(5.104, 0.0001)) {
            val newVal = (preferences.get(DoubleKey.ApsAutoIsfTodOffset0406) - 0.1).coerceAtLeast(-2.0)
            preferences.put(DoubleKey.ApsAutoIsfTodOffset0406, newVal)
            cancelCurrentTempTarget()
            sendSms("TodOffset0406Down: tod_offset_0406=${round(newVal, 2)}")
            addCarePortalNote(todOffsetCarePortalNote(newVal))
            markRun("TodOffset0406DownTT")
        }
        if (readyToRun("TodOffset0406UpTT", 2) && activeTtNear(5.106, 0.0001)) {
            val newVal = (preferences.get(DoubleKey.ApsAutoIsfTodOffset0406) + 0.1).coerceAtMost(2.0)
            preferences.put(DoubleKey.ApsAutoIsfTodOffset0406, newVal)
            cancelCurrentTempTarget()
            sendSms("TodOffset0406Up: tod_offset_0406=${round(newVal, 2)}")
            addCarePortalNote(todOffsetCarePortalNote(newVal))
            markRun("TodOffset0406UpTT")
        }
        if (readyToRun("TodOffset0609DownTT", 2) && activeTtNear(5.110, 0.0001)) {
            val newVal = (preferences.get(DoubleKey.ApsAutoIsfTodOffset0609) - 0.1).coerceAtLeast(-2.0)
            preferences.put(DoubleKey.ApsAutoIsfTodOffset0609, newVal)
            cancelCurrentTempTarget()
            sendSms("TodOffset0609Down: tod_offset_0609=${round(newVal, 2)}")
            addCarePortalNote(todOffsetCarePortalNote(newVal))
            markRun("TodOffset0609DownTT")
        }
        if (readyToRun("TodOffset0609UpTT", 2) && activeTtNear(5.112, 0.0001)) {
            val newVal = (preferences.get(DoubleKey.ApsAutoIsfTodOffset0609) + 0.1).coerceAtMost(2.0)
            preferences.put(DoubleKey.ApsAutoIsfTodOffset0609, newVal)
            cancelCurrentTempTarget()
            sendSms("TodOffset0609Up: tod_offset_0609=${round(newVal, 2)}")
            addCarePortalNote(todOffsetCarePortalNote(newVal))
            markRun("TodOffset0609UpTT")
        }
        if (readyToRun("TodOffset0912DownTT", 2) && activeTtNear(5.116, 0.0001)) {
            val newVal = (preferences.get(DoubleKey.ApsAutoIsfTodOffset0912) - 0.1).coerceAtLeast(-2.0)
            preferences.put(DoubleKey.ApsAutoIsfTodOffset0912, newVal)
            cancelCurrentTempTarget()
            sendSms("TodOffset0912Down: tod_offset_0912=${round(newVal, 2)}")
            addCarePortalNote(todOffsetCarePortalNote(newVal))
            markRun("TodOffset0912DownTT")
        }
        if (readyToRun("TodOffset0912UpTT", 2) && activeTtNear(5.118, 0.0001)) {
            val newVal = (preferences.get(DoubleKey.ApsAutoIsfTodOffset0912) + 0.1).coerceAtMost(2.0)
            preferences.put(DoubleKey.ApsAutoIsfTodOffset0912, newVal)
            cancelCurrentTempTarget()
            sendSms("TodOffset0912Up: tod_offset_0912=${round(newVal, 2)}")
            addCarePortalNote(todOffsetCarePortalNote(newVal))
            markRun("TodOffset0912UpTT")
        }
        if (readyToRun("TodOffset1218DownTT", 2) && activeTtNear(5.122, 0.0001)) {
            val newVal = (preferences.get(DoubleKey.ApsAutoIsfTodOffset1218) - 0.1).coerceAtLeast(-2.0)
            preferences.put(DoubleKey.ApsAutoIsfTodOffset1218, newVal)
            cancelCurrentTempTarget()
            sendSms("TodOffset1218Down: tod_offset_1218=${round(newVal, 2)}")
            addCarePortalNote(todOffsetCarePortalNote(newVal))
            markRun("TodOffset1218DownTT")
        }
        if (readyToRun("TodOffset1218UpTT", 2) && activeTtNear(5.124, 0.0001)) {
            val newVal = (preferences.get(DoubleKey.ApsAutoIsfTodOffset1218) + 0.1).coerceAtMost(2.0)
            preferences.put(DoubleKey.ApsAutoIsfTodOffset1218, newVal)
            cancelCurrentTempTarget()
            sendSms("TodOffset1218Up: tod_offset_1218=${round(newVal, 2)}")
            addCarePortalNote(todOffsetCarePortalNote(newVal))
            markRun("TodOffset1218UpTT")
        }
        if (readyToRun("TodOffset1822DownTT", 2) && activeTtNear(5.128, 0.0001)) {
            val newVal = (preferences.get(DoubleKey.ApsAutoIsfTodOffset1822) - 0.1).coerceAtLeast(-2.0)
            preferences.put(DoubleKey.ApsAutoIsfTodOffset1822, newVal)
            cancelCurrentTempTarget()
            sendSms("TodOffset1822Down: tod_offset_1822=${round(newVal, 2)}")
            addCarePortalNote(todOffsetCarePortalNote(newVal))
            markRun("TodOffset1822DownTT")
        }
        if (readyToRun("TodOffset1822UpTT", 2) && activeTtNear(5.130, 0.0001)) {
            val newVal = (preferences.get(DoubleKey.ApsAutoIsfTodOffset1822) + 0.1).coerceAtMost(2.0)
            preferences.put(DoubleKey.ApsAutoIsfTodOffset1822, newVal)
            cancelCurrentTempTarget()
            sendSms("TodOffset1822Up: tod_offset_1822=${round(newVal, 2)}")
            addCarePortalNote(todOffsetCarePortalNote(newVal))
            markRun("TodOffset1822UpTT")
        }
        if (readyToRun("TodOffset2200DownTT", 2) && activeTtNear(5.134, 0.0001)) {
            val newVal = (preferences.get(DoubleKey.ApsAutoIsfTodOffset2200) - 0.1).coerceAtLeast(-2.0)
            preferences.put(DoubleKey.ApsAutoIsfTodOffset2200, newVal)
            cancelCurrentTempTarget()
            sendSms("TodOffset2200Down: tod_offset_2200=${round(newVal, 2)}")
            addCarePortalNote(todOffsetCarePortalNote(newVal))
            markRun("TodOffset2200DownTT")
        }
        if (readyToRun("TodOffset2200UpTT", 2) && activeTtNear(5.136, 0.0001)) {
            val newVal = (preferences.get(DoubleKey.ApsAutoIsfTodOffset2200) + 0.1).coerceAtMost(2.0)
            preferences.put(DoubleKey.ApsAutoIsfTodOffset2200, newVal)
            cancelCurrentTempTarget()
            sendSms("TodOffset2200Up: tod_offset_2200=${round(newVal, 2)}")
            addCarePortalNote(todOffsetCarePortalNote(newVal))
            markRun("TodOffset2200UpTT")
        }

        // Automatically clear TOD offsets when their sign no longer suits the current MJ/HP2 state.
        // The NOMJ condition is the exact automation state MJ=NOMJremains. A missing HP2 does not
        // satisfy HP2 < 4.0; the MJ-state part of either rule can still clear the applicable offsets.
        //
        // Sustained-duration requirement (todOffsetNegClearSince/PosClearSince, >=10 consecutive
        // minutes) and a cooldown (readyToRun, 30min) added after 2026-08-13/14 real Client data showed
        // HP2 crosses 5.0 in ~20% of ordinary cycles, and this block previously had neither -- a single
        // momentary HP2 dip was enough to instantly wipe every hand-tuned TOD offset (confirmed via a
        // real logged "TOD0 HP2=4.98<5.0" event, no repeat evidence of the other two branches firing).
        // Also lowered the HP2 threshold 5.0 -> 4.0 as a smaller ask on its own -- both changes are
        // deliberately independent fixes (duration/cooldown protects against all three OR'd triggers;
        // the threshold change only narrows the HP2 one), neither is a substitute for the other. Both
        // thresholds (10min sustain, 30min cooldown) are a first cut, not yet validated against a real
        // case that actually needed a genuine reset -- watch for whether a real hypo-relevant reset gets
        // meaningfully delayed by the 10min wait before trusting these numbers.
        //
        // Both HP2 threshold checks (not the NOMJ/StepsHigh/StepsLow branches) also now require
        // todResetSdeltaMmol < 0.1 -- an HP2 crossing while BG is still actively rising is a self-
        // correcting blip, not a genuine sustained low/high; requiring delta to have flattened first
        // keeps the sustained-duration timer from starting on those blips.
        val nomjRemains = checkAutomationState("MJ", "NOMJremains")
        val todResetHp = hypoPrediction2Mmol(
            glucoseStatus.glucose,
            glucoseStatus.shortAvgDelta,
            glucoseStatus.longAvgDelta,
            iobData.iob,
            mealData.mealCOB,
            bgAcce
        )
        // Same mmol conversion hypoPrediction2Mmol() uses internally for its own sdeltaMmol -- required
        // alongside both HP2 threshold checks below so a momentary HP2 crossing while BG is still
        // actively rising doesn't clear offsets on its own; BG needs to actually be flat/falling
        // (negative branch) or have stopped rising (positive branch) too.
        val todResetSdeltaMmol = glucoseStatus.shortAvgDelta / 18.0182
        val todOffsetKeys = listOf(
            DoubleKey.ApsAutoIsfTodOffset0002,
            DoubleKey.ApsAutoIsfTodOffset0204,
            DoubleKey.ApsAutoIsfTodOffset0406,
            DoubleKey.ApsAutoIsfTodOffset0609,
            DoubleKey.ApsAutoIsfTodOffset0912,
            DoubleKey.ApsAutoIsfTodOffset1218,
            DoubleKey.ApsAutoIsfTodOffset1822,
            DoubleKey.ApsAutoIsfTodOffset2200
        )
        val stepsLow = checkAutomationState("Steps", "StepsLow")
        val stepsHigh = checkAutomationState("Steps", "StepsHigh")
        val clearNegativeTodOffsetsNow = !nomjRemains || (todResetHp != null && todResetHp < 4.0 && todResetSdeltaMmol < 0.1) || stepsHigh
        // A positive TOD offset delays/limits SMB, so remove that protection only when every
        // permissive signal agrees. Missing HP2 deliberately fails closed and preserves the offset.
        val clearPositiveTodOffsetsNow = nomjRemains && stepsLow && todResetHp != null && todResetHp > 6.0 && todResetSdeltaMmol < 0.1

        if (clearNegativeTodOffsetsNow) {
            if (todOffsetNegClearSince == 0L) todOffsetNegClearSince = dateUtil.now()
        } else {
            todOffsetNegClearSince = 0L
        }
        if (clearPositiveTodOffsetsNow) {
            if (todOffsetPosClearSince == 0L) todOffsetPosClearSince = dateUtil.now()
        } else {
            todOffsetPosClearSince = 0L
        }
        val todOffsetSustainMs = T.mins(10).msecs()
        val clearNegativeTodOffsets = todOffsetNegClearSince != 0L && dateUtil.now() - todOffsetNegClearSince >= todOffsetSustainMs
        val clearPositiveTodOffsets = todOffsetPosClearSince != 0L && dateUtil.now() - todOffsetPosClearSince >= todOffsetSustainMs

        val resetTodOffsets = todOffsetKeys.filter { key ->
            val value = preferences.get(key)
            (value < 0.0 && clearNegativeTodOffsets) || (value > 0.0 && clearPositiveTodOffsets)
        }
        if (resetTodOffsets.isNotEmpty() && readyToRun("TodOffsetsZero", 30)) {
            val resettingPositive = resetTodOffsets.any { preferences.get(it) > 0.0 }
            val resettingNegative = resetTodOffsets.any { preferences.get(it) < 0.0 }
            val resetValues = resetTodOffsets.joinToString(",") { key ->
                "${key.name}=${round(preferences.get(key), 2)}"
            }
            resetTodOffsets.forEach { key -> preferences.put(key, 0.0) }
            val reason = when {
                clearPositiveTodOffsets && resettingPositive ->
                    "NOMJremains+StepsLow+HP2=${round(todResetHp ?: 0.0, 2)}>6.0+SD=${round(todResetSdeltaMmol, 2)}<0.1"
                stepsHigh && resettingNegative -> "StepsHigh"
                !nomjRemains -> "NOT_NOMJremains"
                else -> "HP2=${round(todResetHp ?: 0.0, 2)}<4.0+SD=${round(todResetSdeltaMmol, 2)}<0.1"
            }
            sendSms("TodOffsetsZero: $reason; $resetValues")
            addCarePortalNote("TOD0 $reason")
            markRun("TodOffsetsZero")
        }

        // --- Graph2ToggleTT: manually setting a TT of 5.138 mmol is used as a remote toggle for
        // ApsAutoIsfShowCarbModelCurve (the "graph2" carb model curve display) — not a real target.
        // Next value after TodOffset2200UpTT's 5.136 in the settings-nudge TT cluster. Same
        // activeTtNear()/cancel/notify/toggle pattern as SensorAgeToggleTT/BoostToggleTT above.
        if (readyToRun("Graph2ToggleTT", 2) && activeTtNear(5.138, 0.0001)) {
            val newState = !preferences.get(BooleanKey.ApsAutoIsfShowCarbModelCurve)
            preferences.put(BooleanKey.ApsAutoIsfShowCarbModelCurve, newState)
            cancelCurrentTempTarget()
            sendSms("Graph2Toggle: ${if (newState) "ON" else "OFF"}")
            addCarePortalNote("G2${if (newState) "On" else "Off"}")
            markRun("Graph2ToggleTT")
        }

        // --- Graph5ToggleTT: manually setting a TT of 5.142 mmol is used as a remote toggle for
        // ApsAutoIsfShowGraph5 (the 5th graph — a fixed clone of the main graph, minus basal) — not a
        // real target. Next value after Graph2ToggleTT's 5.138 / CloudLogsUploadTT's 5.140. Same
        // activeTtNear()/cancel/notify/toggle pattern as Graph2ToggleTT above. Independent switch —
        // deliberately does not touch graphs 2/3/4's own settings.
        if (readyToRun("Graph5ToggleTT", 2) && activeTtNear(5.142, 0.0001)) {
            val newState = !preferences.get(BooleanKey.ApsAutoIsfShowGraph5)
            preferences.put(BooleanKey.ApsAutoIsfShowGraph5, newState)
            cancelCurrentTempTarget()
            sendSms("Graph5Toggle: ${if (newState) "ON" else "OFF"}")
            addCarePortalNote("G5${if (newState) "On" else "Off"}")
            markRun("Graph5ToggleTT")
        }

        // --- MjStateNoMjTT / MjStateMj3TT: manually setting a TT of 5.144 / 5.146 mmol sets the MJ
        // automation state directly to NOMJremains / MJ3 — not real targets. Next values after
        // Graph5ToggleTT's 5.142. Paired in ttCodesList() as a single TtCode.Stepped row ("MJ state"),
        // so the two land as the pair of mutually-exclusive checkboxes in that row's confirm dialog
        // rather than as two separate scannable entries.
        //
        // A manual override, deliberately unconditional: every other writer of this state is gated on
        // time and/or BGL (MJ2old 02:10-03:10, MJ3old 01:05-02:05, MoreMJ 06:00-23:59 plus its own
        // condition tree), which is exactly why a stuck state can't always be corrected from within the
        // automations. Same activeTtNear()/cancel/notify pattern as the other remote-signal TTs above;
        // no toggle state, each fires once per TT-set. Kept as two one-way setters rather than one
        // toggling TT so the resulting state is always known from which code was sent, never dependent
        // on what the state happened to be beforehand.
        if (readyToRun("MjStateNoMjTT", 2) && activeTtNear(5.144, 0.0001)) {
            setAutomationState("MJ", "NOMJremains")
            cancelCurrentTempTarget()
            sendSms("MJstate: NOMJremains")
            addCarePortalNote("MJsNO")
            markRun("MjStateNoMjTT")
        }

        if (readyToRun("MjStateMj3TT", 2) && activeTtNear(5.146, 0.0001)) {
            setAutomationState("MJ", "MJ3")
            cancelCurrentTempTarget()
            sendSms("MJstate: MJ3")
            addCarePortalNote("MJs3")
            markRun("MjStateMj3TT")
        }

        // --- ProfileStandardTT / ProfileLowTT: manually setting a TT of 5.148 / 5.150 mmol switches the
        // profile straight to Standard / Low — not real targets. Next values after MjStateMj3TT's 5.146.
        // Paired in ttCodesList() as one TtCode.Stepped row ("Profile"), same as the MJ pair above.
        //
        // Permanent switches (switchProfileIfNeeded's default duration 0), matching what the automations
        // themselves use, so a manual override isn't silently undone by a timer. It CAN still be undone
        // by another automation though: BasalUp switches back to Standard on a rise (no longer time-gated),
        // and TwilightTH15Acce (06:00-08:00) plus ExportSettingsPodActivation (any time, on pod change)
        // switch to Low without an HP2 gate. So this is a "set it now" control, not a lock — if a switch
        // gets reverted within minutes, one of those three is the thing to look at, not this block.
        // Two one-way setters rather than one toggling TT, same reasoning as the MJ pair: the resulting
        // profile is always known from which code was sent, never dependent on what it was beforehand
        // (which matters precisely because the automations above can change it underneath you).
        if (readyToRun("ProfileStandardTT", 2) && activeTtNear(5.148, 0.0001)) {
            switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfStandardProfileName))
            cancelCurrentTempTarget()
            sendSms("Profile: Standard")
            addCarePortalNote("PrfS")
            markRun("ProfileStandardTT")
        }

        if (readyToRun("ProfileLowTT", 2) && activeTtNear(5.150, 0.0001)) {
            switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfLowProfileName))
            cancelCurrentTempTarget()
            sendSms("Profile: Low")
            addCarePortalNote("PrfL")
            markRun("ProfileLowTT")
        }

        // --- LibreUkf1ToggleTT: 5.152 is the "Tog UKFset1" row in the IOB double-tap popup. Its old
        // sibling LibreUkf2ToggleTT (5.154) is removed -- UKFset2 toggling moved to list2's "Graph:
        // UKF2" entry (see GraphToggleEntry.syncedLiveKey in OverviewFragment.kt). The mutual-exclusion
        // write here (turning UKFset1 on used to also force FslUseUkfLibreSpecialSmoothing off) was
        // removed 2026-08-15 for the same reason it was dropped from list2's popup: that flag no longer
        // has any dosing effect (XdripSourcePlugin.kt/NsIncomingDataProcessor.kt call
        // smoothLibreSpecialRealtime() only for its graph-history side effect and discard the return
        // value) -- it now only gates whether UKF2's graph history accumulates. Silently clearing it here
        // every time UKFset1 got toggled on was the actual cause of UKF2's graph never accumulating
        // points: the two settings are independent now, so toggling one must not touch the other.
        if (readyToRun("LibreUkf1ToggleTT", 2) && activeTtNear(5.152, 0.0001)) {
            val newState = !preferences.get(BooleanKey.FslUseUkfSmoothing)
            preferences.put(BooleanKey.FslUseUkfSmoothing, newState)
            cancelCurrentTempTarget()
            sendSms("LibreUKFset1: ${if (newState) "ON" else "OFF"}")
            addCarePortalNote("UKFset1${if (newState) "On" else "Off"}")
            markRun("LibreUkf1ToggleTT")
        }

        // --- SensorAgeCodeToggleTT: master execution toggle for the complete SensorAge calibration
        // routine, separate from the existing 5.006 aging-sensor adjustment preference toggle.
        if (readyToRun("SensorAgeCodeToggleTT", 2) && activeTtNear(5.156, 0.0001)) {
            val newState = !preferences.get(BooleanKey.ApsAutoIsfSensorAgeCodeEnabled)
            preferences.put(BooleanKey.ApsAutoIsfSensorAgeCodeEnabled, newState)
            cancelCurrentTempTarget()
            sendSms("SensorAgeCode: ${if (newState) "ON" else "OFF"}")
            addCarePortalNote("SAC${if (newState) "On" else "Off"}")
            markRun("SensorAgeCodeToggleTT")
        }

        // Client-only relay codes for the basal-icon direct action menu. On the pump phone these run
        // the full Kotlin actions; pump/Virtual selections do not create these TTs at all.
        if (readyToRun("MjStartActionTT", 2) && activeTtNear(5.158, 0.0001)) {
            cancelCurrentTempTarget()
            handleDirectMjUserAction(EventMjUserAction.Action.START, directMenu = true)
            markRun("MjStartActionTT")
        }

        if (readyToRun("MjRestoreActionTT", 2) && activeTtNear(5.160, 0.0001)) {
            cancelCurrentTempTarget()
            handleDirectMjUserAction(EventMjUserAction.Action.RESTORE, directMenu = true)
            markRun("MjRestoreActionTT")
        }

        if (readyToRun("SteroidStartActionTT", 2) && activeTtNear(5.162, 0.0001)) {
            cancelCurrentTempTarget()
            handleDirectSteroidUserAction(directMenu = true)
            markRun("SteroidStartActionTT")
        }

        if (readyToRun("SteroidIncrease130ActionTT", 2) && activeTtNear(5.168, 0.0001)) {
            cancelCurrentTempTarget()
            handleDirectSteroidUserAction(
                action = EventSteroidUserAction.Action.INCREASE_130,
                directMenu = true
            )
            markRun("SteroidIncrease130ActionTT")
        }

        if (readyToRun("SteroidIncrease150ActionTT", 2) && activeTtNear(5.170, 0.0001)) {
            cancelCurrentTempTarget()
            handleDirectSteroidUserAction(
                action = EventSteroidUserAction.Action.INCREASE_150,
                directMenu = true
            )
            markRun("SteroidIncrease150ActionTT")
        }

        if (readyToRun("SteroidIncrease190ActionTT", 2) && activeTtNear(5.172, 0.0001)) {
            cancelCurrentTempTarget()
            handleDirectSteroidUserAction(
                action = EventSteroidUserAction.Action.INCREASE_190,
                directMenu = true
            )
            markRun("SteroidIncrease190ActionTT")
        }

        if (readyToRun("SteroidIncrease250ActionTT", 2) && activeTtNear(5.174, 0.0001)) {
            cancelCurrentTempTarget()
            handleDirectSteroidUserAction(
                action = EventSteroidUserAction.Action.INCREASE_250,
                directMenu = true
            )
            markRun("SteroidIncrease250ActionTT")
        }

        if (readyToRun("SteroidTurnOffActionTT", 2) && activeTtNear(5.176, 0.0001)) {
            cancelCurrentTempTarget()
            handleDirectSteroidUserAction(
                action = EventSteroidUserAction.Action.TURN_OFF,
                directMenu = true
            )
            markRun("SteroidTurnOffActionTT")
        }

        // --- AnyDeskRestartActionTT: second, redundant channel for the "Send AnyDesk restart" list2
        // button (see runBasalDirectAction()'s ANYDESK_RESTART branch in OverviewFragment.kt), which
        // fires this TT alongside the existing "ADesk" secondary-NS Note above rather than instead of
        // it -- this one rides Client's primary NS sync so it can land before the secondary-NS
        // worker's next poll picks up the Note's revision. Sends its own independent Tasker broadcast
        // (not routed through ApsAutoIsfAnyDeskSecondaryCommandAt/...TaskerHandledAt -- that revision
        // pair belongs to the Note channel only) using a TT-local revision so the two channels can
        // never suppress each other; Tasker's restart task tolerates a double-trigger.
        // This path also runs on Virtual Pump for testing. The
        // "ADeskTTfired" note is local-only diagnostics on this device's own history -- deliberately
        // NOT "ADeskAck": that exact text is Client's secondary-NS worker's allowlisted match for the
        // Note channel's real ack, and this TT channel has no such round-trip to report.
        if (readyToRun("AnyDeskRestartActionTT", 2) && activeTtNear(5.178, 0.0001)) {
            cancelCurrentTempTarget()
            context.sendBroadcast(
                Intent("app.aaps.action.RESTART_ANYDESK")
                    .putExtra("source", "AAPS relay TT")
                    .putExtra("command", "AnyDeskRestartTT")
                    .putExtra("revision", dateUtil.now())
            )
            aapsLogger.info(LTag.APS, "ADesk relay-TT command sent to Tasker")
            addCarePortalNote("ADeskTTfired")
            markRun("AnyDeskRestartActionTT")
        }

        if (readyToRun("MjKotlinButtonsToggleTT", 2) && activeTtNear(5.164, 0.0001)) {
            val newState = !preferences.get(BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled)
            preferences.put(BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled, newState)
            cancelCurrentTempTarget()
            sendSms("MJ Kotlin buttons: ${if (newState) "ON" else "OFF"}")
            addCarePortalNote("MJB${if (newState) "On" else "Off"}")
            rxBus.send(EventRefreshOverview("MJ Kotlin buttons toggled", true))
            markRun("MjKotlinButtonsToggleTT")
        }

        if (readyToRun("SteroidKotlinButtonToggleTT", 2) && activeTtNear(5.166, 0.0001)) {
            val newState = !preferences.get(BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled)
            preferences.put(BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled, newState)
            cancelCurrentTempTarget()
            sendSms("Steroid Kotlin button: ${if (newState) "ON" else "OFF"}")
            addCarePortalNote("StB${if (newState) "On" else "Off"}")
            rxBus.send(EventRefreshOverview("Steroid Kotlin button toggled", true))
            markRun("SteroidKotlinButtonToggleTT")
        }

        // --- CloudLogsUploadTT: manually setting a TT of 5.140 mmol remotely triggers the same log
        // upload as the "Send logs" button on the Maintenance screen (zips logs, sends to cloud
        // storage if configured, else email) — for AAPSClient users who have no settings access to
        // trigger it directly. Next value after Graph2ToggleTT's 5.138. Same
        // activeTtNear()/cancel/notify pattern as the other remote-signal TTs above; no toggle state,
        // just fires once per TT-set.
        if (readyToRun("CloudLogsUploadTT", 2) && activeTtNear(5.140, 0.0001)) {
            importExportPrefs.sendLogs(trigger = "REMOTE_TT")
            cancelCurrentTempTarget()
            sendSms("CloudLogsUpload: triggered")
            addCarePortalNote("CLup")
            markRun("CloudLogsUploadTT")
        }

        // --- DuraWeightDownTT: manually setting a TT of 5.22 mmol is used as a remote -0.1 nudge on
        // ApsAutoIsfDuraWeightNormal (duraISFwt_orig), clamped to a min of 0.00 — not a real target.
        // Same pattern/tight 0.001mmol tolerance as the other settings-nudge TTs above.
        if (readyToRun("DuraWeightDownTT", 2) && activeTtNear(5.022, 0.0001)) {
            val currentDura = preferences.get(DoubleKey.ApsAutoIsfDuraWeightNormal)
            val currentLive = preferences.get(DoubleKey.ApsAutoIsfDuraWeight)
            val newDura = (currentDura - 0.1).coerceAtLeast(0.00)
            preferences.put(DoubleKey.ApsAutoIsfDuraWeightNormal, newDura)
            if (fuzzyEquals(currentLive, currentDura)) preferences.put(DoubleKey.ApsAutoIsfDuraWeight, (currentLive - 0.1).coerceAtLeast(0.00))
            cancelCurrentTempTarget()
            sendSms("DuraWeightDown: duraISFwt_orig=${round(newDura, 2)}")
            // 3 trailing chars (not 2) — Dura's 0-3.00 range needs the whole-number digit too, e.g. "Dd2.4"
            addCarePortalNote(compactSettingNote("D", newDura, 2))
            markRun("DuraWeightDownTT")
        }

        // --- DuraWeightUpTT: manually setting a TT of 5.24 mmol is used as a remote +0.1 nudge on
        // ApsAutoIsfDuraWeightNormal, clamped to a max of 3.00. Same pattern as DuraWeightDownTT above.
        if (readyToRun("DuraWeightUpTT", 2) && activeTtNear(5.024, 0.0001)) {
            val currentDura = preferences.get(DoubleKey.ApsAutoIsfDuraWeightNormal)
            val currentLive = preferences.get(DoubleKey.ApsAutoIsfDuraWeight)
            val newDura = (currentDura + 0.1).coerceAtMost(3.00)
            preferences.put(DoubleKey.ApsAutoIsfDuraWeightNormal, newDura)
            if (fuzzyEquals(currentLive, currentDura)) preferences.put(DoubleKey.ApsAutoIsfDuraWeight, (currentLive + 0.1).coerceAtMost(3.00))
            cancelCurrentTempTarget()
            sendSms("DuraWeightUp: duraISFwt_orig=${round(newDura, 2)}")
            addCarePortalNote(compactSettingNote("D", newDura, 2))
            markRun("DuraWeightUpTT")
        }

        // --- SmbOffsetDownTT: manually setting a TT of 5.36 mmol is used as a remote -0.1 nudge on
        // ApsAutoIsfSmbOffsetOverride (SMBoffset), clamped to a min of 0.50 — not a real target. Same
        // pattern/tight 0.001mmol tolerance as the other settings-nudge TTs above.
        if (readyToRun("SmbOffsetDownTT", 2) && activeTtNear(5.036, 0.0001)) {
            val newOffset = (preferences.get(DoubleKey.ApsAutoIsfSmbOffsetOverride) - 0.1).coerceAtLeast(0.50)
            preferences.put(DoubleKey.ApsAutoIsfSmbOffsetOverride, newOffset)
            cancelCurrentTempTarget()
            sendSms("SmbOffsetDown: SMBoffset=${round(newOffset, 2)}")
            addCarePortalNote(compactSettingNote("SO", newOffset, 1))
            markRun("SmbOffsetDownTT")
        }

        // --- SmbOffsetUpTT: manually setting a TT of 5.38 mmol is used as a remote +0.1 nudge on
        // ApsAutoIsfSmbOffsetOverride, clamped to a max of 1.50. Same pattern as SmbOffsetDownTT above.
        if (readyToRun("SmbOffsetUpTT", 2) && activeTtNear(5.038, 0.0001)) {
            val newOffset = (preferences.get(DoubleKey.ApsAutoIsfSmbOffsetOverride) + 0.1).coerceAtMost(1.50)
            preferences.put(DoubleKey.ApsAutoIsfSmbOffsetOverride, newOffset)
            cancelCurrentTempTarget()
            sendSms("SmbOffsetUp: SMBoffset=${round(newOffset, 2)}")
            addCarePortalNote(compactSettingNote("SO", newOffset, 1))
            markRun("SmbOffsetUpTT")
        }

        // --- LibreSlopeDownTT: manually setting a TT of 5.026 mmol is used as a remote -0.01 nudge on
        // ApsAutoIsfLibreSlopeOrig (LibreSlope_orig), clamped to a min of 0.60 — not a real target.
        // Same pattern/tight 0.0001mmol tolerance as the other settings-nudge TTs above. If FslCalSlope
        // (the live setting) currently equals its own baseline, nudge both together.
        if (readyToRun("LibreSlopeDownTT", 2) && activeTtNear(5.026, 0.0001)) {
            val currentSlopeOrig = preferences.get(DoubleKey.ApsAutoIsfLibreSlopeOrig)
            val currentLive = preferences.get(DoubleKey.FslCalSlope)
            val newSlope = (currentSlopeOrig - 0.01).coerceAtLeast(0.60)
            preferences.put(DoubleKey.ApsAutoIsfLibreSlopeOrig, newSlope)
            if (fuzzyEquals(currentLive, currentSlopeOrig)) preferences.put(DoubleKey.FslCalSlope, (currentLive - 0.01).coerceAtLeast(0.60))
            cancelCurrentTempTarget()
            sendSms("LibreSlopeDown: LibreSlope_orig=${round(newSlope, 2)}")
            addCarePortalNote(compactSettingNote("LS", newSlope, 2, omitLeadingZero = true))
            markRun("LibreSlopeDownTT")
        }

        // --- LibreSlopeUpTT: manually setting a TT of 5.028 mmol is used as a remote +0.01 nudge on
        // ApsAutoIsfLibreSlopeOrig, clamped to a max of 1.00. Same pattern as LibreSlopeDownTT above.
        if (readyToRun("LibreSlopeUpTT", 2) && activeTtNear(5.028, 0.0001)) {
            val currentSlopeOrig = preferences.get(DoubleKey.ApsAutoIsfLibreSlopeOrig)
            val currentLive = preferences.get(DoubleKey.FslCalSlope)
            val newSlope = (currentSlopeOrig + 0.01).coerceAtMost(1.00)
            preferences.put(DoubleKey.ApsAutoIsfLibreSlopeOrig, newSlope)
            if (fuzzyEquals(currentLive, currentSlopeOrig)) preferences.put(DoubleKey.FslCalSlope, (currentLive + 0.01).coerceAtMost(1.00))
            cancelCurrentTempTarget()
            sendSms("LibreSlopeUp: LibreSlope_orig=${round(newSlope, 2)}")
            addCarePortalNote(compactSettingNote("LS", newSlope, 2, omitLeadingZero = true))
            markRun("LibreSlopeUpTT")
        }

        // --- LibreOffsetDownTT: manually setting a TT of 5.032 mmol is used as a remote -0.05 nudge on
        // ApsAutoIsfLibreOffsetOrig (LibreOffset_orig), clamped to a min of 1.20 — not a real target.
        // Same pattern/tight 0.0001mmol tolerance as the other settings-nudge TTs above. If FslCalOffset
        // currently equals its own baseline, nudge both together. Note suffix uses takeLast(4) (not the
        // usual 2) since this setting is >=1.0 — the one outlier allowed to run to 6 total chars
        // ("Ld1.25") rather than dropping the leading digit, so the note stays unambiguous on its own.
        if (readyToRun("LibreOffsetDownTT", 2) && activeTtNear(5.032, 0.0001)) {
            val currentOffsetOrig = preferences.get(DoubleKey.ApsAutoIsfLibreOffsetOrig)
            val currentLive = preferences.get(DoubleKey.FslCalOffset)
            val newOffsetOrig = (currentOffsetOrig - 0.05).coerceAtLeast(1.20)
            preferences.put(DoubleKey.ApsAutoIsfLibreOffsetOrig, newOffsetOrig)
            if (fuzzyEquals(currentLive, currentOffsetOrig)) preferences.put(DoubleKey.FslCalOffset, (currentLive - 0.05).coerceAtLeast(1.20))
            cancelCurrentTempTarget()
            sendSms("LibreOffsetDown: LibreOffset_orig=${round(newOffsetOrig, 2)}")
            addCarePortalNote(compactSettingNote("L", newOffsetOrig, 2))
            markRun("LibreOffsetDownTT")
        }

        // --- LibreOffsetUpTT: manually setting a TT of 5.034 mmol is used as a remote +0.05 nudge on
        // ApsAutoIsfLibreOffsetOrig, clamped to a max of 1.60. Same pattern as LibreOffsetDownTT above.
        if (readyToRun("LibreOffsetUpTT", 2) && activeTtNear(5.034, 0.0001)) {
            val currentOffsetOrig = preferences.get(DoubleKey.ApsAutoIsfLibreOffsetOrig)
            val currentLive = preferences.get(DoubleKey.FslCalOffset)
            val newOffsetOrig = (currentOffsetOrig + 0.05).coerceAtMost(1.60)
            preferences.put(DoubleKey.ApsAutoIsfLibreOffsetOrig, newOffsetOrig)
            if (fuzzyEquals(currentLive, currentOffsetOrig)) preferences.put(DoubleKey.FslCalOffset, (currentLive + 0.05).coerceAtMost(1.60))
            cancelCurrentTempTarget()
            sendSms("LibreOffsetUp: LibreOffset_orig=${round(newOffsetOrig, 2)}")
            addCarePortalNote(compactSettingNote("L", newOffsetOrig, 2))
            markRun("LibreOffsetUpTT")
        }

        // --- CleanGraphTT: manually setting a TT of 5.042 mmol is used as a remote trigger for the
        // "clean main graph" display combo (no SMB labels, no BGL arrowheads, solid uniform-green line)
        // — the same combo as long-pressing the IOB icon then the Basal icon. Not a real target. Since
        // this plugin has no dependency on core:graph (where the actual display state — showSmbLabels/
        // basalToggleIndex — lives), it can't set those companion fields directly; it just raises a
        // one-shot flag that OverviewFragment's updateGraph() picks up and clears on its next run. Same
        // tight 0.0001mmol tolerance/cancel/notify pattern as the other settings-nudge TTs above.
        if (readyToRun("CleanGraphTT", 2) && activeTtNear(5.042, 0.0001)) {
            preferences.put(BooleanKey.ApsAutoIsfCleanGraphRequested, true)
            cancelCurrentTempTarget()
            sendSms("CleanGraph: no SMBs/arrows, solid green")
            addCarePortalNote("CGrph")
            markRun("CleanGraphTT")
        }

        // --- WizardPctDownTT: manually setting a TT of 5.046 mmol is used as a remote -5 nudge on
        // OverviewBolusPercentage (Wizard bolus %), clamped to a min of 50 — not a real target. Same
        // pattern/tight 0.0001mmol tolerance as the other settings-nudge TTs above.
        if (readyToRun("WizardPctDownTT", 2) && activeTtNear(5.046, 0.0001)) {
            val newPct = (preferences.get(IntKey.OverviewBolusPercentage) - 5).coerceAtLeast(50)
            preferences.put(IntKey.OverviewBolusPercentage, newPct)
            cancelCurrentTempTarget()
            sendSms("WizardPctDown: BolusPct=$newPct")
            addCarePortalNote(String.format(Locale.US, "W%03d%%", newPct))
            markRun("WizardPctDownTT")
        }

        // --- WizardPctUpTT: manually setting a TT of 5.048 mmol is used as a remote +5 nudge on
        // OverviewBolusPercentage, clamped to a max of 100. Same pattern as WizardPctDownTT above.
        if (readyToRun("WizardPctUpTT", 2) && activeTtNear(5.048, 0.0001)) {
            val newPct = (preferences.get(IntKey.OverviewBolusPercentage) + 5).coerceAtMost(100)
            preferences.put(IntKey.OverviewBolusPercentage, newPct)
            cancelCurrentTempTarget()
            sendSms("WizardPctUp: BolusPct=$newPct")
            addCarePortalNote(String.format(Locale.US, "W%03d%%", newPct))
            markRun("WizardPctUpTT")
        }

        // --- MildBoostDownTT: manually setting a TT of 5.052 mmol is used as a remote -0.01 nudge on
        // ApsAutoIsfMildBoostRatio alone (unlike SmbDeliveryDownTT/5.002, which nudges it together with
        // ApsAutoIsfSmbDeliveryBaseline), clamped to its own min of 0.1 — not a real target. Same
        // pattern/tight 0.0001mmol tolerance as the other settings-nudge TTs above.
        if (readyToRun("MildBoostDownTT", 2) && activeTtNear(5.052, 0.0001)) {
            val newMildBoost = (preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio) - 0.01).coerceAtLeast(0.1)
            preferences.put(DoubleKey.ApsAutoIsfMildBoostRatio, newMildBoost)
            cancelCurrentTempTarget()
            sendSms("MildBoostDown: mildBoost=${round(newMildBoost, 2)}")
            addCarePortalNote(compactSettingNote("MB", newMildBoost, 2, omitLeadingZero = true))
            markRun("MildBoostDownTT")
        }

        // --- MildBoostUpTT: manually setting a TT of 5.054 mmol is used as a remote +0.01 nudge on
        // ApsAutoIsfMildBoostRatio alone, clamped to its own max of 0.5. Same pattern as MildBoostDownTT above.
        if (readyToRun("MildBoostUpTT", 2) && activeTtNear(5.054, 0.0001)) {
            val newMildBoost = (preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio) + 0.01).coerceAtMost(0.5)
            preferences.put(DoubleKey.ApsAutoIsfMildBoostRatio, newMildBoost)
            cancelCurrentTempTarget()
            sendSms("MildBoostUp: mildBoost=${round(newMildBoost, 2)}")
            addCarePortalNote(compactSettingNote("MB", newMildBoost, 2, omitLeadingZero = true))
            markRun("MildBoostUpTT")
        }

        // --- GentleHypoRiskOver4.5: escalates from prepare50 state (weight 0.07) to Skittles state (0.02) ---
        // Guard: acce weight 0.03–0.08 (only fires when prepare50 is active; Skittles weight 0.02 falls below).
        // 30-min throttle via readyToRun/markRun. Uses UKF-smoothed raw/noise CGM for additional safety checks.
        if (readyToRun("GentleHypoRisk", 30)) {
            val acceW = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
            // 07:30-22:00 gate REMOVED so this also runs overnight -- same reasoning as AlarmHypo1 below:
            // the value of running it at 03:00 is the BGLstate write, not the alert, and the alert is
            // silenced in quiet hours instead (see gentleHypoQuiet). The acceW 0.03-0.08 band is
            // deliberately left alone: it is what makes this a staged alarm, firing only once something
            // else has already dropped acce, and is intended behaviour rather than an oversight.
            val gentleHypoQuiet = isTimeBetween(22, 0, 7, 30)
            if (acceW > 0.03 && acceW <= 0.08) {
                val g    = glucoseStatus.glucose
                val d    = glucoseStatus.delta
                val sd   = glucoseStatus.shortAvgDelta
                val ukfRaw = ukfRawMetrics()
                val (ukfG, ukfD1, ukfD5) = ukfRaw
                val hp = hypoPrediction2Mmol(g, sd, glucoseStatus.longAvgDelta, iobData.iob, mealData.mealCOB, bgAcce, ukfRaw)

                // Block 1: AAPS BGL ≤ 5.3 + raw BGL ≤ 4.0 + OR(raw ≤ 3.5, raw-1min < 0, raw-5min < 0)
                val ukfLow = ukfG != null && ukfG <= 72.1 /* 4.0 mmol */
                val ukfOr1 = (ukfG != null && ukfG <= 63.1 /* 3.5 */) ||
                    (ukfD1 != null && ukfD1 < 0.0) ||
                    (ukfD5 != null && ukfD5 < 0.0)
                val ghB1 = g <= 95.5 /* 5.3 */ && ukfLow && ukfOr1

                // Block 2: AAPS BGL ≤ 5.5 + profile=50% + delta ≤ -0.3 + sdelta ≤ -0.2 + raw fallback OR
                // Note: d ≤ -0.3 already implies d ≤ 0.0, so the OR's AAPS-delta arm is always satisfied.
                val ukfOr2 = (ukfG != null && ukfG <= 63.1 /* 3.5 */) ||
                    d <= 0.0 ||
                    (ukfD5 != null && ukfD5 <= 0.0)
                val ghB2 = g <= 99.1 /* 5.5 */ && profile_percentage == 50 &&
                    d <= -5.40 /* -0.30 */ && sd <= -3.60 /* -0.20 */ && ukfOr2

                // Block 3: the same hypo-prediction shown as HP2, using GentleHypo's UKF raw 5-minute
                // delta so every raw-derived input in this automation comes from one consistent source.
                val ghB3 = hp != null && hp <= 4.1

                val ghBlock = when { ghB1 -> "1"; ghB2 -> "2"; ghB3 -> "HP2"; else -> null }

                // Extra gate on top of the above (does not replace it): only actually fire if
                // UKF raw glucose < 4.0 mmol OR HP2 <= 4.1 OR step60 > 200, AND UKF raw delta-1min <= 0,
                // AND UKF raw delta-5min <= 0.
                val ghExtraLow = (ukfG != null && ukfG < 72.1 /* 4.0 mmol */) ||
                    (hp != null && hp <= 4.1) || recentSteps60Minutes > 200
                val ghExtraD1  = ukfD1 != null && ukfD1 <= 0.0
                val ghExtraD5  = ukfD5 != null && ukfD5 <= 0.0
                val ghExtraOk  = ghExtraLow && ghExtraD1 && ghExtraD5

                if (ghBlock != null && ghExtraOk) {
                    setBgAccelIsfWeight(0.02)
                    preferences.put(IntKey.ApsAutoIsfIobThPercent, 50)
                    val ghSmsText = "GentleHypoRisk [b$ghBlock]: g=${String.format("%.1f", g / 18.016)} d=${String.format("%.2f", d / 18.016)}" +
                        " UKFrawG=${ukfG?.let { String.format("%.1f", it / 18.016) } ?: "--"}" +
                        " UKFrawD1=${ukfD1?.let { String.format("%.2f", it / 18.016) } ?: "--"}" +
                        " UKFrawD5=${ukfD5?.let { String.format("%.2f", it / 18.016) } ?: "--"}" +
                        " HP2=${hp?.let { String.format("%.1f", it) } ?: "--"}" +
                        " iob=${String.format("%.2f", iobData.iob)}"
                    // Silenced in quiet hours (see gentleHypoQuiet above) -- enacted either way, so the
                    // acce/iobTH changes and the BGLstate write below still happen overnight.
                    if (!gentleHypoQuiet) {
                        if (g <= 6.0 * GlucoseUnit.MMOLL_TO_MGDL && mealData.mealCOB <= 9.0) {
                            sendSms(ghSmsText)
                            sendSmsToNumbers(ghSmsText, StringKey.SmsGentleHypoAlertNumbers)
                        }
                        uiInteraction.addNotification(id = 9001, text = "GentleHypoRisk G5 [b$ghBlock]: g=${String.format("%.1f", g / 18.016)}mmol", level = Notification.URGENT)
                    }
                    addGraphAnnouncement("________________Gentle5")
                    addCarePortalNote("Gntl5")
                    //setAutomationState("MJstate", "MJon")
                    setAutomationState("BGLstate", "BGLlastLOW")
                    markRun("GentleHypoRisk")
                    aapsLogger.debug(LTag.APS, "GentleHypoRisk block $ghBlock: g=${String.format("%.1f", g / 18.016)}mmol d=${String.format("%.2f", d / 18.016)} acceW=$acceW UKFrawG=${ukfG?.let { String.format("%.1f", it / 18.016) }} UKFrawD1=${ukfD1?.let { String.format("%.2f", it / 18.016) }} UKFrawD5=${ukfD5?.let { String.format("%.2f", it / 18.016) }} HP2=${hp?.let { String.format("%.1f", it) }}")
                }
            }
        }

        // --- PP50.Off: replaces "PP50.Off CurrProfReal 70_0.70 0.35" automation ---
        // Exits the 50% prepare state: restores full profile, resets acce weight, clears LowBG=50recent.
        // Guard: LowBG=50recent state must be set; firing sets LowBG=NO50rec so it won't re-trigger.
        // 5-min floor throttle added on top of the state guard (see readyToRun() usage note).
        if (readyToRun("PP50Off", 5) && checkAutomationState("LowBG", "50recent")) {
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
                markRun("PP50Off")
            }
        }

        // --- Skittles hypo-risk: replaces SkittlesTT3CurrP02, SkittlesA3ok8.0,5.0,6.0, Skittles3ok2BG9.0 ---
        // Primary guard: startTempTargetIfNeeded() no-ops when a TT is already active, so the
        // 7 condition blocks are evaluated every loop cycle but actions only fire once per TT period.
        // All glucose/delta thresholds in mg/dL; originals in mmol/L noted in comments.
        // 5-min floor throttle added on top of the TT-existence self-guard (see readyToRun() usage note).
        run {
            if (!readyToRun("SkittlesHypoRisk", 5)) return@run
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
                setSmbDeliveryRatio(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline))   // restore delivery baseline on hypo-risk protection
                preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal))   // restore ppWeight baseline
                applyCurrentProfileAt100()
                setAutomationState("LowBG", "50recent")
                sendSms("Skittles $block: hypo risk — TT 5.7 set")
                addCarePortalNote("TT3-$block")
                aapsLogger.debug(LTag.APS, "Skittles block $block: g=${String.format("%.1f", g / 18.016)}mmol d=${String.format("%.2f", d / 18.016)} iob=${String.format("%.2f", iob)} cob=${cob.toInt()} pct=$profile_percentage")
                markRun("SkittlesHypoRisk")
            }
        }

        // --- 50SetRecent: flags LowBG=50recent whenever profile drops to 50% while in NO50rec state ---
        // 5-min floor throttle added on top of the state guard (see readyToRun() usage note).
        // Hysteresis: also locked out for 15 min after a PP50.Off recovery (readyToRun on the "PP50Off"
        // timestamp), so it cannot immediately re-flag 50recent for PP50.Off to exit again — this breaks
        // the observed ~5-min 50Rec<->50ff flap where the two automations toggled the state each cycle.
        if (readyToRun("50SetRecent", 5) && readyToRun("PP50Off", 15)
            && profile_percentage == 50 && checkAutomationState("LowBG", "NO50rec")) {
            setAutomationState("LowBG", "50recent")
            sendSms("50%Recently")
            addCarePortalNote("50Rec")
            markRun("50SetRecent")
        }

        // --- Not50%Recently: clears 50recent flag once profile is back at 100% and BGL rising ---
        if (readyToRun("Not50Recently", 5) && profile_percentage == 100 && checkAutomationState("LowBG", "50recent")
            && glucoseStatus.delta >= 1.8 /* 0.1 mmol */) {
            setAutomationState("LowBG", "NO50rec")
            addCarePortalNote("No50")
            markRun("Not50Recently")
        }

        // --- iobTHDaytimeFloor: enforces a daytime iobTH% floor so it can't stay stranded at a
        // back-off level (as seen: stuck at 18% → SMBs throttled, low IOB). During the day, in a
        // clearly-normal state, restore iobTH to the usual 70% whenever it has dropped to 50% or below.
        //  - rescues from <= 50% (bumps 12/15/16/18/…/50 up to 70; leaves intentional 51+ alone)
        //  - requires profile 100%, LowBG=NO50rec (not in a back-off), no active TT, Steroids Off,
        //  - runs 08:00-22:00 only; EvCap owns 22:00-midnight, so the two cannot raise/lower iobTH
        //    against each other. BGL >= 6.5 mmol and not falling. 30-min throttle.
        if (readyToRun("iobTHDaytimeFloor", 30)
            && isTimeBetween(8, 0, 22, 0)
            && profile_percentage == 100
            && checkAutomationState("LowBG", "NO50rec")
            && checkAutomationState("Steroids", "Steroids Off")
            && activeTtMgdl() == null
            && iobThresholdPercent <= 50
            && glucoseStatus.glucose >= 117.1 /* 6.5 mmol */
            && glucoseStatus.delta >= 0.0
        ) {
            val prevTh = iobThresholdPercent
            preferences.put(IntKey.ApsAutoIsfIobThPercent, 70)
            sendSms("iobTHfloor: iobTH ${prevTh}->70%")
            addCarePortalNote("THfloor")
            aapsLogger.debug(LTag.APS, "iobTHDaytimeFloor: restored iobTH $prevTh -> 70 (g=${String.format("%.1f", glucoseStatus.glucose / 18.016)}mmol)")
            markRun("iobTHDaytimeFloor")
        }

        // --- Extra50%: deepens hypo protection (acce→0.07, iobTH→50%) when BGL falling on 100% profile ---
        // 5-min floor throttle added on top of the state guard (see readyToRun() usage note).
        if (readyToRun("Extra50", 5) && profile_percentage == 100 && checkAutomationState("LowBG", "NO50rec")) {
            val g  = glucoseStatus.glucose
            val d  = glucoseStatus.delta
            val sd = glucoseStatus.shortAvgDelta
            val ld = glucoseStatus.longAvgDelta
            val noMJ = !checkAutomationState("MJ", "NOMJremains")
            // Block 1: steep multi-delta fall, <=8.5 mmol
            val xb1 = d <= -6.3 && sd <= -4.5 && ld <= -3.6 && g <= 153.1 && noMJ
            // Block 2: moderate fall at <=6.5 mmol
            val xb2 = g <= 117.1 && ld <= -2.7 && d <= -3.6 && sd <= -1.8 && noMJ
            // Block 3: overnight (01:00–05:00), <=7.5 mmol, falling
            val xb3 = isTimeBetween(1, 0, 5, 0) && g <= 135.1 && sd < -3.6 && d < -3.6
            val xBlock = when { xb1 -> "1"; xb2 -> "2"; xb3 -> "3"; else -> null }
            if (xBlock != null) {
                setBgAccelIsfWeight(0.07)
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 50)
                applyCurrentProfileAt100()
                setAutomationState("LowBG", "50recent")
                sendSms("Extra50% [b$xBlock]: g=${String.format("%.1f", g / 18.016)} d=${String.format("%.2f", d / 18.016)}")
                addCarePortalNote("X50-$xBlock")
                markRun("Extra50")
            }
        }

        // --- OvernightDuraRescue: deep-night duraISF-led correction when Low profile is under-treating a
        // genuine plateaued high. Narrower, one-shot counterpart to what BasalUp used to do before it was
        // blocked 22:00-06:00 this session -- NOT a re-opening of that gap: time-boxed to 60 minutes via
        // switchProfileIfNeeded's own duration (auto-reverts, no extra revert logic needed), gated on
        // duraISF genuinely dominating the other AutoISF adaptation factors, no live stacking, no recent
        // low (excludes rebound-highs), and BOTH short- and long-avg delta sitting flat rather than still
        // moving either way. NightIobCeiling (00:00-06:00) and the cumulative 10-min SMB cap (00:30-04:00,
        // 0.6U) both key on time/IOB value, not profile name, so they stay fully in force through the
        // rescue window by design -- this only shifts the profile-derived sens anchor and basal, it does
        // not touch the AutoISF acce/bg/pp/dura adaptation factors themselves, which are computed purely
        // from BG shape and are identical regardless of which profile is active.
        //
        // VERIFIED: autoISF(profile, graphActivity, iobData.activity*100) runs at line ~953, well before
        // this block, so autoIsfValues.duraIsf/.finalIsf/.acceIsf/.bgIsf/.ppIsf are THIS cycle's numbers,
        // not stale from the previous loop. (Residual, unconfirmed: getIsfMgdl() -> calculateVariableIsf()
        // -> autoISF(profile) is a second write path into the same autoIsfValues object, callable by other
        // code outside invoke()'s own timing with default currentActivity=0/smbActivity=0 -- see the
        // existing tddRatio comment a few hundred lines down in autoISF() acknowledging this same class of
        // hazard for one field. Not ruled out here for duraIsf/finalIsf specifically; would need AAPS's
        // threading model traced further to close.)
        //
        // 2.5 thresholds checked against real data, not assumed: actual device settings put
        // autoISF_max=2.6, autoISF_min=0.3 (AutoISF_settings_20260725_102523.txt), and 695 overnight rows
        // across 6 recent AIV exports give Final/dura medians of 1.36/1.42, p90 of 2.6/2.84, p95 of
        // 2.81/3.17 (dura is unclamped pre-liftISF and finalISF can exceed autoISF_max via the
        // sensitivityRatio multiplier in withinISFlimits' exercise/resistance/step branches, which is why
        // Final's observed max of 3.12 exceeds the raw 2.6 config ceiling). So 2.5 sits at the overnight
        // p90-p95 mark for both -- reachable, not dead code, and roughly the same rarity band the 10-min
        // SMB cap was deliberately tuned to. Landed there by coincidence of a round number, not by design,
        // but confirmed sane rather than left as a guess.
        run {
            if (!readyToRun("OvernightDuraRescue", 60)) return@run
            if (rescueActive) return@run   // don't stack a second rescue on an active one

            val g   = glucoseStatus.glucose
            val sd  = glucoseStatus.shortAvgDelta   // mg/dL
            val ld  = glucoseStatus.longAvgDelta    // mg/dL
            val onLowProfile = profileFunction.getProfileName() == preferences.get(StringKey.ApsAutoIsfLowProfileName)

            val duraIsf  = autoIsfValues.duraIsf
            val finalIsf = autoIsfValues.finalIsf
            val duraDominant = duraIsf > 2.5 && finalIsf > 2.5 && duraIsf > finalIsf
                && duraIsf >= autoIsfValues.acceIsf && duraIsf >= autoIsfValues.bgIsf && duraIsf >= autoIsfValues.ppIsf

            val noRecentLow = !checkAutomationState("LowBG", "50recent")
            val noStacking  = smbSum30Min() <= 0.0

            val rescueOk = isTimeBetween(2, 0, 4, 0) && onLowProfile && g > 108.1 /* 6.0 mmol */
                && duraDominant && noStacking && noRecentLow
                && ld > -1.8 && ld <= 1.8   /* -0.1 < LDelta <= 0.1 mmol */
                && sd > -1.8 && sd <= 1.8   /* -0.1 < SDelta <= 0.1 mmol */

            if (rescueOk) {
                switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfStandardProfileName), 60)
                preferences.put(LongKey.ApsAutoIsfOvernightRescueUntil, dateUtil.now() + T.mins(60).msecs())
                sendSms("OvernightDuraRescue: g=${round(g / 18.0182, 1)} duraISF=${round(duraIsf, 2)} finalISF=${round(finalIsf, 2)} -> Standard 60min")
                addCarePortalNote("DuraRsc")
                markRun("OvernightDuraRescue")
            }
        }

        // --- OffHighProf: overnight BGL falling on non-standard profile → drop to acce 0.18 / iobTH 18% ---
        // Fires when NOT on the Low profile (i.e. on a named high/steroid profile), Steroids Off, no TT.
        // 30-min floor throttle matches the temporary low-profile duration and prevents another
        // profile-changing block from re-arming OffP-1/OffP-2 within that protection window.
        run {
            if (!readyToRun("OffHighProf", 30)) return@run
            val g  = glucoseStatus.glucose
            val d  = glucoseStatus.delta
            val onCurrentProfile = profileFunction.getProfileName() == preferences.get(StringKey.ApsAutoIsfLowProfileName)
            val noTT = activeTtMgdl() == null
            val steroidOff = checkAutomationState("Steroids", "Steroids Off")
            // Hypo-prediction gate: only back off (drop to the low profile + acce 0.18 / iobTH 18) when a
            // low is actually predicted, not merely because BGL happens to be under 7.5 and drifting down
            // overnight. Without this the two blocks below fire on most quiet nights, and since the profile
            // switch re-arms every time its own 30-min duration expires (the "!onCurrentProfile" gate goes
            // true again on revert), that kept the low profile in place for much of 01:00-06:00 in 30-min
            // chunks -- while acce/iobTH, which are NOT reverted with it, stayed suppressed throughout.
            // Fails OPEN (allows the block to fire) when HP2 can't be evaluated -- see hypoPrediction2Mmol().
            // This is a hypo-protective action, so the safe default on missing data is the pre-existing
            // behaviour rather than silently withholding a back-off that might be warranted.
            val hpNow = hypoPrediction2Mmol(g, glucoseStatus.shortAvgDelta, glucoseStatus.longAvgDelta, iobData.iob, mealData.mealCOB, bgAcce)
            val hypoPredicted = hpNow == null || hpNow < 5.0
            // Block 1: 01:00–06:00, g < 7.5 mmol, delta <= -0.05 mmol, pct >= 100, not on Current Profile
            val ohb1 = isTimeBetween(1, 0, 6, 0) && g < 135.1 && d <= -0.9
                && profile_percentage >= 100 && !onCurrentProfile && noTT && steroidOff
            // Block 2: 05:00–05:30, g <= 7.5 mmol, pct = 100, not on Current Profile
            val ohb2 = isTimeBetween(5, 0, 5, 30) && g <= 135.1
                && profile_percentage == 100 && !onCurrentProfile && noTT && steroidOff
            val ohBlock = when { ohb1 -> "1"; ohb2 -> "2"; else -> null }
            if (ohBlock != null && (isTimeBetween(22, 0, 6, 0) || hypoPredicted)) {
                // !rescueActive gates ONLY the profile switch, same as MJrecentCurrProfAcce/NightAcce
                // below -- acce/iobTH still apply regardless of an active rescue (previously the whole
                // block, including these two, was wrongly gated on !rescueActive as well).
                if (!rescueActive) switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfLowProfileName), 30)
                setBgAccelIsfWeight(0.18)
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 18)
                sendSms("OffHighProf [b$ohBlock]: g=${String.format("%.1f", g / 18.016)} d=${String.format("%.2f", d / 18.016)} HP2=${hpNow?.let { String.format("%.1f", it) } ?: "--"}")
                addCarePortalNote("OffP-$ohBlock")
                markRun("OffHighProf")
            }
        }

        // --- Battery1%: when phone battery drops to <=1%, switch to Current Profile50 and alert ---
        // Guard: profile=100% (precondition). State Profile must be PP130, C100, or AllOK (normal running states).
        // Self-guarding: switching to Profile50 makes profile_percentage=50 next cycle, failing the precondition.
        // Live-pump-only: skip entirely on Virtual Pump (model() == GENERIC_AAPS is how this codebase
        // identifies it elsewhere, e.g. TriggerPumpBatteryLevelTest).
        // No cannula-age gate — that threshold (80h) was pod-specific and doesn't generalize to tubed
        // pumps with independent infusion-set lifespans; critical phone battery should react regardless.
        // 20-min floor throttle added on top of the state guard (see readyToRun() usage note).
        if (readyToRun("Battery1pc", 20) && profile_percentage == 100
            && (checkAutomationState("Profile", "PP130") || checkAutomationState("Profile", "C100") || checkAutomationState("Profile", "AllOK"))
            && receiverStatusStore.batteryLevel <= 1
            && activePlugin.activePump !is VirtualPump) {
            switchProfileIfNeeded("Current Profile50", 0)
            uiInteraction.addNotification(Notification.PERMISSION_BATTERY, "Batt1%", Notification.URGENT)
            addGraphAnnouncement("Batt1%")
            addCarePortalNote("Bt<1%")
            sendSms("LowBattery")
            sendSmsToNumbers("LowBattery", StringKey.SmsBattAlertNumbers)   // additionally targets SmsBattAlertNumbers
            markRun("Battery1pc")
        }

        // --- BatteryOver1%: when battery recovers above 1%, restore Current ProfileReal ---
        // Keys on being on the named "Current Profile50" (the battery-drop profile) rather than a state
        // flag: that name uniquely marks the battery state, since a hypo 50% is a *percentage* on
        // ProfileReal (different name, profile_percentage==50). This replaced the old guard which
        // required checkAutomationState("Profile","Batt1%") — a flag Battery1pc never set, so recovery
        // never fired — and a "profile_percentage == 50" branch that would have wrongly matched (and
        // cancelled) a hypo 50%.
        // 5-min floor throttle added on top of the profile guard (see readyToRun() usage note).
        if (readyToRun("BatteryOver1pc", 5)
            && profileFunction.getProfileName() == "Current Profile50"
            && receiverStatusStore.batteryLevel > 1) {
            switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfStandardProfileName), 0)
            sendSms("AllOK Batt")
            setAutomationState("Profile", "AllOK")
            addCarePortalNote("bat>1")
            markRun("BatteryOver1pc")
        }

        // --- HighOldPod: sets a brief TT 5.0 + 110% profile when cannula is stale (>=60h) or fresh (<=6h) ---
        // Preconditions: no TT, profile=100%. Guards: MJ=NOMJremains state + bolus >=90 min ago.
        // 5-min floor throttle added on top of the preconditions (see readyToRun() usage note).
        if (readyToRun("HighOldPod", 5) && profile_percentage == 100 && activeTtMgdl() == null
            && checkAutomationState("MJ", "NOMJremains")) {
            val g  = glucoseStatus.glucose
            val d  = glucoseStatus.delta
            val sd = glucoseStatus.shortAvgDelta
            val ld = glucoseStatus.longAvgDelta
            val lastBolusMin = minutesSinceLastNormalBolus() ?: Int.MAX_VALUE
            val cannulaH = hoursSinceLastCannulaChange() ?: 0.0
            val oldOrNew = cannulaH >= 60.0 || cannulaH <= 6.0
            if (g >= 180.2 /* 10.0 mmol */ && d in 1.8..5.4 /* 0.1–0.3 mmol */
                && sd >= 1.8 /* 0.1 mmol */ && ld >= 0.0
                && lastBolusMin >= 90 && oldOrNew) {
                val targetProfile = preferences.get(StringKey.ApsAutoIsfStandardProfileName)
                startTempTargetIfNeeded(90.1 /* 5.0 mmol */, 5)
                switchProfileIfNeeded(targetProfile, 30)
                startProfilePercentFor(110, 5, targetProfile)
                setBgAccelIsfWeight(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal))
                preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh))
                addCarePortalNote("Old")
                sendSms("HighOldPod: g=${String.format("%.1f", g / 18.016)} cannula=${String.format("%.1f", cannulaH)}h")
                markRun("HighOldPod")
            }
        }

        // --- Shower12: drops iobTH to 12% during quiet morning rise (no steps, no COB, long post-bolus) ---
        // 5-min floor throttle added on top of the preconditions (see readyToRun() usage note).
        if (readyToRun("Shower12", 5) && profile_percentage > 50 && iobThresholdPercent > 12
            && checkAutomationState("Steroids", "Steroids Off")) {
            val g  = glucoseStatus.glucose
            val d  = glucoseStatus.delta
            val sd = glucoseStatus.shortAvgDelta
            val tt = activeTtMgdl()
            val lastBolusMin = minutesSinceLastNormalBolus() ?: Int.MAX_VALUE
            if (isTimeBetween(5, 30, 8, 30)
                && g <= 144.1 /* 8.0 mmol */ && d >= 6.3 /* 0.35 mmol */ && sd >= 4.5 /* 0.25 mmol */
                && recentSteps60Minutes < 10 && mealData.mealCOB == 0.0 && lastBolusMin >= 180
                && (tt == null || tt <= 127.9 /* 7.1 mmol */)) {
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 12)
                sendSms("Shower12: g=${String.format(Locale.getDefault(), "%.1f", g / 18.016)} iobTH=${iobThresholdPercent}")
                addCarePortalNote("Shwr12")   // careportal note (SMS-only before, so no careportal trail): logs the iobTH 12 drop
                markRun("Shower12")
            }
        }

        // --- HighNight00AM: overnight high BGL (>=7.0 mmol) — switch to ProfileReal, set hypo TT 4.2 ---
        // Fires 01:00–05:45 when glucose elevated and gently rising/flat, iobTH<=50 OR old cannula + MJ state.
        // Threshold lowered from 9.0 to 7.0 mmol (least-invasive first step on the overnight 6-6.5mmol
        // plateau): NightAcce backs off at <=6.0mmol and OffHighProf reverts this automation's own
        // ProfileReal switch once BG falls under 7.5mmol falling, so 7.0 sits with working room on both
        // sides of that existing pair -- low enough to actually engage before BG settles into the
        // 6.0-9.0mmol dead zone where nothing else in this file corrects, high enough to leave NightAcce's
        // own floor and OffHighProf's own revert gate alone. Revisit the exact number after a few nights.
        // 60-min floor throttle added on top of the preconditions (see readyToRun() usage note).
        // DISABLED. This block sets iobTH to 51 (and acce 0.50) between 01:00-05:45 to permit correcting a
        // genuine overnight high -- which directly contradicts NightIobCeiling above, added after the
        // 6->7 Aug 2026 hypo, which pins iobTH to 18 and acce to 0.35 across the same hours. With both
        // active they would fight every cycle: this raises, that lowers ~5 min later. Turned off rather
        // than left to lose that race, so the behaviour is explicit instead of emergent.
        // Accepted consequence: overnight highs now go largely uncorrected between 01:00 and 05:45.
        // Flip this back to true to restore it -- and if you do, reconsider NightIobCeiling at the same
        // time, because the two cannot both be right.
        val highNight00AmEnabled = false
        if (highNight00AmEnabled && readyToRun("HighNight00AM", 60) && activeTtMgdl() == null && checkAutomationState("Steroids", "Steroids Off")) {
            val g       = glucoseStatus.glucose
            val d       = glucoseStatus.delta
            val sd      = glucoseStatus.shortAvgDelta
            val ld      = glucoseStatus.longAvgDelta
            val iobTH   = iobThresholdPercent
            val cannulaH = hoursSinceLastCannulaChange() ?: 0.0
            val baseOk  = isTimeBetween(1, 0, 5, 45) && g >= 126.1 /* 7.0 mmol */
                && d >= 0.0 && d <= 14.4 /* 0.8 mmol */ && sd >= 0.0 && ld >= 0.0 && ld <= 6.3 /* 0.35 mmol */
            val branch1 = baseOk && iobTH <= 50
            val branch2 = baseOk && checkAutomationState("MJ", "NOMJremains") && g >= 126.1 && cannulaH >= 60.0
            if (branch1 || branch2) {
                switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfStandardProfileName), 30)
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 51)
                setBgAccelIsfWeight(0.50)
                startTempTargetIfNeeded(75.7 /* 4.2 mmol */, 5)
                setAutomationState("Profile", "C100")
                sendSms("HighNight00AM: g=${String.format(Locale.getDefault(), "%.1f", g / 18.016)} iobTH=$iobTH")
                addCarePortalNote("HnAM")
                markRun("HighNight00AM")
            }
        }

        // --- ActivityProf50%: sets 50% profile for 180 min during the 6.8mmol Activity TT, BGL
        // stable/falling. Corrected per user: the TT check is an exact match on 6.8mmol (not the
        // 7.3-7.9mmol range this previously guarded on, which never matched the real Activity TT
        // at all), using fuzzyEquals/mmolToMgdl. Also guards against Bolus2/BolusGiven71's own
        // brief 6.8mmol post-bolus TT (same numeric value) via the shared Profile=Bolus state, so a
        // 5-minute post-bolus TT can't be misread as an Activity session and trigger a 180-minute
        // 50% profile drop. Note/SMS text corrected to match the screenshot exactly ("Ac50" /
        // "ActivityProf50% Acce", not the old dynamic g=-value SMS text).
        // 5-min floor throttle added on top of the precondition (see readyToRun() usage note).
        if (readyToRun("ActivityProf50", 5) && profile_percentage == 100 && !checkAutomationState("Profile", "Bolus")) {
            val tt = activeTtMgdl()
            if (tt != null && fuzzyEquals(tt, mmolToMgdl(6.8))
                && glucoseStatus.glucose <= 153.1 /* 8.5 mmol */ && glucoseStatus.delta <= 1.8 /* 0.1 mmol */) {
                sendSms("ActivityProf50% Acce")
                setBgAccelIsfWeight(0.02)
                applyCurrentProfileAt100()
                startProfilePercentFor(50, 180)
                addCarePortalNote("Ac50")
                markRun("ActivityProf50")
            }
        }

        // Code port of "ActivityOff 70_0.70 0.35": exit criteria for ActivityProf50%'s 50% profile
        // drop — 3 branches (still on the 6.8mmol Activity TT but BGL climbed too high; TT already
        // ended with BGL stabilising/rising and cannula not brand-new; or a bolus was just given).
        // Restores ProfileReal, acce to 0.35, iobTH to 70%. Original action list had no CarePortal
        // note, kept minimal to match exactly.
        if (readyToRun("ActivityOff", 5) && profile_percentage == 50) {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            val tt = activeTtMgdl()
            val cannulaH = hoursSinceLastCannulaChange() ?: 0.0
            val lastBolusMin = minutesSinceLastNormalBolus() ?: Int.MAX_VALUE
            val delayedBolusPending = preferences.get(LongKey.DelayedBolusBlockSmbUntil) > dateUtil.now()
            val aoB1 = tt != null && fuzzyEquals(tt, mmolToMgdl(6.8)) && g >= 171.2 /* 9.5 mmol */
            val aoB2 = tt == null && g <= 153.1 /* 8.5 mmol */ && d >= 1.8 /* 0.1 mmol */
                && g >= 108.1 /* 6.0 mmol */ && cannulaH >= 3.0
            // Suppressed while a delayed-bolus window is pending: a bolus alone (the original, or a
            // later correction/meal bolus during the wait) isn't evidence of recovery here — it's
            // specifically what started the wait. aoB1/aoB2 (real BG-based recovery) stay active; only
            // the "a bolus was just given" heuristic is disabled for these 85 min, otherwise almost any
            // bolus during the window reverts the profile and the delayed-bolus wait becomes pointless.
            val aoB3 = lastBolusMin <= 10 && !delayedBolusPending
            if (aoB1 || aoB2 || aoB3) {
                cancelCurrentTempTarget()
                switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfStandardProfileName))
                setBgAccelIsfWeight(0.35)
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 70)
                sendSms("Activity 70_0.70 0.35 Acce")
                addCarePortalNote("ActOff")   // careportal note (SMS-only before, so no careportal trail): logs the Activity 50% exit (TT cancel, profile 100, acce 0.35, iobTH 70)
                markRun("ActivityOff")
            }
        }

        // --- SMB delivery ratio reset: restore the resting baseline (ApsAutoIsfSmbDeliveryBaseline,
        // default 0.14) once no TT is active. The strengthening autos (BolusGiven bg3, BolusGivenMild)
        // each set a boosted delivery alongside a 2-min TT, so "no TT" means the boost window has ended
        // → restore baseline. Runs BEFORE the boost blocks below on purpose: startTempTargetIfNeeded()
        // inserts the TT asynchronously (not visible until the next loop), so a reset placed after a
        // boost would see "no TT" and cancel the boost the same loop. Here it only ever sees a TT that
        // was set on a PREVIOUS loop, so it correctly holds the boosted ratio for the TT's 2 minutes.
        // While a Skittles 5.7 / manual TT is active the reset defers, and those paths already set baseline.
        // The recovery/protective autos' own setSmbDeliveryRatio(baseline) calls remain as a backup.
        // Guard is "NOT (fuzzily) at baseline" — i.e. boosted — rather than ">baseline": the pref reads
        // back a hair above the literal (float rounding), so a ">" guard would be true even AT baseline
        // and re-fire DelOff every loop. fuzzyEquals (±0.001) treats baseline as baseline and only an
        // actual boost as different. Baseline is now a live preference (no more manual retune needed
        // here) — just keep it more than 0.001 apart from whatever boost ratios are in use.
        // smbStacking/hardStackTarget computed here (not down in HardStackDelOff's own block below) so
        // this check can also exempt "currently sitting at HardStackDelOff's own reduced target while
        // stacking is still genuinely happening" — without that exemption, DelOff couldn't tell that
        // state apart from "elevated above baseline, needs restoring", and would fight HardStackDelOff
        // every single cycle: DelOff resets to baseline, HardStackDelOff immediately re-reduces it,
        // repeating for as long as stacking continued (alternating DelOff/HardStackDelOff CarePortal
        // notes every cycle instead of settling).
        val deliveryBaseline = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline)
        val hardStackTarget = deliveryBaseline - 0.03
        val smbStacking = smbInterval5Sec() < 65.0 && smbCount5Min() >= 4
        val atHardStackTarget = fuzzyEquals(smb_delivery_ratio, hardStackTarget)
        if (!fuzzyEquals(smb_delivery_ratio, deliveryBaseline) && activeTtMgdl() == null && !(atHardStackTarget && smbStacking)) {
            setSmbDeliveryRatio(deliveryBaseline)
            addCarePortalNote("DelOff")   // delivery-ratio boost ended (fires once as it drops back to baseline)
        }

        // MildOffsetZero restore: same "no active TT" timer as the delivery-ratio boost above — clears
        // the BolusGivenMild-under-5.9mmol offset-zero flag the moment its own 2-min TT expires.
        if (activeTtMgdl() == null && preferences.get(BooleanKey.ApsAutoIsfMildOffsetZeroActive)) {
            preferences.put(BooleanKey.ApsAutoIsfMildOffsetZeroActive, false)
        }

        // --- HardStackDelOff: forced delivery-ratio REDUCTION (not a full revert) on genuine SMB
        // stacking, independent of TT state. DelOff above only reverts when NO TT is active — it
        // correctly defers while bg3/mild's own short 2-min TT is running, but ALSO defers for any other
        // reason a TT happens to be active (e.g. the 5.7mmol hypo-protection TT can run 180 min), which
        // could leave an elevated ratio stuck well beyond its intended window. This checks the actual
        // stacking pattern instead: gap AND count both required (gap alone can look artificially low off
        // just 2-3 close SMBs; count alone doesn't confirm they're rapid) so both must agree stacking is
        // genuinely happening. No overnight/time-of-night trigger — an earlier unconditional 2300-0100
        // OR-branch was tried and reverted (2026-07-26): the -0.03 nudge it produced (0.11 from a 0.14
        // baseline) was judged too weak to be worth keeping as a separate, stacking-independent trigger.
        // Target is baseline MINUS 0.03 (was: reset straight to baseline) — a smaller nudge down rather
        // than a full snap-back. Computed from the constant baseline, not by re-reading the current
        // (possibly already-reduced) ratio, so repeated cycles while the condition holds can't compound
        // into a runaway decrease. Reversion once neither condition holds isn't handled here at all — it
        // falls out of DelOff above for free: DelOff always tries to restore the constant baseline
        // whenever the ratio isn't there and no TT is active (and it's not still-genuinely-stacking at
        // the hard-stack target — see DelOff's own exemption above), so the next cycle where this
        // condition is false, DelOff's own check (which runs first) puts it straight back.
        // Excludes only the boost's OWN brief TT (75.7=4.2mmol bg3, 90.1=5.0mmol mild) — bg3/mild
        // successfully delivering back-to-back during their own intended 2-min window looks identical to
        // "stacking" by this same measure, and this must not cut that window short. Any OTHER active TT
        // (or none) is fair game. No readyToRun throttle — deliberately checked every iteration, same as
        // DelOff itself; the action is idempotent so re-checking every cycle is harmless.
        val onOwnBoostTt = activeTtMgdl()?.let { fuzzyEquals(it, 75.7) || fuzzyEquals(it, 90.1) } == true
        if (!atHardStackTarget
            && smbStacking
            && !onOwnBoostTt) {
            setSmbDeliveryRatio(hardStackTarget)
            addCarePortalNote("HardStackDelOff")
        }

        // --- BolusGiven71_0.70: post-bolus response — boosts iobTH to 71%, acce 0.70, 110% for 10 min ---
        // 10-min throttle. Three trigger blocks; only outer precondition is profile=100% + no TT.
        // COB>=9 / acce>=0.20 / dura<acce are per-block (postBolusGate) for the manual-bolus branches
        // (bg1/bg2); the SMB-driven branch (bg3) does NOT require them.
        // ApsAutoIsfBoostAutomationsEnabled is the combined master switch for this WHOLE block (bg1/2/3)
        // and BolusGivenMild below — one toggle disables both, per user request.
        if (profile_percentage == 100 && activeTtMgdl() == null
            && preferences.get(BooleanKey.ApsAutoIsfBoostAutomationsEnabled)
            && readyToRun("BolusGiven", 10)) {
            val g  = glucoseStatus.glucose
            val d  = glucoseStatus.delta
            val sd = glucoseStatus.shortAvgDelta
            val iobTH = iobThresholdPercent
            val lastBolusMin = minutesSinceLastNormalBolus() ?: Int.MAX_VALUE
            val cob = mealData.mealCOB
            // Safety cap: within the first 30 min post-bolus, don't let bg1/bg2 fire if delta is
            // already >=0.5 mmol — an early, already-large delta this soon after a bolus reads as a
            // stacking risk, not clearer evidence of need. No effect past 30 min (bg1/bg2's own windows
            // extend well beyond it). Not applied to bg3 (or BolusGivenMild below) — both require
            // lastBolusMin>=120, so a <30-min cap could never bind there anyway.
            val recentBolusDeltaCapOk = !(lastBolusMin < 30 && d >= 9.0 /* 0.5 mmol */)
            // per-block gate for the manual-bolus branches (was the global guard)
            val postBolusGate = cob >= 9
                && preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight) >= 0.20
                && autoIsfValues.duraIsf < autoIsfValues.acceIsf
            // Raw Libre deltas (both normalised to a per-5-min rate) — declared up here so bg1/bg2 can
            // gate on them too, not just bg3.
            val rawDelta5 = rawDelta5MinMgdl() ?: -9999.0
            val rawDelta1 = rawDelta1MinMgdl() ?: -9999.0
            // Block 1: recent bolus (5–35 min), rising BGL, low steps, iobTH<71, steroids off.
            // Raw-Libre confirmation added at the SAME magnitude as each branch's AAPS delta, so a
            // smoothed-delta blip the sensor doesn't back up can't start the boost.
            val bgRise1 = g >= 108.1 && sd >= 3.6 && d >= 3.6   // >=6.0 mmol rising
                && rawDelta5 >= 3.6 && rawDelta1 >= 3.6 /* 0.2 mmol raw confirmation */
            val bgRise2 = g >= 90.1  && sd >= 7.2 && d >= 7.2   // >=5.0 mmol fast rise
                && rawDelta5 >= 7.2 && rawDelta1 >= 7.2 /* 0.4 mmol raw confirmation */
            val profileName = profileFunction.getProfileName()
            val onNormalProfile = profileName == preferences.get(StringKey.ApsAutoIsfStandardProfileName) || profileName == preferences.get(StringKey.ApsAutoIsfLowProfileName)
            val bg1 = postBolusGate && (bgRise1 || bgRise2) && recentSteps60Minutes <= 1600
                && iobTH < 71 && g <= 198.2 && checkAutomationState("Steroids", "Steroids Off")
                && (lastBolusMin <= 120 || cob >= 10)
                && lastBolusMin >= 5 && lastBolusMin <= 35 && onNormalProfile && recentBolusDeltaCapOk
            // Block 2: high BGL (>=10 mmol), COB>=9, rising, bolus within 90 min, not MJ state.
            // Same-magnitude raw-Libre confirmation as its AAPS delta (>0.2 mmol).
            val bg2 = postBolusGate && g >= 180.2 && lastBolusMin <= 90 && d > 3.6 && cob >= 9
                && rawDelta5 > 3.6 && rawDelta1 > 3.6 /* 0.2 mmol raw confirmation */ && recentBolusDeltaCapOk
                && !checkAutomationState("MJ", "NOMJremains")
            // Block 3 (NEW): delivery-driven rise with NO recent manual bolus/carbs (>=120 min for both).
            // Total IOB (bolus + basal) rose > 0.80 U over 5 min, and BGL rising on the AAPS delta and on
            // BOTH the raw Libre 5-min AND 1-min deltas at the same >=0.8 mmol threshold. NB: rawDelta1MinMgdl()
            // normalises the 1-min trend to a per-5-min rate (×5), so it's the same scale as rawDelta5 —
            // 0.8 here means 0.8 mmol/5min, not 0.8 mmol in one minute.
            val lastCarbMin = minutesSinceLastCarbs() ?: Int.MAX_VALUE
            val iobChange5 = totalIobAt(dateUtil.now()) - totalIobAt(dateUtil.now() - 5 * 60_000L)
            // While SMBs are stacking (avg gap <=70s over the last 5 min), raise the rise bar 10%
            // (x1.10) rather than blocking outright — the boost can still fire on a strong enough rise.
            val stackK = if (smbInterval5Sec() <= 70) 1.10 else 1.0
            // Keeps the iobChange5 U-thresholds calibrated in real terms as ApsAutoIsfSmbDeliveryBaseline
            // moves. 0.17 is the reference point: the device was actually delivering at 0.17 on
            // 2026-07-22, the data the 0.85/0.40 thresholds below were tuned from (commit
            // 347smbcriteriaFixed) — baseline has since dropped to 0.14 via the DelOff/baseline-preference
            // work, so the same real-world rise now delivers proportionally less insulin per SMB
            // (~x0.82 at 0.14) and would produce a smaller iobChange5 than the tuning assumed, making
            // these thresholds harder to clear than intended for identical underlying urgency. Only
            // applied to the IOB-quantity thresholds (0.85/0.40 here, 0.40 in Mild below); the rawDelta/d
            // mmol thresholds measure glucose movement, not insulin delivered, so scaling them by a
            // delivery ratio wouldn't be meaningful.
            val thresholdScale = deliveryBaseline / 0.17
            // d/iobChange5 loosened from 0.7mmol/1.00 to 0.60mmol/0.85 (2026-07-24): an incident showed
            // rawDelta5 already unambiguously in bg3's territory (1.17-1.89mmol, well above the 0.8mmol
            // floor) for several straight minutes while d (peaked 0.69mmol) and iobChange5 (peaked 0.93)
            // sat just under the old bar the whole time — bg3 never fired, fast-rise caps kept cutting
            // delivery with nothing to counteract them, and mild only caught it minutes later once
            // rawDelta5 had settled back under its own upper cap. rawDelta5's own >=0.8mmol floor is
            // unchanged — this only closes the gap on bg3's smoothed-signal confirmation.
            // Same incident also showed rawDelta1 (the noisiest single-point signal — see mild's own
            // rawDelta1FloorOk comment) declining through the window (1.94->1.39->0.54->0.28mmol) even
            // while d/iobChange5/rawDelta5 all confirmed a genuine rise, so bg3's own unconditional
            // >=0.8mmol rawDelta1 floor would STILL have blocked it even after the d/iobChange5 fix
            // above. Mirrors mild's exact exception: fully dropped below 9.0mmol, reduced to >=0.25mmol
            // (4.5*stackK) at/above it — same threshold values as mild's, since it's the identical
            // underlying noise problem, not a new one needing separate justification.
            val rawDelta1FloorOkBg3 = g < 162.1 /* 9.0 mmol */ || rawDelta1 >= 4.5 * stackK
            // Delivery-suppressed bypass: if a fast-rise cap has throttled SMB to ~zero (0-1 delivered
            // in the last 5 min) while raw signals are unambiguously still confirming a rise, don't let
            // the resulting stalled/negative iobChange5 — an artifact of the suppression itself, not
            // evidence the rise resolved — block the boost. Raw-only, no `d`: `d` is itself downstream
            // of how much insulin has actually been delivered, so requiring it here just re-imports the
            // same lag this bypass exists to route around. Direct numbers, not stackK-scaled: stackK is
            // provably always 1.0 whenever smbCount5Min() <= 1 (smbInterval5Sec() can't compute an
            // interval — and so can't detect stacking — with fewer than 2 SMBs in the window), so
            // scaling by it here would be dead weight implying a scaling that can never actually apply.
            val deliverySuppressedBg3 = smbCount5Min() <= 1 && rawDelta5 >= 14.4 && rawDelta1 >= 14.4
            // Window extended 22:00 -> end of day (2026-08-14): a real event showed bg3's own signal
            // thresholds satisfied at 9:57pm, with iobChange5 closing fast, only to be cut off by the
            // old 22:00 boundary before it could fire -- the rise then ran uncontested another 30+ min.
            // Later meals are common enough that the boost shouldn't structurally stop working for them.
            // Safety is NOT reopened by this: the cumulative 30-min/10-min SMB caps in
            // DetermineBasalAutoISF.kt (built from the Aug 6-7 hypo incident) trim/zero microBolus
            // AFTER this fires regardless of time of day -- they're what actually prevents stacking, not
            // this window. Deliberately NOT extended into the 00:00-06:00 overnight guard territory
            // (NightIobCeiling, MJrec, etc.) -- see isTimeBetween()'s own doc comment for why endH=0/
            // endM=0 here means "runs to end of day," not "wraps past midnight."
            val bg3 = isTimeBetween(8, 30, 0, 0)
                && lastBolusMin >= 120 && lastCarbMin >= 120
                && ((iobChange5 > 0.85 * stackK * thresholdScale && d >= 10.8 * stackK /* 0.60 mmol */) || deliverySuppressedBg3)
                && rawDelta5 >= 14.4 * stackK /* 0.8 mmol */ && rawDelta1FloorOkBg3
                && g <= 171.2 /* 9.5 mmol: no strong (bg3) boost above this */
                && profileName != preferences.get(StringKey.ApsAutoIsfLowProfileName)                  // not on the MJ/night profile
                && !mjActive()         // and MJ must not be in an active cycle (was: == NOMJremains)
                // Cross-cooldown with mild: lastBolusMin/lastCarbMin only track manual boluses (BS.Type.NORMAL),
                // never SMB — so mild firing doesn't touch that gate and couldn't otherwise block bg3 here.
                // Without this, bg3's own IOB bump from a mild fire ~minutes ago could sit inside bg3's next
                // 5-min iobChange5 window and look like fresh evidence for a second (redundant) boost.
                // Matches mild's own 10-min self-throttle, so both blocks reason about the same window.
                && readyToRun("BolusGivenMild", 10)
                // Movement guard: don't boost while actively walking/exercising — both a sensor-artifact
                // risk (movement can distort readings) and a hypo-risk multiplier (activity raises
                // insulin sensitivity), so a big boost is specifically the wrong response right then.
                && recentSteps5Minutes <= 100 && recentSteps30Minutes <= 200
                // Steps60 guard: same movement-risk reasoning as the 5m/30m checks above, extended to a
                // full hour so a longer walk/activity session still blocks the boost even once the
                // shorter windows have quieted back down.
                && recentSteps60Minutes < 300
                // Long-smoothed-avg-delta floor: glucoseStatus.longAvgDelta averages the ~17.5-42.5 min-old
                // readings (DeltaCalculator.kt) — requiring it above 7.2 mg/dL (0.4mmol) confirms the rise
                // isn't just a short-lived spike riding on an otherwise flat/falling longer trend.
                && glucoseStatus.longAvgDelta > 7.2 /* 0.4 mmol */
            //WAS && iobChange5 > 0.80 && d >= 1.8 /* 0.1 mmol */ && rawDelta5 >= 3.6 /* 0.2 mmol */
            if (bg1 || bg2 || bg3) {
                val bBlock = if (bg1) "1" else if (bg2) "2" else "3"
                markRun("BolusGiven")
                // Extra bg3-only marker (bg1/bg2 share the "BolusGiven" key): the fast-rise-caps bypass
                // keys on "mild or bg3 fired within 30 min", and must NOT extend to bg1/bg2 fires.
                if (bBlock == "3") markRun("BolusGivenBg3")
                sendSms("BolusGiven71 [b$bBlock]: g=${String.format(Locale.getDefault(), "%.1f", g / 18.016)} iobTH=$iobTH")
                cancelCurrentTempTarget()
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 71)
                switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfStandardProfileName), 30)
                setBgAccelIsfWeight(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal))
                addCarePortalNote("Giv-$bBlock")
                setAutomationState("Profile", "Bolus")
                preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh))
                // "Strong" boost ratio is derived from the mild-boost base, not set independently —
                // see ApsAutoIsfMildBoostRatio's doc comment.
                setSmbDeliveryRatio(preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio) + 0.03)
                                            // strengthen SMB delivery during the post-bolus boost;
                                            // recovery/protective autos restore it to baseline (see below)
                startProfilePercentFor(110, 2, preferences.get(StringKey.ApsAutoIsfStandardProfileName))//WAS duration = 10,
                startTempTargetIfNeeded(75.7 /* 4.2 mmol */, 2)//WAS duration = 5,
            }
        }

        // --- BolusGivenMild: gentle sibling of BolusGiven bg3. Strengthens SMB delivery on a *mild*
        // delivery-driven rise (much lower gates), WITHOUT boosting the profile — it holds the daytime
        // 5.0 target with a 2-min TT (which doubles as the timer for the delivery-ratio reset above) and
        // raises the SMB delivery ratio to the configurable mild-boost base. Leaves iobTH / acce / ppWeight / profile untouched.
        // Own 10-min throttle, independent of bg1/2/3. rawDelta5 capped BELOW bg3's 0.8mmol threshold so
        // the two are mutually exclusive (one delta value can't satisfy both), preventing a same-loop
        // double-fire (the no-TT precondition can't catch bg3's async-inserted TT within the same loop).
        // ApsAutoIsfBoostAutomationsEnabled: same combined master switch as BolusGiven bg1/2/3 above.
        if (profile_percentage == 100 && activeTtMgdl() == null
            && preferences.get(BooleanKey.ApsAutoIsfBoostAutomationsEnabled)
            && readyToRun("BolusGivenMild", 10)) {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            val lastBolusMin = minutesSinceLastNormalBolus() ?: Int.MAX_VALUE
            val lastCarbMin = minutesSinceLastCarbs() ?: Int.MAX_VALUE
            val iobChange5 = totalIobAt(dateUtil.now()) - totalIobAt(dateUtil.now() - 5 * 60_000L)
            val rawDelta5 = rawDelta5MinMgdl() ?: -9999.0
            val rawDelta1 = rawDelta1MinMgdl() ?: -9999.0
            // While SMBs are stacking (avg gap <=70s), shift the whole band up 10% (x1.10) rather than
            // blocking. The upper cap scales too, so mild and bg3 stay complementary at 14.4*stackK.
            val stackK = if (smbInterval5Sec() <= 70) 1.10 else 1.0
            // See bg3's identical thresholdScale comment above — same reasoning (0.17 reference point),
            // applied to mild's own 0.40 iobChange5 threshold below.
            val thresholdScale = deliveryBaseline / 0.17
            // rawDelta1's FLOOR is the noisiest single gate here — a single-minute raw sample can dip
            // negative even mid-genuine-rise (observed: rΔ1 -0.28 while Δ/SΔ/rΔ5/IOBΔ5 all confirmed a
            // rise), vetoing an otherwise well-confirmed fire. Below 9.0 mmol that floor is dropped
            // entirely; the UPPER cap (< 14.4*stackK, keeping mild out of bg3's territory) still always
            // applies, so mutual exclusivity with bg3 is preserved either way.
            val rawDelta1FloorOk = g < 162.1 /* 9.0 mmol */ || rawDelta1 >= 4.5 * stackK
            // Delivery-suppressed bypass — same reasoning as bg3's mirror above: raw-only (no `d`,
            // which lags behind suppressed delivery itself), direct numbers (not stackK-scaled, since
            // stackK is provably always 1.0 whenever smbCount5Min() <= 1). Keeps mild's own lower floor
            // (5.4mg/dL/0.30mmol, matching the main fire condition's lowered threshold above) and upper
            // cap (<14.4) so it still can't overlap bg3's territory.
            val deliverySuppressedMild = smbCount5Min() <= 1 && rawDelta5 >= 5.4 && rawDelta5 < 14.4 && rawDelta1 < 14.4
            // Window extended to end of day, same reasoning/date as bg3's identical change above.
            val fire = isTimeBetween(8, 30, 0, 0)
                && lastBolusMin >= 120 && lastCarbMin >= 120
                && ((iobChange5 > 0.40 * stackK * thresholdScale && d >= 5.4 * stackK /* 0.30 mmol; AAPS smoothed-delta confirmation — lowered from 0.35mmol for earlier detection */) || deliverySuppressedMild)
                && rawDelta5 >= 5.4 * stackK /* 0.30 mmol — lowered from 0.35mmol for earlier detection */ && rawDelta5 < 14.4 * stackK /* bg3 owns >= this */
                && rawDelta1FloorOk && rawDelta1 < 14.4 * stackK /* same upper band as rawDelta5 */
                && profileFunction.getProfileName() != preferences.get(StringKey.ApsAutoIsfLowProfileName)   // not on the MJ/night profile
                && !mjActive()               // and MJ must not be in an active cycle (was: == NOMJremains)
                // Cross-cooldown with bg3: same reasoning as bg3's mirror check above — lastBolusMin/
                // lastCarbMin don't see either automation's own SMB delivery, so without this a bg3 fire's
                // IOB bump could sit inside mild's iobChange5 window and look like a fresh rise. 10 min
                // matches bg3's own self-throttle.
                && readyToRun("BolusGivenBg3", 10)
                // Movement guard: same reasoning as bg3's mirror check above.
                && recentSteps5Minutes <= 100 && recentSteps30Minutes <= 200
                // Sub-7.5mmol delivery-rate ceiling, added 2026-08-14 after two real hypos (4.4mmol and
                // a sustained 4.5mmol) both traced to this exact mechanism: BG still under 7.5mmol (not
                // genuinely high), a strong rise confirmed, mild fires and keeps re-arming every couple
                // minutes, and iobChange5 climbs well past what the eventual (modest) peak BG actually
                // needed by the time delivery caught up. The existing 10-min/30-min cumulative caps in
                // DetermineBasalAutoISF.kt only trim AFTER the fact; this stops mild from re-arming in
                // the first place once delivery is already running hot while BG hasn't earned it yet.
                // 0.8U/5min is anchored on both real events: episode 1 peaked ~0.91U/5min (this would
                // have trimmed its last few minutes), episode 2 spiked to ~1.7-1.8U/5min in a single
                // cycle (this would have caught it almost immediately). Only applies below 7.5mmol —
                // above that, bg3's own territory takes over and this guard doesn't apply at all. First
                // cut, not yet device-verified against a case that needed the full-strength tier and
                // would have been wrongly capped by this -- watch for that before trusting the number.
                && !(g < 135.1 /* 7.5 mmol */ && iobChange5 > 0.8)
            if (fire) {
                // Tiered delivery boost: lower BGL (caught the rise earlier, more headroom) -> stronger
                // response; at/above 9.0 mmol, the unadjusted base. Tiers are relative bumps on top of
                // the configurable base (ApsAutoIsfMildBoostRatio), not independent absolutes — raising
                // or lowering the base shifts all three together.
                val mildBase = preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio)
                val deliveryRatio = when {
                    g < 135.1 /* 7.5 mmol */ -> mildBase + 0.05
                    g < 162.1 /* 9.0 mmol */ -> mildBase + 0.02
                    else                     -> mildBase
                }
                setSmbDeliveryRatio(deliveryRatio)               // stronger SMBs; the no-TT reset restores baseline
                startTempTargetIfNeeded(90.1 /* 5.0 mmol */, 2)  // 2-min target/timer; leaves profile at 100%
                // Below 5.9mmol, the separate "no COB + BG under target+offset -> zero SMB" gate in
                // DetermineBasalAutoISF can still block SMB outright regardless of delivery ratio — zero
                // the offset for this same 2-min window so the boost above isn't wasted. Not applied at/above
                // 5.9mmol: there's more headroom above target there already, so the gate is less likely to bind.
                if (g < 106.2 /* 5.9 mmol */) {
                    preferences.put(BooleanKey.ApsAutoIsfMildOffsetZeroActive, true)
                }
                preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh))
                sendSms("BolusGivenMild: g=${String.format(Locale.getDefault(), "%.1f", g / 18.016)}")
                addCarePortalNote("BMild")
                markRun("BolusGivenMild")
            }
        }

        // --- BolusGivenMildFailsafe: safety-net sibling of BolusGivenMild for when SMBs simply haven't
        // been delivering during a clearly-confirmed rise (sensor dropout, pod disconnect, etc.) -- not
        // a delivery-ratio tuning scenario, a "the loop isn't acting at all" one. Requires ALL of Delta,
        // SDelta, and AAPS's own longAvgDelta to independently confirm the rise (stricter than
        // BolusGivenMild's own OR-based gate), near-zero IOB (nothing already in the pipe), and genuine
        // SMB silence over a longer window (smbCount20Min() == 0, not the 5-min signal
        // deliverySuppressedMild uses -- that shorter window can just be a normal gap between doses).
        // Restricted to 09:00-21:00 (narrower than BolusGivenMild's own 8:30-22:00) and, like it, no
        // separate enable toggle -- shares ApsAutoIsfBoostAutomationsEnabled with its siblings, since
        // this should fire rarely and a dedicated switch would be one more thing to remember to turn on.
        // Keeps the same recent-bolus/carb gate as regular BolusGivenMild.
        if (profile_percentage == 100 && activeTtMgdl() == null
            && preferences.get(BooleanKey.ApsAutoIsfBoostAutomationsEnabled)
            && isTimeBetween(9, 0, 21, 0)
            && readyToRun("BolusGivenMildFailsafe", 10)) {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            val sd = glucoseStatus.shortAvgDelta
            val ld = glucoseStatus.longAvgDelta
            val iobNow = totalIobAt(dateUtil.now())
            val lastBolusMin = minutesSinceLastNormalBolus() ?: Int.MAX_VALUE
            val lastCarbMin = minutesSinceLastCarbs() ?: Int.MAX_VALUE
            val fire = g > 117.0 /* 6.5 mmol */
                && d >= 5.4 /* 0.3 mmol Delta */
                && sd >= 5.4 /* 0.3 mmol SDelta */
                && ld >= 3.6 /* 0.2 mmol longAvgDelta */
                && iobNow <= 0.20
                && smbCount20Min() == 0                  // genuine silence, not just a normal gap
                && lastBolusMin >= 120 && lastCarbMin >= 120
                && readyToRun("BolusGivenBg3", 10)        // cross-cooldown, same as BolusGivenMild
                && readyToRun("BolusGivenMild", 10)       // and with BolusGivenMild itself
                && recentSteps5Minutes <= 100 && recentSteps30Minutes <= 200
            if (fire) {
                val mildBase = preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio)
                val deliveryRatio = when {
                    g < 135.1 /* 7.5 mmol */ -> mildBase + 0.05
                    g < 162.1 /* 9.0 mmol */ -> mildBase + 0.02
                    else                     -> mildBase
                }
                setSmbDeliveryRatio(deliveryRatio)
                startTempTargetIfNeeded(90.1 /* 5.0 mmol */, 2)
                if (g < 106.2 /* 5.9 mmol */) {
                    preferences.put(BooleanKey.ApsAutoIsfMildOffsetZeroActive, true)
                }
                preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh))
                sendSms("BolusGivenMildFailsafe: g=${String.format(Locale.getDefault(), "%.1f", g / 18.016)} IOB=${round(iobNow, 2)} (no SMB in 20min despite confirmed rise)")
                addCarePortalNote("BMildFS")
                markRun("BolusGivenMildFailsafe")
            }
        }

        // --- Boost-trigger debug telemetry: read-only mirror of the bg3/mild fire conditions above,
        // logged every cycle regardless of whether their own guards/throttles would actually let them
        // run — so a near-miss (and which specific sub-condition blocked it) is visible in AutoISF script
        // debug, not just an actual fire. Recomputes everything itself from scratch; shares no state with
        // the real guarded blocks and cannot affect dosing.
        run {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            val rawDelta5 = rawDelta5MinMgdl() ?: -9999.0
            val rawDelta1 = rawDelta1MinMgdl() ?: -9999.0
            val iobChange5 = totalIobAt(dateUtil.now()) - totalIobAt(dateUtil.now() - 5 * 60_000L)
            val stackK = if (smbInterval5Sec() <= 70) 1.10 else 1.0
            val thresholdScale = deliveryBaseline / 0.17
            val lastBolusMin = minutesSinceLastNormalBolus() ?: Int.MAX_VALUE
            val lastCarbMin = minutesSinceLastCarbs() ?: Int.MAX_VALUE
            val profileName = profileFunction.getProfileName()
            val outerGuardOk = profile_percentage == 100 && activeTtMgdl() == null
                && preferences.get(BooleanKey.ApsAutoIsfBoostAutomationsEnabled)
            val movementOk = recentSteps5Minutes <= 100 && recentSteps30Minutes <= 200
            val rawDelta1FloorOkBg3 = g < 162.1 /* 9.0 mmol */ || rawDelta1 >= 4.5 * stackK
            val deliverySuppressedBg3 = smbCount5Min() <= 1 && rawDelta5 >= 14.4 && rawDelta1 >= 14.4
            val bg3Would = outerGuardOk && readyToRun("BolusGiven", 10) && isTimeBetween(8, 30, 0, 0)
                && lastBolusMin >= 120 && lastCarbMin >= 120
                && ((iobChange5 > 0.85 * stackK * thresholdScale && d >= 10.8 * stackK) || deliverySuppressedBg3)
                && rawDelta5 >= 14.4 * stackK && rawDelta1FloorOkBg3
                && g <= 171.2 && profileName != preferences.get(StringKey.ApsAutoIsfLowProfileName) && !mjActive()
                && readyToRun("BolusGivenMild", 10) && movementOk
                && recentSteps60Minutes < 300 && glucoseStatus.longAvgDelta > 7.2 /* 0.4 mmol */
            val rawDelta1FloorOkMild = g < 162.1 /* 9.0 mmol */ || rawDelta1 >= 4.5 * stackK
            val deliverySuppressedMild = smbCount5Min() <= 1 && rawDelta5 >= 5.4 && rawDelta5 < 14.4 && rawDelta1 < 14.4
            val mildWould = outerGuardOk && readyToRun("BolusGivenMild", 10) && isTimeBetween(8, 30, 0, 0)
                && lastBolusMin >= 120 && lastCarbMin >= 120
                && ((iobChange5 > 0.40 * stackK * thresholdScale && d >= 5.4 * stackK) || deliverySuppressedMild)
                && rawDelta5 >= 5.4 * stackK && rawDelta5 < 14.4 * stackK
                && rawDelta1FloorOkMild && rawDelta1 < 14.4 * stackK
                && profileName != preferences.get(StringKey.ApsAutoIsfLowProfileName) && !mjActive()
                && readyToRun("BolusGivenBg3", 10) && movementOk
                && !(g < 135.1 && iobChange5 > 0.8) // mirrors mild's own new sub-7.5mmol rate ceiling above
            val mildBase = preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio)
            val mildDeliveryRatioWould = when {
                g < 135.1 /* 7.5 mmol */ -> mildBase + 0.05
                g < 162.1 /* 9.0 mmol */ -> mildBase + 0.02
                else                     -> mildBase
            }
            consoleError.add("BoostDebug settings: deliveryBaseline=${round(deliveryBaseline, 2)} mildBase=${round(mildBase, 2)} hardStackTarget=${round(hardStackTarget, 2)} currentRatio=${round(smb_delivery_ratio, 2)} thresholdScale=${round(thresholdScale, 2)} ;;")
            consoleError.add("BoostDebug stacking: smbStacking=$smbStacking smbInterval5Sec=${round(smbInterval5Sec(), 1)} smbCount5Min=${smbCount5Min()} stackK=${round(stackK, 2)} ;;")
            consoleError.add("BoostDebug signals: g=${convert_bg(g)} d=${deltaForDisplay(d)} rawDelta5=${deltaForDisplay(rawDelta5)} rawDelta1=${deltaForDisplay(rawDelta1)} iobChange5=${round(iobChange5, 2)} lastBolusMin=$lastBolusMin lastCarbMin=$lastCarbMin longAvgDelta=${deltaForDisplay(glucoseStatus.longAvgDelta)} recentSteps60Minutes=$recentSteps60Minutes ;;")
            consoleError.add("BoostDebug bg3: rawDelta1FloorOk=$rawDelta1FloorOkBg3 deliverySuppressed=$deliverySuppressedBg3 wouldFire=$bg3Would ;;")
            consoleError.add("BoostDebug mild: rawDelta1FloorOk=$rawDelta1FloorOkMild deliverySuppressed=$deliverySuppressedMild deliveryRatioWould=${round(mildDeliveryRatioWould, 2)} wouldFire=$mildWould ;;")
        }

        // --- TT 5.7 reversal block: replaces TToff2/3/4/5 and HypoTTOff1 automations, plus
        // TT5.8New1/2/3 — screenshotted as "TT=5.8mmol" but ported here against the same 5.7 mmol
        // guard per user confirmation (nothing in this file ever sets a 5.8 mmol TT; CarbsStopTT5.7's
        // own cb3 branch already treats 102.7-104.5 mg/dL, i.e. 5.7-5.8 mmol, as the same TT). ---
        // Primary guard: activeTtMgdl() must be ~5.7 mmol/L. Once TT is cancelled the guard fails,
        // so these conditions self-prevent re-firing without needing readyToRun() throttle.
        // All glucose/delta thresholds in mg/dL; originals in mmol/L in comments.
        // 5-min floor throttle added on top of the TT-existence self-guard (see readyToRun() usage note).
        run {
            if (!readyToRun("TT57Reversal", 5)) return@run
            val ttMgdl = activeTtMgdl() ?: return@run
            // Guard: only act on the 5.7 mmol TT. Tolerance is 0.1mmol, which intentionally reaches
            // exactly to 5.8mmol (5.7 and 5.8 are 0.1mmol apart) — CarbsStopTT5.7's own cb3 branch
            // already treats them as the same TT, so TT5.8New1/2/3 below fold into this same guard.
            if (kotlin.math.abs(ttMgdl - mmolToMgdl(5.7)) > mmolToMgdl(0.1)) return@run

            val g   = glucoseStatus.glucose
            val d   = glucoseStatus.delta
            val sd  = glucoseStatus.shortAvgDelta
            val ld  = glucoseStatus.longAvgDelta
            val iob = iobData.iob
            val cob = mealData.mealCOB

            // TToff2 — loosest: any stabilisation ≥ 6.0 (earliest exit)
            val off2 = g >= 108.1 /* 6.0 */ && d >= -4.50 /* -0.25 */ && sd >= -4.50

            // TToff3 — plateau-into-rise: same BGL/IOB/COB floor, but now requires an actual
            // rise (was a flat ±0.10 band). Both the Libre raw 5-min delta and the AAPS delta
            // must be ≥ +0.10 mmol, so a flat/falling low no longer reverses the TT — this is
            // what breaks the Set50↔TToff flap at ~4.5–4.7.
            val off3 = cob <= 4.0 && g >= 81.1 /* 4.5 */ && iob <= 0.8 &&
                (rawDelta5MinMgdl() ?: -9999.0) >= 1.8 /* Libre 5-min ≥ +0.10 */ &&
                d >= 1.8 /* Delta ≥ +0.10 */

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

            // TT5.8New1 — DISABLED. Was a "falling catch" (g <= 9.0 && d <= -0.1) that cancelled the
            // 5.7 TT whenever BGL was dropping below 9.0 — but Skittles *sets* that TT precisely when
            // BGL is low and falling, so new1 cancelled the protective TT the same cycle Skittles set
            // it, driving the TT3<->COff1 loop and the 50%<->100% profile flap. Removed for now.

            // TT5.8New2 — high-G, low-IOB/COB catch: no delta requirement, covers falls steeper than
            // off2's -0.25 floor once insulin/carbs are no longer driving anything.
            val new2 = g >= 147.7 /* 8.2 */ && iob <= 1.6 && cob <= 4.0

            // TT5.8New3 — mid-G, very-low-IOB/COB catch: like off3 but without requiring flatness.
            val new3 = g >= 117.1 /* 6.5 */ && iob <= 0.8 && cob <= 4.0

            // MildConfirmed — BolusGivenMild's own full fire condition, recomputed here since Mild can't
            // run while this TT is active (its outer gate requires activeTtMgdl()==null). Reuses every
            // guard from Mild's own block (not just the delta threshold) — raw delta bounds, iobChange5,
            // bolus/carb timing, MJ state, cross-cooldown with bg3, movement guard — on the reasoning
            // that Mild's own strict multi-signal rise confirmation is itself adequate evidence the hypo
            // risk this TT was protecting against has passed. Cancelling here lets Mild actually fire
            // (delivery ratio + offset-zero) on the next loop cycle once activeTtMgdl() is clear.
            val mildLastBolusMin = minutesSinceLastNormalBolus() ?: Int.MAX_VALUE
            val mildLastCarbMin = minutesSinceLastCarbs() ?: Int.MAX_VALUE
            val mildIobChange5 = totalIobAt(dateUtil.now()) - totalIobAt(dateUtil.now() - 5 * 60_000L)
            val mildRawDelta5 = rawDelta5MinMgdl() ?: -9999.0
            val mildRawDelta1 = rawDelta1MinMgdl() ?: -9999.0
            val mildStackK = if (smbInterval5Sec() <= 70) 1.10 else 1.0
            val mildThresholdScale = deliveryBaseline / 0.17
            val mildRawDelta1FloorOk = g < 162.1 /* 9.0 mmol */ || mildRawDelta1 >= 4.5 * mildStackK
            val mildDeliverySuppressed = smbCount5Min() <= 1 && mildRawDelta5 >= 5.4 && mildRawDelta5 < 14.4 && mildRawDelta1 < 14.4
            val mildConfirmed = preferences.get(BooleanKey.ApsAutoIsfBoostAutomationsEnabled) &&
                isTimeBetween(8, 30, 0, 0) &&
                mildLastBolusMin >= 120 && mildLastCarbMin >= 120 &&
                ((mildIobChange5 > 0.40 * mildStackK * mildThresholdScale && d >= 5.4 * mildStackK) || mildDeliverySuppressed) &&
                mildRawDelta5 >= 5.4 * mildStackK && mildRawDelta5 < 14.4 * mildStackK &&
                mildRawDelta1FloorOk && mildRawDelta1 < 14.4 * mildStackK &&
                profileFunction.getProfileName() != preferences.get(StringKey.ApsAutoIsfLowProfileName) &&
                !mjActive() &&
                readyToRun("BolusGivenBg3", 10) &&
                recentSteps5Minutes <= 100 && recentSteps30Minutes <= 200 &&
                !(g < 135.1 && mildIobChange5 > 0.8) // mirrors mild's own new sub-7.5mmol rate ceiling above

            val which = when { off2 -> "off2"; off3 -> "off3"; off4 -> "off4"; off5 -> "off5"; off1 -> "off1"; else -> null }
            if (which != null) {
                cancelCurrentTempTarget()
                setBgAccelIsfWeight(0.50)
                applyCurrentProfileAt100()
                setAutomationState("LowBG", "NO50rec")
                sendSms("TT 5.7 ended [$which]: g=${String.format("%.1f", g / 18.016)} d=${String.format("%.2f", d / 18.016)}")
                addCarePortalNote("TToff-$which")
                aapsLogger.debug(LTag.APS, "TT reversal [$which]: g=${String.format("%.1f", g / 18.016)}mmol d=${String.format("%.2f", d / 18.016)} sd=${String.format("%.2f", sd / 18.016)} iob=${String.format("%.2f", iob)} cob=${cob.toInt()}")
                markRun("TT57Reversal")
            } else {
                // TT5.8New1/2/3's original action lists were just "Send SMS" + "Stop temp target" —
                // no acce/profile/state reset, no CarePortal note. Kept minimal to match exactly.
                // MildConfirmed reuses this same minimal style — it isn't a "hypo recovered" event like
                // off1-off5, just clearing the way for Mild's own (separately gated) boost to run.
                val whichNew = when { new2 -> "TT5.8New2"; new3 -> "TT5.8New3"; mildConfirmed -> "TT5.7MildOff"; else -> null }
                if (whichNew != null) {
                    cancelCurrentTempTarget()
                    sendSms(whichNew)
                    addCarePortalNote("TToff-${if (new2) "N2" else if (new3) "N3" else "Mld"}")   // careportal note (SMS-only before, so no careportal trail): logs the 5.7 TT reversal
                    markRun("TT57Reversal")
                }
            }
        }

        // --- Activity TT reversal block: TT6.0New1/TT6.0New2 — exit criteria for the manually-set
        // "Activity" temp target (GUI default ~6.8 mmol, sits inside this 6.5-7.6 mmol band). Nothing
        // in this file creates a TT in this range; it's set by hand via the GUI. Self-guarding like
        // the 5.7 TT block above: cancelling the TT fails the range guard next cycle. Original action
        // lists were just "Send SMS" + "Stop temp target" — kept minimal to match exactly.
        // 5-min floor throttle added on top of the TT-existence self-guard (see readyToRun() usage note).
        run {
            if (!readyToRun("ActivityTTReversal", 5)) return@run
            val ttMgdl = activeTtMgdl() ?: return@run
            if (ttMgdl < mmolToMgdl(6.5) || ttMgdl > mmolToMgdl(7.6)) return@run

            val g   = glucoseStatus.glucose
            val d   = glucoseStatus.delta
            val iob = iobData.iob
            val cob = mealData.mealCOB

            // TT6.0New1 — active meal recovery: high/rising BGL with carbs still onboard and IOB headroom
            val new1 = d >= 1.8 /* 0.1 */ && g >= 162.1 /* 9.0 */ && cob >= 4.0 && iob <= 2.0

            // TT6.0New2 — quiet/settled: moderate BGL, very low IOB, flat-or-rising, no COB requirement
            val new2 = g >= 144.1 /* 8.0 */ && iob <= 0.8 && d >= 0.0

            if (new1) {
                cancelCurrentTempTarget()
                sendSms("TT6.0New")
                addCarePortalNote("ActTToff1")   // careportal note (SMS-only before, so no careportal trail): logs the Activity (6.5-7.6) TT reversal
                markRun("ActivityTTReversal")
            } else if (new2) {
                cancelCurrentTempTarget()
                sendSms("TT6.0New2")
                addCarePortalNote("ActTToff2")   // careportal note (SMS-only before, so no careportal trail): logs the Activity (6.5-7.6) TT reversal
                markRun("ActivityTTReversal")
            }
        }

        // --- T8.0Off3ok: exit criteria for the manually-set "hyp" temp target (8.0mmol for 20 min
        // via GUI, per user confirmation — a separate TT from the 6.5-7.6mmol Activity band above).
        // The screenshot's "Temp Target" bound checks were a misread per user correction: the real
        // check is glucose reaching the TT's own 8.0mmol value, so this guards on the TT itself
        // (~8.0mmol, same ±1.8mg/dL tolerance style as the 5.7mmol block). The inner OR keeps its
        // original two-branch tradeoff — a lower glucose floor (6.5mmol) needs a steeper rise
        // (0.3mmol) to compensate, vs a higher floor (7.0mmol) only needing a gentler rise (0.2mmol)
        // — with no extra top-level glucose gate stacked on top of it. Original action list was just
        // "Send SMS" + "Stop temp target".
        // 5-min floor throttle added on top of the TT-existence self-guard (see readyToRun() usage note).
        run {
            if (!readyToRun("T80Off3ok", 5)) return@run
            val ttMgdl = activeTtMgdl() ?: return@run
            // Guard: only act on the 8.0 mmol "hyp" TT. Tolerance is the native engine's own
            // validated 0.001 mg/dL constant (Comparator.check's default), not 0.001 mmol.
            if (!fuzzyEquals(ttMgdl, mmolToMgdl(8.0))) return@run

            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            val stepsOk = recentSteps60Minutes <= 1500 && recentSteps30Minutes <= 300 &&
                recentSteps5Minutes <= 50 && recentSteps15Minutes <= 500
            val riseOk = (g >= 126.1 /* 7.0 mmol */ && d >= 3.6 /* 0.2 mmol */) ||
                (g >= 117.1 /* 6.5 mmol */ && d >= 5.4 /* 0.3 mmol */)

            if (stepsOk && riseOk) {
                cancelCurrentTempTarget()
                sendSms("TT8.0lf")
                addCarePortalNote("TT8.0off")   // careportal note (SMS-only before, so no careportal trail): logs the 8.0 "hyp" TT reversal
                markRun("T80Off3ok")
            }
        }

        // --- 50pc makes5.7: safety TT when on 50% profile and BGL dropping below 5.0 mmol ---
        // Precondition: no TT active. Was purely self-guarding (relying on activeTtMgdl() != null
        // next cycle), but the TT write can lag behind the next loop cycle's read, letting this
        // re-fire within the same 5-min cycle before the guard takes effect. Added an explicit
        // 10-min readyToRun() throttle on top of the self-guard to cover that race.
        if (profile_percentage == 50 && activeTtMgdl() == null && readyToRun("50pcMakes5.7", 10)) {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            if (g < 90.1 && d <= -0.9) {   // < 5.0 mmol, delta <= -0.05 mmol
                startTempTargetIfNeeded(102.7, 150)   // 5.7 mmol for 150 min
                sendSms("50pc makes5.7: g=${String.format("%.1f", g / 18.016)} d=${String.format("%.2f", d / 18.016)}")
                addCarePortalNote("50pcTT")
                aapsLogger.debug(LTag.APS, "50pc makes5.7: g=${String.format("%.1f", g / 18.016)}mmol d=${String.format("%.2f", d / 18.016)}")
                markRun("50pcMakes5.7")
            }
        }

        // --- carbsStopTT1ok4.4: cancel very-low TT (<=4.4 mmol) when carbs active and rising ---
        // Self-guarding: cancels TT so tt<=79.3 is false next cycle.
        // 5-min floor throttle added on top of the self-guard (see readyToRun() usage note).
        run {
            if (!readyToRun("CarbsStopTT1", 5)) return@run
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
                markRun("CarbsStopTT1")
            }
        }

        // --- CarbsStopTT5.7: cancel 5.7 mmol TT when carbs up or recent bolus and BGL stable/rising ---
        // Self-guarding: cancels TT so TT range checks fail next cycle.
        // 5-min floor throttle added on top of the self-guard (see readyToRun() usage note).
        run {
            if (!readyToRun("CarbsStopTT57", 5)) return@run
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
                    markRun("CarbsStopTT57")
                }
            }
        }

        // --- Usual2forTH70: restores iobTH=70 and acce weight=0.50 when BGL has recovered ---
        // Replaces "Usual2forTH70 CurrProfReal0.35" automation.
        // Guard: profile at 100% (not in 50% state), iobTH still reduced (<70), no TT, BGL >= 5.5mmol.
        // Self-guarding: sets iobTH=70 so condition iobThresholdPercent<70 fails next cycle.
        // 5-min floor throttle added on top of the self-guard (see readyToRun() usage note).
        if (readyToRun("Usual2forTH", 5)
            && profile_percentage == 100
            && iobThresholdPercent < 70
            && activeTtMgdl() == null
            && glucoseStatus.glucose >= 99.1     // 5.5 mmol
            && checkAutomationState("Steroids", "Steroids Off")
        ) {
            val g   = glucoseStatus.glucose
            val d   = glucoseStatus.delta
            val sd  = glucoseStatus.shortAvgDelta
            val cob = mealData.mealCOB
            val steps60  = recentSteps60Minutes
            val steps180 = StepService.getRecentStepCount180Min()
            val iobTH = iobThresholdPercent
            // "iobTH==50" alone isn't proof the fall that caused it has actually stopped — Extra50%
            // (X50-N) can fire at any point up to 6.5mmol while still falling, which overlaps this
            // block's own >=5.5mmol floor. Requiring d/sd both >=0 confirms the fall has genuinely
            // stabilized (not just crossed the recovery BG floor mid-fall) before treating iobTH==50 as
            // recovery evidence — without this, Extra50%/Usual2forTH could ping-pong every ~5-10 min in
            // that overlap zone (Extra50% drops iobTH to 50, Usual2forTH immediately sees iobTH==50 +
            // BG>=5.5 and restores it, re-arming Extra50%, repeat). Only gates the iobTH==50 disjunct —
            // iobTH<=19 is a separate, more severe reduction from other automations, not what was
            // flip-flopping, so it's left alone.
            val stabilized = d >= 0.0 && sd >= 0.0
            // block 1: daytime 08:00–20:00, some activity, iobTH low or reduced
            val u2b1 = isTimeBetween(8, 0, 20, 0) &&
                (steps180 >= 10 || iobTH <= 19 || (iobTH == 50 && stabilized)) &&
                steps60 >= 50
            // block 2: day 09:01–20:00, iobTH at night/twilight level
            val u2b2 = isTimeBetween(9, 1, 20, 0) &&
                (iobTH <= 19 || (iobTH == 50 && stabilized))
            // block 3: 05:01–20:00, waking/rising BGL
            val u2b3 = isTimeBetween(5, 1, 20, 0) &&
                (cob >= 10.0 || steps60 >= 100 || g >= 153.1 || sd >= 18.0)  // 8.5mmol / 1.0mmol
            val u2block = when { u2b1->"1"; u2b2->"2"; u2b3->"3"; else->null }
            if (u2block != null) {
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 70)
                setBgAccelIsfWeight(0.50)
                setSmbDeliveryRatio(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline))   // daytime "back to usual" recovery restores delivery baseline
                preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal))   // restore ppWeight baseline
                setAutomationState("LowBG", "NO50rec")
                applyCurrentProfileAt100()
                sendSms("Usual2forTH [b$u2block]: g=${String.format("%.1f", g / 18.016)} iobTH=$iobTH")
                addCarePortalNote("UsuIP-$u2block")
                aapsLogger.debug(LTag.APS, "Usual2forTH block $u2block: g=${String.format("%.1f", g / 18.016)}mmol iobTH=$iobTH steps60=$steps60 steps180=$steps180 cob=${cob.toInt()} sd=${String.format("%.2f", sd / 18.016)}")
                markRun("Usual2forTH")
            }
        }

        // --- CarbsTHoff: sets iobTH to 70% when post-carb BGL is falling or mid-range anomaly ---
        // (was 50%; raised to 70 to match the daytime floor so it isn't just bumped up again).
        // 5-min floor throttle added on top of the preconditions (see readyToRun() usage note).
        if (readyToRun("CarbsTHoff", 5) && profile_percentage == 100 && activeTtMgdl() == null
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
                switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfStandardProfileName), 30)
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 70)
                preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal))
                setSmbDeliveryRatio(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline))   // daytime recovery restores delivery baseline
                setAutomationState("LowBG", "NO50rec")
                sendSms("CarbsTHoff [b$ctBlock]: g=${String.format("%.1f", g / 18.016)} iobTH=$iobTH")
                addCarePortalNote("COff1-$ctBlock")
                markRun("CarbsTHoff")
            }
        }

        // Code port of "PreSoakSENSOR24hrs": reminds to pre-soak a new sensor ~14.0 or ~14.5 days into
        // the current sensor's life. Two narrow ~0.1h match windows, each gated on cannula age <=80h
        // (skip if the pod is also near end of life). Live-pump-only, matching the original's note.
        if (readyToRun("PreSoakSensor24hrs", 15) && activePlugin.activePump !is VirtualPump) {
            val sensorH = hoursSinceLastSensorChange() ?: 0.0
            val cannulaH = hoursSinceLastCannulaChange() ?: 0.0
            val soakB1 = sensorH >= 336.0 && sensorH <= 336.1 && cannulaH <= 80.0
            val soakB2 = sensorH >= 348.0 && sensorH <= 348.1 && cannulaH <= 80.0
            if (soakB1 || soakB2) {
                sendSms("_____SOAK")
                uiInteraction.addNotification(id = 9003, text = "PreSoak24hrs", level = Notification.URGENT)
                addGraphAnnouncement("PreSoak24hrs")
                markRun("PreSoakSensor24hrs")
            }
        }

        // Code port of "SENSOR at 14.9 days": narrow ~0.1h match window, gated on cannula age <=80h.
        // Live-pump-only, matching the original's note.
        if (readyToRun("SensorS1hr", 15) && activePlugin.activePump !is VirtualPump) {
            val sensorH = hoursSinceLastSensorChange() ?: 0.0
            val cannulaH = hoursSinceLastCannulaChange() ?: 0.0
            if (sensorH >= 359.0 && sensorH <= 359.1 && cannulaH <= 80.0) {
                uiInteraction.addNotification(id = 9004, text = "_____S1hr", level = Notification.URGENT)
                addGraphAnnouncement("_____S1hr")
                sendSms("SENSOR at 14.9 days ? overlap")
                markRun("SensorS1hr")
            }
        }

        // Code port of "SENSOR at 14 days 22 hours due": narrow ~0.1h match window, gated on cannula
        // age <=80h. Live-pump-only, matching the original's note.
        if (readyToRun("SensorS2hr", 15) && activePlugin.activePump !is VirtualPump) {
            val sensorH = hoursSinceLastSensorChange() ?: 0.0
            val cannulaH = hoursSinceLastCannulaChange() ?: 0.0
            if (sensorH >= 357.9 && sensorH <= 358.0 && cannulaH <= 80.0) {
                uiInteraction.addNotification(id = 9005, text = "_____S2hr", level = Notification.URGENT)
                addGraphAnnouncement("_____S2hr")
                sendSms("SENSOR at 14 days 22 hours due")
                markRun("SensorS2hr")
            }
        }

        // Code port of "POD 78 hours": narrow ~0.1h match window during 08:00 AM-11:59 PM, permanently
        // switches to Current ProfileReal and flags Profile state PP130. Live-pump-only, matching the
        // original's note.
        if (readyToRun("Pod2", 10) && activePlugin.activePump !is VirtualPump) {
            val cannulaH = hoursSinceLastCannulaChange() ?: 0.0
            if (cannulaH >= 78.0 && cannulaH <= 78.1 && isTimeBetween(8, 0, 23, 59)) {
                uiInteraction.addNotification(id = 9006, text = "_____POD2", level = Notification.URGENT)
                addGraphAnnouncement("_____POD2")
                setAutomationState("Profile", "PP130")
                switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfStandardProfileName))
                sendSms("POD 78 hours")
                sendSmsToNumbers("POD 78 hours", StringKey.SmsPod2Numbers)
                markRun("Pod2")
            }
        }

        // Code port of "POD 79 hours": narrow ~0.1h match window during 07:00 AM-11:59 PM. Live-pump-only,
        // matching the original's note. (Original had a 2nd OR-branch, "Time BTW 12:03 AM & 12:03 AM",
        // which is a zero-width/dead window under isTimeBetween's semantics — dropped per instruction,
        // this now matches Pod2's single-AND-group structure.)
        if (readyToRun("Pod1", 10) && activePlugin.activePump !is VirtualPump) {
            val cannulaH = hoursSinceLastCannulaChange() ?: 0.0
            if (cannulaH >= 79.0 && cannulaH <= 79.1 && isTimeBetween(7, 0, 23, 59)) {
                uiInteraction.addNotification(id = 9007, text = "POD 79 h", level = Notification.URGENT)
                addGraphAnnouncement("POD 79 h")
                sendSms("_____POD1hr")
                markRun("Pod1")
            }
        }

        // Code port of "MJ recent or Steps CurrProf Acce" (title kept the stale "Steps" reference,
        // dropped here since the actual condition tree has no steps-count check at all — confirmed
        // against the current automation dialog). No live-pump gate: the original's Note field was
        // empty (no "not virtual pump" restriction specified for this one).
        // Throttle raised 5 -> 480 min (8h) to serve as this block's SELF-LATCH, replacing the removed
        // "!onCurrentProfile" gate. That gate was doing double duty: the switchProfileIfNeeded call below
        // used to satisfy it, which is what stopped the block re-firing every 5 min for the rest of the
        // 00:00-08:00 window. Dropping the profile switch (see below) removes that latch with it.
        // A settings-based latch (acce == 0.50 && iobTH == 70) was tried instead and is WRONG here: it
        // overlaps NightAcce (01:00-06:00), which deliberately writes acce 0.35 / iobTH 18 -- each block
        // would then falsify the other's latch and they would ping-pong those settings every 5 minutes
        // all night. 480 min means this can fire at most once per 00:00-08:00 window regardless of what
        // else rewrites those settings afterwards, which reproduces the old outcome exactly: MJrec applies
        // early, NightAcce overwrites later, and neither fights back.
        if (readyToRun("MJrecentCurrProfAcce", 480)) {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            val cannulaH = hoursSinceLastCannulaChange() ?: 0.0
            if ((g <= 144.1 /* 8.0 mmol */ || d <= 1.8 /* 0.1 mmol */)
                && checkAutomationState("Steroids", "Steroids Off")
                && cannulaH < 72.0
                && !checkAutomationState("MJ", "NOMJremains")
                && activeTtMgdl() == null
                && profile_percentage == 100
                && isTimeBetween(0, 0, 8, 0)) {
                // Profile switch now HP2-GATED rather than unconditional -- same rationale and same
                // fail-closed choice as EveningTH above (permanent switch, no revert before BasalUp's
                // 07:00 window, so skipping is far cheaper than switching unnecessarily). The acce-weight
                // and iobTH actions below still run unconditionally, so the MJ-night tuning this block
                // exists for is preserved whether or not a low is predicted.
                val hpMj = hypoPrediction2Mmol(g, glucoseStatus.shortAvgDelta, glucoseStatus.longAvgDelta, iobData.iob, mealData.mealCOB, bgAcce)
                if ((isTimeBetween(22, 0, 6, 0) || (hpMj != null && hpMj < 5.0)) && !rescueActive) switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfLowProfileName))
                sendSms("MJ recent CurrProf Acce HP2=${hpMj?.let { String.format("%.1f", it) } ?: "--"}")
                setBgAccelIsfWeight(0.50)
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 70)
                addCarePortalNote("MJrec")
                markRun("MJrecentCurrProfAcce")
            }
        }

        // Code port of "BasalUp": raises acce weight back to neutral (0.50) and switches to Current
        // ProfileReal when glucose is stable/rising outside a pod/MJ/afternoon guard window, low
        // activity, and currently on the Low profile. No live-pump gate: the original's Note field
        // was empty.
        if (readyToRun("BasalUp", 5)) {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            val cannulaH = hoursSinceLastCannulaChange() ?: 0.0
            val onCurrentProfile = profileFunction.getProfileName() == preferences.get(StringKey.ApsAutoIsfLowProfileName)
            val cannulaOrStateOk = cannulaH >= 72.0 || cannulaH <= 6.0 ||
                checkAutomationState("MJ", "NOMJremains") || isTimeBetween(12, 0, 18, 0)
            // Time gate REMOVED (was isTimeBetween(7, 0, 0, 0) = 07:00-23:59). This was the ONLY automated
            // route off the low/MJ-night profile, so anything that switched to it overnight kept you there
            // until morning with no recovery path -- the core of the "overnight corrections blocked"
            // problem. Left undamped deliberately: this block already self-limits hard, since it requires
            // onCurrentProfile (it can only ever undo a switch something else made), so with the HP2 < 5.0
            // gates now on EveningTH/MJrec/NightAcce/OffHighProf it should rarely have anything to undo.
            // The one pairing to watch is OffHighProf (01:00-06:00), which is this block's mirror image --
            // it switches TO low on a fall, this switches back on a rise -- so a wobbling BGL could
            // alternate them at the 5-min throttle. That is observable directly as alternating BsUp /
            // OffP-1 careportal notes; damping (longer overnight throttle, or a stronger rise than
            // d >= 0.2) is deliberately NOT pre-applied, to avoid adding another unvalidated constant
            // against an oscillation that may never materialise now the HP2 gates are in place.
            if (g >= 81.1 /* 4.5 mmol */
                && cannulaOrStateOk
                && recentSteps60Minutes <= 1000
                && recentSteps30Minutes <= 600
                && profile_percentage == 100
                && d >= 3.6 /* 0.2 mmol */
                // BLOCKED 22:00-06:00. Earlier today this block's original isTimeBetween(7, 0, 0, 0) gate
                // was removed so it could recover from an overnight low-profile switch -- but that was
                // before the four profile-switch blocks were made unconditional across the same hours,
                // and the two now directly contradict: they switch TO the weak profile, this switches
                // straight back on any rise. The Logs1 export caught it happening on 6 Aug -- Current
                // Profile at 23:01/23:02 with delta +0.12/+0.09, then ProfileReal at 23:08 as soon as
                // delta reached +0.22, two minutes before the SMB burst. So it was actively putting the
                // STRONGER profile in place exactly when BG was rising and SMBs were about to be sized,
                // which is the worst possible moment. Recovery from the weak profile is left to the
                // morning; overnight it must not undo the protection.
                && !isTimeBetween(22, 0, 6, 0)
                && onCurrentProfile) {
                switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfStandardProfileName))
                setBgAccelIsfWeight(0.50)
                sendSms("BasalUp Acce")
                addCarePortalNote("BsUp")
                markRun("BasalUp")
                // Arms a 5-min window (see determine_basal()'s basalUpOffsetZeroActive param) that lets
                // DetermineBasalAutoISF.kt force varOffset to 0 on cycles within it where bg is still under
                // targetBgOffset -- armed unconditionally here since BasalUp already requires g>=4.5mmol,
                // and the "under targetOffset" check itself needs determine_basal()'s own accurate
                // time-of-day-dependent value, not a re-derived approximation here.
                markRun("BasalUpOffsetZero")
            }
        }

        // Code port of "MoreMJ": advances MJ state to MJ3 when acce weight is very low, activity is
        // low, glucose is falling low on the 50% profile with no carbs and IOB still present. No
        // live-pump gate: the original's Note field was empty.
        // Outer window 06:00-00:00 (isTimeBetween(6,0,0,0) = 6am through end of day): MoreMJ should not
        // apply overnight 00:00-06:00 at all -- this is the structural fix for the flip against MJoff's
        // midnight block (00:00-01:00), since MoreMJ now simply cannot fire in that window regardless of
        // the noRecentHighTrigger OR-path below (which has no time-of-day/BG gate of its own).
        // Hysteresis: also locked out for 65 min after MJoff last fired (readyToRun on the "MJoff"
        // timestamp) -- kept as a second line of defense for MJoff's OTHER block (12:00-21:04, high BG),
        // which is inside this 06:00-00:00 window and so isn't covered by the gate above. Same pattern as
        // the 50SetRecent/PP50Off flap fix elsewhere in this file.
        if (readyToRun("MoreMJ", 5) && readyToRun("MJoff", 65) && isTimeBetween(6, 0, 0, 0)) {
            val acceW = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
            val lastBolusMin = minutesSinceLastNormalBolus() ?: Int.MAX_VALUE
            val existingConditionsMet = acceW <= 0.11
                && StepService.getRecentStepCount180Min() <= 400
                && recentSteps60Minutes <= 200
                && checkAutomationState("MJ", "NOMJremains")
                && isTimeBetween(10, 30, 22, 0)
                && glucoseStatus.glucose < 99.1 /* 5.5 mmol */
                && profile_percentage == 50
                && mealData.mealCOB <= 0.0
                && lastBolusMin > 210
                // Was checkAutomationState("BGLstate", "BGLlastLOW"). BGLstate is set to BGLlastLOW in
                // three places (GentleHypoRisk, AlarmHypo1, and one more) and cleared in NONE -- it is a
                // one-way latch, so after the first hypo alert the device ever fires it reads BGLlastLOW
                // permanently. As a "recent low" signal it is therefore a constant: always true once any
                // alert has fired, always false if none ever has. Either way it carried no information
                // here. Swapped to LowBG=50recent, which is genuinely maintained (set on lows, cleared by
                // Not50Recently on recovery, with its own anti-flap throttle) and is the same flag
                // BolusWizard and the new recent-low rebound guard in DetermineBasalAutoISF.kt both read,
                // so all three now agree on what "recent low" means. BGLstate itself is left in place
                // rather than repaired: adding a cleared-value would mean calling setState with a value
                // that may not be declared for it (values are configured at runtime in the
                // automation-state plugin, not in code) and setAutomationState does not catch the
                // IllegalStateException that an undeclared value raises.
                && checkAutomationState("LowBG", "50recent")
                && iobData.iob >= 0.2
            // Independent OR-path: no recent genuine high (raw Libre >12.0mmol within 48h) plus MJ
            // state still allowing it -- ignores all the other existingConditionsMet checks above.
            val noRecentHighTrigger = !recentLibreOver12(48) && checkAutomationState("MJ", "NOMJremains")
            if (existingConditionsMet || noRecentHighTrigger) {
                sendSms("MoreMJ")
                setAutomationState("MJ", "MJ3")
                addCarePortalNote("MoreMJ")
                markRun("MoreMJ")
            }
        }

        // Code port of "High6PP": brief 120% profile boost across 3 condition blocks when BGL is high
        // with controlled delta and a low/no temp target tolerance. Note: "TT now <=4.2 tolerant".
        // Precondition: profile=100%. Daytime-only outer gate (09:00-21:00) — each branch below still
        // has its own (narrower or equal) window, so this just bounds all three to the same daytime
        // range without changing their individual per-branch windows. 30-min floor throttle. Also
        // requires recentLibreOver12(48): this boost increases insulin delivery in response to a
        // perceived high, so it's gated on genuine confirmation (raw Libre >12.0mmol) within the last
        // 48h -- without that, a borderline/moderate reading isn't enough on its own to warrant it.
        if (readyToRun("High6PP", 30) && profile_percentage == 100 && isTimeBetween(9, 0, 21, 0) && recentLibreOver12(48)) {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            val sd = glucoseStatus.shortAvgDelta
            val ld = glucoseStatus.longAvgDelta
            val tt = activeTtMgdl() ?: Double.MAX_VALUE
            val h6b1 = g >= 144.1 /* 8.0 mmol */ && tt <= 77.5 /* 4.3 mmol */ && isTimeBetween(0, 0, 21, 0)
                && d >= 1.8 /* 0.1 mmol */ && d <= 5.4 /* 0.3 mmol */
            val h6b2 = g >= 126.1 /* 7.0 mmol */ && tt <= 75.7 /* 4.2 mmol */ && isTimeBetween(10, 0, 22, 0)
                && ld >= 0.0 && sd >= 0.0 && d >= 0.0 && d <= 5.4 /* 0.3 mmol */ && sd <= 5.4
            val h6b3 = g >= 108.1 /* 6.0 mmol */ && mealData.mealCOB >= 10.0 && tt <= 75.7 /* 4.2 mmol */
                && d <= 5.4 /* 0.3 mmol */ && d >= 1.8 /* 0.1 mmol */
            if (h6b1 || h6b2 || h6b3) {
                startProfilePercentFor(120, 5)
                preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh))
                sendSms("High6PP Acce")
                addCarePortalNote("P120")
                markRun("High6PP")
            }
        }

        // Code port of "High6PPoff": exits the 120% boost when either no TT exists at 120%, or
        // BGL/delta have stabilised in the evening window at 120%. No live-pump gate: the original's
        // Note field was empty.
        if (readyToRun("High6PPoff", 5)) {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            val off1 = profile_percentage == 120 && activeTtMgdl() == null
            val off2 = isTimeBetween(21, 0, 0, 0) && g <= 144.1 /* 8.0 mmol */
                && profile_percentage == 120 && d <= 3.6 /* 0.2 mmol */
            if (off1 || off2) {
                switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfStandardProfileName))
                sendSms("High6PPoff Acce")
                addCarePortalNote("off120")
                markRun("High6PPoff")
            }
        }

        // Code port of "PodChangeHighPP130": brief 130% profile boost around a pod change (fresh
        // <=2h or stale >=78h) when BGL is high and rising, 10am-6pm. Live-pump-only, matching the
        // original's note. Precondition: profile=100%. Also requires a genuine recent high (raw Libre
        // >12.0mmol within 48h) -- without that recent confirmation the boost isn't warranted.
        if (readyToRun("PodChangeHighPP130", 5) && activePlugin.activePump !is VirtualPump
            && profile_percentage == 100 && recentLibreOver12(48)) {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            val cannulaH = hoursSinceLastCannulaChange() ?: 0.0
            if (checkAutomationState("Profile", "PP130")
                && (cannulaH >= 78.0 || cannulaH <= 2.0)
                && g >= 144.1 /* 8.0 mmol */
                && d >= 3.6 /* 0.2 mmol */
                && cannulaH <= 80.0
                && isTimeBetween(10, 0, 18, 0)) {
                switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfStandardProfileName))
                setAutomationState("Profile", "C100")
                startProfilePercentFor(130, 60)
                preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh))
                sendSms("PodChangeHighPP130 Acce")
                addCarePortalNote("Pod130")
                markRun("PodChangeHighPP130")
            }
        }

        // Code port of "HighPP130Off": exits the 130%/110% boost across 3 exit conditions
        // (stabilised no-TT, falling with TT, or simply no-TT at all while boosted). Note: "or 110%".
        if (autoIsfValues.bgAcceleration < 2.0 && readyToRun("HighPP130Off", 2)) {
            // WAS if (autoIsfValues.bgAcceleration < 0.10 && readyToRun("HighPP130Off", 2)) {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            val cannulaH = hoursSinceLastCannulaChange() ?: 0.0
            val boosted = profile_percentage == 130 || profile_percentage == 110
            val off1 = cannulaH <= 78.0 && cannulaH >= 2.0 && g <= 180.2 /* 10.0 mmol */
                && boosted && activeTtMgdl() == null
            // off2: with a TT active, exit the boost once BGL is no longer clearly rising — on any of
            // AAPS delta < +0.1, acce weight < 0.1, or raw Libre 5-min delta < 0.2. (Glucose<=10 dropped.)
            val acceW = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
            // acceW < 0.099, not < 0.1: AlarmHypo sets acce to exactly 0.10 and 0.1 has no exact float
            // representation, so "< 0.1" is float-dependent at that value. 0.099 is deterministic and
            // mirrors how the native Comparator evaluates "IS_LESSER 0.1" (obj < 0.1 - 0.001 tolerance) —
            // it excludes the 0.10 AlarmHypo level and still catches the deep-protective 0.02 / 0.07 levels.
            val off2 = (d < 12.6 /* 0.7 mmol */ || acceW < 0.099 /* i.e. < 0.1, float-safe */ || (rawDelta5MinMgdl() ?: 9999.0) < 14.4 /* 0.8 mmol */)
                && boosted && activeTtMgdl() != null
            //WAS val off2 = (d < 1.8 /* 0.1 mmol */ || acceW < 0.1 || (rawDelta5MinMgdl() ?: 9999.0) < 3.6 /* 0.2 mmol */)
            //                 && boosted && activeTtMgdl() != null
            val off3 = activeTtMgdl() == null && boosted
            if (off1 || off2 || off3) {
                sendSms("HighPP130Off")
                setAutomationState("Profile", "C100")
                switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfStandardProfileName))
                addCarePortalNote("130Off")
                setAutomationState("LowBG", "NO50rec")
                markRun("HighPP130Off")
            }
        }

        // Code port of "AcceUp0.5": raises acce weight to its boosted level (ApsAutoIsfBgAccelWeightHigh,
        // not the resting baseline) when currently very low, BGL is stable/rising, no TT, and either MJ
        // is clear or steroids are on. No live-pump gate: the original's Note field was empty.
        if (readyToRun("AcceUp0.5", 5)) {
            val acceW = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
            // Daytime gate (8:00-22:00) ADDED -- the source automation had none. Overnight this block was
            // fighting every acce-lowering block in this file: OffHighProf (0.18), NightAcce (0.35),
            // EveningTH (0.45), MJrec and TwilightTH15Acce (0.50) all land at or under this block's
            // "acceW <= 0.5" trigger, so each deliberate overnight back-off immediately re-armed it. It
            // then raised acce to High, which armed RecentPodOff (acce == acceHigh && no TT), which reset
            // acce AND switched the profile to Standard -- which in turn re-armed OffHighProf's
            // "!onCurrentProfile" gate, producing the observed Acce -> OffP-1 -> Acce -> pTTOff cycle
            // repeating all night. Confining this to daytime leaves the overnight blocks' acce values
            // standing, which is what they were chosen for. Note it was already dormant on MJ-active
            // nights via the MJ/Steroids condition below; this extends that to non-MJ nights too.
            if (acceW <= 0.5
                && isTimeBetween(8, 0, 22, 0)
                && glucoseStatus.glucose >= 108.1 /* 6.0 mmol */
                && profile_percentage == 100
                && activeTtMgdl() == null
                && (checkAutomationState("MJ", "NOMJremains") || checkAutomationState("Steroids", "SteroidsON"))) {
                setBgAccelIsfWeight(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightHigh))
                sendSms("AcceUp")
                addCarePortalNote("Acce")
                markRun("AcceUp0.5")
            }
        }

        // OldPod2 disabled: its 130% profile boost was too strong. Keep its run-history name intact
        // elsewhere so RecentPodOff can still unwind a boost made by an older build after an upgrade.

        // Code port of "RecentPodOff": relaxes acce weight back to baseline once the RecentPod/OldPod2
        // safety TT has ended and acce weight is still at its boosted (ApsAutoIsfBgAccelWeightHigh)
        // level. No live-pump gate: the original's Note field was empty.
        if (readyToRun("RecentPodOff", 5)) {
            val acceW = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
            val acceHigh = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightHigh)
            // fuzzyEquals, not ==: acceHigh has no exact float representation at values like 0.95, so
            // "acceW == acceHigh" could read false after the round-trip and this recovery would never
            // fire (acce stuck at its boosted level). Same float trap as the DelOff reset.
            // Requires that a pod boost ACTUALLY happened, which this block never checked despite its
            // name and the comment above both describing it as undoing RecentPod/OldPod2. Its only real
            // condition was "acce is at High and no TT is running" -- a state AcceUp0.5 produces exactly
            // (it sets acce to High and starts no TT), so this fired immediately after every AcceUp0.5
            // and undid it. AcceUp0.5's effect could therefore never survive at all, and because this
            // block ALSO switches the profile to Standard, each spurious fire re-armed OffHighProf's
            // "!onCurrentProfile" gate -- the profile half of the overnight flip-flop. !readyToRun(k, 60)
            // reads as "k fired within the last 60 min" (same idiom as smbBoostRecent's bypass gate);
            // 60 min comfortably covers the short safety TT those two start plus its aftermath, while
            // still being far too short to catch an unrelated AcceUp0.5 hours later.
            val podBoostRecent = !readyToRun("RecentPod", 60) || !readyToRun("OldPod2", 60)
            if (fuzzyEquals(acceW, acceHigh) && activeTtMgdl() == null && podBoostRecent) {
                setBgAccelIsfWeight(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal))
                switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfStandardProfileName))
                sendSms("RecentPodOff Acce")
                addCarePortalNote("pTTOff")
                markRun("RecentPodOff")
            }
        }

        // Code port of "Exercise limit Acce": alerts when a fast rise coincides with high recent step
        // activity. No live-pump gate: the original's Note field was empty.
        if (readyToRun("ExerciseLimitAcce", 30)) {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            if (g >= 126.1 /* 7.0 mmol */ && d >= 7.2 /* 0.4 mmol */ && recentSteps60Minutes >= 1000) {
                uiInteraction.addNotification(id = 9008, text = "_____ST601k", level = Notification.URGENT)
                addGraphAnnouncement("_____ST601k")
                sendSms("Exercise limit Acce")
                markRun("ExerciseLimitAcce")
            }
        }

        // Code port of "RecentPod": brief 130% profile boost + 4.2mmol TT when cannula is very
        // fresh/stale, carbs are up, and BGL is rising with headroom on IOB. Live-pump-only, matching
        // the original's note. Preconditions: profile=100%, no TT. Also requires a genuine recent high
        // (raw Libre >12.0mmol within 48h) -- without that recent confirmation the boost isn't warranted.
        if (readyToRun("RecentPod", 5) && activePlugin.activePump !is VirtualPump
            && profile_percentage == 100 && activeTtMgdl() == null && recentLibreOver12(48)) {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            val cannulaH = hoursSinceLastCannulaChange() ?: 0.0
            if ((cannulaH <= 6.0 || cannulaH >= 48.0)
                && d >= 3.6 /* 0.2 mmol */
                && mealData.mealCOB >= 16.0
                && iobData.iob <= 4.0
                && g >= 108.0 /* 6.0 mmol */) {
                startProfilePercentFor(130, 5)
                startTempTargetIfNeeded(75.7 /* 4.2 mmol */, 5)
                setBgAccelIsfWeight(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightHigh))
                preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh))
                sendSms("RecentPod Acce")
                addCarePortalNote("RecPod")   // careportal note (SMS-only before, so no careportal trail): logs the 130% + 4.2 TT + boosted-acce fresh/stale-pod boost
                markRun("RecentPod")
            }
        }

        // Code port of "ExportSettingsPodActivation": triggers a real encrypted settings backup
        // (exportSettingsFor, faithfully mirroring ActionSettingsExport) when a new pod has been
        // activated since the last export — mirrors TriggerPodChange's own "Pod activated" check
        // (last CANNULA_CHANGE more recent than last SETTINGS_EXPORT). Live-pump-only, matching the
        // original's note.
        if (readyToRun("ExportSettingsPodActivation", 5) && activePlugin.activePump !is VirtualPump) {
            val lastPodChange = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)
            val lastSettingsExport = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SETTINGS_EXPORT)
            val podActivatedSinceExport = lastPodChange != null && lastSettingsExport != null
                && lastSettingsExport.timestamp < lastPodChange.timestamp
            if (podActivatedSinceExport) {
                sendSms("ExportSettingsPodActivation")
                exportSettingsFor("NewPod")
                switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfLowProfileName))
                cancelCurrentTempTarget()
                setAutomationState("Profile", "PP130")
                markRun("ExportSettingsPodActivation")
            }
        }

        // Code port of "EveningTH CurrProf 50_0.45": lowers acce weight/iobTH ceiling in the evening
        // while MJ is still clear, then flips to a higher iobTH floor once MJ goes active overnight.
        // No live-pump gate: the original's Note field described a recent MJ-state change, not a
        // virtual-pump restriction.
        // --- EveningIobCeiling: caps iobTH at 45% from 22:00 to midnight, independent of BGL direction ---
        // Motivated by 27 Jul 2026 21:48-21:59: nine SMBs ~60s apart took IOB 3.02 -> 4.56 at BG 10.7-11.3,
        // and BG then fell to 3.8 by 04:38. Nothing stopped it -- the iobTH actually in force was 6.65-7.32U
        // (~70-77% of max_iob), roughly 2U above where the harm occurred, and EveningTH could not fire
        // because all its branches gate on a LOW BGL (<=7.5mmol) or on 22:00+.
        //
        // Deliberately separate from EveningTH rather than raising that block's BGL guard: EveningTH also
        // sets acce 0.45 and (HP2-gated) switches to the low profile, and both of those are back-off actions
        // that would be wrong applied to a genuine high -- blunting the acceleration response and weakening
        // basal/ISF while BG is climbing risks trading an overnight low for an overnight high. This block
        // therefore touches iobTH and nothing else.
        //
        // A true CEILING, not a setter: gated on iobThresholdPercent > 45 so it only ever lowers. That
        // means it cannot fight the overnight blocks that set iobTH far lower (NightAcce 18, TwilightTH15
        // 15) by raising it back up. Window ends at midnight rather than running into the small hours,
        // specifically to avoid overlapping MJrec (00:00-08:00), which sets iobTH to 70 -- the two would
        // otherwise contradict each other every cycle in the 00:00-01:00 overlap.
        if (readyToRun("EveningIobCeiling", 5)
            && isTimeBetween(22, 0, 0, 0)
            && checkAutomationState("MJ", "NOMJremains")
            && iobThresholdPercent > 45
        ) {
            preferences.put(IntKey.ApsAutoIsfIobThPercent, 45)
            sendSms("EveningIobCeiling: iobTH ${iobThresholdPercent} -> 45")
            addCarePortalNote("EvCap")
            markRun("EveningIobCeiling")
        }

        // --- NightIobCeiling: caps iobTH at 18% from midnight to 06:00, independent of BGL ---
        // Companion to EveningIobCeiling above, same ceiling-not-setter shape (only ever lowers), but a
        // far tighter value because overnight there are no carbs to cover -- IOB that would be routine
        // after a meal is dangerous at 03:00.
        //
        // Motivated by the night of 6->7 Aug 2026, where BGL reached 3.5 and stayed there 04:00-05:30.
        // Two SMB bursts caused it: 23:10-23:18 (~1.50U, IOB 0.36 -> 2.00) and 01:08-01:15 (~1.30U, IOB
        // 1.43 -> 2.71), both at ~60s intervals with COB already decayed to 0 and BGL only 7.2-8.6.
        // NightAcce covers 01:00-06:00 and sets iobTH to 18 already, but could not fire for either burst
        // because it requires BGL <= 6.0mmol -- and BGL was 8.5. That BGL gate is the reason the overnight
        // protection was unreachable, which is why this block deliberately has NO BGL condition at all.
        // Starting at 00:00 rather than 01:00 also closes the midnight-to-01:00 gap.
        //
        // KNOWN CONFLICT, deliberate: HighNight00AM (01:00-05:45, 60-min throttle) sets iobTH to 51 to
        // permit correcting a genuine overnight high, and this will pull it back to 18 within ~5 min.
        // That is the intended precedence given the episode above -- an uncorrected overnight high is the
        // accepted cost of not repeating a sustained 3.5 -- but it does mean HighNight00AM is now largely
        // neutered between 01:00 and 05:45. Revisit that pairing if overnight highs start persisting.
        // Caps BOTH iobTH (18%) and acce weight (0.35) -- the two settings NightAcce would have lowered
        // had it been able to fire. Checked against the 6->7 Aug data: SmbRatio sat at 0.14 and ppWt at
        // 0.08 all night, already their configured baselines (ApsAutoIsfSmbDeliveryBaseline /
        // ApsAutoIsfPpWeightNormal), so resetting those two would be a no-op and they are deliberately
        // not touched here. acce weight was the one genuinely carried in elevated: 0.50 from 23:00 through
        // 02:20 -- i.e. across BOTH SMB bursts -- only falling to 0.07 after 02:40, once BGL was already
        // collapsing. At 0.50 the acceleration term amplifies the ISF response to a rise, and both bursts
        // occurred during rises (7.2->7.9 and 8.3->8.6), so it was actively compounding them.
        // Each cap is independent and only ever lowers, so neither can raise a value some other overnight
        // block has already set tighter (e.g. TwilightTH15Acce's iobTH 15).
        if (readyToRun("NightIobCeiling", 5) && isTimeBetween(0, 0, 6, 0)) {
            val nightAcceW = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
            val capIob = iobThresholdPercent > 18
            val capAcce = nightAcceW > 0.35
            if (capIob || capAcce) {
                if (capIob) preferences.put(IntKey.ApsAutoIsfIobThPercent, 18)
                if (capAcce) setBgAccelIsfWeight(0.35)
                sendSms("NightIobCeiling: iobTH ${iobThresholdPercent}->${if (capIob) "18" else "unchanged"} acce ${round(nightAcceW, 2)}->${if (capAcce) "0.35" else "unchanged"}")
                addCarePortalNote("NtCap")
                markRun("NightIobCeiling")
            }
        }

        if (readyToRun("EveningTH", 5)) {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            val lastBolusMin = minutesSinceLastNormalBolus() ?: Int.MAX_VALUE
            val onCurrentProfileEither = profileFunction.getProfileName() == preferences.get(StringKey.ApsAutoIsfLowProfileName) || profileFunction.getProfileName() == preferences.get(StringKey.ApsAutoIsfStandardProfileName)
            // TWO FIXES, both from the careportal trail on the night of 7->8 Aug 2026, which showed "Eve"
            // firing every 5-6 min from 23:08 to 00:18 and alternating with "NtCap" from midnight:
            //
            // 1) SELF-LATCH RESTORED. evB1 latched on "iobThresholdPercent < 50" back when this block set
            //    iobTH to 50 -- after firing, 50 < 50 was false and it stopped. Changing the action to 45
            //    (as a backstop) silently broke that: 45 < 45 is false but 45 < 50 is TRUE, so the block
            //    re-armed itself on its own output and re-fired every cycle for the rest of its window.
            //    The latch value must track whatever this block actually writes -- they are now both 45.
            //
            // 2) WINDOWS END AT MIDNIGHT (were 20:00-01:00 / 22:00-05:00). Even with the latch fixed, this
            //    overlapped NightIobCeiling (00:00-06:00, sets iobTH 18): NtCap would write 18, evB1 would
            //    then see 18 < 45 and write 45 straight back, NtCap would write 18 again, and so on every
            //    ~6 min. That was the observed Eve/NtCap alternation, and it is why iobTH still read 5.13
            //    at 00:20 instead of the intended 1.71. It resolved by itself at 01:00 only because evB1's
            //    old window happened to end there. Confining the evening blocks to before midnight gives
            //    NightIobCeiling sole ownership of 00:00-06:00.
            val evB1 = isTimeBetween(20, 0, 0, 0) && g <= 135.1 /* 7.5 mmol */ && iobThresholdPercent < 45
                && mealData.mealCOB <= 0.0 && d <= 3.6 /* 0.2 mmol */ && activeTtMgdl() == null
                && lastBolusMin >= 90 && !checkAutomationState("MJ", "MJ active")
            val evB2 = iobThresholdPercent >= 51 && g <= 135.1 /* 7.5 mmol */ && mealData.mealCOB <= 0.0
                && isTimeBetween(20, 0, 0, 0) && activeTtMgdl() == null && d <= -1.8 /* -0.1 mmol */
                && onCurrentProfileEither && lastBolusMin >= 90 && profile_percentage == 100
                && !checkAutomationState("MJ", "MJ active")
            val evB3 = isTimeBetween(22, 0, 0, 0) && g >= 122.5 /* 6.8 mmol */ && d >= 1.8 /* 0.1 mmol */
                && onCurrentProfileEither && lastBolusMin >= 90 && profile_percentage == 100
                && iobThresholdPercent >= 51 && checkAutomationState("MJ", "MJ active")
            if (evB1 || evB2 || evB3) {
                setBgAccelIsfWeight(0.45)
                // 50 -> 45 as a secondary backstop only (~4.28U at max_iob 9.5, vs 4.75 at 50). NOT the
                // primary fix for the 27 Jul 2026 evening stacking episode: this block's own BG gates
                // (evB1/evB2 need <=7.5mmol, evB3 needs 22:00+) meant it could not fire during that burst
                // at BG 10.7-11.3 anyway, so the lower value only takes effect in the low-side evening
                // cases where this automation already ran. The stacking itself is addressed by the
                // escalating anti-stack trim in DetermineBasalAutoISF.kt.
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 45)
                // Profile switch now HP2-GATED rather than unconditional. Parking on the low/MJ-night
                // profile from 20:00 was suppressing overnight corrections: the low profile's weaker
                // basal/ISF persists all night once switched, and BasalUp (the only switch back to
                // Standard) is gated from 07:00, so there was no automated route off it before morning.
                // Gating on a genuinely predicted low keeps the hypo protection this switch exists for
                // while stopping it parking there on quiet nights. Fails CLOSED (no switch) when HP2 is
                // unavailable -- opposite of OffHighProf's fail-open, and deliberately so: this switch is
                // permanent (duration 0) with no automatic revert before 07:00, so the cost of switching
                // when it wasn't warranted is much higher than skipping one evening adjustment.
                val hpEve = hypoPrediction2Mmol(g, glucoseStatus.shortAvgDelta, glucoseStatus.longAvgDelta, iobData.iob, mealData.mealCOB, bgAcce)
                // 22:00-06:00 makes this switch UNCONDITIONAL; outside those hours the HP2 < 5.0 gate still
                // applies. Same added condition on MJrec, NightAcce and OffHighProf. Reason: HP2 contains
                // IOB ((BGL - IOB) + ...), so it only falls below a threshold once insulin is already on
                // board -- it confirms an overdose rather than anticipating one. Verified against the
                // 6->7 Aug 2026 night: HP2 was 7.1 at 23:09 and 6.9 at 01:06, crossing 6.5 only ~4 minutes
                // into each SMB burst, by which point most of the dose had gone. Gating a PREVENTIVE
                // action on a REACTIVE measure cannot work, so overnight it is dropped entirely.
                // The Logs1 export confirmed the profile in force at 23:08 and 02:33 was ProfileReal (the
                // stronger one) through both bursts -- the weak profile has to be in place BEFORE a burst
                // starts, which is what an unconditional overnight switch achieves. Window starts at 22:00
                // rather than midnight because the first burst began at 23:10.
                if (isTimeBetween(22, 0, 6, 0) || (hpEve != null && hpEve < 5.0)) switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfLowProfileName))
                sendSms("EveningTH CurrProf 50_0.45 Acce HP2=${hpEve?.let { String.format("%.1f", it) } ?: "--"}")
                addCarePortalNote("Eve")
                markRun("EveningTH")
            }
        }

        // Code port of "TwilightTH15Acce0.50": drops iobTH to 15% and acce weight to 0.50 during a
        // quiet early morning (low steps, flat/falling BGL) while iobTH sits in the 17-39% band. No
        // live-pump gate: the original's Note field was empty.
        if (readyToRun("TwilightTH15Acce", 5)) {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            val iobTH = iobThresholdPercent
            if (isTimeBetween(6, 0, 8, 0)
                && recentSteps60Minutes <= 10
                && g <= 117.1 /* 6.5 mmol */
                && activeTtMgdl() == null
                && iobTH > 16 && iobTH <= 39
                && checkAutomationState("Steroids", "Steroids Off")
                && d <= 0.0) {
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 15)
                setBgAccelIsfWeight(0.50)
                switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfLowProfileName))
                sendSms("TwilightTH15Acce0.50")
                addCarePortalNote("TWi")
                markRun("TwilightTH15Acce")
            }
        }

        // Code port of "NightAcce_0.35TH18": overnight iobTH/acce reset to 18%/0.35 (plus a
        // settings-export backup, reusing exportSettingsFor) when iobTH sits outside the 18% band,
        // no carbs are active, and BGL is low. No live-pump gate: the original's Note field was empty.
        if (readyToRun("NightAcce", 5)) {
            val iobTH = iobThresholdPercent
            val acceW = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
            if (isTimeBetween(1, 0, 6, 0)
                && (iobTH >= 19 || iobTH <= 17)
                && mealData.mealCOB <= 0.0
                && activeTtMgdl() == null
                && glucoseStatus.glucose <= 108.1 /* 6.0 mmol */
                && checkAutomationState("Steroids", "Steroids Off")
                && acceW >= 0.08) {
                setBgAccelIsfWeight(0.35)
                // Profile switch now HP2-GATED rather than unconditional -- same rationale and same
                // fail-closed choice as EveningTH/MJrec above. No self-latch concern here, unlike MJrec:
                // this block's own "(iobTH >= 19 || iobTH <= 17)" trigger already latches it (setting
                // iobTH to 18 below falsifies that condition), so it applies once and stops regardless of
                // whether the profile switch happened.
                val hpNight = hypoPrediction2Mmol(glucoseStatus.glucose, glucoseStatus.shortAvgDelta, glucoseStatus.longAvgDelta, iobData.iob, mealData.mealCOB, bgAcce)
                if ((isTimeBetween(22, 0, 6, 0) || (hpNight != null && hpNight < 5.0)) && !rescueActive) switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfLowProfileName))
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 18)
                setSmbDeliveryRatio(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline))   // overnight reset restores delivery baseline
                preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal))   // restore ppWeight baseline
                exportSettingsFor("AutoExport")
                sendSms("NightAcce_0.35TH18 HP2=${hpNight?.let { String.format("%.1f", it) } ?: "--"}")
                addCarePortalNote("Night")
                markRun("NightAcce")
            }
        }

        // Code port of "SemiTwilightAcce_0.50TH16": drops acce weight to 0.50 and iobTH to 16% across
        // 3 morning branches. Branch 3's iobTH check was screenshotted as AND(>17, <=15) — impossible
        // as an AND (self-contradictory, always false). Per user confirmation the actual condition was
        // a single "iobTH percent = 16" equality check, misread off the screenshot as two separate
        // threshold rows. No live-pump gate: the original's Note field was empty.
        if (readyToRun("SemiTwilightAcce", 5)) {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            val sd = glucoseStatus.shortAvgDelta
            val ld = glucoseStatus.longAvgDelta
            val iobTH = iobThresholdPercent
            val acceW = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
            val stB1 = isTimeBetween(6, 0, 9, 0) && StepService.getRecentStepCount180Min() >= 10
                && activeTtMgdl() == null && iobTH <= 15 && checkAutomationState("Steroids", "Steroids Off")
            val stB2 = isTimeBetween(2, 0, 9, 0) && g >= 180.2 /* 10.0 mmol */ && acceW <= 0.025
                && activeTtMgdl() == null && checkAutomationState("Steroids", "Steroids Off")
            val stB3 = isTimeBetween(6, 0, 10, 30) && g >= 126.1 /* 7.0 mmol */
                && ld >= 0.0 && d >= 1.8 /* 0.1 mmol */ && sd >= 0.0
                && iobTH == 16 && iobTH < 40
                && checkAutomationState("Steroids", "Steroids Off")
            if (stB1 || stB2 || stB3) {
                setBgAccelIsfWeight(0.50)
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 16)
                setSmbDeliveryRatio(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline))   // morning recovery restores delivery baseline
                preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal))   // restore ppWeight baseline
                sendSms("SemiTwilightAcce_0.50TH16")
                addCarePortalNote("Semi")
                markRun("SemiTwilightAcce")
            }
        }

        // Code port of "ConnectPod": alerts when the pump hasn't reported in >=20 min during the
        // day while the pod is still within its normal wear window. Mirrors TriggerPumpLastConnection
        // (activePump.lastDataTime). Live-pump-only: "last connection to pump" is meaningless on the
        // Virtual Pump.
        if (readyToRun("ConnectPod", 20) && activePlugin.activePump !is VirtualPump) {
            val lastConnectionMinAgo = (dateUtil.now() - activePlugin.activePump.lastDataTime) / 60_000
            val cannulaH = hoursSinceLastCannulaChange() ?: 0.0
            if (lastConnectionMinAgo >= 20
                && isTimeBetween(8, 0, 23, 0)
                && cannulaH <= 80.0) {
                sendSms("ConnectPod")
                sendSmsToNumbers("ConnectPod", StringKey.SmsConnectPodNumbers)
                markRun("ConnectPod")
            }
        }

        // Code port of "Bolus2": starts a brief 6.8mmol temp target after a bolus when BGL is
        // falling fast post-bolus (branch 1), or when carbs are up and MJ is active shortly after a
        // bolus (branch 2). Precondition: no TT active. No live-pump gate: the original's Note field
        // was the user's own uncertainty comment ("not sure if auto needed"), not a virtual-pump
        // restriction. SMS text kept literal ("BolusTTfor10mins") despite the action's actual 5 min
        // duration — that mismatch is in the source automation, not introduced here.
        // setAutomationState("Profile","Bolus") is an addition beyond the original screenshot's
        // action list, per user instruction: this TT shares the exact same 6.8mmol value as the
        // manually-set Activity TT, so without this marker ActivityProf50% could misread a brief
        // 5-minute post-bolus TT as an Activity session and trigger its 180-minute 50% profile drop.
        // Reuses the same Profile=Bolus state BolusGiven71_0.70 already sets for the same reason.
        if (readyToRun("Bolus2", 20) && activeTtMgdl() == null) {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            val sd = glucoseStatus.shortAvgDelta
            val iob = iobData.iob
            val cob = mealData.mealCOB
            val lastBolusMin = minutesSinceLastNormalBolus() ?: Int.MAX_VALUE
            val b1 = lastBolusMin <= 5 && sd <= -3.6 /* -0.2 mmol */ && d <= -5.4 /* -0.3 mmol */
                && g <= 126.1 /* 7.0 mmol */ && iob >= 0.5 && cob >= 9.0
            val b2 = lastBolusMin <= 15 && checkAutomationState("MJ", "MJ active")
                && cob >= 4.0 && g <= 126.1 /* 7.0 mmol */
            if (b1 || b2) {
                startTempTargetIfNeeded(122.5 /* 6.8 mmol */, 5)
                sendSms("BolusTTfor10mins Acce")
                addCarePortalNote("Bol2")
                setAutomationState("Profile", "Bolus")
                markRun("Bolus2")
            }
        }

        // Standalone: clears Profile=Bolus (set by Bolus2/BolusGiven71 to disambiguate their own
        // brief post-bolus 6.8mmol TT from the manually-set Activity TT) once enough time has
        // passed since the last bolus. Without this, the marker would stick indefinitely and could
        // block a genuine future Activity TT from ever triggering ActivityProf50%. 15 min chosen to
        // safely exceed Bolus2's own duration under either reading of it (SMS text says "10mins",
        // coded action is 5 min — see the mismatch noted on Bolus2 above).
        if (checkAutomationState("Profile", "Bolus")) {
            val lastBolusMin = minutesSinceLastNormalBolus() ?: Int.MAX_VALUE
            if (lastBolusMin > 15) {
                setAutomationState("Profile", "C100")
            }
        }

        // Code port of "AlarmHypo1 0.700.35": hypo alarm — 3 OR-branches (time-gated slow decline,
        // an unconditional emergency floor at 3.0mmol with no time gate, and a steps-driven
        // variant), drops acce weight to 0.10. Per user correction, sets BOTH BGLstate=BGLlastLOW
        // and LowBG=50recent (screenshot only showed the former, but both AlarmHypo automations
        // should set both).
        if (readyToRun("AlarmHypo1", 15)) {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            val sd = glucoseStatus.shortAvgDelta
            val acceW = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
            val ukfRaw = ukfRawMetrics()
            val (ukfG, ukfD1, ukfD5) = ukfRaw
            val hp = hypoPrediction2Mmol(g, sd, glucoseStatus.longAvgDelta, iobData.iob, mealData.mealCOB, bgAcce, ukfRaw)
            val hp1 = hypoPrediction1Mmol(g, iobData.iob, sd, mealData.mealCOB)
            // ah1b1's 07:30-23:30 gate REMOVED so this branch also covers the small hours. It exists to
            // catch a slow decline into hypo, and that is no less real at 04:00 -- on 6->7 Aug 2026 BGL sat
            // at 3.5 from 04:00-05:30 and this block could not fire at all, because ah1b1 was outside its
            // window and ah1b2's emergency floor is 3.0. The point of running it overnight is less the
            // alert than the two state writes below: LowBG=50recent is what arms the recent-low rebound
            // guard in DetermineBasalAutoISF.kt, and without it that guard is unreachable after any
            // overnight low. acce is also dropped to 0.10, which is protective in its own right.
            //
            // Alerting is suppressed during quiet hours instead (see alarmHypoQuiet below) -- the block is
            // enacted, but silently, so it cannot wake you. The graph annotation still lands so the event
            // is visible in the morning.
            val alarmHypoQuiet = isTimeBetween(22, 0, 7, 30)
            val ah1b1 = d < -0.36 /* -0.02 mmol */ && sd < -0.36
                && g < 77.5 /* 4.3 mmol */ && acceW <= 0.08
            val ah1b2 = g < 54.0 /* 3.0 mmol */
            val ah1b3 = isTimeBetween(7, 0, 23, 0) && d <= -0.9 /* -0.05 mmol */
                && recentSteps60Minutes >= 102 && g < 77.5 /* 4.3 mmol */ && acceW <= 0.08
            // 3.8 -> 3.4 (tightened), AND'd with HP1 <= 3.8 as a second, differently-weighted predictor
            // that must also agree -- HP1 uses raw Libre delta5 (not UKF) and treats COB as always
            // protective (no gating), so requiring both narrows this branch to cases where the two
            // formulas' disagreements (COB handling, delta source, delta weight) don't matter.
            val ah1b4 = hp != null && hp <= 3.4 && hp1 != null && hp1 <= 3.8 && acceW <= 0.08
            if (ah1b1 || ah1b2 || ah1b3 || ah1b4) {
                setBgAccelIsfWeight(0.10)
                val ah1SmsText = "AlarmHypo: g=${String.format("%.1f", g / 18.016)} d=${String.format("%.2f", d / 18.016)}" +
                    " UKFrawG=${ukfG?.let { String.format("%.1f", it / 18.016) } ?: "--"}" +
                    " UKFrawD1=${ukfD1?.let { String.format("%.2f", it / 18.016) } ?: "--"}" +
                    " UKFrawD5=${ukfD5?.let { String.format("%.2f", it / 18.016) } ?: "--"}" +
                    " HP=${hp1?.let { String.format("%.1f", it) } ?: "--"}" +
                    " HP2=${hp?.let { String.format("%.1f", it) } ?: "--"}" +
                    " iob=${String.format("%.2f", iobData.iob)}"
                // Alerting only outside quiet hours. The URGENT notification is what actually wakes you,
                // and the SMS likewise, so both are gated -- but the state writes and acce drop below are
                // NOT, since those are the parts that need to happen overnight. ah1b2 (the <3.0mmol
                // emergency floor) is deliberately NOT exempted from the quiet window: if you would rather
                // be woken for a genuine severe low regardless of the hour, change this to
                // (!alarmHypoQuiet || ah1b2).
                if (!alarmHypoQuiet) {
                    if (g <= 6.0 * GlucoseUnit.MMOLL_TO_MGDL && mealData.mealCOB <= 9.0) {
                        sendSms(ah1SmsText)
                        sendSmsToNumbers(ah1SmsText, StringKey.SmsAlarmHypo1Numbers)
                    }
                    uiInteraction.addNotification(id = 9009, text = "H4", level = Notification.URGENT)
                }
                addGraphAnnouncement("_____H4")
                setAutomationState("BGLstate", "BGLlastLOW")
                setAutomationState("LowBG", "50recent")
                markRun("AlarmHypo1")
            }
        }

        // Code port of "AlarmHypo2 0.700.35": hypo alarm — single AND group, catches either
        // G<=4.3mmol outright or G<=5.5mmol with recent exercise (Steps30>=1000) likely
        // accelerating the drop. Sets both BGLstate=BGLlastLOW and LowBG=50recent, feeding the
        // existing 50%-profile state machine (50SetRecent/Not50%Recently/Extra50%/PP50.Off).
        if (readyToRun("AlarmHypo2", 15)) {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            val sd = glucoseStatus.shortAvgDelta
            val acceW = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
            val ukfRaw = ukfRawMetrics()
            val (ukfG, ukfD1, ukfD5) = ukfRaw
            val hp = hypoPrediction2Mmol(g, sd, glucoseStatus.longAvgDelta, iobData.iob, mealData.mealCOB, bgAcce, ukfRaw)
            val hp1 = hypoPrediction1Mmol(g, iobData.iob, sd, mealData.mealCOB)
            // 3.8 -> 3.4 (tightened), AND'd with HP1 <= 3.8 -- same reasoning as AlarmHypo1's ah1b4.
            val lowOk = g <= 77.5 /* 4.3 mmol */ ||
                (g <= 99.1 /* 5.5 mmol */ && recentSteps30Minutes >= 1000) ||
                (hp != null && hp <= 3.4 && hp1 != null && hp1 <= 3.8)
            // 07:30-23:30 gate REMOVED, alerting silenced in quiet hours instead -- same treatment and
            // reasoning as AlarmHypo1 above and GentleHypoRisk. This one matters most of the three for
            // the LowBG=50recent write, since that is what arms the recent-low rebound guard in
            // DetermineBasalAutoISF.kt, and overnight was exactly when it could never fire.
            val alarmHypo2Quiet = isTimeBetween(22, 0, 7, 30)
            if (d <= 0.0 && sd <= 0.0 && lowOk && acceW <= 0.08) {
                setBgAccelIsfWeight(0.10)
                val ah2SmsText = "AlarmHypo: g=${String.format("%.1f", g / 18.016)} d=${String.format("%.2f", d / 18.016)}" +
                    " UKFrawG=${ukfG?.let { String.format("%.1f", it / 18.016) } ?: "--"}" +
                    " UKFrawD1=${ukfD1?.let { String.format("%.2f", it / 18.016) } ?: "--"}" +
                    " UKFrawD5=${ukfD5?.let { String.format("%.2f", it / 18.016) } ?: "--"}" +
                    " HP=${hp1?.let { String.format("%.1f", it) } ?: "--"}" +
                    " HP2=${hp?.let { String.format("%.1f", it) } ?: "--"}" +
                    " iob=${String.format("%.2f", iobData.iob)}"
                if (!alarmHypo2Quiet) {
                    if (g <= 6.0 * GlucoseUnit.MMOLL_TO_MGDL && mealData.mealCOB <= 9.0) {
                        sendSms(ah2SmsText)
                        sendSmsToNumbers(ah2SmsText, StringKey.SmsAlarmHypo2Numbers)
                    }
                    uiInteraction.addNotification(id = 9010, text = "A4", level = Notification.URGENT)
                }
                addGraphAnnouncement("__________A4")
                setAutomationState("LowBG", "50recent")
                uiInteraction.addNotification(id = 9011, text = "H4", level = Notification.URGENT)
                addGraphAnnouncement("H4")
                setAutomationState("BGLstate", "BGLlastLOW")
                markRun("AlarmHypo2")
            }
        }

        // Code port of "Steps Steroids OFF": asserts Steroids=Steroids Off based on sustained
        // activity with moderate IOB, controlled glucose, and no carbs. Per user confirmation, this
        // is the automation that actually sets the Steroids state (previously only ever read as a
        // precondition elsewhere in this file, never set) — Steroids ON is a manual user action, no
        // automated counterpart needed.
        if (readyToRun("StepsSteroidsOff", 5)) {
            val g = glucoseStatus.glucose
            val d = glucoseStatus.delta
            val iob = iobData.iob
            if (recentSteps60Minutes >= 1000 && iob <= 4.0 && iob >= 2.0
                && g <= 144.1 /* 8.0 mmol */ && d >= 9.0 /* 0.5 mmol */ && mealData.mealCOB == 0.0) {
                sendSms("Steps Steroids OFF")
                setAutomationState("Steroids", "Steroids Off")
                switchProfileIfNeeded(preferences.get(StringKey.ApsAutoIsfLowProfileName))
                setBgAccelIsfWeight(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal))
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 50)
                addCarePortalNote("Steps Steroids OFF")
                markRun("StepsSteroidsOff")
            }
        }

        // Experimental correction bolus for retrospective testing on Virtual Pump only. This is
        // intentionally hard-gated twice (eligibility and immediately before queueing) so installing
        // this branch on a real-pump build cannot deliver it. It runs the normal constrained Wizard in
        // the background with a pseudo-COB input, but records carbs=0: no fake carb treatment is made.
        run {
            val actionKey = "VirtualPseudoWizard"
            val virtualPump = activePlugin.activePump is VirtualPump
            val ukfBgl = computeUkfRawBgl()
            val hp = hypoPrediction2Mmol(glucoseStatus.glucose, glucoseStatus.shortAvgDelta, glucoseStatus.longAvgDelta, iobData.iob, mealData.mealCOB, bgAcce)
            val noTempTarget = persistenceLayer.getTemporaryTargetActiveAt(now) == null
            // Match the other coded automations' "no bolus within" meaning: NORMAL/user boluses only.
            // SMBs are deliberately ignored here; otherwise an active loop would make this gate nearly
            // permanently false.
            val lastNormalBolusMinutes = minutesSinceLastNormalBolus() ?: Int.MAX_VALUE
            val noNormalBolus120 = lastNormalBolusMinutes >= 120
            val allBgHigh = allRecentBgAbove90Minutes(8.0 * GlucoseUnit.MMOLL_TO_MGDL)
            val duraActiveMinutes = recentAdaptationMinutes { it.duraIsf }
            val acceActiveMinutes = recentAdaptationMinutes { it.acceIsf }
            val stepsOk = recentSteps60Minutes < 600 ||
                (glucoseStatus.glucose > 11.0 * GlucoseUnit.MMOLL_TO_MGDL && recentSteps60Minutes < 1500)
            val adaptationDurationOk = duraActiveMinutes > 5.0 && acceActiveMinutes > 4.0
            val hp2HighBgBypass = hp != null && hp > 7.5 &&
                glucoseStatus.glucose > 10.0 * GlucoseUnit.MMOLL_TO_MGDL
            val cooldownReady = readyToRun(actionKey, 90)
            val conditionsMet = virtualPump &&
                ukfBgl > 12.0 * GlucoseUnit.MMOLL_TO_MGDL &&
                bgAcce > 3.0 &&
                glucoseStatus.shortAvgDelta > 0.5 * GlucoseUnit.MMOLL_TO_MGDL &&
                allBgHigh &&
                (adaptationDurationOk || hp2HighBgBypass) &&
                mealData.mealCOB == 0.0 &&
                noTempTarget &&
                stepsOk &&
                hp != null && hp > 7.5 &&
                noNormalBolus120 &&
                cooldownReady &&
                !commandQueue.bolusInQueue()

            consoleError.add(
                "VirtualPseudoWizard ${if (conditionsMet) "READY" else "blocked"}: " +
                    "virtual=$virtualPump ukf=${String.format(Locale.US, "%.1f", ukfBgl / GlucoseUnit.MMOLL_TO_MGDL)} " +
                    "acce=${String.format(Locale.US, "%.2f", bgAcce)} SDelta=${String.format(Locale.US, "%.2f", glucoseStatus.shortAvgDelta / GlucoseUnit.MMOLL_TO_MGDL)} " +
                    "allBG90>8=$allBgHigh duraMin=${String.format(Locale.US, "%.1f", duraActiveMinutes)} " +
                    "acceMin=${String.format(Locale.US, "%.1f", acceActiveMinutes)} adaptationOk=$adaptationDurationOk HP2+BGL10bypass=$hp2HighBgBypass " +
                    "COB=${String.format(Locale.US, "%.1f", mealData.mealCOB)} " +
                    "noTT=$noTempTarget steps60=$recentSteps60Minutes stepsOk=$stepsOk HP2=${hp?.let { String.format(Locale.US, "%.1f", it) } ?: "--"} " +
                    "noNormalBolus120=$noNormalBolus120 lastNormalBolusMin=$lastNormalBolusMinutes " +
                    "cooldown90=$cooldownReady queueFree=${!commandQueue.bolusInQueue()} ;;"
            )
            consoleError.add("VirtualPseudoWizard last result: $virtualPseudoWizardLastStatus ;; ")

            if (conditionsMet) {
                // Calculation-only carb entry; do not also add COB because Wizard sums both.
                // The delivered correction treatment below continues to record zero carbs.
                val calculationCarbs = when {
                    glucoseStatus.glucose > 13.0 * GlucoseUnit.MMOLL_TO_MGDL -> 20
                    glucoseStatus.glucose > 11.0 * GlucoseUnit.MMOLL_TO_MGDL -> 15
                    else -> 10
                }
                val wizard = bolusWizardProvider.get().doCalc(
                    profile = profile,
                    profileName = profileFunction.getProfileName(),
                    tempTarget = null,
                    carbs = calculationCarbs,
                    cob = 0.0,
                    bg = glucoseStatus.glucose,
                    correction = 0.0,
                    useBg = true,
                    useCob = false,
                    includeBolusIOB = true,
                    includeBasalIOB = true,
                    useSuperBolus = false,
                    useTT = false,
                    useTrend = false,
                    useAlarm = false,
                    notes = "Virtual-only pseudo-wizard; calculationCarbs=${calculationCarbs}g; zero carbs recorded",
                    quickWizard = false,
                    positiveIOBOnly = false
                )
                val insulin = wizard.insulinAfterConstraints
                if (insulin > 0.0 && activePlugin.activePump is VirtualPump) {
                    val bolusInfo = DetailedBolusInfo().apply {
                        this.insulin = insulin
                        carbs = 0.0
                        eventType = TE.Type.CORRECTION_BOLUS
                        mgdlGlucose = glucoseStatus.glucose
                        glucoseType = TE.MeterType.SENSOR
                        bolusCalculatorResult = wizard.createBolusCalculatorResult()
                        notes = "Virtual-only pseudo-wizard; calculationCarbs=${calculationCarbs}g; zero carbs recorded"
                        bolusType = BS.Type.NORMAL
                    }
                    val accepted = commandQueue.bolus(bolusInfo, object : Callback() {
                        override fun run() {
                            if (result.success) {
                                markRun(actionKey)
                                virtualPseudoWizardLastStatus = "SUCCESS ${String.format(Locale.US, "%.2f", insulin)}U at ${dateUtil.timeString(dateUtil.now())}"
                                aapsLogger.info(LTag.APS, "VirtualPseudoWizard SUCCESS ${String.format(Locale.US, "%.2f", insulin)}U")
                            } else {
                                virtualPseudoWizardLastStatus = "FAILED at ${dateUtil.timeString(dateUtil.now())}: ${result.comment}"
                                aapsLogger.error(LTag.APS, "VirtualPseudoWizard FAILED: ${result.comment}")
                            }
                        }
                    })
                    if (!accepted) {
                        virtualPseudoWizardLastStatus = "FAILED at ${dateUtil.timeString(dateUtil.now())}: bolus queue rejected request"
                        aapsLogger.error(LTag.APS, "VirtualPseudoWizard FAILED: bolus queue rejected request")
                    }
                    else consoleError.add("VirtualPseudoWizard queued ${String.format(Locale.US, "%.2f", insulin)}U with calculationCarbs=${calculationCarbs}g and recordedCarbs=0 ;; ")
                } else {
                    consoleError.add("VirtualPseudoWizard no dose: constrained Wizard result=${String.format(Locale.US, "%.2f", insulin)}U ;; ")
                }
            }
        }

        } // end ApsAutoIsfCustomAutomationsEnabled

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
        // Computed once (nullable) and reused below for BOTH smbBoostRecent and the fast-rise-capping
        // confirmation params, to avoid querying the raw BG readings table twice per loop. The two uses
        // apply OPPOSITE fallbacks on purpose: smbBoostRecent's raw checks must FAIL-safe when data is
        // missing (-9999 -> don't bypass/relax capping), whereas the new capping-confirmation params
        // must PASS-safe when missing (+9999 -> don't let absent confirmation block a cap the original
        // Delta/SDelta logic would already have applied).
        val rawDelta1Raw = rawDelta1MinMgdl()
        val rawDelta5Raw = rawDelta5MinMgdl()
        val aapsDelta1Raw = aapsDelta1MinMgdl()
        val rawDelta15Raw = rawDelta15MinMgdl()
        // SMB adjustment confirmations follow the LibreUKF toggle. When enabled, use the UKF-smoothed
        // raw signal; when disabled, preserve the existing immediate Libre .noise deltas. The recent-
        // boost reversion gate below and DetermineBasal's early-morning fresh-reversal guard deliberately
        // continue to use immediate raw values because their purpose is to see a turn before smoothing.
        val useUkfLibreSpecial = preferences.get(BooleanKey.FslUseUkfLibreSpecialSmoothing)
        val useUkfRawForSmb = preferences.get(BooleanKey.FslUseUkfSmoothing) || useUkfLibreSpecial
        val smbUkfRaw = if (useUkfRawForSmb) ukfRawMetrics() else null
        val smbDelta1Raw = if (useUkfRawForSmb) smbUkfRaw?.delta1 else rawDelta1Raw
        val smbDelta5Raw = if (useUkfRawForSmb) smbUkfRaw?.delta5 else rawDelta5Raw
        consoleLog.add(
            "SMB raw source=${if (useUkfRawForSmb) "UKFRAW" else "RAW"}: " +
                "D1=${smbDelta1Raw?.let { String.format("%.2f", it) } ?: "--"} " +
                "D5=${smbDelta5Raw?.let { String.format("%.2f", it) } ?: "--"}"
        )

        // --- Sub75HeavyDelivery: arms a hard 10-min cooldown once smbSum10Min() exceeds 1.0U while
        // glucose is still under 7.5mmol -- see determine_basal()'s sub75HeavyDeliveryCooldown param
        // (DetermineBasalAutoISF.kt) for the two real events (4.4mmol, sustained 4.5mmol) this is
        // anchored on. Deliberately a plain markRun/readyToRun cooldown, not another rolling-window
        // amount cap like smbSum10Min/30Min below it -- this forces a genuine pause instead of
        // re-permitting delivery the instant the oldest minute ages out of its own window, giving
        // already-delivered insulin time to actually show up in BG before more goes in. First cut, not
        // yet device-verified against a case that genuinely needed to keep dosing through this window.
        // readyToRun() gates the trigger itself (not just markRun's own effect) so the note/SMS fires
        // once per 10-min window, not every single cycle the condition happens to stay true.
        if (glucoseStatus.glucose < 135.1 /* 7.5 mmol */ && smbSum10Min() > 1.0 && readyToRun("Sub75HeavyDelivery", 10)) {
            sendSms("Sub75HeavyDelivery: SMB cooldown armed (10min=${round(smbSum10Min(), 2)}U, g=${String.format(Locale.getDefault(), "%.1f", glucoseStatus.glucose / 18.016)})")
            addCarePortalNote("Sub75Cap")
            markRun("Sub75HeavyDelivery")
        }
        // Early release: if the cooldown is currently active but bg has already recovered above 7.5mmol
        // AND is climbing fast (delta > 0.7mmol), clear it rather than waiting out the remaining minutes.
        // A fast rise past the trigger threshold is itself evidence the earlier heavy delivery is already
        // taking effect, not still stacking -- continuing to block SMB through the rest of the window at
        // that point risks under-treating the recovery rather than protecting against it. Clearing (not
        // just letting it lapse) resets lastRunTimestamps directly so the very next cycle can dose again,
        // instead of waiting for the 10-min mark. Separate note (not "Sub75Cap" again) so an early release
        // is distinguishable from a fresh arm/re-arm in the careportal history.
        if (!readyToRun("Sub75HeavyDelivery", 10) && glucoseStatus.glucose > 135.1 /* 7.5 mmol */ && glucoseStatus.delta > 12.6 /* 0.7 mmol */) {
            lastRunTimestamps.remove("Sub75HeavyDelivery")
            addCarePortalNote("Sub75Clr")
        }

        // TEMP diagnostic 2026-08-16 (UKF3426 branch): loop's own ground-truth drop-crossing timestamp,
        // logged EVERY cycle (not gated by the 5-min throttle the five per-type blocks below use) so its
        // timing precision isn't artificially coarsened to 5 minutes -- it's the reference point every
        // other type's "lead/lag vs first" text is measured against, and glucoseStatus.delta is already
        // computed at zero extra cost.
        logDropDetection("Loop", glucoseStatus.delta)

        // TEMP diagnostic 2026-08-15 (UKF3426 branch): sanity-check UKF2's own delta5/delta15/delta30
        // and bgAcceleration/deltaPl/deltaPn against the loop's actual (LibreSpecial/UKFset1) values --
        // proof-of-concept for the per-type delta/acceleration comparison work, log-only for now (see
        // this branch's scope notes). 5-min throttle, just to avoid spamming every 1-min cycle during
        // this investigation phase. glucoseStatus itself doesn't expose bgAcceleration/deltaPl/deltaPn
        // (those are AutoISF-specific, not on the base GlucoseStatus type -- same cast the existing
        // determine_basal() call site already does), so cast once here for the comparison.
        if (readyToRun("Ukf2DeltaMetricsLog", 5)) {
            val ukf2Deltas = ukf2DeltaMetrics()
            val ukf2Accel = ukf2AccelerationMetrics()
            val loopStatus = glucoseStatus as? GlucoseStatusAutoIsf
            aapsLogger.debug(
                LTag.APS,
                "Ukf2DeltaMetrics: delta5=${round(ukf2Deltas.delta, 2)} delta15=${round(ukf2Deltas.shortAvgDelta, 2)} delta30=${round(ukf2Deltas.longAvgDelta, 2)} " +
                    "accel=${round(ukf2Accel.bgAcceleration, 3)} deltaPl=${round(ukf2Accel.deltaPl, 2)} deltaPn=${round(ukf2Accel.deltaPn, 2)} window=${round(ukf2Accel.windowMinutes, 1)}min corrSqu=${round(ukf2Accel.corrSqu, 3)} " +
                    "(loop: delta5=${round(glucoseStatus.delta, 2)} delta15=${round(glucoseStatus.shortAvgDelta, 2)} delta30=${round(glucoseStatus.longAvgDelta, 2)} " +
                    "accel=${loopStatus?.let { round(it.bgAcceleration, 3) } ?: "--"} deltaPl=${loopStatus?.let { round(it.deltaPl, 2) } ?: "--"} deltaPn=${loopStatus?.let { round(it.deltaPn, 2) } ?: "--"})"
            )
            logAccelSignDisagreement("UKF2", ukf2Accel.bgAcceleration, loopStatus?.bgAcceleration)
            logDropDetection("UKF2", ukf2Deltas.delta)
            markRun("Ukf2DeltaMetricsLog")
        }

        // TEMP diagnostic 2026-08-15 (UKF3426 branch): second type in the per-type comparison, same
        // shape as Ukf2DeltaMetricsLog above but separate log line/throttle key rather than combined
        // into one line -- that one's already dense with two sets of numbers, a third would be
        // unreadable. loopStatus reused from the block above (same glucoseStatus cast).
        if (readyToRun("UkfSet1DeltaMetricsLog", 5)) {
            val ukfSet1Deltas = ukfSet1DeltaMetrics()
            val ukfSet1Accel = ukfSet1AccelerationMetrics()
            val loopStatus = glucoseStatus as? GlucoseStatusAutoIsf
            aapsLogger.debug(
                LTag.APS,
                "UkfSet1DeltaMetrics: delta5=${round(ukfSet1Deltas.delta, 2)} delta15=${round(ukfSet1Deltas.shortAvgDelta, 2)} delta30=${round(ukfSet1Deltas.longAvgDelta, 2)} " +
                    "accel=${round(ukfSet1Accel.bgAcceleration, 3)} deltaPl=${round(ukfSet1Accel.deltaPl, 2)} deltaPn=${round(ukfSet1Accel.deltaPn, 2)} window=${round(ukfSet1Accel.windowMinutes, 1)}min corrSqu=${round(ukfSet1Accel.corrSqu, 3)} " +
                    "(loop: delta5=${round(glucoseStatus.delta, 2)} delta15=${round(glucoseStatus.shortAvgDelta, 2)} delta30=${round(glucoseStatus.longAvgDelta, 2)} " +
                    "accel=${loopStatus?.let { round(it.bgAcceleration, 3) } ?: "--"} deltaPl=${loopStatus?.let { round(it.deltaPl, 2) } ?: "--"} deltaPn=${loopStatus?.let { round(it.deltaPn, 2) } ?: "--"})"
            )
            logAccelSignDisagreement("UKFset1", ukfSet1Accel.bgAcceleration, loopStatus?.bgAcceleration)
            logDropDetection("UKFset1", ukfSet1Deltas.delta)
            markRun("UkfSet1DeltaMetricsLog")
        }

        // TEMP diagnostic 2026-08-16 (UKF3426 branch): third and final type completing the live pair
        // (LibreSpecial's own shadow computation) -- same shape as the two logs above, own separate
        // line/throttle key.
        if (readyToRun("LibreSpecialShadowMetricsLog", 5)) {
            val libreSpecialDeltas = libreSpecialShadowDeltaMetrics()
            val libreSpecialAccel = libreSpecialShadowAccelerationMetrics()
            val loopStatus = glucoseStatus as? GlucoseStatusAutoIsf
            aapsLogger.debug(
                LTag.APS,
                "LibreSpecialShadowMetrics: delta5=${round(libreSpecialDeltas.delta, 2)} delta15=${round(libreSpecialDeltas.shortAvgDelta, 2)} delta30=${round(libreSpecialDeltas.longAvgDelta, 2)} " +
                    "accel=${round(libreSpecialAccel.bgAcceleration, 3)} deltaPl=${round(libreSpecialAccel.deltaPl, 2)} deltaPn=${round(libreSpecialAccel.deltaPn, 2)} window=${round(libreSpecialAccel.windowMinutes, 1)}min corrSqu=${round(libreSpecialAccel.corrSqu, 3)} " +
                    "(loop: delta5=${round(glucoseStatus.delta, 2)} delta15=${round(glucoseStatus.shortAvgDelta, 2)} delta30=${round(glucoseStatus.longAvgDelta, 2)} " +
                    "accel=${loopStatus?.let { round(it.bgAcceleration, 3) } ?: "--"} deltaPl=${loopStatus?.let { round(it.deltaPl, 2) } ?: "--"} deltaPn=${loopStatus?.let { round(it.deltaPn, 2) } ?: "--"})"
            )
            logAccelSignDisagreement("LibreSpecial", libreSpecialAccel.bgAcceleration, loopStatus?.bgAcceleration)
            logDropDetection("LibreSpecial", libreSpecialDeltas.delta)
            markRun("LibreSpecialShadowMetricsLog")
        }

        // TEMP diagnostic 2026-08-16 (UKF3426 branch): UKF1 and UKF3 -- both fully stateless/batch, no
        // shadow-persistence problem like the live pair above, just a fresh recompute each call over a
        // bounded raw-reading window. Same shape as the three logs above, own separate lines/throttle
        // keys.
        if (readyToRun("Ukf1DeltaMetricsLog", 5)) {
            val ukf1Deltas = ukf1DeltaMetrics()
            val ukf1Accel = ukf1AccelerationMetrics()
            val loopStatus = glucoseStatus as? GlucoseStatusAutoIsf
            aapsLogger.debug(
                LTag.APS,
                "Ukf1DeltaMetrics: delta5=${round(ukf1Deltas.delta, 2)} delta15=${round(ukf1Deltas.shortAvgDelta, 2)} delta30=${round(ukf1Deltas.longAvgDelta, 2)} " +
                    "accel=${round(ukf1Accel.bgAcceleration, 3)} deltaPl=${round(ukf1Accel.deltaPl, 2)} deltaPn=${round(ukf1Accel.deltaPn, 2)} window=${round(ukf1Accel.windowMinutes, 1)}min corrSqu=${round(ukf1Accel.corrSqu, 3)} " +
                    "(loop: delta5=${round(glucoseStatus.delta, 2)} delta15=${round(glucoseStatus.shortAvgDelta, 2)} delta30=${round(glucoseStatus.longAvgDelta, 2)} " +
                    "accel=${loopStatus?.let { round(it.bgAcceleration, 3) } ?: "--"} deltaPl=${loopStatus?.let { round(it.deltaPl, 2) } ?: "--"} deltaPn=${loopStatus?.let { round(it.deltaPn, 2) } ?: "--"})"
            )
            logAccelSignDisagreement("UKF1", ukf1Accel.bgAcceleration, loopStatus?.bgAcceleration)
            logDropDetection("UKF1", ukf1Deltas.delta)
            markRun("Ukf1DeltaMetricsLog")
        }

        if (readyToRun("Ukf3DeltaMetricsLog", 5)) {
            val ukf3Deltas = ukf3DeltaMetrics()
            val ukf3Accel = ukf3AccelerationMetrics()
            val loopStatus = glucoseStatus as? GlucoseStatusAutoIsf
            aapsLogger.debug(
                LTag.APS,
                "Ukf3DeltaMetrics: delta5=${round(ukf3Deltas.delta, 2)} delta15=${round(ukf3Deltas.shortAvgDelta, 2)} delta30=${round(ukf3Deltas.longAvgDelta, 2)} " +
                    "accel=${round(ukf3Accel.bgAcceleration, 3)} deltaPl=${round(ukf3Accel.deltaPl, 2)} deltaPn=${round(ukf3Accel.deltaPn, 2)} window=${round(ukf3Accel.windowMinutes, 1)}min corrSqu=${round(ukf3Accel.corrSqu, 3)} " +
                    "(loop: delta5=${round(glucoseStatus.delta, 2)} delta15=${round(glucoseStatus.shortAvgDelta, 2)} delta30=${round(glucoseStatus.longAvgDelta, 2)} " +
                    "accel=${loopStatus?.let { round(it.bgAcceleration, 3) } ?: "--"} deltaPl=${loopStatus?.let { round(it.deltaPl, 2) } ?: "--"} deltaPn=${loopStatus?.let { round(it.deltaPn, 2) } ?: "--"})"
            )
            logAccelSignDisagreement("UKF3", ukf3Accel.bgAcceleration, loopStatus?.bgAcceleration)
            logDropDetection("UKF3", ukf3Deltas.delta)
            markRun("Ukf3DeltaMetricsLog")
        }

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
            steps5M = steps5,
            smbInt5Sec = smbInterval5Sec(),  // rapid-stacking guard: <=70s trims the SMB to 90% (before fast-rise caps)
            // Bypass the fast-rise SMB caps when a delivery boost (BolusGiven bg3 or BolusGivenMild)
            // fired within the last 30 min: an unexpectedly high spike now reverts more readily (the
            // raw-delta-driven reversals), so the caps' conservatism isn't needed during that window.
            // readyToRun() is a pure timestamp check (no mutation); true-when-never-run, so !ready =
            // "fired within the last 30 min".
            // Self-gating on the RAW deltas: the bypass only takes effect while both the 1-min and 5-min
            // raw Libre deltas still read >= +0.1 mmol. The raws see a turn before the smoothed AAPS
            // deltas do, so the moment the rise breaks the caps re-apply on the next 1-min loop (and
            // resume if the rise resumes within the 30 min). Missing raw data (-9999 fallback) fails
            // safe: caps apply.
            // Reversion/high-IOB guard: the raw-delta self-gate above only sees the SHORT-term trend, so
            // a brief renewed uptick right after a boost could pass it even while the LONGER trend is
            // still net-negative (recovering from an overshoot the boost itself likely caused) and IOB is
            // already elevated from that same recent boost — exactly when the caps' conservatism is
            // still warranted, not when it should be waived. 0.33 * max_iob (not a flat unit count) sits
            // above typical daytime IOB but below the ~3.3-3.6U peaks actually observed during genuine
            // sustained rises, so the bypass stays available through those, only cutting out right at the
            // observed high-IOB tail.
            smbBoostRecent = (!readyToRun("BolusGivenBg3", 30) || !readyToRun("BolusGivenMild", 30))
                && (rawDelta1Raw ?: -9999.0) >= 1.8 /* +0.1 mmol */
                && (rawDelta5Raw ?: -9999.0) >= 1.8 /* +0.1 mmol */
                && glucoseStatus.longAvgDelta > -1.8 /* -0.1 mmol: longer trend not still reverting */
                && iobData.iob < 0.33 * oapsProfile.max_iob,
            // Extra AND confirmations on the fast-rise capping blocks' own Delta gate (see
            // DetermineBasalAutoISF.kt). Pass-safe fallback (9999.0) when data is missing.
            rawDelta5Mgdl = smbDelta5Raw ?: 9999.0,
            immediateRawDelta5Mgdl = rawDelta5Raw ?: 9999.0,
            rawDelta1Mgdl = smbDelta1Raw ?: 9999.0,
            aapsDelta1Mgdl = aapsDelta1Raw ?: 9999.0,
            rawDelta15Mgdl = rawDelta15Raw ?: 9999.0,
            // Same LowBG state BolusWizard reads to halve carb insulin -- see the recent-low rebound
            // guard in DetermineBasalAutoISF.kt. Reusing the existing, already-maintained flag (set on
            // lows, cleared on recovery, with its own anti-flap throttle) rather than adding a second
            // recent-low detector that could disagree with it.
            recentLowActive = checkAutomationState("LowBG", "50recent"),
            smbSum10Min = smbSum10Min(),
            smbSum30Min = smbSum30Min(),
            sub75HeavyDeliveryCooldown = !readyToRun("Sub75HeavyDelivery", 10),
            basalUpOffsetZeroActive = !readyToRun("BasalUpOffsetZero", 5)
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
        autoIsfValues.smbDeliveryRatio = smb_delivery_ratio
        autoIsfValues.iob = iobData.iob
        // Read directly from local prefs here — this whole function only ever runs on the device
        // actually executing AutoISF (the master), never on an AAPSClient follower, so there's no
        // sync concern on this side. NSDeviceStatusHandler.kt reconstructs these two fields from the
        // AcceIsfWeight:/FslCalSlope: reason-text lines above when building the client's own AIV row.
        autoIsfValues.acceIsfWeight = bgAccel_ISF_weight
        autoIsfValues.fslCalSlope = preferences.get(DoubleKey.FslCalSlope)
        lastAPSResult?.let { result ->
            autoIsfValues.insulinReq = result.json()?.optDouble("insulinReq", 0.0) ?: 0.0
            autoIsfValues.tbrRate    = if (currentTemp.rate > 0.0 && currentTemp.duration > 0) currentTemp.rate else result.rate
            autoIsfValues.smbDelivered = result.smb
            (result.rawData() as? RT)?.let { rt ->
                rt.autoIsfAcce  = autoIsfValues.acceIsf
                rt.autoIsfBg    = autoIsfValues.bgIsf
                rt.autoIsfPp    = autoIsfValues.ppIsf
                rt.autoIsfDura  = autoIsfValues.duraIsf
                rt.autoIsfFinal = autoIsfValues.finalIsf
                // Opposite direction from the four fields above: uci has no independent computation in
                // this plugin, so it flows FROM rt (set in DetermineBasalAutoISF.kt) INTO autoIsfValues
                // here, for local persistence below.
                autoIsfValues.uamCarbImpact = rt.autoIsfUamCarbImpact ?: 0.0
                // Same direction as autoIsfAcce/Bg/Pp/Dura/Final above -- computed here, copied into rt
                // for outbound sync. See computeUkfRawBgl() below.
                autoIsfValues.ukfRawBgl = computeUkfRawBgl()
                rt.autoIsfUkfRawBgl = autoIsfValues.ukfRawBgl
                rt.autoIsfSettingsSnapshot = autoIsfSettingsSnapshot()
                // Dedicated, unconditional reason lines for the client-sync fallback (see
                // NSDeviceStatusHandler.kt). Unlike the existing consoleLog.add() text for these
                // same values (which is conditional on which branch fired, and consoleLog doesn't
                // reliably survive the NS round-trip anyway), these always append exactly one line
                // with a fixed format — same reliable pattern already used for bg_acce/Delta/SDelta.
                rt.reason.append("SMB delivery ratio: ${round(autoIsfValues.smbDeliveryRatio, 2)} ;")
                rt.reason.append("iobThEffectiveU: ${round(autoIsfValues.iobThEffective, 2)} ;")
                rt.reason.append("FslCalSlope: ${round(preferences.get(DoubleKey.FslCalSlope), 2)} ;")
                rt.reason.append("AcceIsfWeight: ${round(bgAccel_ISF_weight, 2)} ;")
                // LowBG state, logged EVERY cycle (not only when the recent-low rebound guard in
                // DetermineBasalAutoISF.kt actually fires) so the exporter's LowBG column shows whether
                // the guard was armed at any given row, not merely where it bit. That distinction is the
                // whole point of the column: it lets a low be reviewed afterwards to see whether the
                // guard WOULD have applied, which is what validates the threshold. Written into reason
                // rather than persisted as a new AIV field, following the same precedent as the ppWeight
                // and AcceIsfWeight lines above -- reason text survives the NS round-trip and is already
                // regex-parsed on client builds, so no DB column or migration is needed for it.
                rt.reason.append("LowBGrecent: ${if (checkAutomationState("LowBG", "50recent")) "Y" else "N"} ;")
            }
        }
        disposable += persistenceLayer.insertOrUpdateAutoIsfValues(autoIsfValues).subscribe()
        rxBus.send(EventOpenAPSUpdateGui())
    }

    /** Stable, sorted snapshot mirrored to AAPSClient with each AutoISF device-status result. */
    private fun autoIsfSettingsSnapshot(): String {
        val lines = mutableListOf<String>()
        lines.add("configuration_flavor = ${config.FLAVOR}")
        lines.add("configuration_version = ${config.VERSION_NAME}")
        lines.add("configuration_application_id = ${config.APPLICATION_ID}")
        lines.add("configuration_profile = ${profileFunction.getProfileName()}")
        BooleanKey.entries.filter { it.name.contains("AutoIsf") }.forEach { lines.add("${it.key} = ${preferences.get(it)}") }
        IntKey.entries.filter { it.name.contains("AutoIsf") }.forEach { lines.add("${it.key} = ${preferences.get(it)}") }
        DoubleKey.entries.filter { it.name.contains("AutoIsf") }.forEach {
            lines.add("${it.key} = ${String.format(Locale.US, "%.2f", preferences.get(it))}")
        }
        UnitDoubleKey.entries.filter { it.name.contains("AutoIsf") }.forEach {
            lines.add("${it.key} = ${String.format(Locale.US, "%.2f", preferences.get(it))}")
        }
        StringKey.entries.filter { it.name.contains("AutoIsf") }.forEach { lines.add("${it.key} = ${preferences.get(it)}") }
        // These are live calibration values but their enum names do not contain "AutoIsf", so the
        // generic filters above would otherwise omit the day-0/day-1 sensor-age override actually in use.
        lines.add("${DoubleKey.FslCalSlope.key} = ${String.format(Locale.US, "%.2f", preferences.get(DoubleKey.FslCalSlope))}")
        lines.add("${DoubleKey.FslCalOffset.key} = ${String.format(Locale.US, "%.2f", preferences.get(DoubleKey.FslCalOffset))}")
        return lines.sorted().joinToString("\n")
    }

    // UKF-smoothed Raw BG (mg/dL), computed once per cycle and persisted into autoIsfValues.ukfRawBgl /
    // rt.autoIsfUkfRawBgl -- so PrepareBgDataWorker.kt (the graph) and AutoIsfHistoryExporter.kt (the
    // delta columns) can both just READ this value instead of each independently recomputing the UKF
    // smoothing pass over the raw signal, same reuse-not-recalculate intent as everything else that
    // already reads off autoIsfValues rather than redoing its own work.
    //
    // No calibration (slope=1.0, offset=0.0) -- was previously calibrated with a +0.10/-0.5 offset from
    // the live FslCalSlope/FslCalOffset settings, matching PrepareBgDataWorker.kt's graph line; both
    // reverted together to smoothing the raw noise value completely as-is. 60-min lookback: enough
    // context for the UKF's forward pass to settle and its RTS backward pass to have something to smooth
    // against, without querying more history than a single "what's the current smoothed raw value" call
    // actually needs. Uses smoothForDisplay() -- the same stateless, non-adaptive entry point the graph
    // uses -- so this never touches the live BG smoothing pipeline's own persisted/adaptive state either.
    private fun computeUkfRawBgl(): Double {
        val lookbackMs = T.mins(60).msecs()
        val rawReadings = persistenceLayer.getBgReadingsDataFromTimeToTime(dateUtil.now() - lookbackMs, dateUtil.now(), false)
            .filter { it.noise != null && it.noise!! > 10.0 }
            .sortedByDescending { it.timestamp }
        if (rawReadings.isEmpty()) return 0.0
        // Newest-first in, newest-first out (see smoothForDisplay()'s own contract) -- index 0 is the
        // smoothed raw/noise value for "right now", independent of the live AAPS BGL mode.
        return ukfSmoothing.smoothForDisplay(rawReadings.map { it.timestamp to it.noise!! }).firstOrNull() ?: 0.0
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
        String.format(Locale.US, "%.1f", profileUtil.fromMgdlToUnits(value)).replace("-0.0", "0.0")

    private fun deltaForDisplay(valueMgdl: Double): String =
        String.format(Locale.US, "%.2f", profileUtil.fromMgdlToUnits(valueMgdl)).replace("-0.00", "0.00")

    fun convert_isf(value: Double): String =
        String.format("%.1f", profileUtil.fromMgdlToUnits(value))

    fun convert_bg_to_units(value: Double, profile: OapsProfileAutoIsf): Double =
        if (profile.out_units == "mmol/L") value * Constants.MGDL_TO_MMOLL else value

    fun activityMonitor(isTempTarget: Boolean, bg: Double, target_bg: Double, now: Int, sDelta: Double): Double {
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
        if (useSleepState && (recentSteps5Minutes + recentSteps10Minutes + recentSteps15Minutes == recentSteps30Minutes)) {   // no steps between 15m and 30m; was: && now>=inactivity_idle_end) -- dropped deliberately, see AndroidAPS-3426 merge (2026-08-12)
            automationStateService.setState("query_got_up", "query_it")
        }
        aapsLogger.debug(LTag.APS, "State json for got up query: {\"query_got_up\":\"${automationStateService.getState("query_got_up")}\"}")

        if (!activityDetection) {
            consoleLog.add("Activity monitor disabled in settings")
        } else if (isTempTarget) {
            consoleLog.add("Activity monitor disabled: tempTarget")
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
            // phoneMoved only gates ACTIVITY detection below: stale/absent motion data could hide real
            // movement happening elsewhere, so a high step count without phone motion is left unapplied
            // (falls through to neutral/inactivity checks). It does NOT gate inactivity detection --
            // a stationary phone doesn't invalidate a low step count, if anything it corroborates it.
            } else if (phoneMoved && (recentSteps5Minutes > 300 || recentSteps10Minutes > 300 || recentSteps15Minutes > 300 || recentSteps30Minutes > 1500 || recentSteps60Minutes > 2500)) {
                activityRatio = 1 - 0.3 * activity_scale_factor
                consoleLog.add("Activity monitor detected activity, sensitivity ratio: $activityRatio")
            } else if (phoneMoved && (recentSteps5Minutes > 200 || recentSteps10Minutes > 200 || recentSteps15Minutes > 200
                || recentSteps30Minutes > 500 || recentSteps60Minutes > 800)
            ) {
                activityRatio = 1 - 0.15 * activity_scale_factor
                consoleLog.add("Activity monitor detected partial activity, sensitivity ratio: $activityRatio")
            } else if (bg < target_bg && recentSteps60Minutes <= 200) {
                consoleLog.add("Activity monitor disabled inactivity detection: bg < target")
            // Inactivity boost only applies while BG isn't already falling on its own (SDelta not
            // negative) -- avoids stacking extra insulin on top of an already-adequate downward trend;
            // it still engages once BG stalls or starts climbing, which is when it's actually needed.
            } else if (recentSteps60Minutes < 50 && sDelta >= -0.02 * 18) {
                activityRatio = 1 + 0.2 * inactivity_scale_factor
                consoleLog.add("Activity monitor detected inactivity, sensitivity ratio: $activityRatio")
            } else if (recentSteps60Minutes <= 200 && sDelta >= -0.02 * 18) {
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

    // iob default 0.0 (== untapered, full dura_ISF effect) matches currentActivity/smbActivity's own
    // default -- calculateVariableIsf()'s cached/arbitrary-timestamp call site below doesn't have a live
    // IOB any more than it has live activity, same pre-existing limitation (see that call site's comment).
    fun autoISF(
        profile: Profile,
        currentActivity: Double = 0.0,
        smbActivity: Double = 0.0,
        iob: Double = 0.0,
        hpMmol: Double? = null
    ): Double {

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
                consoleError.add("exerciseModeActive or resistanceModeActive sensitivityRatio: ${round(sensitivityRatio, 2)}")
            } else if (stepActivityDetected) {
                sensitivityRatio = activityRatio
                consoleError.add("stepActivityDetected : sensitivityRatio: ${round(activityRatio, 2)}")
            } else if (stepInactivityDetected) {
                sensitivityRatio = activityRatio
                consoleError.add("stepInactivityDetected : sensitivityRatio: ${round(activityRatio, 2)}")
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
        consoleError.add("bgAccel_ISF_weight is ${round(bgAccel_ISF_weight, 2)} ;;")
        consoleError.add("pp_ISF_weight is ${round(pp_ISF_weight, 2)} ;;")
        consoleError.add("iobThresholdPercent is ${iobThresholdPercent} ;;")
        consoleError.add("insulin activity graph: ${round(currentActivity, 2)} ;;")
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
            consoleError.add("acce_ISF adaptation by-passed as correlation ${round(fit_corr, 2)} is too low")
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
                var duraBoost = dura05Weight * avg05Weight * (avg05 - target_bg)
                // IOB-gated taper: dura_ISF's "fight the resistance" boost pushes MORE insulin out (a
                // higher dura_ISF makes sens/final_ISF smaller, i.e. more aggressive, not less), so left
                // unchecked it keeps adding insulin regardless of how much is already on board. Measured
                // against 142 real dura-engagement episodes on real device data (2026-07-25 to 08-09):
                // episodes ending with IOB >= ~2.3U precede a later low (<4.0mmol within 3h) far more often
                // than episodes ending with IOB ~1.0U or less (median IOB-at-episode-end 2.34U for the
                // later-low group vs 1.04U for episodes that resolved cleanly) -- duration and time-of-day
                // were NOT distinguishing between the two groups, only IOB was. So taper the boost itself
                // by current IOB rather than lowering dura_ISF_weight globally, which would blunt the ~73%
                // of engagements that resolve cleanly (those mostly have low IOB already, so the taper
                // leaves them untouched). Never fully zeroed (iobTaperFloor), same "scaled trim, not a
                // hard block" philosophy as the SMB anti-stack escalating trim elsewhere in this file.
                val iobTaperStart = 1.5   // full boost below this -- above the resolved-cleanly median (~1.04U), some margin
                val iobTaperEnd = 3.0     // floor reached at/above this -- around/above the later-low median (~2.34U)
                val iobTaperFloor = 0.3
                val iobTaperFactor = when {
                    hpMmol != null && hpMmol > 7.0 -> 1.0
                    iob <= iobTaperStart           -> 1.0
                    iob >= iobTaperEnd             -> iobTaperFloor
                    else                           -> 1.0 - (1.0 - iobTaperFloor) * (iob - iobTaperStart) / (iobTaperEnd - iobTaperStart)
                }
                if (hpMmol != null && hpMmol > 7.0) {
                    consoleError.add("dura_ISF IOB taper disabled: HP2=${round(hpMmol, 2)} > 7.0")
                }
                if (iobTaperFactor < 1.0) {
                    val beforeTaper = duraBoost
                    duraBoost *= iobTaperFactor
                    lastDuraTaperTimestamp = dateUtil.now()
                    lastDuraTaperInfo = "IOB ${round(iob, 2)}U -> x${round(iobTaperFactor, 2)} (boost ${round(beforeTaper, 2)} -> ${round(duraBoost, 2)})"
                    consoleError.add("dura_ISF IOB taper: $lastDuraTaperInfo")
                }
                dura_ISF += duraBoost
                sens_modified = true
                consoleError.add("dura_ISF adaptation is ${round(dura_ISF, 2)} because ISF ${convert_isf(sens)} did not do it for ${round(dura05, 1)}m")
            }
        }
        autoIsfValues.duraIsf = dura_ISF
        // Unconditional (every cycle, not just cycles where the taper fires) so Script Debug always has
        // *something* for the taper rather than only ever showing it live on the exact cycle it engaged --
        // see lastDuraTaperTimestamp/-Info's own declaration comment for why.
        consoleError.add(
            if (lastDuraTaperTimestamp > 0L) "dura_ISF taper last engaged: ${dateUtil.timeString(lastDuraTaperTimestamp)} (${dateUtil.minAgoShort(lastDuraTaperTimestamp)}) -- $lastDuraTaperInfo"
            else "dura_ISF taper: not engaged since app start"
        )

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
            consoleError.add("weakest autoISF factor ${round(liftISF, 2)} limited by autoISF_min ${round(minISFReduction, 2)}")
            liftISFlimited = minISFReduction
        } else if (liftISF > maxISFReduction) {
            consoleError.add("strongest autoISF factor ${round(liftISF, 2)} limited by autoISF_max ${round(maxISFReduction, 2)}")
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
        if (smb_delivery_ratio_bg_range == 0.0) {                     // deactivated in SMB extended menu
            // Checked before fullLoop: with the BG range at 0 there is no interpolation (new_SMB stays
            // == fix_SMB), so fullLoop's max(fix, new) would equal fix_SMB anyway — returning the fixed
            // value here is identical, and logs the accurate "fixed value" message instead of the
            // misleading "max of fixed and interpolated".
            consoleLog.add("SMB delivery ratio set to fixed value ${round(fix_SMB, 2)}")
            return fix_SMB
        }
        if (loop_wanted_smb == "fullLoop") {                                // go for max impact
            consoleLog.add("SMB delivery ratio set to ${round(max(fix_SMB, new_SMB), 2)} as max of fixed and interpolated values")
            return max(fix_SMB, new_SMB)
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
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled, summary = R.string.mj_kotlin_buttons_enabled_summary, title = R.string.mj_kotlin_buttons_enabled_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled, summary = R.string.steroid_kotlin_button_enabled_summary, title = R.string.steroid_kotlin_button_enabled_title))
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
                    addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfOldSensorAdjEnabled, summary = R.string.old_sensor_adj_enabled_summary, title = R.string.old_sensor_adj_enabled_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfLibreSlopeOrig, dialogMessage = R.string.autoisf_libre_slope_orig_summary, title = R.string.autoisf_libre_slope_orig_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfLibreOffsetOrig, dialogMessage = R.string.autoisf_libre_offset_orig_summary, title = R.string.autoisf_libre_offset_orig_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.MaintenanceCleanupDays, dialogMessage = R.string.MaintenanceCleanupDays_summary, title = R.string.MaintenanceCleanupDays_title))
                })
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseAutoIsfWeights, summary = R.string.openapsama_enable_autoISF, title = R.string.openapsama_enable_autoISF))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfMin, dialogMessage = R.string.openapsama_autoISF_min_summary, title = R.string.openapsama_autoISF_min))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfMax, dialogMessage = R.string.openapsama_autoISF_max_summary, title = R.string.openapsama_autoISF_max))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfMaxLow, dialogMessage = R.string.openapsama_autoISF_max_low_summary, title = R.string.openapsama_autoISF_max_low))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfBgAccelWeight, dialogMessage = R.string.openapsama_bgAccel_ISF_weight_summary, title = R.string.openapsama_bgAccel_ISF_weight))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfBgAccelWeightNormal, dialogMessage = R.string.autoisf_bgaccel_isf_weight_normal_summary, title = R.string.autoisf_bgaccel_isf_weight_normal_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfBgAccelWeightHigh, dialogMessage = R.string.autoisf_bgaccel_isf_weight_high_summary, title = R.string.autoisf_bgaccel_isf_weight_high_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfBgBrakeWeight, dialogMessage = R.string.openapsama_bgBrake_ISF_weight_summary, title = R.string.openapsama_bgBrake_ISF_weight))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfLowBgWeight, dialogMessage = R.string.openapsama_lower_ISFrange_weight_summary, title = R.string.openapsama_lower_ISFrange_weight))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfHighBgWeight, dialogMessage = R.string.openapsama_higher_ISFrange_weight_summary, title = R.string.openapsama_higher_ISFrange_weight))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfPpWeight, dialogMessage = R.string.openapsama_pp_ISF_weight_summary, title = R.string.openapsama_pp_ISF_weight))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfPpWeightNormal, dialogMessage = R.string.autoisf_pp_isf_weight_normal_summary, title = R.string.autoisf_pp_isf_weight_normal_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfPpWeightHigh, dialogMessage = R.string.autoisf_pp_isf_weight_high_summary, title = R.string.autoisf_pp_isf_weight_high_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfDuraWeight, dialogMessage = R.string.openapsama_dura_ISF_weight_summary, title = R.string.openapsama_dura_ISF_weight))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfDuraWeightNormal, dialogMessage = R.string.autoisf_dura_isf_weight_normal_summary, title = R.string.autoisf_dura_isf_weight_normal_title))
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
                    addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfSmbOffsetOverrideEnabled, summary = R.string.autoisf_smb_offset_override_enabled_summary, title = R.string.autoisf_smb_offset_override_enabled_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfSmbOffsetOverride, dialogMessage = R.string.autoisf_smb_offset_override_summary, title = R.string.autoisf_smb_offset_override_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfTodOffset0002, dialogMessage = R.string.autoisf_tod_offset_0002_summary, title = R.string.autoisf_tod_offset_0002_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfTodOffset0204, dialogMessage = R.string.autoisf_tod_offset_0204_summary, title = R.string.autoisf_tod_offset_0204_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfTodOffset0406, dialogMessage = R.string.autoisf_tod_offset_0406_summary, title = R.string.autoisf_tod_offset_0406_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfTodOffset0609, dialogMessage = R.string.autoisf_tod_offset_0609_summary, title = R.string.autoisf_tod_offset_0609_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfTodOffset0912, dialogMessage = R.string.autoisf_tod_offset_0912_summary, title = R.string.autoisf_tod_offset_0912_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfTodOffset1218, dialogMessage = R.string.autoisf_tod_offset_1218_summary, title = R.string.autoisf_tod_offset_1218_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfTodOffset1822, dialogMessage = R.string.autoisf_tod_offset_1822_summary, title = R.string.autoisf_tod_offset_1822_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfTodOffset2200, dialogMessage = R.string.autoisf_tod_offset_2200_summary, title = R.string.autoisf_tod_offset_2200_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfSmbDeliveryBaseline, dialogMessage = R.string.autoisf_smb_delivery_baseline_summary, title = R.string.autoisf_smb_delivery_baseline_title))
                    addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfBoostAutomationsEnabled, summary = R.string.autoisf_boost_automations_enabled_summary, title = R.string.autoisf_boost_automations_enabled_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfMildBoostRatio, dialogMessage = R.string.autoisf_mild_boost_ratio_summary, title = R.string.autoisf_mild_boost_ratio_title))
                    addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfSmbOnEvenTarget, summary = R.string.enableSMB_EvenOn_OddOff_always_summary, title = R.string.enableSMB_EvenOn_OddOff_always))
                    addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfSplitBolusEnabled, summary = R.string.split_bolus_enabled_summary, title = R.string.split_bolus_enabled_title))
                    addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfShowCarbModelCurve, summary = R.string.show_carb_model_curve_summary, title = R.string.show_carb_model_curve_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsAutoIsfSplitBolusInterval, dialogMessage = R.string.split_bolus_interval_summary, title = R.string.split_bolus_interval_title))
                    addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfTddSensitivity, summary = R.string.autoisf_tdd_sensitivity_summary, title = R.string.autoisf_tdd_sensitivity))
                    addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfTddFactor, summary = R.string.autoisf_tdd_factor_summary, title = R.string.autoisf_tdd_factor))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfTddFactorFallback, dialogMessage = R.string.autoisf_tdd_factor_fallback_summary, title = R.string.autoisf_tdd_factor_fallback_title))
                })
            })
        }
    }
}

/*
OpenAPSAutoISFPlugin.ktaisf321UK_616
*/
