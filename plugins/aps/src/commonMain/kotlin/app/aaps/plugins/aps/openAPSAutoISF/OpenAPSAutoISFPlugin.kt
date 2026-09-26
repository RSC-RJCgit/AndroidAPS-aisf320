package app.aaps.plugins.aps.openAPSAutoISF

import androidx.collection.LongSparseArray
import androidx.collection.forEach
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.AIV
import app.aaps.core.data.model.LiveSteps
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.data.model.getPassedDurationToTimeInMinutes
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CodedProfileRoles
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.receivers.ReceiverStatusStore
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.smoothing.DisplayRawSmoothing
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.maintenance.Maintenance
import app.aaps.core.interfaces.smsCommunicator.Sms
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.DoubleNonKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef.Companion.withArgs
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.target
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.icons.IcPluginOpenAPS
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.utils.MidnightUtils
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.aps.keys.ApsIntentKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import app.aaps.core.interfaces.rx.events.EventAutoIsfDirectTtCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToLong

@SingleIn(AppScope::class)
@Inject
open class OpenAPSAutoISFPlugin(
    aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val constraintsChecker: ConstraintsChecker,
    override val rh: TextResolver,
    private val profileFunction: ProfileFunction,
    private val profileRepository: ProfileRepository,
    private val profileUtil: ProfileUtil,
    private val config: Config,
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val hardLimits: HardLimits,
    preferences: Preferences,
    protected val dateUtil: DateUtil,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val persistenceLayer: PersistenceLayer,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val bgQualityCheck: BgQualityCheck,
    notificationManager: NotificationManager,
    private val determineBasalAutoISF: DetermineBasalAutoISF,
    private val profiler: Profiler,
    private val glucoseStatusCalculatorAutoIsf: GlucoseStatusCalculatorAutoIsf,
    private val apsResultProvider: () -> APSResult,
    private val ch: ConcentrationHelper,
    private val tddCalculator: TddCalculator,
    private val displayRawSmoothing: DisplayRawSmoothing,
    private val receiverStatusStore: ReceiverStatusStore,
    private val smsCommunicator: SmsCommunicator,
    private val maintenance: Maintenance,
) : PluginBaseWithPreferences(
    PluginDescription()
        .mainType(PluginType.APS)
        .composeContent { plugin ->
            app.aaps.plugins.aps.compose.OpenAPSComposeContent(
                apsPlugin = plugin as APS,
                rxBus = rxBus,
                rh = rh,
                dateUtil = dateUtil
            )
        }
        .icon(IcPluginOpenAPS)
        .pluginName(ApsStrings.openaps_auto_isf)
        .shortName(ApsStrings.autoisf_shortname)
        .preferencesVisibleInSimpleMode(false)
        .showInList { (config.APS || config.AAPSCLIENT) && config.isEngineeringMode() && config.isDev() }   // AAPSCLIENT: visible so a client can select the master's APS (still eng+dev only)
        .description(ApsStrings.description_auto_isf),
    ownPreferences = ApsIntentKey.entries,
    aapsLogger, rh, preferences, notificationManager
), APS, PluginConstraints, CodedProfileRoles {

    // last values
    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.AUTO_ISF
    override var lastAPSResult: APSResult? = null
    private var consoleError = mutableListOf<String>()
    private var consoleLog = mutableListOf<String>()
    val autoIsfVersion = "3.0.1"
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
        get() = if (preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange) < 10.0) preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange) * Constants.MMOLL_TO_MGDL else preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange)
    val smbMaxRangeExtension; get() = preferences.get(DoubleKey.ApsAutoIsfSmbMaxRangeExtension)
    private val enableSMB_EvenOn_OddOff_always; get() = preferences.get(BooleanKey.ApsAutoIsfSmbOnEvenTarget) // for profile target
    val iobThresholdPercent; get() = preferences.get(IntKey.ApsAutoIsfIobThPercent)
    private val exerciseMode; get() = SMBDefaults.exercise_mode
    private val highTemptargetRaisesSensitivity; get() = preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens)
    val normalTarget = Constants.NORMAL_TARGET_MGDL
    private val minutesClass; get() = if (preferences.get(IntKey.ApsMaxSmbFrequency) == 1) 6L else 30L  // ga-zelle: later get correct 1 min CGM flag from glucoseStatus ? ... or from apsResults?
    private val runMarks = RunMarks()
    private var processStartedAtMs: Long = 0L
    private var automationStates: AutomationStateStore? = null

    private fun states(): AutomationStateStore {
        automationStates?.let { return it }
        val store = AutomationStateStore(
            currentJson = preferences.get(StringNonKey.AutomationCurrentStates),
            valuesJson = preferences.get(StringNonKey.AutomationStateValues),
            saveCurrent = { preferences.put(StringNonKey.AutomationCurrentStates, it) },
            saveValues = { preferences.put(StringNonKey.AutomationStateValues, it) },
        )
        automationStates = store
        return store
    }

    override fun markSteroidsOff() {
        val required = requiredAutomationStates["Steroids"] ?: return
        val store = states()
        store.ensureDeclared("Steroids", required.values, required.defaultValue)
        store.setState("Steroids", "Steroids Off")
    }

    private var list1Job: Job? = null

    override suspend fun onStart() {
        super.onStart()
        if (processStartedAtMs == 0L) processStartedAtMs = dateUtil.now()
        requiredAutomationStates.forEach { (name, required) ->
            states().ensureDeclared(name, required.values, required.defaultValue)
        }
        var count = 0
        val apsResults = persistenceLayer.getApsResults(dateUtil.now() - T.days(1).msecs(), dateUtil.now())
        apsResults.forEach {
            val glucose = it.glucoseStatus?.glucose ?: return@forEach
            val variableSens = it.variableSens ?: return@forEach
            val timestamp = it.date
            val key = timestamp - timestamp % T.mins(minutesClass).msecs() + glucose.toLong()
            if (variableSens > 0) isfCacheLock.withLock { autoIsfCache.put(key, variableSens) }
            count++
        }
        aapsLogger.debug(LTag.APS, "Loaded $count variable sensitivity values from database")
        if (list1Job == null) {
            list1Job = pluginScope.launch {
                rxBus.toFlow(EventAutoIsfDirectTtCode::class).collect { event ->
                    applyDirectListCode(event.mmol)
                }
            }
        }
    }

    override fun usingDynamicIsf() = true //: Boolean = preferences.get(BooleanKey.ApsUseAutoIsf)

    override fun getIsfMgdl(profile: Profile, caller: String): Double? {
        val start = dateUtil.now()
        val multiplier = (profile as ProfileSealed.EPS).value.originalPercentage / 100.0
        val sensitivity = runBlocking { calculateVariableIsf(start) }
        if (sensitivity.second == null && caller == "OpenAPSSMBPlugin")
            notificationManager.post(
                NotificationId.DYN_ISF_FALLBACK,
                ApsStrings.fallback_to_isf_no_tdd.withArgs(sensitivity.first),
                level = NotificationLevel.INFO,
                date = start,
                validTo = dateUtil.now() + T.mins(1).msecs())
        else
            notificationManager.dismiss(NotificationId.DYN_ISF_FALLBACK)
        profiler.log(LTag.APS, "getIsfMgdl() ${sensitivity.first} ${sensitivity.second} ${dateUtil.dateAndTimeAndSecondsString(start)} $caller", start)
        return sensitivity.second?.let { it * multiplier }
    }

    override fun getAverageIsfMgdl(timestamp: Long, caller: String): Double? {
        var count = 0
        var sum = 0.0
        val start = timestamp - T.hours(24).msecs()
        isfCacheLock.withLock {
            autoIsfCache.forEach { key, value ->
                if (key in start..timestamp) {
                    count++
                    sum += value
                }
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

    override fun specialShowInListCondition(): Boolean {
        try {
            val pump = activePlugin.activePump
            return pump.pumpDescription.isTempBasalCapable
        } catch (_: Exception) {
            return true
        }
    }

    private val autoIsfCache = LongSparseArray<Double>()

    /** Guards [autoIsfCache]. Was `synchronized(autoIsfCache)`, which is JVM only. */
    private val isfCacheLock = AapsLock()

    private suspend fun calculateVariableIsf(timestamp: Long): Pair<String, Double?> {
        val profile = profileFunction.getProfile(timestamp) ?: return Pair("OFF", null)
        val glucose = glucoseStatusProvider.glucoseStatusData?.glucose ?: return Pair("GLUC", null)
        // Round down to minutesClass min and use it as a key for caching
        // Add BG to key as it affects calculation
        val key = timestamp - timestamp % T.mins(minutesClass).msecs() + glucose.toLong()
        val sensitivity = autoISF(profile)
        if (sensitivity > 0) {
            // can default to 0, e.g. for the first 2-3 loops in a virgin setup
            aapsLogger.debug("calculateVariableIsf CALC ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $sensitivity")
            isfCacheLock.withLock {
                autoIsfCache.put(key, sensitivity)
                if (autoIsfCache.size() > 1000) autoIsfCache.clear()
            }
        }
        // this return is mandatory, otherwise it messed up the AutoISF algo.
        return Pair("OFF", null)
    }

    override suspend fun invoke(initiator: String, tempBasalFallback: Boolean) = withContext(Dispatchers.Default) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator tempBasalFallback: $tempBasalFallback")
        lastAPSResult = null
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val profile = profileFunction.getProfile()
        val pump = activePlugin.activePump
        if (profile == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(CoreUiStrings.no_profile_set)))
            aapsLogger.debug(LTag.APS, rh.gs(CoreUiStrings.no_profile_set))
            return@withContext
        }
        if (!isEnabled()) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.openapsma_disabled)))
            aapsLogger.debug(LTag.APS, rh.gs(ApsStrings.openapsma_disabled))
            return@withContext
        }
        if (glucoseStatus == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.openapsma_no_glucose_data)))
            aapsLogger.debug(LTag.APS, rh.gs(ApsStrings.openapsma_no_glucose_data))
            return@withContext
        }

        val inputConstraints = ConstraintObject(0.0, aapsLogger) // fake. only for collecting all results

        if (!hardLimits.checkHardLimits(profile.iCfg.dia, InterfacesStrings.profile_dia, hardLimits.diaRange())) return@withContext
        if (!hardLimits.checkHardLimits(
                profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()),
                InterfacesStrings.profile_carbs_ratio_value,
                hardLimits.icRange()
            )
        ) return@withContext
        if (!hardLimits.checkHardLimits(profile.getIsfMgdl("OpenAPSAutoISFPlugin"), InterfacesStrings.profile_sensitivity_value, HardLimits.LIMIT_ISF)) return@withContext
        if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), CoreUiStrings.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return@withContext
        if (!hardLimits.checkHardLimits(ch.fromPump(pump.baseBasalRate), CoreUiStrings.current_basal_value, 0.01, hardLimits.maxBasal())) return@withContext

        // End of check, start gathering data

        val autoIsfMode = usingDynamicIsf()  // preferences.get(BooleanKey.ApsUseAutoIsf)
        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val advancedFiltering = constraintsChecker.isAdvancedFilteringEnabled().also { inputConstraints.copyReasons(it) }.value()

        val now = dateUtil.now()
        if (usesLiveSteps() && freshLiveSteps(now) == null) {
            aapsLogger.debug(LTag.APS, "Live steps missing or older than 20 minutes. Calculation skipped.")
            rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.live_steps_missing)))
            return@withContext
        }
        val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        val currentTemp = CurrentTemp(
            duration = tb?.plannedRemainingMinutes ?: 0,
            rate = tb?.convertedToAbsolute(now, profile) ?: 0.0,
            minutesrunning = tb?.getPassedDurationToTimeInMinutes(now)
        )
        var minBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetLowMgdl(), 0.1), InterfacesStrings.profile_low_target, HardLimits.LIMIT_MIN_BG)
        var maxBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetHighMgdl(), 0.1), InterfacesStrings.profile_high_target, HardLimits.LIMIT_MAX_BG)
        var targetBg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), CoreUiStrings.temp_target_value, HardLimits.LIMIT_TARGET_BG)
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            minBg = hardLimits.verifyHardLimits(tempTarget.lowTarget, CoreUiStrings.temp_target_low_target, HardLimits.LIMIT_TEMP_MIN_BG)
            maxBg = hardLimits.verifyHardLimits(tempTarget.highTarget, CoreUiStrings.temp_target_high_target, HardLimits.LIMIT_TEMP_MAX_BG)
            targetBg = hardLimits.verifyHardLimits(tempTarget.target(), CoreUiStrings.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG)
        }

        var autosensResult = AutosensResult()
        var variableSensitivity = profile.getProfileIsfMgdl()
        val sens = profile.getIsfMgdl("OpenAPSAutoISFPlugin")
        val autoIsfFactors = AutoIsfFactors()

        if (constraintsChecker.isAutosensModeEnabled().value()) {
            val autosensData = iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSAutoISFPlugin")
            if (autosensData == null) {
                rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.openaps_no_as_data)))
                return@withContext
            }
            autosensResult = autosensData.autosensResult
        } else autosensResult.sensResult = "autosens disabled"
        val iobArray = iobCobCalculator.calculateIobArrayForSMB(autosensResult, SMBDefaults.exercise_mode, preferences.get(IntKey.ApsAutoIsfHalfBasalExerciseTarget), isTempTarget)
        val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()
        val iobData = iobArray[0]
        val profile_percentage = if (profile is ProfileSealed.EPS) profile.value.originalPercentage else 100
        val microBolusAllowed = constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).also { inputConstraints.copyReasons(it) }.value()

        if (autoIsfMode) {
            consoleError = mutableListOf()
            consoleLog = mutableListOf()
            variableSensitivity = autoISF(profile, autoIsfFactors)
        }
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
            half_basal_exercise_target = preferences.get(IntKey.ApsAutoIsfHalfBasalExerciseTarget),
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
            current_basal = ch.fromPump(activePlugin.activePump.baseBasalRate),
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

        // Refuse to run the algorithm with degenerate ISF inputs — division by these would produce NaN/Infinity in
        // the result. carb_ratio feeds csf (sens/carb_ratio) and autosensResult.ratio becomes the non-autoISF
        // sensitivityRatio (sens = profile.sens/sensitivityRatio); a non-finite/≤0 value of any cascades to a NaN
        // carbsReq (round() crash). Mirrors the OpenAPSSMBPlugin guard.
        val invalidInputs = !oapsProfile.sens.isFinite() || oapsProfile.sens <= 0.0 ||
            !oapsProfile.carb_ratio.isFinite() || oapsProfile.carb_ratio <= 0.0 ||
            !autosensResult.ratio.isFinite() || autosensResult.ratio <= 0.0 ||
            (autoIsfMode && (!oapsProfile.variable_sens.isFinite() || oapsProfile.variable_sens <= 0.0))
        if (invalidInputs) {
            val msg = "OpenAPS AutoISF aborting: invalid ISF inputs " +
                "autoIsfMode=$autoIsfMode sens=${oapsProfile.sens} carb_ratio=${oapsProfile.carb_ratio} " +
                "autosensRatio=${autosensResult.ratio} variable_sens=${oapsProfile.variable_sens}"
            aapsLogger.error(LTag.APS, msg)
            rxBus.send(EventResetOpenAPSGui(msg))
            return@withContext
        }
        //done calculate exercise ratio
        var exerciseRatio = 1.0
        // TODO eliminate
        val target_bg = (minBg + maxBg) / 2
        if (highTemptargetRaisesSensitivity && isTempTarget && target_bg > normalTarget
            || oapsProfile.low_temptarget_lowers_sensitivity && isTempTarget && target_bg < normalTarget
        ) {
            // w/ target 100, temp target 110 = .89, 120 = 0.8, 140 = 0.67, 160 = .57, and 200 = .44
            // e.g.: Sensitivity ratio set to 0.8 based on temp target of 120; Adjusting basal from 1.65 to 1.35; ISF from 58.9 to 73.6
            //sensitivityRatio = 2/(2+(target_bg-normalTarget)/40);
            val c = (oapsProfile.half_basal_exercise_target - normalTarget).toDouble()
            if (c * (c + target_bg - normalTarget) > 0.0) {
                var sensitivityRatio = c / (c + target_bg - normalTarget)
                // limit sensitivityRatio to profile.autosens_max (1.2x by default)
                sensitivityRatio = min(sensitivityRatio, preferences.get(DoubleKey.AutosensMax))
                sensitivityRatio = round(sensitivityRatio, 2)
                exerciseRatio = sensitivityRatio
            }
        }
        var iobTH_reduction_ratio = 1.0
        var use_iobTH = false
        if (iobThresholdPercent != 100) {
            iobTH_reduction_ratio = profile_percentage / 100.0 * exerciseRatio
            use_iobTH = true
        }
        val iobTHtolerance = 130.0
        val iobTHvirtual = iobThresholdPercent * iobTHtolerance / 10000.0 * oapsProfile.max_iob * iobTH_reduction_ratio
        val loopWantedSmb = loop_smb(microBolusAllowed, oapsProfile, iobData.iob, use_iobTH, iobTHvirtual / iobTHtolerance * 100.0)
        val flatBGsDetected = bgQualityCheck.state == BgQualityCheck.State.FLAT
        val smbRatio = determine_varSMBratio(glucoseStatus.glucose.toInt(), target_bg, loopWantedSmb)

        aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal AutoISF <<<")
        aapsLogger.debug(LTag.APS, "Glucose status:     $glucoseStatus")
        aapsLogger.debug(LTag.APS, "Current temp:       $currentTemp")
        aapsLogger.debug(LTag.APS, "IOB data:           ${iobArray.joinToString()}")
        aapsLogger.debug(LTag.APS, "Profile:            $oapsProfile")
        aapsLogger.debug(LTag.APS, "Autosens data:      $autosensResult")
        aapsLogger.debug(LTag.APS, "Meal data:          $mealData")
        aapsLogger.debug(LTag.APS, "MicroBolusAllowed:  $microBolusAllowed")
        aapsLogger.debug(LTag.APS, "flatBGsDetected:    $flatBGsDetected")
        aapsLogger.debug(LTag.APS, "AutoIsfMode:        $autoIsfMode")
        //aapsLogger.debug(LTag.APS, "AutoISF extras:     ${Json.encodeToString(OapsProfile.serializer(), oapsProfile)}")

        val raw5 = rawDelta5MinMgdl(now)
        val ukf = ukfRawNow(now)
        val smb10 = smbSum(now, 10 * 60 * 1000L)
        val sub75Note = updateSub75Mark(runMarks, now, glucoseStatus.glucose, glucoseStatus.delta, smb10)
        if (sub75Note == "arm") aapsLogger.debug(LTag.APS, "sc7.5 cooldown armed, 10 min SMB $smb10")
        if (sub75Note == "clear") aapsLogger.debug(LTag.APS, "sc7.5 cooldown cleared")
        val uamRecent = runMarks.recent(RunMark.UAM_BST, 20, now)
        val minuteOfDay = Instant.fromEpochMilliseconds(now).toLocalDateTime(TimeZone.currentSystemDefault()).let { it.hour * 60 + it.minute }
        val stepSample = if (usesLiveSteps()) freshLiveSteps(now)
        else persistenceLayer.getLastStepsCountFromTimeToTime(now - 30 * 60 * 1000L, now)
        val statesOn = preferences.get(BooleanKey.AutomationStatesEnabled)
        if (nightFrSkipShouldFire(
                ready = runMarks.ready(RunMark.NIGHT_FR_SKIP, 60, now),
                profilePercent = profile_percentage,
                tempTargetSet = isTempTarget,
                boostAutomationsOn = preferences.get(BooleanKey.ApsAutoIsfBoostAutomationsEnabled),
                minuteOfDay = minuteOfDay,
                bg = glucoseStatus.glucose,
                delta = glucoseStatus.delta,
                shortDelta = glucoseStatus.shortAvgDelta,
                rawDelta5 = ukf.delta5 ?: -9999.0,
                iob = iobData.iob,
                smbSum10 = smb10,
                lowBgRecent = statesOn && states().inState("LowBG", "50recent"),
                mjActive = statesOn && states().inState("MJ", "MJ active"),
                steps5 = stepSample?.steps5min ?: 0,
                steps30 = stepSample?.steps30min ?: 0,
            )
        ) {
            runMarks.mark(RunMark.NIGHT_FR_SKIP, now)
            sendAutoSms("NightFrSkip: g=${decimals(glucoseStatus.glucose / 18.016, 1)} d=${decimals(glucoseStatus.delta / 18.016, 2)} iob=${decimals(iobData.iob, 2)}")
            carePortalNote("NtFRSk")
            aapsLogger.debug(LTag.APS, "NightFrSkip marked")
        }
        applyDeliveryRestore(
            now = now,
            tempTargetSet = isTempTarget,
            mealCob = mealData.mealCOB,
        )
        applyOldPodBoost(
            now = now,
            bg = glucoseStatus.glucose,
            delta = glucoseStatus.delta,
            maxIob = oapsProfile.max_iob,
            tempTargetSet = isTempTarget,
        )
        if (applyRemoteToggles(now)) isTempTarget = false
        smbBoostedThisCycle = false
        markBolusBoosts(
            now = now,
            profilePercent = profile_percentage,
            tempTargetSet = isTempTarget,
            minuteOfDay = minuteOfDay,
            bg = glucoseStatus.glucose,
            delta = glucoseStatus.delta,
            shortDelta = glucoseStatus.shortAvgDelta,
            longDelta = glucoseStatus.longAvgDelta,
            rawDelta5 = ukf.delta5 ?: -9999.0,
            cob = mealData.mealCOB,
            iob = iobData.iob,
            statesOn = statesOn,
            steps5 = stepSample?.steps5min ?: 0,
            steps30 = stepSample?.steps30min ?: 0,
            ukfDelta1 = ukf.delta1 ?: -9999.0,
        )
        applyOvernightDuraRescue(
            now = now,
            minuteOfDay = minuteOfDay,
            bg = glucoseStatus.glucose,
            shortDelta = glucoseStatus.shortAvgDelta,
            longDelta = glucoseStatus.longAvgDelta,
            factors = autoIsfFactors,
            smbSum30 = smbSum(now, 30 * 60 * 1000L),
            statesOn = statesOn,
        )
        applyHighNight(
            now = now,
            minuteOfDay = minuteOfDay,
            tempTargetSet = isTempTarget,
            bg = glucoseStatus.glucose,
            delta = glucoseStatus.delta,
            shortDelta = glucoseStatus.shortAvgDelta,
            longDelta = glucoseStatus.longAvgDelta,
            statesOn = statesOn,
        )
        applyBasalUp(
            now = now,
            minuteOfDay = minuteOfDay,
            bg = glucoseStatus.glucose,
            delta = glucoseStatus.delta,
            profilePercent = profile_percentage,
            statesOn = statesOn,
        )
        applyPersistentRise(
            now = now,
            minuteOfDay = minuteOfDay,
            delta = glucoseStatus.delta,
            bg = glucoseStatus.glucose,
            tempTargetSet = isTempTarget,
            statesOn = statesOn,
            steps5 = stepSample?.steps5min ?: 0,
            steps30 = stepSample?.steps30min ?: 0,
        )
        applyHighBrakes(
            now = now,
            minuteOfDay = minuteOfDay,
            bg = glucoseStatus.glucose,
            delta = glucoseStatus.delta,
            shortDelta = glucoseStatus.shortAvgDelta,
            longDelta = glucoseStatus.longAvgDelta,
            ukfDelta5 = ukf.delta5,
            ukfDelta15 = ukf.delta15,
            factors = autoIsfFactors,
            iob = iobData.iob,
            cob = mealData.mealCOB,
            rawDelta5 = raw5,
            statesOn = statesOn,
        )
        applyHigh6(
            now = now,
            minuteOfDay = minuteOfDay,
            bg = glucoseStatus.glucose,
            delta = glucoseStatus.delta,
            shortDelta = glucoseStatus.shortAvgDelta,
            longDelta = glucoseStatus.longAvgDelta,
            profilePercent = profile_percentage,
            cob = mealData.mealCOB,
            statesOn = statesOn,
        )
        applyPodBoosts(
            now = now,
            minuteOfDay = minuteOfDay,
            bg = glucoseStatus.glucose,
            delta = glucoseStatus.delta,
            profilePercent = profile_percentage,
            cob = mealData.mealCOB,
            iob = iobData.iob,
            statesOn = statesOn,
            livePump = activePlugin.activePump !is VirtualPump,
        )
        revertRaisedWeights(
            now = now,
            bg = glucoseStatus.glucose,
            steps5 = stepSample?.steps5min ?: 0,
            steps30 = stepSample?.steps30min ?: 0,
        )
        revertRaisedDose(
            now = now,
            bg = glucoseStatus.glucose,
            profilePercent = profile_percentage,
            steps5 = stepSample?.steps5min ?: 0,
            steps30 = stepSample?.steps30min ?: 0,
        )
        applyActivityProf50(
            now = now,
            profilePercent = profile_percentage,
            profileIsBolus = statesOn && states().inState("Profile", "Bolus"),
            lowTargetMgdl = if (isTempTarget) persistenceLayer.getTemporaryTargetActiveAt(now)?.lowTarget else null,
            bg = glucoseStatus.glucose,
            delta = glucoseStatus.delta,
        )
        applyActivityOff(
            now = now,
            profilePercent = profile_percentage,
            lowTargetMgdl = if (isTempTarget) persistenceLayer.getTemporaryTargetActiveAt(now)?.lowTarget else null,
            bg = glucoseStatus.glucose,
            delta = glucoseStatus.delta,
        )
        applyHypo50(
            now = now,
            minuteOfDay = minuteOfDay,
            bg = glucoseStatus.glucose,
            delta = glucoseStatus.delta,
            shortDelta = glucoseStatus.shortAvgDelta,
            longDelta = glucoseStatus.longAvgDelta,
            profilePercent = profile_percentage,
            cob = mealData.mealCOB,
            iob = iobData.iob,
            statesOn = statesOn,
            steps30 = stepSample?.steps30min ?: 0,
            steps60 = stepSample?.steps60min ?: 0,
            livePump = activePlugin.activePump !is VirtualPump,
        )
        applyMjCycle(
            now = now,
            minuteOfDay = minuteOfDay,
            bg = glucoseStatus.glucose,
            delta = glucoseStatus.delta,
            shortDelta = glucoseStatus.shortAvgDelta,
            profilePercent = profile_percentage,
            cob = mealData.mealCOB,
            iob = iobData.iob,
            statesOn = statesOn,
            steps60 = stepSample?.steps60min ?: 0,
            steps180 = stepSample?.steps180min ?: 0,
        )
        applyRescue(
            now = now,
            minuteOfDay = minuteOfDay,
            bg = glucoseStatus.glucose,
            delta = glucoseStatus.delta,
            shortDelta = glucoseStatus.shortAvgDelta,
            longDelta = glucoseStatus.longAvgDelta,
            profilePercent = profile_percentage,
            cob = mealData.mealCOB,
            iob = iobData.iob,
            maxIob = oapsProfile.max_iob,
            targetBg = targetBg,
            statesOn = statesOn,
        )
        applyProfileBatch(now = now, bg = glucoseStatus.glucose)
        applyTtExits(
            now = now,
            minuteOfDay = minuteOfDay,
            bg = glucoseStatus.glucose,
            delta = glucoseStatus.delta,
            shortDelta = glucoseStatus.shortAvgDelta,
            longDelta = glucoseStatus.longAvgDelta,
            profilePercent = profile_percentage,
            cob = mealData.mealCOB,
            iob = iobData.iob,
            statesOn = statesOn,
        )
        applyEvening(
            now = now,
            minuteOfDay = minuteOfDay,
            bg = glucoseStatus.glucose,
            delta = glucoseStatus.delta,
            shortDelta = glucoseStatus.shortAvgDelta,
            longDelta = glucoseStatus.longAvgDelta,
            profilePercent = profile_percentage,
            cob = mealData.mealCOB,
            iob = iobData.iob,
            statesOn = statesOn,
            steps60 = stepSample?.steps60min ?: 0,
            steps180 = stepSample?.steps180min ?: 0,
        )
        applySensorNotes(
            now = now,
            bg = glucoseStatus.glucose,
            delta = glucoseStatus.delta,
            shortDelta = glucoseStatus.shortAvgDelta,
        )
        applyStepsSteroidsOff(
            now = now,
            bg = glucoseStatus.glucose,
            delta = glucoseStatus.delta,
            cob = mealData.mealCOB,
            iob = iobData.iob,
            steps60 = stepSample?.steps60min ?: 0,
            statesOn = statesOn,
        )
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
            auto_isf_consoleError = consoleError,
            auto_isf_consoleLog = consoleLog,
            fastRiseSettingOn = preferences.get(BooleanKey.ApsAutoIsfFastRiseEnabled),
            libreActive = libreActive(now),
            rawDelta5Mgdl = ukf.delta5 ?: 0.0,
            aapsDelta1Mgdl = aapsDelta1MinMgdl(now) ?: 0.0,
            hour = Instant.fromEpochMilliseconds(now).toLocalDateTime(TimeZone.currentSystemDefault()).hour,
            todOffsetMgdl = todOffsetMmol(
                hour = Instant.fromEpochMilliseconds(now).toLocalDateTime(TimeZone.currentSystemDefault()).hour,
                offset0002 = preferences.get(DoubleKey.ApsAutoIsfTodOffset0002),
                offset0204 = preferences.get(DoubleKey.ApsAutoIsfTodOffset0204),
                offset0406 = preferences.get(DoubleKey.ApsAutoIsfTodOffset0406),
                offset0609 = preferences.get(DoubleKey.ApsAutoIsfTodOffset0609),
                offset0912 = preferences.get(DoubleKey.ApsAutoIsfTodOffset0912),
                offset1218 = preferences.get(DoubleKey.ApsAutoIsfTodOffset1218),
                offset1822 = preferences.get(DoubleKey.ApsAutoIsfTodOffset1822),
                offset2200 = preferences.get(DoubleKey.ApsAutoIsfTodOffset2200),
            ) * 18.0,
            lastAlarmHypoAt = preferences.get(LongNonKey.ApsAutoIsfLastAlarmHypoAt),
            lowReboundGuardEnabled = preferences.get(BooleanKey.ApsAutoIsfLowReboundGuardEnabled),
            steps60 = steps60(now),
            iobThUser = iobThresholdPercent,
            bgAcceleration = (glucoseStatus as? GlucoseStatusAutoIsf)?.bgAcceleration ?: 0.0,
            immediateRawDelta5Mgdl = raw5 ?: 9999.0,
            rawDelta15Mgdl = rawDelta15MinMgdl(now) ?: 9999.0,
            steps30 = steps30(now),
            steps180 = steps180(now),
            smbSum10Min = smb10,
            smbSum30Min = smbSum(now, 30 * 60 * 1000L),
            tddFactor = tddFactorValue(
                enabled = preferences.get(BooleanKey.ApsAutoIsfTddFactor),
                fallback = preferences.get(DoubleKey.ApsAutoIsfTddFactorFallback),
                tddRatio = tddRatioForFactor(),
            ),
            steps5 = steps5(now),
            steps15 = steps15(now),
            hypoPrediction2 = raw5?.let {
                hypoPrediction2Mmol(glucoseStatus.glucose, glucoseStatus.shortAvgDelta, it, iobData.iob, mealData.mealCOB)
            },
            smbBoostRecent = smbBoostRecentNow(
                bolusGiven = runMarks.recent(RunMark.BOLUS_GIVEN, 30, now),
                bolusGivenMild = runMarks.recent(RunMark.BOLUS_GIVEN_MILD, 30, now),
                bolusGivenBg3 = runMarks.recent(RunMark.BOLUS_GIVEN_BG3, 30, now),
                uamBoost = uamRecent,
                cob = mealData.mealCOB,
                rawDelta5 = ukf.delta5,
                longAvgDelta = glucoseStatus.longAvgDelta,
            ),
            nightFrSkipActive = runMarks.recent(RunMark.NIGHT_FR_SKIP, 2, now),
            uamBoostRecent = uamRecent,
            uamBstMinutesAgo = runMarks.minutesAgo(RunMark.UAM_BST, now) ?: Int.MAX_VALUE,
            sub75Cooldown = runMarks.recent(RunMark.SUB75, 10, now),
            smbIntervalSec = smbInterval5Sec(now),
            smbStackStart = preferences.get(LongNonKey.ApsAutoIsfSmbStackStart),
            mildOffsetZero = preferences.get(BooleanNonKey.ApsAutoIsfMildOffsetZeroActive),
            mildThisCycle = mildThisCycle,
            bg3ThisCycle = bg3ThisCycle,
            mildFailsafeThisCycle = mildFailsafeThisCycle,
            uamBoostEnabled = preferences.get(BooleanKey.ApsAutoIsfUamBoostEnabled),
            uamBoostUnrestricted = preferences.get(BooleanKey.ApsAutoIsfUamBoostUnrestrictedEnabled),
            uamBoostMaxBolus = preferences.get(DoubleKey.ApsAutoIsfUamBoostMaxBolus),
            uamBoostMaxIobPercent = preferences.get(IntKey.ApsAutoIsfUamBoostMaxIobPercent).toDouble(),
            uamBoostScale = preferences.get(DoubleKey.ApsAutoIsfUamBoostScale),
            daytimeGateBypass = newPodHighBypass(now, glucoseStatus.glucose) || runMarks.recent(RunMark.USUAL2, 90, now),
            recentLowBg = recentLowBgMgdl(now),
        ).also {
            if (!usesLiveSteps()) {
                it.reason.append(
                    LiveSteps.reasonText(steps5(now), steps10(now), steps15(now), steps30(now), steps60(now), steps180(now))
                )
            }
            determineBasalAutoISF.smbStackStartToStore?.let { start ->
                preferences.put(LongNonKey.ApsAutoIsfSmbStackStart, start)
            }
            if (determineBasalAutoISF.uamBoostFiredThisCycle) {
                runMarks.mark(RunMark.UAM_BST, now)
            }
            val determineBasalResult = apsResultProvider().with(it)
            // Preserve input data
            determineBasalResult.inputConstraints = inputConstraints
            determineBasalResult.autosensResult = autosensResult
            determineBasalResult.iobData = iobArray
            determineBasalResult.glucoseStatus = glucoseStatus
            determineBasalResult.currentTemp = currentTemp
            determineBasalResult.oapsProfileAutoIsf = oapsProfile
            determineBasalResult.mealData = mealData
            lastAPSResult = determineBasalResult
            lastAPSRun = now
            lastCycleSmb = determineBasalResult.smb
            lastCycleSmbAt = now
            lastCycleInsulinReq = it.insulinReq
            if (autoIsfFactors.recorded) {
                persistenceLayer.insertAutoIsfValue(
                    AIV(
                        timestamp = now,
                        acceIsf = autoIsfFactors.acceIsf,
                        bgIsf = autoIsfFactors.bgIsf,
                        ppIsf = autoIsfFactors.ppIsf,
                        duraIsf = autoIsfFactors.duraIsf,
                        finalIsf = autoIsfFactors.finalIsf,
                        glucose = autoIsfFactors.glucose,
                        delta = autoIsfFactors.delta,
                        shortAvgDelta = autoIsfFactors.shortAvgDelta,
                        longAvgDelta = autoIsfFactors.longAvgDelta,
                        bgAcceleration = autoIsfFactors.bgAcceleration,
                        iob = iobData.iob,
                        smbDelivered = determineBasalResult.smb,
                        ukfRawBgl = ukf.glucose ?: 0.0,
                        iobThEffective = if (use_iobTH) iobTHvirtual / iobTHtolerance * 100.0 else oapsProfile.max_iob,
                    )
                )
            }
            aapsLogger.debug(LTag.APS, "Result: $it")
            rxBus.send(EventAPSCalculationFinished())
        }

        rxBus.send(EventOpenAPSUpdateGui())
    }

    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? = glucoseStatusCalculatorAutoIsf.getGlucoseStatusData(allowOldData)

    override fun isSuperBolusEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        value.set(false)
        return value
    }

    override suspend fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val maxIobPref = preferences.get(DoubleKey.ApsSmbMaxIob)
            maxIob.setIfSmaller(maxIobPref, rh.gs(ApsStrings.limiting_iob, maxIobPref, rh.gs(ApsStrings.maxvalueinpreferences)), this)
            maxIob.setIfSmaller(hardLimits.maxIobSMB(), rh.gs(ApsStrings.limiting_iob, hardLimits.maxIobSMB(), rh.gs(ApsStrings.hardlimit)), this)
        }
        return maxIob
    }

    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        if (isEnabled()) {
            var maxBasal = preferences.get(DoubleKey.ApsMaxBasal)
            if (maxBasal < profile.getMaxDailyBasal()) {
                maxBasal = profile.getMaxDailyBasal()
                absoluteRate.addReason(rh.gs(ApsStrings.increasing_max_basal), this)
            }
            absoluteRate.setIfSmaller(maxBasal, rh.gs(CoreUiStrings.limitingbasalratio, maxBasal, rh.gs(ApsStrings.maxvalueinpreferences)), this)

            // Check percentRate but absolute rate too, because we know real current basal in pump
            val maxBasalMultiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier)
            val maxFromBasalMultiplier = floor(maxBasalMultiplier * profile.getBasal() * 100) / 100
            absoluteRate.setIfSmaller(
                maxFromBasalMultiplier,
                rh.gs(CoreUiStrings.limitingbasalratio, maxFromBasalMultiplier, rh.gs(ApsStrings.max_basal_multiplier)),
                this
            )
            val maxBasalFromDaily = preferences.get(DoubleKey.ApsMaxDailyMultiplier)
            val maxFromDaily = floor(profile.getMaxDailyBasal() * maxBasalFromDaily * 100) / 100
            absoluteRate.setIfSmaller(maxFromDaily, rh.gs(CoreUiStrings.limitingbasalratio, maxFromDaily, rh.gs(ApsStrings.max_daily_basal_multiplier)), this)
        }
        return absoluteRate
    }

    override suspend fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseSmb)
        if (!enabled) value.set(false, rh.gs(ApsStrings.smb_disabled_in_preferences), this)
        return value
    }

    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseUam)
        if (!enabled) value.set(false, rh.gs(ApsStrings.uam_disabled_in_preferences), this)
        return value
    }

    override fun isAutosensModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseAutosens)
        if (!enabled) value.set(false, rh.gs(ApsStrings.autosens_disabled_in_preferences), this)
        return value
    }

    // Rounds value to 'digits' decimal places
    // different for negative numbers fun round(value: Double, digits: Int): Double = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    fun round(value: Double, digits: Int): Double {
        // Pass NaN AND ±Infinity through untouched - Math.round saturates at Long.MAX_VALUE, which would
        // turn an infinite value into a normal looking number and hide it from the invalidInputs check.
        if (!value.isFinite()) return value
        val scale = 10.0.pow(digits.toDouble())
        return (value * scale).roundToLong() / scale
    }

    fun convert_bg(value: Double): String =
        profileUtil.fromMgdlToStringInUnits(value).replace("-0.0", "0.0")

    fun convert_bg_to_units(value: Double, profile: OapsProfileAutoIsf): Double =
        if (profile.out_units == "mmol/L") value * Constants.MGDL_TO_MMOLL else value

    suspend fun autoISF(profile: Profile, factors: AutoIsfFactors? = null): Double {
        val sens = profile.getProfileIsfMgdl()
        val glucose_status = glucoseStatusCalculatorAutoIsf.getGlucoseStatusData(allowOldData = false)

        val high_temptarget_raises_sensitivity = exerciseMode || highTemptargetRaisesSensitivity
        var target_bg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), CoreUiStrings.temp_target_value, HardLimits.LIMIT_TARGET_BG)
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            target_bg = hardLimits.verifyHardLimits(tempTarget.target(), CoreUiStrings.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG)
        }
        var sensitivityRatio: Double
        var origin_sens = ""
        val low_temptarget_lowers_sensitivity = preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens)
        val exerciseModeActive = high_temptarget_raises_sensitivity && isTempTarget && target_bg > normalTarget
        val resistanceModeActive = low_temptarget_lowers_sensitivity && isTempTarget && target_bg < normalTarget
        var tempTargetRatio = 1.0
        if (exerciseModeActive || resistanceModeActive) {
            // w/ target 100, temp target 110 = .89, 120 = 0.8, 140 = 0.67, 160 = .57, and 200 = .44
            // e.g.: Sensitivity ratio set to 0.8 based on temp target of 120; Adjusting basal from 1.65 to 1.35; ISF from 58.9 to 73.6
            val halfBasalTarget = preferences.get(IntKey.ApsAutoIsfHalfBasalExerciseTarget)
            val c = (halfBasalTarget - normalTarget).toDouble()
            if (c * (c + target_bg - normalTarget) <= 0.0) {
                tempTargetRatio = preferences.get(DoubleKey.AutosensMax)
            } else {
                tempTargetRatio = c / (c + target_bg - normalTarget)
                tempTargetRatio = min(tempTargetRatio, preferences.get(DoubleKey.AutosensMax))
                tempTargetRatio = round(tempTargetRatio, 2)
                origin_sens = " from low TT modifier"
            }
        }
        val nowMs = dateUtil.now()
        val stepSample = if (usesLiveSteps()) freshLiveSteps(nowMs)
        else persistenceLayer.getLastStepsCountFromTimeToTime(nowMs - 60 * 60 * 1000L, nowMs)
        val startedAt = if (processStartedAtMs == 0L) nowMs else processStartedAtMs
        val activityRatio = if (glucose_status == null) 1.0 else activitySensitivityRatio(
            enabled = preferences.get(BooleanKey.ApsActivityDetection),
            tempTargetSet = isTempTarget,
            phoneMoved = PhoneMotion.movedRecently(nowMs),
            stepsKnown = stepSample != null,
            steps5 = stepSample?.steps5min ?: 0,
            steps10 = stepSample?.steps10min ?: 0,
            steps15 = stepSample?.steps15min ?: 0,
            steps30 = stepSample?.steps30min ?: 0,
            steps60 = stepSample?.steps60min ?: 0,
            hour = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(TimeZone.currentSystemDefault()).hour,
            bg = glucose_status.glucose,
            targetBg = target_bg,
            shortDelta = glucose_status.shortAvgDelta,
            minutesSinceStart = (nowMs - startedAt) / 60_000L,
            sleeping = preferences.get(BooleanKey.AutomationStatesEnabled) &&
                automationStates?.inState("Sleeping", "True") == true,
            sleepStateExists = automationStates?.hasStateValues("Sleeping") == true,
            ignoreInactivityOvernight = preferences.get(BooleanKey.ApsIgnoreInactivityOvernight),
            idleStartHour = preferences.get(IntKey.ApsActivityIdleStart),
            idleEndHour = preferences.get(IntKey.ApsActivityIdleEnd),
            activityScale = preferences.get(DoubleKey.ApsActivityScaleFactor),
            inactivityScale = preferences.get(DoubleKey.ApsInactivityScaleFactor),
        )
        val autosensOn = constraintsChecker.isAutosensModeEnabled().value()
        var autosensRatio = 1.0
        if (autosensOn) {
            iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSAutoISFPlugin")?.also {
                autosensRatio = it.autosensResult.ratio
            }
        }
        val tddRatio = if (autosensOn) null else tddRatioForFactor()
        sensitivityRatio = loopSensitivityRatio(
            tempTargetActive = exerciseModeActive || resistanceModeActive,
            tempTargetRatio = tempTargetRatio,
            activityRatio = activityRatio,
            autosensOn = autosensOn,
            autosensRatio = autosensRatio,
            tddSensitivityOn = preferences.get(BooleanKey.ApsAutoIsfTddSensitivity),
            tddRatio = tddRatio,
        )
        if (!usingDynamicIsf() || !autoIsfWeights || glucose_status == null) {
            consoleError.add("autoISF weights disabled in Preferences")
            consoleError.add("----------------------------------")
            consoleError.add("end AutoISF")
            consoleError.add("----------------------------------")
            return round(sens / sensitivityRatio, 1)
        }
        val autosensResult = AutosensResult()

        if (constraintsChecker.isAutosensModeEnabled().value()) {
            val autosensData = iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSAutoISFPlugin")
            if (autosensData == null) {
                rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.openaps_no_as_data)))
                return sens
            }
            autosensData.autosensResult
        } else autosensResult.sensResult = "autosens disabled"

        val dura05: Double = glucose_status.duraISFminutes
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
        consoleError.add("Parabola fit results were acceleration:${round(bg_acce, 2)}, correlation:$fit_corr, duration:${glucose_status.parabolaMinutes}m")
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

        val bg_ISF = 1 + interpolate(100 - bg_off)
        consoleError.add("bg_ISF adaptation is ${round(bg_ISF, 2)}")
        var liftISF: Double
        var final_ISF: Double
        if (bg_ISF < 1.0) {
            liftISF = min(bg_ISF, acce_ISF)
            if (acce_ISF > 1.0) {
                liftISF = bg_ISF * acce_ISF                                 // bg_ISF could become > 1 now
                consoleError.add("bg_ISF adaptation lifted to ${round(liftISF, 2)} as bg accelerates already")
            }
            final_ISF = withinISFlimits(liftISF, autoISF_min, maxISFReduction, sensitivityRatio, origin_sens, isTempTarget, high_temptarget_raises_sensitivity, target_bg, normalTarget)
            factors?.record(acce_ISF, bg_ISF, pp_ISF, 1.0, final_ISF, glucose_status)
            return min(720.0, round(sens / final_ISF, 1))         // observe ISF maximum of 720(?)
        } else if (bg_ISF > 1.0) {
            sens_modified = true
        }

        val bg_delta = glucose_status.delta
        val deltaType = "pp"
        when {
            bg_off > 0.0                     -> {
                consoleError.add(deltaType + "_ISF adaptation by-passed as average glucose < $target_bg+10")
            }

            glucose_status.shortAvgDelta < 0 -> {
                consoleError.add(deltaType + "_ISF adaptation by-passed as no rise or too short lived")
            }

            else                             -> {
                pp_ISF = 1.0 + max(0.0, bg_delta * pp_ISF_weight)
                consoleError.add("pp_ISF adaptation is ${round(pp_ISF, 2)}")
                if (pp_ISF != 1.0) {
                    sens_modified = true
                }

            }
        }

        var dura_ISF = 1.0
        val weightISF: Double = dura_ISF_weight
        when {
            dura05 < 10.0      -> {
                consoleError.add("dura_ISF by-passed; bg is only $dura05 m at level $avg05")
            }

            avg05 <= target_bg -> {
                consoleError.add("dura_ISF by-passed; avg. glucose $avg05 below target $target_bg")
            }

            else               -> {
                // fight the resistance at high levels
                val dura05Weight = dura05 / 60
                val avg05Weight = weightISF / target_bg
                dura_ISF += dura05Weight * avg05Weight * (avg05 - target_bg)
                sens_modified = true
                consoleError.add("dura_ISF adaptation is ${round(dura_ISF, 2)} because ISF ${round(sens, 1)} did not do it for ${round(dura05, 1)}m")
            }
        }
        if (sens_modified) {
            liftISF = max(dura_ISF, max(bg_ISF, max(acce_ISF, pp_ISF)))
            if (acce_ISF < 1.0) {
                consoleError.add("strongest autoISF factor ${round(liftISF, 2)} weakened to ${round(liftISF * acce_ISF, 2)} as bg decelerates already")
                liftISF = liftISF * acce_ISF
            }
            final_ISF = withinISFlimits(liftISF, autoISF_min, maxISFReduction, sensitivityRatio, origin_sens, isTempTarget, high_temptarget_raises_sensitivity, target_bg, normalTarget)
            factors?.record(acce_ISF, bg_ISF, pp_ISF, dura_ISF, final_ISF, glucose_status)
            return round(sens / final_ISF, 1)
        }
        consoleError.add("----------------------------------")
        consoleError.add("end AutoISF")
        consoleError.add("----------------------------------")
        factors?.record(acce_ISF, bg_ISF, pp_ISF, dura_ISF, 1.0, glucose_status)
        return round(sens / sensitivityRatio, 1)     // nothing changed
    }

    fun interpolate(xdata: Double): Double {   // interpolate ISF behaviour based on polygons defining nonlinear functions defined by value pairs for ...
        //  ...         <----------------------  glucose  ---------------------->
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
        liftISF: Double, minISFReduction: Double, maxISFReduction: Double, sensitivityRatio: Double, origin_sens: String, temptargetSet: Boolean,
        high_temptarget_raises_sensitivity: Boolean, target_bg: Double, normalTarget: Int
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
        var originSensFinal = origin_sens
        if (high_temptarget_raises_sensitivity && temptargetSet && target_bg > normalTarget) {
            finalISF = liftISFlimited * sensitivityRatio
            originSensFinal = " including exercise mode impact"
        } else if (liftISFlimited >= 1) {
            finalISF = max(liftISFlimited, sensitivityRatio)
            originSensFinal = if (liftISFlimited >= sensitivityRatio) "" else "from low TT modifier"
        } else {
            finalISF = min(liftISFlimited, sensitivityRatio)
            if (liftISFlimited <= sensitivityRatio) {
                originSensFinal = ""                                        // low TT lowers sensitivity dominates
            }
        }
        consoleError.add("final ISF factor is ${round(finalISF, 2)} " + originSensFinal)
        consoleError.add("----------------------------------")
        consoleError.add("end AutoISF")
        consoleError.add("----------------------------------")
        return finalISF
    }

    fun loop_smb(microBolusAllowed: Boolean, profile: OapsProfileAutoIsf, iob_data_iob: Double, useIobTh: Boolean, iobThEffective: Double): String {
        val iobThUser = preferences.get(IntKey.ApsAutoIsfIobThPercent)  //iobThresholdPercent
        if (useIobTh) {
            val iobThPercent = round(iobThEffective / profile.max_iob * 100.0, 0)
            if (iobThPercent == iobThUser.toDouble()) {
                consoleLog.add("User setting iobTH=$iobThUser% not modulated")
            } else {
                consoleLog.add("User setting iobTH=$iobThUser% modulated to ${iobThPercent.toInt()}% or ${round(iobThEffective, 2)}U")
                consoleLog.add("  due to profile %, exercise mode or similar")
            }
        } else {
            consoleLog.add("User setting iobTH=100% disables iobTH method")
        }

        if (!microBolusAllowed) {
            return "AAPS"                                                 // see message in enable_smb
        }
        if (enableSMB_EvenOn_OddOff_always) {
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

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "openapsautoisf_settings",
        title = ApsStrings.openaps_auto_isf,
        items = listOf(
            DoubleKey.ApsMaxBasal,
            DoubleKey.ApsSmbMaxIob,
            BooleanKey.ApsUseAutosens,
            BooleanKey.AutomationStatesEnabled,
            BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual,
            BooleanKey.ApsAutoIsfBoostAutomationsEnabled,
            BooleanKey.ApsAutoIsfCustomAutomationsEnabled,
            DoubleKey.ApsAutoIsfSmbDeliveryBaseline,
            DoubleKey.ApsAutoIsfMildBoostRatio,
            BooleanKey.ApsAutoIsfUamBoostEnabled,
            BooleanKey.ApsAutoIsfUamBoostUnrestrictedEnabled,
            DoubleKey.ApsAutoIsfUamBoostMaxBolus,
            IntKey.ApsAutoIsfUamBoostMaxIobPercent,
            DoubleKey.ApsAutoIsfUamBoostScale,
            StringKey.ApsAutoIsfLowProfileName,
            BooleanKey.ApsAutoIsfTddSensitivity,
            BooleanKey.ApsAutoIsfTddFactor,
            DoubleKey.ApsAutoIsfTddFactorFallback,
            BooleanKey.ApsSensitivityRaisesTarget,
            BooleanKey.ApsResistanceLowersTarget,
            BooleanKey.ApsAutoIsfHighTtRaisesSens,
            BooleanKey.ApsAutoIsfLowTtLowersSens,
            IntKey.ApsAutoIsfHalfBasalExerciseTarget,
            BooleanKey.ApsActivityDetection,
            DoubleKey.ApsActivityScaleFactor,
            DoubleKey.ApsInactivityScaleFactor,
            BooleanKey.ApsIgnoreInactivityOvernight,
            IntKey.ApsActivityIdleStart,
            IntKey.ApsActivityIdleEnd,
            BooleanKey.ApsUseSmb,
            BooleanKey.ApsAutoIsfFastRiseEnabled,
            BooleanKey.ApsAutoIsfLowReboundGuardEnabled,
            BooleanKey.ApsAutoIsfShowCarbModelCurve,
            BooleanKey.ApsUseSmbWithHighTt,
            BooleanKey.ApsUseSmbAlways,
            BooleanKey.ApsUseSmbWithCob,
            BooleanKey.ApsUseSmbWithLowTt,
            BooleanKey.ApsUseSmbAfterCarbs,
            BooleanKey.ApsUseUam,
            IntKey.ApsMaxSmbFrequency,
            IntKey.ApsMaxMinutesOfBasalToLimitSmb,
            IntKey.ApsUamMaxMinutesOfBasalToLimitSmb,
            IntKey.ApsCarbsRequestThreshold,
            PreferenceSubScreenDef(
                key = "absorption_smb_advanced",
                title = CoreUiStrings.advanced_settings_title,
                items = listOf(
                    ApsIntentKey.LinkToDocs,
                    BooleanKey.ApsAlwaysUseShortDeltas,
                    DoubleKey.ApsMaxDailyMultiplier,
                    DoubleKey.ApsMaxCurrentBasalMultiplier
                )
            ),
            PreferenceSubScreenDef(
                key = "auto_isf_settings",
                title = ApsStrings.autoISF_settings_title,
                items = listOf(
                    BooleanKey.ApsUseAutoIsfWeights,
                    DoubleKey.ApsAutoIsfMin,
                    DoubleKey.ApsAutoIsfMax,
                    DoubleKey.ApsAutoIsfMaxLow,
                    DoubleKey.ApsAutoIsfTodOffset0002,
                    DoubleKey.ApsAutoIsfTodOffset0204,
                    DoubleKey.ApsAutoIsfTodOffset0406,
                    DoubleKey.ApsAutoIsfTodOffset0609,
                    DoubleKey.ApsAutoIsfTodOffset0912,
                    DoubleKey.ApsAutoIsfTodOffset1218,
                    DoubleKey.ApsAutoIsfTodOffset1822,
                    DoubleKey.ApsAutoIsfTodOffset2200,
                    DoubleKey.ApsAutoIsfBgAccelWeight,
                    DoubleKey.ApsAutoIsfBgAccelWeightNormal,
                    DoubleKey.ApsAutoIsfBgBrakeWeight,
                    DoubleKey.ApsAutoIsfLowBgWeight,
                    DoubleKey.ApsAutoIsfHighBgWeight,
                    DoubleKey.ApsAutoIsfPpWeight,
                    DoubleKey.ApsAutoIsfPpWeightNormal,
                    DoubleKey.ApsAutoIsfPpWeightHigh,
                    DoubleKey.ApsAutoIsfDuraWeight,
                    IntKey.ApsAutoIsfIobThPercent,
                    IntKey.ApsAutoIsfIobThPercentNormal,
                    IntKey.ApsAutoIsfProfilePercentNormal
                )
            ),
            PreferenceSubScreenDef(
                key = "smb_delivery_settings",
                title = ApsStrings.smb_delivery_settings_title,
                items = listOf(
                    DoubleKey.ApsAutoIsfSmbDeliveryRatio,
                    DoubleKey.ApsAutoIsfSmbDeliveryRatioMin,
                    DoubleKey.ApsAutoIsfSmbDeliveryRatioMax,
                    DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange,
                    DoubleKey.ApsAutoIsfSmbMaxRangeExtension,
                    BooleanKey.ApsAutoIsfSmbOnEvenTarget
                )
            )
        ),
        icon = pluginDescription.icon
    )

    private fun usesLiveSteps(): Boolean =
        config.APS && !config.AAPSCLIENT &&
            preferences.get(BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual) &&
            activePlugin.activePump.selectedActivePump() is VirtualPump

    /** Newest live-phone sample from the last 20 minutes. Null when this phone measures its own steps. */
    private suspend fun freshLiveSteps(now: Long): SC? {
        if (!usesLiveSteps()) return null
        val own = "openaps://${config.deviceModelForUpload}"
        return persistenceLayer.getStepsCountFromTimeToTime(now - LiveSteps.MAX_AGE_MS, now)
            .let { LiveSteps.sampleFor(now, it, fromLivePhone = true, ownDevice = own) }
            ?.takeIf { LiveSteps.hasDosingBuckets(it.toBuckets()) }
    }

    private fun SC.toBuckets(): Map<Int, Int> = mapOf(
        5 to steps5min, 10 to steps10min, 15 to steps15min, 30 to steps30min, 60 to steps60min, 180 to steps180min
    )

    private suspend fun liveOrLocal(now: Long, windowMs: Long, read: (SC) -> Int): Int {
        freshLiveSteps(now)?.let { return read(it) }
        if (usesLiveSteps()) return 0
        return persistenceLayer.getLastStepsCountFromTimeToTime(now - windowMs, now)?.let(read) ?: 0
    }

    // Steps a watch stored in the last hour. No sample means 0, so the quiet morning cap can apply.
    private suspend fun steps60(now: Long): Int = liveOrLocal(now, 60 * 60 * 1000L) { it.steps60min }

    private suspend fun steps30(now: Long): Int = liveOrLocal(now, 30 * 60 * 1000L) { it.steps30min }

    private suspend fun steps10(now: Long): Int = liveOrLocal(now, 10 * 60 * 1000L) { it.steps10min }

    private suspend fun steps5(now: Long): Int = liveOrLocal(now, 5 * 60 * 1000L) { it.steps5min }

    private suspend fun steps15(now: Long): Int = liveOrLocal(now, 15 * 60 * 1000L) { it.steps15min }

    // The 3-hour count is stored on the sample, so the lookback is the sample age, not a second sum.
    private suspend fun steps180(now: Long): Int = liveOrLocal(now, 180 * 60 * 1000L) { it.steps180min }

    // Autosens on keeps the ratio at 1.0, matching 3.2.1. The live blend is used only when autosens is off.
    private suspend fun tddRatioForFactor(): Double {
        if (constraintsChecker.isAutosensModeEnabled().value()) return 1.0
        if (!preferences.get(BooleanKey.ApsAutoIsfTddSensitivity)) return 1.0
        val tdd7D = tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = true))?.data?.totalAmount
        val tdd1D = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = true))?.data?.totalAmount
        val tddLast4H = tddCalculator.calculateDaily(-4, 0)?.totalAmount
        val tddLast8to4H = tddCalculator.calculateDaily(-8, -4)?.totalAmount
        return blendedTddRatio(tdd7D, tdd1D, tddLast4H, tddLast8to4H)?.ratio ?: 1.0
    }

    // Puts a raised post-meal weight, and an acceleration weight that sits above its baseline, back.
    // The first run copies the live weights into the baselines, so a phone that has never saved
    // those two settings does not change its dose. A raw Libre high in the last 48 hours is not
    // tracked yet, so that trigger stays open.
    private suspend fun revertRaisedWeights(now: Long, bg: Double, steps5: Int, steps30: Int) {
        val livePp = preferences.get(DoubleKey.ApsAutoIsfPpWeight)
        val liveAcce = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
        if (preferences.getIfExists(DoubleKey.ApsAutoIsfPpWeightNormal) == null) {
            preferences.put(DoubleKey.ApsAutoIsfPpWeightNormal, livePp)
        }
        if (preferences.getIfExists(DoubleKey.ApsAutoIsfBgAccelWeightNormal) == null) {
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeightNormal, liveAcce)
        }
        val decision = ppAcceWeightRevert(
            currentPp = livePp,
            baselinePp = preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal),
            currentAcce = liveAcce,
            baselineAcce = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal),
            glucoseMgdl = bg,
            steps5 = steps5,
            steps30 = steps30,
            steps60 = steps60(now),
            noRecentHigh = true,
            recentBoost = ppWeightBoostMarks.any { runMarks.recent(it, 15, now) },
        )
        if (!decision.restorePp && !decision.restoreAcce) return
        if (decision.restorePp) {
            preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal))
        }
        if (decision.restoreAcce) {
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal))
        }
        runMarks.mark(RunMark.PP_WEIGHT_REVERT, now)
        val ppBase = preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal)
        val acceBase = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal)
        val why = when (decision.reason) {
            "bg" -> "BG<8.5mmol"
            "activity" -> "activity"
            else -> "noRecentHigh48h"
        }
        val what = when {
            decision.restorePp && decision.restoreAcce -> "ppISFwt=${decimals(ppBase, 2)} acceISFwt=${decimals(acceBase, 2)}"
            decision.restorePp -> "ppISFwt=${decimals(ppBase, 2)}"
            else -> "acceISFwt=${decimals(acceBase, 2)}"
        }
        sendAutoSms("PpWeightRevert: $what ($why)")
        carePortalNote(if (decision.restorePp && decision.restoreAcce) "PArv" else if (decision.restorePp) "PPrv" else "ACrv")
        aapsLogger.debug(LTag.APS, "Weight revert reason=${decision.reason}")
    }

    // Puts a raised IOB threshold, and a profile percent that sits above its baseline, back.
    // The first run copies the live values into the baselines, so that pass does not change the dose.
    // A value below the baseline is left as it is. A raw Libre high in the last 48 hours is not
    // tracked yet, so that trigger stays open.
    private suspend fun revertRaisedDose(now: Long, bg: Double, profilePercent: Int, steps5: Int, steps30: Int) {
        val liveIobTh = preferences.get(IntKey.ApsAutoIsfIobThPercent)
        if (preferences.getIfExists(IntKey.ApsAutoIsfIobThPercentNormal) == null) {
            preferences.put(IntKey.ApsAutoIsfIobThPercentNormal, liveIobTh)
        }
        if (preferences.getIfExists(IntKey.ApsAutoIsfProfilePercentNormal) == null) {
            preferences.put(IntKey.ApsAutoIsfProfilePercentNormal, profilePercent)
        }
        val decision = iobProfileRevert(
            currentIobTh = liveIobTh,
            baselineIobTh = preferences.get(IntKey.ApsAutoIsfIobThPercentNormal),
            currentProfilePercent = profilePercent,
            baselineProfilePercent = preferences.get(IntKey.ApsAutoIsfProfilePercentNormal),
            glucoseMgdl = bg,
            steps5 = steps5,
            steps30 = steps30,
            steps60 = steps60(now),
            noRecentHigh = true,
            recentBoost = ppWeightBoostMarks.any { runMarks.recent(it, 15, now) },
        )
        if (!decision.restoreIobTh && !decision.restoreProfilePercent) return
        if (!runMarks.ready(RunMark.IOB_PROFILE_REVERT, 5, now)) return
        if (decision.restoreIobTh) {
            preferences.put(IntKey.ApsAutoIsfIobThPercent, preferences.get(IntKey.ApsAutoIsfIobThPercentNormal))
        }
        if (decision.restoreProfilePercent) {
            val baseline = preferences.get(IntKey.ApsAutoIsfProfilePercentNormal)
            val switched = profileFunction.createProfileSwitch(
                durationInMinutes = 0,
                percentage = baseline,
                timeShiftInHours = 0,
                action = Action.PROFILE_SWITCH,
                source = Sources.Automation,
                note = "AutoISF: profile percent back to baseline",
                listValues = listOf(ValueWithUnit.Percent(baseline))
            ) != null
            if (!switched) aapsLogger.debug(LTag.APS, "Profile percent revert did not write a switch")
        }
        runMarks.mark(RunMark.IOB_PROFILE_REVERT, now)
        aapsLogger.debug(LTag.APS, "Dose revert reason=${decision.reason}")
    }

    // Puts the SMB delivery ratio back to its saved baseline when no temp target is on.
    // While four or more SMBs land inside 65 seconds, and the meal is under 9 g, the ratio goes to
    // baseline minus 0.03 instead. A boost mark from the last 3 minutes is left alone.
    // This runs before the boost marks, so a later write in the same loop is not undone here.
    private suspend fun applyDeliveryRestore(now: Long, tempTargetSet: Boolean, mealCob: Double) {
        if (!tempTargetSet && preferences.get(BooleanNonKey.ApsAutoIsfMildOffsetZeroActive)) {
            preferences.put(BooleanNonKey.ApsAutoIsfMildOffsetZeroActive, false)
        }
        val baseline = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline)
        val resting = baseline.coerceAtMost(smb_delivery_ratio_max)
        val hardStackTarget = (baseline - 0.03).coerceAtLeast(0.1)
        val stacking = smbIsStacking(smbInterval5Sec(now), smbCount5(now))
        val current = smb_delivery_ratio
        val atHardStack = deliveryNear(current, hardStackTarget)
        if (delOffShouldRestore(
                currentRatio = current,
                restingBaseline = resting,
                tempTargetSet = tempTargetSet,
                atHardStackTarget = atHardStack,
                smbStacking = stacking,
                recentDeliveryBoost = runMarks.recent(RunMark.BOLUS_GIVEN, 2, now) ||
                    runMarks.recent(RunMark.BOLUS_GIVEN_MILD, 2, now) ||
                    runMarks.recent(RunMark.BOLUS_GIVEN_MILD_FAILSAFE, 2, now),
            )
        ) {
            preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryRatio, resting)
            carePortalNote("DelOff")
            aapsLogger.debug(LTag.APS, "SMB delivery ratio back to $resting")
        }
        val recentBoost = runMarks.recent(RunMark.BOLUS_GIVEN, 3, now) ||
            runMarks.recent(RunMark.BOLUS_GIVEN_MILD, 3, now)
        if (hardStackShouldReduce(
                atHardStackTarget = atHardStack,
                smbStacking = stacking,
                recentOwnBoost = recentBoost,
                mealCob = mealCob,
            )
        ) {
            preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryRatio, hardStackTarget)
            carePortalNote("HardStackDelOff")
            aapsLogger.debug(LTag.APS, "SMB delivery ratio down to $hardStackTarget while SMBs are stacking")
        }
    }

    // Marks BolusGiven, BolusGivenBg3, or BolusGivenMild when the 3.2.1 rise gates pass.
    private var cobSustainedSince = 0L
    private var smbBoostedThisCycle = false
    private var lastCycleSmb = 0.0
    private var lastCycleSmbAt = 0L
    private var lastCycleInsulinReq: Double? = null
    private var lastBigDoseAt = 0L
    private var lastBigDoseBg = 0.0
    private var lastBigDoseShort = 0.0
    private var mildThisCycle = false
    private var bg3ThisCycle = false
    private var mildFailsafeThisCycle = false

    // A strong mark raises the IOB threshold to 71 and, unless caution applies, the profile percent to 110 for 2 minutes.
    // Both marks raise the post-meal weight. A value that is not above its baseline is left alone.
    // The SMB delivery ratio is raised. A mild mark also holds a 5.0 target for 2 minutes, and that
    // hold is skipped when a temp target is already active. The restore then puts the ratio back.
    // The acceleration weight is not written.
    // Libre raw is used. A missing raw value is -9999, so the rise gate stays closed.
    private suspend fun markBolusBoosts(
        now: Long,
        profilePercent: Int,
        tempTargetSet: Boolean,
        minuteOfDay: Int,
        bg: Double,
        delta: Double,
        shortDelta: Double,
        longDelta: Double,
        rawDelta5: Double,
        cob: Double,
        iob: Double,
        statesOn: Boolean,
        steps5: Int,
        steps30: Int,
        ukfDelta1: Double,
    ) {
        mildThisCycle = false
        bg3ThisCycle = false
        mildFailsafeThisCycle = false
        seedBaselines(profilePercent)
        val boostOn = preferences.get(BooleanKey.ApsAutoIsfBoostAutomationsEnabled)
        val baseline = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline)
        val profileName = profileFunction.getOriginalProfileName()
        val lowName = preferences.get(StringKey.ApsAutoIsfLowProfileName)
        val onLowProfile = profileName == lowName
        val mjActive = statesOn && states().inState("MJ", "MJ active")
        val rawDelta1 = ukfDelta1
        val interval = smbInterval5Sec(now)
        val iobChange5 = iobAt(now) - iobAt(now - 5 * 60_000L)
        val lastBolusMin = minutesSinceLastPositiveNormalBolus(now)
        val recentAlarm = now - preferences.get(LongNonKey.ApsAutoIsfLastAlarmHypoAt) <= 60 * 60_000L
        val bypass = newPodHighBypass(now, bg) || runMarks.recent(RunMark.USUAL2, 90, now)
        val readyMild = runMarks.ready(RunMark.BOLUS_GIVEN_MILD, 5, now)
        val readyBg3 = runMarks.ready(RunMark.BOLUS_GIVEN_BG3, 5, now)
        val readyGiven = runMarks.ready(RunMark.BOLUS_GIVEN, 5, now)
        val bg3 = bg3BoostShouldFire(
            readyBolusGiven = readyGiven,
            readyBg3 = readyBg3,
            readyMild = readyMild,
            profilePercent = profilePercent,
            boostAutomationsOn = boostOn,
            minuteOfDay = minuteOfDay,
            daytimeBypass = bypass,
            bg = bg,
            delta = delta,
            longDelta = longDelta,
            rawDelta5 = rawDelta5,
            rawDelta1 = rawDelta1,
            iobChange5 = iobChange5,
            smbCount5 = smbCount5(now),
            onLowProfile = onLowProfile,
            mjActive = mjActive,
            steps5 = steps5,
            steps30 = steps30,
            steps60 = steps60(now),
            smbIntervalSec = interval,
            deliveryBaseline = baseline,
        )
        val mild = mildBoostShouldFire(
            readyMild = readyMild,
            readyBg3 = readyBg3,
            profilePercent = profilePercent,
            tempTargetSet = tempTargetSet,
            boostAutomationsOn = boostOn,
            minuteOfDay = minuteOfDay,
            daytimeBypass = bypass,
            bg = bg,
            delta = delta,
            shortDelta = shortDelta,
            rawDelta5 = rawDelta5,
            rawDelta1 = rawDelta1,
            iobChange5 = iobChange5,
            cob = cob,
            minutesSinceNormalBolus = lastBolusMin,
            recentAlarmHypo = recentAlarm,
            onLowProfile = onLowProfile,
            mjActive = mjActive,
            steps5 = steps5,
            steps30 = steps30,
            smbIntervalSec = interval,
            deliveryBaseline = baseline,
        )
        val blocked = bg3 && bg3BoostBlocked(
            recentBolusGiven = runMarks.recent(RunMark.BOLUS_GIVEN, 60, now),
            recentMild = runMarks.recent(RunMark.BOLUS_GIVEN_MILD, 60, now),
            recentMildFailsafe = runMarks.recent(RunMark.BOLUS_GIVEN_MILD_FAILSAFE, 60, now),
            iob = iob,
        )
        val standard110 = preferences.get(StringKey.ApsAutoIsfStandard110ProfileName)
        val caution = (statesOn && !states().inState("MJ", "NOMJremains")) ||
            (standard110.isNotEmpty() && profileName == standard110) ||
            steps30 > 200
        val mildBase = preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio)
        if (bg3 && !blocked) {
            runMarks.mark(RunMark.BOLUS_GIVEN, now)
            runMarks.mark(RunMark.BOLUS_GIVEN_BG3, now)
            bg3ThisCycle = true
            val iobThBefore = preferences.get(IntKey.ApsAutoIsfIobThPercent)
            applyBoostRaise(boostRaises(strong = true, caution = caution), boostedDeliveryRatio(mildBase, strong = true, caution = caution))
            sendAutoSms("BolusGiven71 [b3]: g=${decimals(bg / 18.016, 1)} iobTH=$iobThBefore")
            carePortalNote("Giv-3")
            aapsLogger.debug(LTag.APS, "BolusGiven bg3 marked")
        } else if (mild) {
            runMarks.mark(RunMark.BOLUS_GIVEN_MILD, now)
            mildThisCycle = true
            if (bg < 106.2) preferences.put(BooleanNonKey.ApsAutoIsfMildOffsetZeroActive, true)
            applyBoostRaise(boostRaises(strong = false, caution = false), boostedDeliveryRatio(mildBase, strong = false, caution = caution))
            startMildHoldTarget()
            val mildStep = if (caution) 0.075 else 0.15
            sendAutoSms("BolusGivenMild: g=${decimals(bg / 18.016, 1)} (+$mildStep)")
            carePortalNote("BMild")
            aapsLogger.debug(LTag.APS, "BolusGivenMild marked")
        } else if (mildFailsafeShouldFire(
                readyFailsafe = runMarks.ready(RunMark.BOLUS_GIVEN_MILD_FAILSAFE, 5, now),
                readyMild = readyMild,
                readyBg3 = readyBg3,
                profilePercent = profilePercent,
                tempTargetSet = tempTargetSet,
                boostAutomationsOn = boostOn,
                minuteOfDay = minuteOfDay,
                daytimeBypass = bypass,
                bg = bg,
                delta = delta,
                shortDelta = shortDelta,
                longDelta = longDelta,
                iob = iob,
                smbCount20 = smbCount20(now),
                steps5 = steps5,
                steps30 = steps30,
            )
        ) {
            runMarks.mark(RunMark.BOLUS_GIVEN_MILD_FAILSAFE, now)
            mildFailsafeThisCycle = true
            if (bg < 106.2) preferences.put(BooleanNonKey.ApsAutoIsfMildOffsetZeroActive, true)
            applyBoostRaise(boostRaises(strong = false, caution = false), boostedDeliveryRatio(mildBase, strong = false, caution = false))
            startMildHoldTarget()
            sendAutoSms("BolusGivenMildFailsafe: g=${decimals(bg / 18.016, 1)} IOB=${decimals(iob, 2)} (no SMB in 20min despite confirmed rise)")
            carePortalNote("BMildFS")
            aapsLogger.debug(LTag.APS, "BolusGivenMildFailsafe marked")
        } else if (blocked) {
            carePortalNote("GivBlk")
            aapsLogger.debug(LTag.APS, "BolusGiven bg3 suppressed")
        }
        if (not50RecentlyShouldFire(
                ready = runMarks.ready(RunMark.NOT50_RECENTLY, 5, now),
                profilePercent = profilePercent,
                lowBgRecent = statesOn && states().inState("LowBG", "50recent"),
                delta = delta,
                bg = bg,
            )
        ) {
            applyNot50Clear()
            runMarks.mark(RunMark.NOT50_RECENTLY, now)
            carePortalNote("No50")
            aapsLogger.debug(LTag.APS, "Not50Recently cleared")
        }
        val iobThNow = preferences.get(IntKey.ApsAutoIsfIobThPercent)
        if (iobThDaytimeFloorShouldFire(
                ready = runMarks.ready(RunMark.IOB_TH_DAYTIME_FLOOR, 30, now),
                minuteOfDay = minuteOfDay,
                profilePercent = profilePercent,
                lowBgClear = statesOn && states().inState("LowBG", "NO50rec"),
                steroidsOff = statesOn && states().inState("Steroids", "Steroids Off"),
                tempTargetSet = tempTargetSet,
                iobTh = iobThNow,
                bg = bg,
                delta = delta,
            )
        ) {
            val iobBaseline = preferences.get(IntKey.ApsAutoIsfIobThPercentNormal)
            if (70 <= iobBaseline) {
                preferences.put(IntKey.ApsAutoIsfIobThPercent, 70)
                runMarks.mark(RunMark.IOB_TH_DAYTIME_FLOOR, now)
                sendAutoSms("iobTHfloor: iobTH ${iobThNow}->70%")
                carePortalNote("THfloor")
                aapsLogger.debug(LTag.APS, "iobTH daytime floor $iobThNow -> 70")
            } else {
                aapsLogger.debug(LTag.APS, "iobTH daytime floor left $iobThNow, 70 is above the baseline")
            }
        }
        val extraBlock = extra50Block(
            ready = runMarks.ready(RunMark.EXTRA50, 5, now),
            profilePercent = profilePercent,
            lowBgClear = statesOn && states().inState("LowBG", "NO50rec"),
            mjNotRemaining = statesOn && !states().inState("MJ", "NOMJremains"),
            minuteOfDay = minuteOfDay,
            bg = bg,
            delta = delta,
            shortDelta = shortDelta,
            longDelta = longDelta,
        )
        if (extraBlock != null) {
            val iobBaseline = preferences.get(IntKey.ApsAutoIsfIobThPercentNormal)
            if (50 <= iobBaseline) preferences.put(IntKey.ApsAutoIsfIobThPercent, 50)
            applyExtra50State()
            runMarks.mark(RunMark.EXTRA50, now)
            sendAutoSms("Extra50% [b$extraBlock]: g=${decimals(bg / 18.016, 1)} d=${decimals(delta / 18.016, 2)}")
            carePortalNote("X50-$extraBlock")
            aapsLogger.debug(LTag.APS, "Extra50 block $extraBlock")
        }
        applyShowerAndPodAge(
            now = now,
            minuteOfDay = minuteOfDay,
            bg = bg,
            delta = delta,
            shortDelta = shortDelta,
            profilePercent = profilePercent,
            cob = cob,
            statesOn = statesOn,
        )
        val usualBlock = usual2Block(
            ready = runMarks.ready(RunMark.USUAL2, 5, now),
            profilePercent = profilePercent,
            tempTargetSet = tempTargetSet,
            iobTh = preferences.get(IntKey.ApsAutoIsfIobThPercent),
            bg = bg,
            delta = delta,
            shortDelta = shortDelta,
            steroidsOff = statesOn && states().inState("Steroids", "Steroids Off"),
            minuteOfDay = minuteOfDay,
            steps60 = steps60(now),
            steps180 = steps180(now),
            cob = cob,
            earlyAfterShower = shower12OpensUsual2(runMarks.minutesAgo(RunMark.SHOWER12, now)),
        )
        if (usualBlock != null) {
            val usualTh = preferences.get(IntKey.ApsAutoIsfIobThPercent)
            runMarks.mark(RunMark.USUAL2, now)
            applyUsual2(now)
            sendAutoSms("Usual2forTH [b$usualBlock]: g=${decimals(bg / 18.016, 1)} iobTH=$usualTh")
            carePortalNote("UsuIP-$usualBlock")
            aapsLogger.debug(LTag.APS, "Usual2 block $usualBlock")
        }
        // The prediction branch stays closed: it needs the UKF raw 5 minute change.
        // The acceleration weight is not lowered. A drop to 0.10 would stay, because the restore only raises it.
        val acceNow = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
        val hypo1 = alarmHypo1ShouldFire(
            ready = runMarks.ready(RunMark.ALARM_HYPO_1, 15, now),
            bg = bg,
            delta = delta,
            shortDelta = shortDelta,
            acceWeight = acceNow,
            minuteOfDay = minuteOfDay,
            steps60 = steps60(now),
            hp = null,
            hp1 = null,
            recentBolusOrCarbs = false,
        )
        val hypo2 = alarmHypo2ShouldFire(
            ready = runMarks.ready(RunMark.ALARM_HYPO_2, 15, now),
            bg = bg,
            delta = delta,
            shortDelta = shortDelta,
            acceWeight = acceNow,
            steps30 = steps30,
            hp = null,
            hp1 = null,
            recentBolusOrCarbs = false,
        )
        if (hypo1 || hypo2) {
            preferences.put(LongNonKey.ApsAutoIsfLastAlarmHypoAt, now)
            applyAlarmHypoState()
            if (hypo1) runMarks.mark(RunMark.ALARM_HYPO_1, now)
            if (hypo2) runMarks.mark(RunMark.ALARM_HYPO_2, now)
            aapsLogger.debug(LTag.APS, "AlarmHypo marked 1=$hypo1 2=$hypo2")
        }
    }

    // Between 02:00 and 04:00, a flat high on the Low profile can move to the Standard profile for 60 minutes.
    // The duration factor must be above 2.5 and higher than the other factors. No SMB in the last 30 minutes.
    private suspend fun applyOvernightDuraRescue(
        now: Long,
        minuteOfDay: Int,
        bg: Double,
        shortDelta: Double,
        longDelta: Double,
        factors: AutoIsfFactors,
        smbSum30: Double,
        statesOn: Boolean,
    ) {
        if (!factors.recorded) return
        val lowName = preferences.get(StringKey.ApsAutoIsfLowProfileName)
        val standardName = preferences.get(StringKey.ApsAutoIsfStandardProfileName)
        val fire = overnightDuraRescueShouldFire(
            ready = runMarks.ready(RunMark.OVERNIGHT_DURA_RESCUE, 60, now),
            rescueActive = preferences.get(LongNonKey.ApsAutoIsfOvernightRescueUntil) > now,
            minuteOfDay = minuteOfDay,
            onLowProfile = lowName.isNotBlank() && profileFunction.getOriginalProfileName() == lowName,
            bg = bg,
            shortDelta = shortDelta,
            longDelta = longDelta,
            duraIsf = factors.duraIsf,
            finalIsf = factors.finalIsf,
            acceIsf = factors.acceIsf,
            bgIsf = factors.bgIsf,
            ppIsf = factors.ppIsf,
            smbSum30 = smbSum30,
            lowBgRecent = !statesOn || states().inState("LowBG", "50recent"),
        )
        if (!fire) return
        if (standardName.isBlank() || profileFunction.getOriginalProfileName() == standardName) {
            aapsLogger.debug(LTag.APS, "Overnight dura rescue: no standard profile to switch to")
            return
        }
        val iCfg = profileFunction.getRunningOrRequestedICfg()
        val store = profileRepository.profile.value
        if (iCfg == null || store == null || store.getSpecificProfile(standardName) == null) {
            aapsLogger.debug(LTag.APS, "Overnight dura rescue: standard profile is not in the store")
            return
        }
        val switched = profileFunction.createProfileSwitch(
            profileStore = store,
            profileName = standardName,
            durationInMinutes = 60,
            percentage = 100,
            timeShiftInHours = 0,
            timestamp = now,
            action = Action.PROFILE_SWITCH,
            source = Sources.Automation,
            note = "AutoISF: overnight dura rescue",
            listValues = listOf(
                ValueWithUnit.SimpleString(standardName),
                ValueWithUnit.Percent(100),
                ValueWithUnit.Minute(60)
            ),
            iCfg = iCfg,
        ) != null
        if (!switched) {
            aapsLogger.debug(LTag.APS, "Overnight dura rescue did not write a switch")
            return
        }
        preferences.put(LongNonKey.ApsAutoIsfOvernightRescueUntil, now + 60 * 60_000L)
        runMarks.mark(RunMark.OVERNIGHT_DURA_RESCUE, now)
        sendAutoSms("OvernightDuraRescue: g=${decimals(bg / 18.0182, 1)} duraISF=${decimals(factors.duraIsf, 2)} finalISF=${decimals(factors.finalIsf, 2)} -> Standard 60min")
        carePortalNote("DuraRsc")
        aapsLogger.debug(LTag.APS, "Overnight dura rescue -> $standardName for 60 min")
    }

    // A confirmed overnight rise moves to the Standard profile for 30 minutes.
    // Profile is marked HnAM. The IOB threshold and the acceleration weight stay as they are.
    private suspend fun applyHighNight(
        now: Long,
        minuteOfDay: Int,
        tempTargetSet: Boolean,
        bg: Double,
        delta: Double,
        shortDelta: Double,
        longDelta: Double,
        statesOn: Boolean,
    ) {
        if (!highNightShouldFire(
                ready = runMarks.ready(RunMark.HIGH_NIGHT, 60, now),
                tempTargetSet = tempTargetSet,
                steroidsOff = statesOn && states().inState("Steroids", "Steroids Off"),
                minuteOfDay = minuteOfDay,
                bg = bg,
                delta = delta,
                shortDelta = shortDelta,
                longDelta = longDelta,
            )
        ) return
        val standardName = preferences.get(StringKey.ApsAutoIsfStandardProfileName)
        val alreadyThere = standardName.isNotBlank() && profileFunction.getOriginalProfileName() == standardName
        val switched = alreadyThere || switchToStandardFor(standardName, 30, now)
        if (!switched) {
            aapsLogger.debug(LTag.APS, "High night: standard profile was not switched")
            return
        }
        val store = states()
        if (statesOn && store.hasStateValues("Profile")) store.setState("Profile", "HnAM")
        runMarks.mark(RunMark.HIGH_NIGHT, now)
        sendAutoSms("HighNight00AM: g=${decimals(bg / 18.016, 1)} d=${decimals(delta / 18.016, 2)} sd=${decimals(shortDelta / 18.016, 2)} ld=${decimals(longDelta / 18.016, 2)}")
        carePortalNote("HnAM")
        aapsLogger.debug(LTag.APS, "High night -> $standardName for 30 min")
    }

    // A stable or rising glucose on a Low-family profile moves to Standard at the same A, B, or C letter.
    // The letter comes from the running profile name, so a stale Low Current name cannot block it.
    // The acceleration weight goes back to 0.50. From 07:00 until midnight. Profile stays at 100%.
    private suspend fun applyBasalUp(
        now: Long,
        minuteOfDay: Int,
        bg: Double,
        delta: Double,
        profilePercent: Int,
        statesOn: Boolean,
    ) {
        val store = states()
        val cannula = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)
        val podHours = if (cannula == null) null else (now - cannula.timestamp) / 3_600_000.0
        if (!basalUpShouldFire(
                ready = runMarks.ready(RunMark.BASAL_UP, 5, now),
                bg = bg,
                delta = delta,
                profilePercent = profilePercent,
                minuteOfDay = minuteOfDay,
                steps60 = steps60(now),
                steps30 = steps30(now),
                podHours = podHours,
                onLowFamily = runningOnLowLadder(
                    profileFunction.getOriginalProfileName(),
                    preferences.get(StringKey.ApsAutoIsfLowProfileName).trim(),
                    lowLadderNames(),
                ),
                mj3 = statesOn && store.inState("MJ", "MJ3"),
                noMjRemains = statesOn && store.inState("MJ", "NOMJremains"),
            )
        ) return
        switchToStandardAtSharedTier(now)
        preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, 0.50)
        runMarks.mark(RunMark.BASAL_UP, now)
        sendAutoSms("BasalUp Acce")
        carePortalNote("BsUp")
        aapsLogger.debug(LTag.APS, "BasalUp -> standard at 100%, acceleration weight 0.50")
    }

    // A mild rise with no SMB for 10 minutes clears a temp target and forces the target offset to 0.
    // The clock keeps running when the release itself is not allowed. One SMB or a flat delta resets it.
    private suspend fun applyPersistentRise(
        now: Long,
        minuteOfDay: Int,
        delta: Double,
        bg: Double,
        tempTargetSet: Boolean,
        statesOn: Boolean,
        steps5: Int,
        steps30: Int,
    ) {
        val holding = delta >= 1.8 && smbCount5(now) <= 0
        val startedAt = preferences.get(LongNonKey.ApsAutoIsfPersistentRiseStartedAt)
        val clock = persistentRiseClock(holding, startedAt, now)
        if (clock.startedAt != startedAt) {
            preferences.put(LongNonKey.ApsAutoIsfPersistentRiseStartedAt, clock.startedAt)
        }
        val lowName = preferences.get(StringKey.ApsAutoIsfLowProfileName).trim()
        if (!persistentRiseShouldFire(
                ready = runMarks.ready(RunMark.PERSISTENT_RISE, 5, now),
                boostOn = preferences.get(BooleanKey.ApsAutoIsfBoostAutomationsEnabled),
                minuteOfDay = minuteOfDay,
                daytimeBypass = newPodHighBypass(now, bg) || runMarks.recent(RunMark.USUAL2, 90, now),
                holding = holding,
                persistentMinutes = clock.persistentMinutes,
                onLowCurrent = profileFunction.getOriginalProfileName() == lowName,
                mjActive = statesOn && states().inState("MJ", "MJ active"),
                steps5 = steps5,
                steps30 = steps30,
            )
        ) return
        if (tempTargetSet) {
            persistenceLayer.cancelCurrentTemporaryTargetIfAny(
                timestamp = now,
                action = Action.CANCEL_TT,
                source = Sources.Automation,
                note = "AutoISF: persistent rise",
                listValues = emptyList(),
            )
        }
        preferences.put(BooleanNonKey.ApsAutoIsfMildOffsetZeroActive, true)
        preferences.put(LongNonKey.ApsAutoIsfPersistentRiseStartedAt, 0L)
        runMarks.mark(RunMark.PERSISTENT_RISE, now)
        sendAutoSms("PersistentRiseRelease: g=${decimals(bg / 18.016, 1)} d=${decimals(delta / 18.016, 2)} (${clock.persistentMinutes.toInt()}min sustained, no SMB)")
        carePortalNote("PstRs")
        aapsLogger.debug(LTag.APS, "Persistent rise release, offset zero, ${clock.persistentMinutes.toInt()} min")
    }

    // Plateau brakes. Night and day set a 4.0 mmol target for 2 minutes and raise the pp weight.
    // Dawn sets a 4.2 mmol target only. Each one waits 30 minutes after the others.
    // A fast IOB rise, or a delta that leaves the plateau, cancels that brake's own target.
    private suspend fun applyHighBrakes(
        now: Long,
        minuteOfDay: Int,
        bg: Double,
        delta: Double,
        shortDelta: Double,
        longDelta: Double,
        ukfDelta5: Double?,
        ukfDelta15: Double?,
        factors: AutoIsfFactors,
        iob: Double,
        cob: Double,
        rawDelta5: Double?,
        statesOn: Boolean,
    ) {
        val store = states()
        val active = persistenceLayer.getTemporaryTargetActiveAt(now)
        val twilightAt = preferences.get(LongNonKey.ApsAutoIsfHiBrkTwilightTtAt)
        val plan = highBrakePlan(
            HighBrakeSnapshot(
                minuteOfDay = minuteOfDay,
                bg = bg,
                delta = delta,
                shortDelta = shortDelta,
                longDelta = longDelta,
                ukfDelta5 = ukfDelta5,
                ukfDelta15 = ukfDelta15,
                factorsReady = factors.recorded,
                duraIsf = factors.duraIsf,
                acceIsf = factors.acceIsf,
                bgIsf = factors.bgIsf,
                ppIsf = factors.ppIsf,
                hp1Mmol = rawDelta5?.let { hypoPrediction2Mmol(bg, shortDelta, it, iob, cob) },
                iobChange5 = iobAt(now) - iobAt(now - 5 * 60_000L),
                ttLowMgdl = active?.lowTarget,
                steroidsOff = statesOn && store.inState("Steroids", "Steroids Off"),
                nightReady30 = runMarks.ready(RunMark.HIGH_EVE_NIGHT_BRAKE, 30, now),
                dayReady30 = runMarks.ready(RunMark.HIGH_DAYTIME_BRAKE, 30, now),
                twilightReady30 = runMarks.ready(RunMark.HI_BRK_TWILIGHT, 30, now),
                twilightReady15 = runMarks.ready(RunMark.HI_BRK_TWILIGHT, 15, now),
                nightMarkedWithin6 = runMarks.recent(RunMark.HIGH_EVE_NIGHT_BRAKE, 6, now),
                dayMarkedWithin6 = runMarks.recent(RunMark.HIGH_DAYTIME_BRAKE, 6, now),
                twilightOwn = twilightAt > 0L && active?.timestamp == twilightAt,
                mj3OrNoMj = statesOn && (store.inState("MJ", "MJ3") || store.inState("MJ", "NOMJremains")),
                lowBg50Recent = statesOn && store.inState("LowBG", "50recent"),
                daytimeBypass = newPodHighBypass(now, bg) || runMarks.recent(RunMark.USUAL2, 90, now),
            )
        )
        if (plan.cutNight || plan.cutDay || plan.cutTwilight) {
            persistenceLayer.cancelCurrentTemporaryTargetIfAny(
                timestamp = now,
                action = Action.CANCEL_TT,
                source = Sources.Automation,
                note = "AutoISF: high brake cut",
                listValues = emptyList(),
            )
            if (plan.cutNight) carePortalNote("HiBrkCut")
            if (plan.cutDay) carePortalNote("HiBrkDayCut")
            if (plan.cutTwilight) carePortalNote("HiBrkTwilightCut")
            aapsLogger.debug(LTag.APS, "High brake cut night=${plan.cutNight} day=${plan.cutDay} twilight=${plan.cutTwilight}")
        }
        if (plan.fireNight || plan.fireDayHigh || plan.fireDayMid) {
            preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh))
            startBrakeTarget(now, 72.1, "AutoISF: high brake 4.0")
            if (plan.fireNight) runMarks.mark(RunMark.HIGH_EVE_NIGHT_BRAKE, now)
            if (plan.fireDayHigh || plan.fireDayMid) runMarks.mark(RunMark.HIGH_DAYTIME_BRAKE, now)
            if (plan.fireNight || plan.fireDayHigh || plan.fireDayMid) {
                sendAutoSms("HighEveNightBrake: TT 4.0mmol@2min + ppWeight high, g=${decimals(bg / 18.016, 1)}")
                carePortalNote("HiBrk")
            }
            aapsLogger.debug(LTag.APS, "High brake fire night=${plan.fireNight} dayHigh=${plan.fireDayHigh} dayMid=${plan.fireDayMid}")
        }
        if (plan.fireTwilight) {
            startBrakeTarget(now, 4.2 * 18.0, "AutoISF: twilight brake 4.2")
            preferences.put(LongNonKey.ApsAutoIsfHiBrkTwilightTtAt, now)
            runMarks.mark(RunMark.HI_BRK_TWILIGHT, now)
            sendAutoSms("HiBrkTwilight: TT 4.2mmol@2min, g=${decimals(bg / 18.016, 1)}")
            carePortalNote("HiBrkTwilight")
            aapsLogger.debug(LTag.APS, "Twilight brake, 4.2 mmol for 2 min")
        }
    }

    // Carbs must stay at 5 g or more for 45 minutes before the evening cap relaxes to 60%.
    // The clock is forgotten when the app process stops.
    private fun cobHasStayedUp(now: Long, cob: Double): Boolean {
        if (cob >= 5.0) {
            if (cobSustainedSince == 0L) cobSustainedSince = now
        } else {
            cobSustainedSince = 0L
        }
        if (cobSustainedSince <= 0L) return false
        return (now - cobSustainedSince) / 60_000.0 >= 45.0
    }

    private suspend fun minutesSinceLastCarbs(now: Long): Int? {
        val last = persistenceLayer.getNewestCarbs()?.timestamp ?: return null
        return ((now - last).toDouble() / 60_000.0).toInt()
    }

    // Stuck high, poor response, and the unexplained-high backstop run before the night ceiling.
    // The tier C revert is not throttled. OffHighProf runs before NightAcce so a later night write can replace it.
    private suspend fun applyRescue(
        now: Long,
        minuteOfDay: Int,
        bg: Double,
        delta: Double,
        shortDelta: Double,
        longDelta: Double,
        profilePercent: Int,
        cob: Double,
        iob: Double,
        maxIob: Double,
        targetBg: Double,
        statesOn: Boolean,
    ) {
        val store = states()
        val ukf = ukfRawNow(now)
        val hp = ukf.delta5?.let { hypoPrediction2Mmol(bg, shortDelta, it, iob, cob) }
        val branch = stuckHighBranch(
            ready = runMarks.ready(RunMark.STUCK_HIGH, 30, now),
            bg = bg,
            iob = iob,
            maxIob = maxIob,
            hp = hp,
            targetMmol = targetBg / 18.0182,
        )
        if (branch == StuckHighBranch.RATIO) {
            val boosted = stuckHighRatio(smb_delivery_ratio, smb_delivery_ratio_max)
            if (!deliveryNear(smb_delivery_ratio, boosted)) {
                preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryRatio, boosted)
                startBrakeTarget(now, targetBg, "AutoISF: stuck high ratio", 30)
                escalateToStuckHighTierC(now)
                runMarks.mark(RunMark.STUCK_HIGH, now)
                sendAutoSms("StuckHighRescue [ratio]: g=${decimals(bg / 18.0182, 1)} HP2=${if (hp == null) "--" else decimals(hp, 2)} SMBdel -> ${decimals(boosted, 2)}")
                aapsLogger.debug(LTag.APS, "Stuck high ratio -> $boosted")
            }
        } else if (branch == StuckHighBranch.TARGET) {
            startBrakeTarget(now, rescueTargetMgdl(targetBg), "AutoISF: stuck high target", 30)
            escalateToStuckHighTierC(now)
            runMarks.mark(RunMark.STUCK_HIGH, now)
            sendAutoSms("StuckHighRescue [target]: g=${decimals(bg / 18.0182, 1)} HP2=${if (hp == null) "--" else decimals(hp, 2)}")
            aapsLogger.debug(LTag.APS, "Stuck high target")
        }
        val recentBoost = runMarks.recent(RunMark.BOLUS_GIVEN_MILD, 3, now) ||
            runMarks.recent(RunMark.BOLUS_GIVEN_MILD_FAILSAFE, 3, now) ||
            runMarks.recent(RunMark.BOLUS_GIVEN, 3, now) ||
            runMarks.recent(RunMark.BOLUS_GIVEN_BG3, 3, now) ||
            runMarks.recent(RunMark.UAM_BST, 3, now)
        if (shouldLatchBigDose(lastCycleSmb, recentBoost) && lastCycleSmbAt > 0L) {
            lastBigDoseAt = lastCycleSmbAt
            lastBigDoseBg = bg
            lastBigDoseShort = shortDelta
        }
        if (lastBigDoseAt > 0L) {
            val stage = poorResponseStage(
                minutesSinceDose = (now - lastBigDoseAt) / 60_000.0,
                riseMgdl = bg - lastBigDoseBg,
                stillRising = delta > 0.0,
                noDecel = shortDelta >= lastBigDoseShort * 0.75,
                iobRoom = iob < 0.30 * maxIob,
                stage1Ready = runMarks.ready(RunMark.POOR_RESPONSE_1, 10, now),
                stage2Ready = runMarks.ready(RunMark.POOR_RESPONSE_2, 10, now),
            )
            if (stage == 1) {
                val boosted = poorResponseRatio(smb_delivery_ratio, smb_delivery_ratio_max)
                if (!deliveryNear(smb_delivery_ratio, boosted)) {
                    preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryRatio, boosted)
                    startBrakeTarget(now, targetBg, "AutoISF: poor response 1", 20)
                    runMarks.mark(RunMark.POOR_RESPONSE_1, now)
                    sendAutoSms("PoorResponseRescue [stage1]: SMBdel -> ${decimals(boosted, 2)}")
                    carePortalNote("PRR1")
                    aapsLogger.debug(LTag.APS, "Poor response stage 1 ratio -> $boosted")
                }
            } else if (stage == 2) {
                val stage2Ratio = stuckHighRatio(smb_delivery_ratio, smb_delivery_ratio_max)
                preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryRatio, stage2Ratio)
                startBrakeTarget(now, rescueTargetMgdl(targetBg), "AutoISF: poor response 2", 30)
                escalateToStuckHighTierC(now)
                runMarks.mark(RunMark.POOR_RESPONSE_2, now)
                sendAutoSms("PoorResponseRescue [stage2]: SMBdel -> ${decimals(stage2Ratio, 2)}")
                carePortalNote("PRR2")
                aapsLogger.debug(LTag.APS, "Poor response stage 2")
            }
        }
        val mealNow = cob > 0.0 || runMarks.recent(RunMark.UAM_BST, 120, now)
        val tracked = nextUnexplainedHigh(
            now = now,
            highNow = bg > 144.1,
            mealNow = mealNow,
            since = preferences.get(LongNonKey.ApsAutoIsfUnexplainedHighSince),
            mealSeen = preferences.get(BooleanNonKey.ApsAutoIsfUnexplainedHighMealSeen),
        )
        preferences.put(LongNonKey.ApsAutoIsfUnexplainedHighSince, tracked.since)
        preferences.put(BooleanNonKey.ApsAutoIsfUnexplainedHighMealSeen, tracked.mealSeen)
        if (unexplainedHighIsSustained(now, tracked.since) && !tracked.mealSeen &&
            runMarks.ready(RunMark.UNEXPLAINED_HIGH, 30, now)
        ) {
            escalateToStuckHighTierC(now)
            runMarks.mark(RunMark.UNEXPLAINED_HIGH, now)
            sendAutoSms("UnexplainedHighTierC: g=${decimals(bg / 18.0182, 1)} high 2h+, no meal/UAM seen -> TierC")
            carePortalNote("UnHTC")
            aapsLogger.debug(LTag.APS, "Unexplained high tier C")
        }
        revertStuckHighTierC(now, bg)
        val offHigh = offHighBlock(
            ready = runMarks.ready(RunMark.OFF_HIGH, 30, now),
            minuteOfDay = minuteOfDay,
            bg = bg,
            delta = delta,
            shortDelta = shortDelta,
            longDelta = longDelta,
            profilePercent = profilePercent,
            onLowProfile = profileFunction.getOriginalProfileName() == preferences.get(StringKey.ApsAutoIsfLowProfileName).trim(),
            ttActive = persistenceLayer.getTemporaryTargetActiveAt(now) != null,
            steroidsOff = statesOn && store.inState("Steroids", "Steroids Off"),
            nightHighTag = statesOn && store.inState("Profile", "HnAM"),
        )
        if (offHigh != null && offHighShouldAct(minuteOfDay, hp)) {
            val rescue = preferences.get(LongNonKey.ApsAutoIsfOvernightRescueUntil) > now
            if (!rescue) switchToLowAtSharedTier(now, "AutoISF: off high", 30)
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, 0.18)
            preferences.put(IntKey.ApsAutoIsfIobThPercent, 18)
            if (statesOn && store.inState("Profile", "HnAM") && store.hasStateValues("Profile")) {
                store.setState("Profile", "C100")
            }
            runMarks.mark(RunMark.OFF_HIGH, now)
            sendAutoSms("OffHighProf [b$offHigh]: g=${decimals(bg / 18.016, 1)} d=${decimals(delta / 18.016, 2)}")
            carePortalNote("OffP-$offHigh")
            aapsLogger.debug(LTag.APS, "OffHighProf block $offHigh")
        }
    }

    // Points Standard and Low at tier C and remembers the names they had. A second fire does not overwrite those names.
    // Skipped while a hypo alarm is recent and an MJ cycle is still on. The ratio and temp target still happen.
    private suspend fun escalateToStuckHighTierC(now: Long) {
        if (preferences.get(BooleanNonKey.ApsAutoIsfStuckHighTierCActive)) return
        val store = states()
        val statesOn = preferences.get(BooleanKey.AutomationStatesEnabled)
        val hypoRevert = statesOn && store.inState("AlarmHypo", "AlarmRecent") &&
            store.hasStateValues("MJ") && !store.inState("MJ", "NOMJremains")
        if (hypoRevert) {
            aapsLogger.debug(LTag.APS, "Stuck high tier C skipped: hypo alarm revert is active")
            return
        }
        val lowRungs = lowLadderNames()
        val standardRungs = standardLadderNames()
        val currentLow = preferences.get(StringKey.ApsAutoIsfLowProfileName).trim()
        val currentStandard = preferences.get(StringKey.ApsAutoIsfStandardProfileName).trim()
        val targetLow = lowRungs.getOrNull(2)?.ifBlank { currentLow }.orEmpty()
        val targetStandard = standardRungs.getOrNull(2)?.ifBlank { currentStandard }.orEmpty()
        if (targetLow.isBlank() || targetStandard.isBlank()) return
        val running = profileFunction.getOriginalProfileName()
        preferences.put(StringNonKey.ApsAutoIsfStuckHighPrevLow, currentLow)
        preferences.put(StringNonKey.ApsAutoIsfStuckHighPrevStandard, currentStandard)
        preferences.put(StringKey.ApsAutoIsfLowProfileName, targetLow)
        preferences.put(StringKey.ApsAutoIsfStandardProfileName, targetStandard)
        val nudge = roleTierDeliveryNudge(
            previousBand = roleTierBandForIndex(sharedRoleLadderIndex(currentStandard, currentLow, standardRungs, lowRungs)),
            newBand = 1,
            smbBaseline = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline),
            mildRatio = preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio),
        )
        if (nudge != null) {
            preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryBaseline, nudge.smbBaseline)
            preferences.put(DoubleKey.ApsAutoIsfMildBoostRatio, nudge.mildRatio)
        }
        val switchedTo = when (running) {
            currentLow -> targetLow
            currentStandard -> targetStandard
            else -> ""
        }
        if (switchedTo.isNotBlank()) switchToStandardFor(switchedTo, 0, now, "AutoISF: stuck high tier C")
        preferences.put(BooleanNonKey.ApsAutoIsfStuckHighTierCActive, true)
        keepSteroidsOff()
        carePortalNote("STCOn")
        aapsLogger.debug(LTag.APS, "Stuck high tier C on")
    }

    // Puts the saved role names back once glucose is under 7.5 mmol.
    private suspend fun revertStuckHighTierC(now: Long, bg: Double) {
        if (!preferences.get(BooleanNonKey.ApsAutoIsfStuckHighTierCActive) || bg >= 135.1) return
        val escalatedLow = preferences.get(StringKey.ApsAutoIsfLowProfileName).trim()
        val escalatedStandard = preferences.get(StringKey.ApsAutoIsfStandardProfileName).trim()
        val restoredLow = preferences.get(StringNonKey.ApsAutoIsfStuckHighPrevLow).trim()
        val restoredStandard = preferences.get(StringNonKey.ApsAutoIsfStuckHighPrevStandard).trim()
        val running = profileFunction.getOriginalProfileName()
        if (restoredLow.isNotBlank()) preferences.put(StringKey.ApsAutoIsfLowProfileName, restoredLow)
        if (restoredStandard.isNotBlank()) preferences.put(StringKey.ApsAutoIsfStandardProfileName, restoredStandard)
        val switchedTo = when {
            running == escalatedLow && restoredLow.isNotBlank() -> restoredLow
            running == escalatedStandard && restoredStandard.isNotBlank() -> restoredStandard
            else -> ""
        }
        if (switchedTo.isNotBlank()) switchToStandardFor(switchedTo, 0, now, "AutoISF: stuck high tier C off")
        val lowRungs = lowLadderNames()
        val standardRungs = standardLadderNames()
        val nudge = roleTierDeliveryNudge(
            previousBand = 1,
            newBand = roleTierBandForIndex(sharedRoleLadderIndex(restoredStandard, restoredLow, standardRungs, lowRungs)),
            smbBaseline = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline),
            mildRatio = preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio),
        )
        if (nudge != null) {
            preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryBaseline, nudge.smbBaseline)
            preferences.put(DoubleKey.ApsAutoIsfMildBoostRatio, nudge.mildRatio)
        }
        preferences.put(BooleanNonKey.ApsAutoIsfStuckHighTierCActive, false)
        preferences.put(StringNonKey.ApsAutoIsfStuckHighPrevLow, "")
        preferences.put(StringNonKey.ApsAutoIsfStuckHighPrevStandard, "")
        sendAutoSms("StuckHighTierC off: BGL ${decimals(bg / 18.0182, 1)}")
        carePortalNote("STCOf")
        aapsLogger.debug(LTag.APS, "Stuck high tier C off")
    }

    // A boost earlier in this loop is left alone. NightAcce and SemiTwilight both restore the baseline.
    private fun restoreSmbBaselineUnlessBoosted() {
        if (smbBoostedThisCycle) return
        preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryRatio, preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline))
    }

    // Battery and the profile-name check run first. The batch step sees this loop's poor-response mark.
    // The low-glucose tier A reset is last, so it can undo a step from the same loop.
    private suspend fun applyProfileBatch(now: Long, bg: Double) {
        val safety = preferences.get(StringNonKey.ApsAutoIsfSafetyProfileName).trim()
        if (battery1ShouldFire(
                ready = runMarks.ready(RunMark.BATTERY_1, 20, now),
                running = profileFunction.getOriginalProfileName(),
                safetyName = safety,
                batteryPercent = receiverStatusStore.batteryLevel,
                livePump = activePlugin.activePump !is VirtualPump,
            )
        ) {
            switchToStandardFor(safety, 0, now, "AutoISF: battery 1%")
            runMarks.mark(RunMark.BATTERY_1, now)
            phoneAlert("Batt1%")
            sendAutoSms("LowBattery")
            sendAutoSmsToNumbers("LowBattery", StringKey.SmsBattAlertNumbers)
            carePortalNote("Bt<1%")
        }
        if (batteryOver1ShouldFire(
                ready = runMarks.ready(RunMark.BATTERY_OVER_1, 5, now),
                running = profileFunction.getOriginalProfileName(),
                safetyName = safety,
                batteryPercent = receiverStatusStore.batteryLevel,
            )
        ) {
            switchToStandardAtSharedTier(now, 0)
            val store = states()
            if (preferences.get(BooleanKey.AutomationStatesEnabled) && store.hasStateValues("Profile")) store.setState("Profile", "AllOK")
            runMarks.mark(RunMark.BATTERY_OVER_1, now)
            sendAutoSms("AllOK Batt")
            carePortalNote("bat>1")
        }
        if (runMarks.ready(RunMark.PROFILE_ROLE_SANITY, 360, now)) {
            val profiles = profileRepository.profile.value
            val names = profiles?.getProfileList()?.map { it.toString() }.orEmpty()
            val fills = blankRoleFills(
                roleValues = mapOf(
                    StringKey.ApsAutoIsfStandardProfileName.key to preferences.get(StringKey.ApsAutoIsfStandardProfileName),
                    StringKey.ApsAutoIsfLowProfileName.key to preferences.get(StringKey.ApsAutoIsfLowProfileName),
                    StringKey.ApsAutoIsfStandard100ProfileName.key to preferences.get(StringKey.ApsAutoIsfStandard100ProfileName),
                    StringKey.ApsAutoIsfStandard105ProfileName.key to preferences.get(StringKey.ApsAutoIsfStandard105ProfileName),
                    StringKey.ApsAutoIsfStandard110ProfileName.key to preferences.get(StringKey.ApsAutoIsfStandard110ProfileName),
                    StringKey.ApsAutoIsfLow70ProfileName.key to preferences.get(StringKey.ApsAutoIsfLow70ProfileName),
                    StringKey.ApsAutoIsfLow80ProfileName.key to preferences.get(StringKey.ApsAutoIsfLow80ProfileName),
                    StringKey.ApsAutoIsfLow90ProfileName.key to preferences.get(StringKey.ApsAutoIsfLow90ProfileName),
                ),
                profileNames = names,
            )
            fills.forEach { (key, value) ->
                roleKeyForBlankFill(key)?.let { preferences.put(it, value) }
            }
            if (fills.isNotEmpty()) phoneNote("ProfileRole: filled blank role settings from profile names")
            val missing = missingProfileRoles(
                standardFound = profiles?.getSpecificProfile(preferences.get(StringKey.ApsAutoIsfStandardProfileName).trim()) != null,
                lowFound = profiles?.getSpecificProfile(preferences.get(StringKey.ApsAutoIsfLowProfileName).trim()) != null,
                safetyFound = profiles?.getSpecificProfile(safety) != null,
            )
            if (missing.isNotEmpty()) {
                phoneNote("ProfileRole: ${missing.joinToString(", ")} profile(s) not found -- check Settings")
                carePortalNote("ProfRoleMissing")
            }
            runMarks.mark(RunMark.PROFILE_ROLE_SANITY, now)
        }
        val bglSince = heldSince(now, bg > 216.2, preferences.get(LongNonKey.ApsAutoIsfBatchBgl12Since))
        preferences.put(LongNonKey.ApsAutoIsfBatchBgl12Since, bglSince)
        val ukfGlucose = ukfRawNow(now).glucose
        val ukfSince = heldSince(now, ukfGlucose != null && ukfGlucose > 252.2, preferences.get(LongNonKey.ApsAutoIsfBatchUkf14Since))
        preferences.put(LongNonKey.ApsAutoIsfBatchUkf14Since, ukfSince)
        val choice = profileBatchChoice(
            holdA = preferences.get(BooleanNonKey.ApsAutoIsfProfileBatchRevertEnabled),
            holdC = preferences.get(BooleanNonKey.ApsAutoIsfProfileBatchRevertCEnabled),
            autoOn = preferences.get(BooleanNonKey.ApsAutoIsfProfileBatchAutoEnabled),
            bgl12For2h = heldForHours(now, bglSince, 2),
            ukf14For2h = heldForHours(now, ukfSince, 2),
            poorResponseRecent = runMarks.recent(RunMark.POOR_RESPONSE_2, 5, now),
            gentleHypoRecent = runMarks.recent(RunMark.GENTLE_HYPO_RISK, 5, now),
            morningStreak = preferences.get(IntNonKey.ApsAutoIsfMorningRoleSwapChangeStreak),
        )
        when (choice) {
            BatchChoice.BOTH_HOLDS -> aapsLogger.debug(LTag.APS, "Profile batch holds both on, skipped")
            BatchChoice.HOLD_A -> if (resetToRung(now, 0, runMarks.ready(RunMark.PROFILE_BATCH_REVERT, 5, now))) {
                runMarks.mark(RunMark.PROFILE_BATCH_REVERT, now)
                aapsLogger.debug(LTag.APS, "Profile batch hold A")
            }
            BatchChoice.HOLD_C -> if (resetToRung(now, 2, runMarks.ready(RunMark.PROFILE_BATCH_REVERT_C, 5, now))) {
                runMarks.mark(RunMark.PROFILE_BATCH_REVERT_C, now)
                aapsLogger.debug(LTag.APS, "Profile batch hold C")
            }
            BatchChoice.STEP_DOWN, BatchChoice.STEP_UP -> {
                stepBatch(now, up = choice == BatchChoice.STEP_UP)
                if (choice == BatchChoice.STEP_DOWN && preferences.get(IntNonKey.ApsAutoIsfMorningRoleSwapChangeStreak) >= 2) {
                    preferences.put(IntNonKey.ApsAutoIsfMorningRoleSwapChangeStreak, 0)
                }
            }
            BatchChoice.NONE -> Unit
        }
        val hypoStore = states()
        val hypoStatesOn = preferences.get(BooleanKey.AutomationStatesEnabled)
        if (alarmHypoRoleShouldRevert(
                statesOn = hypoStatesOn,
                alarmRecent = hypoStore.inState("AlarmHypo", "AlarmRecent"),
                mjHasValues = hypoStore.hasStateValues("MJ"),
                noMjRemains = hypoStore.inState("MJ", "NOMJremains"),
            ) && resetToRung(now, 0, runMarks.ready(RunMark.ALARM_HYPO_ROLE_REVERT, 30, now))
        ) {
            runMarks.mark(RunMark.ALARM_HYPO_ROLE_REVERT, now)
            aapsLogger.debug(LTag.APS, "Alarm hypo role revert to tier A")
        }
        if (!runMarks.ready(RunMark.LOW_BG_TIER_A_SCAN, 5, now)) return
        runMarks.mark(RunMark.LOW_BG_TIER_A_SCAN, now)
        val from = now - 12 * 3_600_000L
        val steps = persistenceLayer.getStepsCountFromTimeToTime(from, now).map { it.timestamp to it.steps60min }
        val loopSeries = persistenceLayer.getBgReadingsDataFromTimeToTime(from, now, ascending = true)
            .map { it.timestamp to it.value }
        val loopHit = sustainedLowEpisode(loopSeries, steps, sustainedMinutes = 10, maxMgdl = 72.1, maxSteps60 = 1000)
        val ukfHit = sustainedLowEpisode(ukf1Series(now), steps, sustainedMinutes = 10, maxMgdl = 72.1, maxSteps60 = 1000)
        if ((loopHit || ukfHit) && resetToRung(now, 0, runMarks.ready(RunMark.LOW_BG_TIER_A, 30, now))) {
            runMarks.mark(RunMark.LOW_BG_TIER_A, now)
            aapsLogger.debug(LTag.APS, "Low BG tier A reset loop=$loopHit ukf=$ukfHit")
        }
    }

    private suspend fun resetToRung(now: Long, index: Int, ready: Boolean): Boolean {
        val lowRungs = lowLadderNames()
        val standardRungs = standardLadderNames()
        val lowCurrent = preferences.get(StringKey.ApsAutoIsfLowProfileName).trim()
        val standardCurrent = preferences.get(StringKey.ApsAutoIsfStandardProfileName).trim()
        val names = sharedRungNames(
            index = index,
            lowRungs = lowRungs,
            standardRungs = standardRungs,
            lowCurrent = lowCurrent,
            standardAnchor = preferences.get(StringKey.ApsAutoIsfStandard100ProfileName).trim(),
            standardCurrent = standardCurrent,
        ) ?: return false
        if (lowCurrent == names.first && standardCurrent == names.second) return false
        if (!ready) return false
        return stepSharedRung(now, index)
    }

    private suspend fun stepBatch(now: Long, up: Boolean): Boolean {
        if (!runMarks.ready(RunMark.PROFILE_BATCH_STEP, 30, now)) return false
        val lowRungs = lowLadderNames()
        val standardRungs = standardLadderNames()
        val lowCurrent = preferences.get(StringKey.ApsAutoIsfLowProfileName).trim()
        val standardCurrent = preferences.get(StringKey.ApsAutoIsfStandardProfileName).trim()
        val slot = profileBatchSlot(
            running = profileFunction.getOriginalProfileName(),
            lowRole = lowCurrent,
            standardRole = standardCurrent,
            lowIndex = ladderIndexOf(lowCurrent, lowRungs),
            standardIndex = ladderIndexOf(standardCurrent, standardRungs),
            sharedIndex = sharedRoleLadderIndex(standardCurrent, lowCurrent, standardRungs, lowRungs),
        )
        val step = profileBatchStep(slot, up) ?: return false
        val wrote = stepSharedRung(now, step.index, step.switchRunning)
        if (step.thenStandard && wrote) {
            val standardName = preferences.get(StringKey.ApsAutoIsfStandardProfileName).trim()
            if (standardName.isNotBlank()) switchToStandardFor(standardName, 0, now, "AutoISF: profile batch up")
        }
        if (step.thenLow) {
            val lowName = preferences.get(StringKey.ApsAutoIsfLowProfileName).trim()
            if (lowName.isNotBlank()) switchToStandardFor(lowName, 0, now, "AutoISF: profile batch down")
        }
        if (!wrote) return false
        runMarks.mark(RunMark.PROFILE_BATCH_STEP, now)
        carePortalNote(if (up) "BtchUp" else "BtchDn")
        aapsLogger.debug(LTag.APS, "Profile batch step ${if (up) "up" else "down"}")
        return true
    }

    // Oldest first. Libre raw through the display smoother, same series as the UKF1 line.
    private suspend fun ukf1Series(now: Long): List<Pair<Long, Double>> {
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(now - 12 * 3_600_000L, now, ascending = false)
            .filter { (it.noise ?: 0.0) > 10.0 }
            .sortedByDescending { it.timestamp }
        if (readings.isEmpty()) return emptyList()
        val smoothed = displayRawSmoothing.smoothForDisplay(readings.map { it.timestamp to it.noise!! })
        if (smoothed.size != readings.size) return emptyList()
        return readings.mapIndexed { index, reading -> reading.timestamp to smoothed[index] }.asReversed()
    }

    // Temp-target exits, in the same order as 3.2.1. Each one re-reads the target, so an earlier cancel
    // is visible to the next rule. CarbsTHoff needs no target, so it is last.
    private suspend fun applyTtExits(
        now: Long,
        minuteOfDay: Int,
        bg: Double,
        delta: Double,
        shortDelta: Double,
        longDelta: Double,
        profilePercent: Int,
        cob: Double,
        iob: Double,
        statesOn: Boolean,
    ) {
        val store = states()
        fun noRecent50() {
            if (statesOn && store.hasStateValues("LowBG")) store.setState("LowBG", "NO50rec")
        }
        suspend fun cancel(note: String) {
            persistenceLayer.cancelCurrentTemporaryTargetIfAny(
                timestamp = now,
                action = Action.CANCEL_TT,
                source = Sources.Automation,
                note = note,
                listValues = emptyList(),
            )
        }
        suspend fun ttMgdl(): Double? = persistenceLayer.getTemporaryTargetActiveAt(now)?.lowTarget
        val ukfDelta5 = ukfRawNow(now).delta5 ?: -9999.0
        val full = tt57FullExit(
            ready = runMarks.ready(RunMark.TT57_REVERSAL, 5, now),
            ttMgdl = ttMgdl(),
            bg = bg,
            delta = delta,
            shortDelta = shortDelta,
            longDelta = longDelta,
            iob = iob,
            cob = cob,
            ukfDelta5 = ukfDelta5,
            steps15 = steps15(now),
            steps30 = steps30(now),
            steps60 = steps60(now),
        )
        if (full != null) {
            cancel("AutoISF: TT 5.7 $full")
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, 0.50)
            writeProfilePercent(100, 0, "AutoISF: TT 5.7 $full")
            noRecent50()
            runMarks.mark(RunMark.TT57_REVERSAL, now)
            sendAutoSms("TT 5.7 ended [$full]: g=${decimals(bg / 18.016, 1)} d=${decimals(delta / 18.016, 2)}")
            carePortalNote("TToff-$full")
        } else {
            val lowName = preferences.get(StringKey.ApsAutoIsfLowProfileName)
            val mild = tt57MildConfirmed(
                boostOn = preferences.get(BooleanKey.ApsAutoIsfBoostAutomationsEnabled),
                minuteOfDay = minuteOfDay,
                daytimeBypass = newPodHighBypass(now, bg) || runMarks.recent(RunMark.USUAL2, 90, now),
                bg = bg,
                delta = delta,
                rawDelta5 = ukfDelta5,
                rawDelta1 = ukfRawNow(now).delta1 ?: -9999.0,
                iobChange5 = iobAt(now) - iobAt(now - 5 * 60_000L),
                smbCount5 = smbCount5(now),
                onLowProfile = profileFunction.getOriginalProfileName() == lowName,
                mjActive = statesOn && store.inState("MJ", "MJ active"),
                readyBg3 = runMarks.ready(RunMark.BOLUS_GIVEN_BG3, 5, now),
                steps5 = steps5(now),
                steps30 = steps30(now),
                smbIntervalSec = smbInterval5Sec(now),
                deliveryBaseline = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline),
            )
            val light = tt57LightExit(
                ready = runMarks.ready(RunMark.TT57_REVERSAL, 5, now),
                ttMgdl = ttMgdl(),
                bg = bg,
                iob = iob,
                cob = cob,
                mildConfirmed = mild,
            )
            if (light != null) {
                cancel("AutoISF: TT 5.7 $light")
                runMarks.mark(RunMark.TT57_REVERSAL, now)
                val lightName = when (light) {
                    "N2" -> "TT5.8New2"
                    "N3" -> "TT5.8New3"
                    else -> "TT5.7MildOff"
                }
                sendAutoSms(lightName)
                carePortalNote("TToff-$light")
            }
        }
        val activity = activityTtExit(
            ready = runMarks.ready(RunMark.ACTIVITY_TT_REVERSAL, 5, now),
            ttMgdl = ttMgdl(),
            bg = bg,
            delta = delta,
            iob = iob,
            cob = cob,
        )
        if (activity != null) {
            cancel("AutoISF: activity TT $activity")
            runMarks.mark(RunMark.ACTIVITY_TT_REVERSAL, now)
            sendAutoSms(if (activity == "1") "TT6.0New" else "TT6.0New2")
            carePortalNote(if (activity == "1") "ActTToff1" else "ActTToff2")
        }
        if (t80OffShouldFire(
                ready = runMarks.ready(RunMark.T80_OFF, 5, now),
                ttMgdl = ttMgdl(),
                bg = bg,
                delta = delta,
                steps5 = steps5(now),
                steps15 = steps15(now),
                steps30 = steps30(now),
                steps60 = steps60(now),
            )
        ) {
            cancel("AutoISF: TT 8.0 off")
            runMarks.mark(RunMark.T80_OFF, now)
            sendAutoSms("TT8.0lf")
            carePortalNote("TT8.0off")
        }
        if (carbsStop1ShouldFire(
                ready = runMarks.ready(RunMark.CARBS_STOP_TT1, 5, now),
                ttMgdl = ttMgdl(),
                cob = cob,
                delta = delta,
                iob = iob,
                minuteOfDay = minuteOfDay,
            )
        ) {
            cancel("AutoISF: carbs stop TT 4.4")
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, 0.50)
            writeProfilePercent(100, 0, "AutoISF: carbs stop TT 4.4")
            runMarks.mark(RunMark.CARBS_STOP_TT1, now)
            sendAutoSms("carbsStopTT1: g=${decimals(bg / 18.016, 1)} d=${decimals(delta / 18.016, 2)} iob=${decimals(iob, 2)}")
            carePortalNote("Coff4")
        }
        val active = persistenceLayer.getTemporaryTargetActiveAt(now)
        val ownMild = active != null && active.timestamp == preferences.get(LongNonKey.ApsAutoIsfLastBmildTtAt)
        val stop57 = carbsStop57Block(
            ready = runMarks.ready(RunMark.CARBS_STOP_TT57, 5, now),
            ttMgdl = active?.lowTarget,
            cob = cob,
            iob = iob,
            bg = bg,
            delta = delta,
            minutesSinceBolus = minutesSinceLastPositiveNormalBolus(now),
            ownMildTt = ownMild,
        )
        if (stop57 != null) {
            cancel("AutoISF: carbs stop TT 5.7 $stop57")
            writeProfilePercent(100, 0, "AutoISF: carbs stop TT 5.7 $stop57")
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, 0.50)
            noRecent50()
            runMarks.mark(RunMark.CARBS_STOP_TT57, now)
            sendAutoSms("CarbsStopTT [b$stop57]: g=${decimals(bg / 18.016, 1)} tt=${decimals((active?.lowTarget ?: 0.0) / 18.016, 1)}")
            carePortalNote("Coff2-$stop57")
        }
        val th = carbsThOffBlock(
            ready = runMarks.ready(RunMark.CARBS_TH_OFF, 5, now),
            profilePercent = profilePercent,
            ttActive = persistenceLayer.getTemporaryTargetActiveAt(now) != null,
            steroidsOff = !statesOn || store.inState("Steroids", "Steroids Off"),
            bg = bg,
            delta = delta,
            shortDelta = shortDelta,
            acce = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight),
            iobTh = preferences.get(IntKey.ApsAutoIsfIobThPercent),
            minutesSinceBolus = minutesSinceLastPositiveNormalBolus(now),
        )
        if (th != null) {
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, 0.50)
            switchToStandardAtSharedTier(now, 30)
            preferences.put(IntKey.ApsAutoIsfIobThPercent, 70)
            preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal))
            restoreSmbBaselineUnlessBoosted()
            noRecent50()
            runMarks.mark(RunMark.CARBS_TH_OFF, now)
            sendAutoSms("CarbsTHoff [b$th]: g=${decimals(bg / 18.016, 1)} iobTH=${preferences.get(IntKey.ApsAutoIsfIobThPercent)}")
            carePortalNote("COff1-$th")
            aapsLogger.debug(LTag.APS, "Carbs TH off $th")
        }
    }

    // Reminders. A missing sensor age counts as 0 hours.
    private suspend fun applySensorNotes(now: Long, bg: Double, delta: Double, shortDelta: Double) {
        val liveUkf = ukfRawNow(now).glucose
        val liveHigh = liveUkf != null && liveUkf > 216.2
        if (liveHigh) {
            preferences.put(LongNonKey.ApsAutoIsfLibreOver12Ts, now)
        } else if (libreOver12ShouldScan(
                liveHigh = false,
                existingTs = preferences.get(LongNonKey.ApsAutoIsfLibreOver12Ts),
                now = now,
                ready = runMarks.ready(RunMark.LIBRE_OVER_12, 30, now),
            )
        ) {
            val hit = libreOver12Hit(now)
            if (hit != null) preferences.put(LongNonKey.ApsAutoIsfLibreOver12Ts, hit)
            runMarks.mark(RunMark.LIBRE_OVER_12, now)
        }
        val livePump = activePlugin.activePump !is VirtualPump
        val sensor = hoursSinceSensor(now) ?: 0.0
        val pod = hoursSincePod(now)
        val soak = preSoakBlock(
            ready = runMarks.ready(RunMark.PRESOAK_SENSOR, 15, now),
            livePump = livePump,
            sensorHours = sensor,
            podHours = pod,
        )
        if (soak != null) {
            runMarks.mark(RunMark.PRESOAK_SENSOR, now)
            sendAutoSms("_____SOAK")
            phoneAlert("PreSoak24hrs")
        }
        if (sensorHourHit(
                ready = runMarks.ready(RunMark.SENSOR_S1, 15, now),
                livePump = livePump,
                sensorHours = sensor,
                podHours = pod,
                low = 359.0,
                high = 359.1,
            )
        ) {
            runMarks.mark(RunMark.SENSOR_S1, now)
            phoneAlert("_____S1hr")
            sendAutoSms("SENSOR at 14.9 days ? overlap")
        }
        if (sensorHourHit(
                ready = runMarks.ready(RunMark.SENSOR_S2, 15, now),
                livePump = livePump,
                sensorHours = sensor,
                podHours = pod,
                low = 357.9,
                high = 358.0,
            )
        ) {
            runMarks.mark(RunMark.SENSOR_S2, now)
            phoneAlert("_____S2hr")
            sendAutoSms("SENSOR at 14 days 22 hours due")
        }
        val sensorDays = hoursSinceSensor(now)?.div(24.0)
        if (sensorAgeShouldTurnOff(
                codeEnabled = preferences.get(BooleanNonKey.ApsAutoIsfSensorAgeCodeEnabled),
                podHours = pod,
                sensorDays = sensorDays,
            )
        ) {
            preferences.put(BooleanNonKey.ApsAutoIsfSensorAgeCodeEnabled, false)
            preferences.put(BooleanNonKey.ApsAutoIsfSensorAgeAutoOffLatched, true)
            if (runMarks.ready(RunMark.SENSOR_AGE_AUTO_OFF, 60, now)) {
                runMarks.mark(RunMark.SENSOR_AGE_AUTO_OFF, now)
                sendAutoSms(
                    "SensorAgeCode: OFF " +
                        "(pod=${pod?.let { decimals(it, 1) } ?: "--"}h " +
                        "sensorDays=${sensorDays?.let { decimals(it, 2) } ?: "--"})"
                )
                carePortalNote("SaAutoOff")
            }
        } else if (sensorAgeShouldTurnOn(
                codeEnabled = preferences.get(BooleanNonKey.ApsAutoIsfSensorAgeCodeEnabled),
                latched = preferences.get(BooleanNonKey.ApsAutoIsfSensorAgeAutoOffLatched),
                podHours = pod,
                sensorDays = sensorDays,
            )
        ) {
            preferences.put(BooleanNonKey.ApsAutoIsfSensorAgeCodeEnabled, true)
            preferences.put(BooleanNonKey.ApsAutoIsfSensorAgeAutoOffLatched, false)
            if (runMarks.ready(RunMark.SENSOR_AGE_AUTO_ON, 60, now)) {
                runMarks.mark(RunMark.SENSOR_AGE_AUTO_ON, now)
                sendAutoSms(
                    "SensorAgeCode: ON " +
                        "(pod=${pod?.let { decimals(it, 1) } ?: "--"}h " +
                        "sensorDays=${sensorDays?.let { decimals(it, 2) } ?: "--"})"
                )
                carePortalNote("SaAutoOn")
            }
        }
        applyOldSensorSlope(now, bg, delta, shortDelta, sensorDays, pod)
    }

    // Newest smoothed raw over 12.0 mmol in the last 48 hours. Empty when the smoother drops a point.
    private suspend fun libreOver12Hit(now: Long): Long? {
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(now - 48 * 3_600_000L, now, ascending = false)
            .filter { (it.noise ?: 0.0) > 10.0 }
            .sortedByDescending { it.timestamp }
        if (readings.isEmpty()) return null
        val smoothed = displayRawSmoothing.smoothForDisplay(readings.map { it.timestamp to it.noise!! })
        if (smoothed.size != readings.size) return null
        val index = smoothed.indexOfFirst { it > 216.2 }
        return if (index < 0) null else readings[index].timestamp
    }

    // Writes the Libre slope and offset. This app does not read those values back into the glucose yet.
    private fun applyOldSensorSlope(
        now: Long,
        bg: Double,
        delta: Double,
        shortDelta: Double,
        sensorDays: Double?,
        podHours: Double?,
    ) {
        val slopeBase = preferences.get(DoubleNonKey.ApsAutoIsfLibreSlopeOrig)
        val offsetBase = preferences.get(DoubleNonKey.ApsAutoIsfLibreOffsetOrig)
        val currentSlope = preferences.get(DoubleNonKey.FslCalSlope)
        val currentOffset = preferences.get(DoubleNonKey.FslCalOffset)
        val active = preferences.get(BooleanNonKey.ApsAutoIsfOldSensorAdjActive)
        if (!preferences.get(BooleanNonKey.ApsAutoIsfSensorAgeCodeEnabled)) {
            if (active || slopesDiffer(currentSlope, slopeBase) || slopesDiffer(currentOffset, offsetBase)) {
                if (slopeRestoreDeferred(bg, delta, shortDelta)) {
                    aapsLogger.debug(LTag.APS, "Sensor age slope restore deferred")
                } else {
                    preferences.put(DoubleNonKey.FslCalSlope, slopeBase)
                    preferences.put(DoubleNonKey.FslCalOffset, offsetBase)
                    preferences.put(BooleanNonKey.ApsAutoIsfOldSensorAdjActive, false)
                    aapsLogger.debug(LTag.APS, "Sensor age slope restored")
                }
            }
            return
        }
        val recentHigh = preferences.get(LongNonKey.ApsAutoIsfLibreOver12Ts).let { ts ->
            ts != 0L && now - ts <= 24 * 3_600_000L
        }
        val tier = oldSensorTier(sensorDays, podHours, slopeBase, offsetBase)
        if (!preferences.get(BooleanNonKey.ApsAutoIsfOldSensorAdjEnabled)) return
        if (tier != null && recentHigh) {
            if (!active) {
                preferences.put(DoubleNonKey.ApsAutoIsfFslCalSlopeNormal, currentSlope)
                preferences.put(DoubleNonKey.ApsAutoIsfFslCalOffsetNormal, currentOffset)
                preferences.put(BooleanNonKey.ApsAutoIsfOldSensorAdjActive, true)
            }
            if (slopesDiffer(currentSlope, tier.slope) || slopesDiffer(currentOffset, tier.offset)) {
                preferences.put(DoubleNonKey.FslCalSlope, tier.slope)
                preferences.put(DoubleNonKey.FslCalOffset, tier.offset)
                aapsLogger.debug(LTag.APS, "Old sensor tier ${tier.name}")
            }
        } else {
            if (slopesDiffer(currentSlope, slopeBase) || slopesDiffer(currentOffset, offsetBase)) {
                preferences.put(DoubleNonKey.FslCalSlope, slopeBase)
                preferences.put(DoubleNonKey.FslCalOffset, offsetBase)
            }
            preferences.put(BooleanNonKey.ApsAutoIsfOldSensorAdjActive, false)
        }
    }

    // A temp target near 5.00 mmol is a remote switch, not a real target. Cancel it before the
    // bolus boosts so it does not block them this cycle. Returns true when a target was cancelled.
    private suspend fun applyRemoteToggles(now: Long): Boolean {
        val tt = persistenceLayer.getTemporaryTargetActiveAt(now)?.lowTarget ?: return false
        val code = remoteToggleCode(tt) ?: return false
        val mark = when (code) {
            RemoteToggleCode.SENSOR_AGE -> RunMark.SENSOR_AGE_TOGGLE
            RemoteToggleCode.BOOST -> RunMark.BOOST_TOGGLE
            RemoteToggleCode.SMB_DOWN -> RunMark.SMB_DELIVERY_DOWN
            RemoteToggleCode.SMB_UP -> RunMark.SMB_DELIVERY_UP
            RemoteToggleCode.PP_DOWN -> RunMark.PP_WEIGHT_DOWN
            RemoteToggleCode.PP_UP -> RunMark.PP_WEIGHT_UP
            RemoteToggleCode.PP_HIGH_DOWN -> RunMark.PP_WEIGHT_HIGH_DOWN
            RemoteToggleCode.PP_HIGH_UP -> RunMark.PP_WEIGHT_HIGH_UP
            RemoteToggleCode.ACCE_DOWN -> RunMark.ACCE_WEIGHT_DOWN
            RemoteToggleCode.ACCE_UP -> RunMark.ACCE_WEIGHT_UP
            RemoteToggleCode.ACCE_HIGH_DOWN -> RunMark.ACCE_WEIGHT_HIGH_DOWN
            RemoteToggleCode.ACCE_HIGH_UP -> RunMark.ACCE_WEIGHT_HIGH_UP
            RemoteToggleCode.HIGH_ISF_DOWN -> RunMark.HIGH_ISF_DOWN
            RemoteToggleCode.HIGH_ISF_UP -> RunMark.HIGH_ISF_UP
            RemoteToggleCode.MAX_LOW_DOWN -> RunMark.MAX_LOW_DOWN
            RemoteToggleCode.MAX_LOW_UP -> RunMark.MAX_LOW_UP
            RemoteToggleCode.MAX_DOWN -> RunMark.MAX_DOWN
            RemoteToggleCode.MAX_UP -> RunMark.MAX_UP
            RemoteToggleCode.TOD_0002_DOWN -> RunMark.TOD_0002_DOWN
            RemoteToggleCode.TOD_0002_UP -> RunMark.TOD_0002_UP
            RemoteToggleCode.TOD_0204_DOWN -> RunMark.TOD_0204_DOWN
            RemoteToggleCode.TOD_0204_UP -> RunMark.TOD_0204_UP
            RemoteToggleCode.TOD_0406_DOWN -> RunMark.TOD_0406_DOWN
            RemoteToggleCode.TOD_0406_UP -> RunMark.TOD_0406_UP
            RemoteToggleCode.TOD_0609_DOWN -> RunMark.TOD_0609_DOWN
            RemoteToggleCode.TOD_0609_UP -> RunMark.TOD_0609_UP
            RemoteToggleCode.TOD_0912_DOWN -> RunMark.TOD_0912_DOWN
            RemoteToggleCode.TOD_0912_UP -> RunMark.TOD_0912_UP
            RemoteToggleCode.TOD_1218_DOWN -> RunMark.TOD_1218_DOWN
            RemoteToggleCode.TOD_1218_UP -> RunMark.TOD_1218_UP
            RemoteToggleCode.TOD_1822_DOWN -> RunMark.TOD_1822_DOWN
            RemoteToggleCode.TOD_1822_UP -> RunMark.TOD_1822_UP
            RemoteToggleCode.TOD_2200_DOWN -> RunMark.TOD_2200_DOWN
            RemoteToggleCode.TOD_2200_UP -> RunMark.TOD_2200_UP
            RemoteToggleCode.GRAPH2 -> RunMark.GRAPH2
            RemoteToggleCode.CLOUD_LOGS -> RunMark.CLOUD_LOGS
            RemoteToggleCode.MJ_NO -> RunMark.MJ_NO
            RemoteToggleCode.MJ3 -> RunMark.MJ3
            RemoteToggleCode.MJ_ACTIVE -> RunMark.MJ_ACTIVE
            RemoteToggleCode.MJ2 -> RunMark.MJ2
        }
        if (!runMarks.ready(mark, 2, now)) return false
        applyToggleAction(code)
        persistenceLayer.cancelCurrentTemporaryTargetIfAny(
            timestamp = now,
            action = Action.CANCEL_TT,
            source = Sources.Automation,
            note = "AutoISF: $mark",
            listValues = emptyList(),
        )
        runMarks.mark(mark, now)
        return true
    }

    // A double tap on the IOB chip. Same change as the matching temp target, with no target and no wait.
    private suspend fun applyDirectListCode(mmol: Double) {
        if (config.AAPSCLIENT) return
        val code = remoteToggleCode(mmol * Constants.MMOLL_TO_MGDL) ?: return
        applyToggleAction(code)
    }

    private suspend fun applyToggleAction(code: RemoteToggleCode) {
        when (code) {
            RemoteToggleCode.SENSOR_AGE -> {
                val newState = !preferences.get(BooleanNonKey.ApsAutoIsfOldSensorAdjEnabled)
                preferences.put(BooleanNonKey.ApsAutoIsfOldSensorAdjEnabled, newState)
                sendAutoSms("SensorAgeToggle: ${if (newState) "ON" else "OFF"}")
                carePortalNote("STg${if (newState) "On" else "Off"}")
            }
            RemoteToggleCode.BOOST -> {
                val newState = !preferences.get(BooleanKey.ApsAutoIsfBoostAutomationsEnabled)
                preferences.put(BooleanKey.ApsAutoIsfBoostAutomationsEnabled, newState)
                sendAutoSms("BoostToggle: ${if (newState) "ON" else "OFF"}")
                carePortalNote("BTg${if (newState) "On" else "Off"}")
            }
            RemoteToggleCode.SMB_DOWN -> {
                val baseline = nudgeDown(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline), 0.1)
                val mild = nudgeDown(preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio), 0.1)
                preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryBaseline, baseline)
                preferences.put(DoubleKey.ApsAutoIsfMildBoostRatio, mild)
                sendAutoSms("SmbDeliveryDown: baseline=${decimals(baseline, 2)} mildBoost=${decimals(mild, 2)}")
                carePortalNote(compactSettingNote("SB", baseline, 2, omitLeadingZero = true))
                carePortalNote(compactSettingNote("SM", mild, 2, omitLeadingZero = true))
            }
            RemoteToggleCode.SMB_UP -> {
                val baseline = nudgeUp(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline), 0.5)
                val mild = nudgeUp(preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio), 1.0)
                preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryBaseline, baseline)
                preferences.put(DoubleKey.ApsAutoIsfMildBoostRatio, mild)
                sendAutoSms("SmbDeliveryUp: baseline=${decimals(baseline, 2)} mildBoost=${decimals(mild, 2)}")
                carePortalNote(compactSettingNote("SB", baseline, 2, omitLeadingZero = true))
                carePortalNote(compactSettingNote("SM", mild, 2, omitLeadingZero = true))
            }
            RemoteToggleCode.PP_DOWN -> {
                val current = preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal)
                val live = preferences.get(DoubleKey.ApsAutoIsfPpWeight)
                val next = nudgeDown(current, 0.0)
                preferences.put(DoubleKey.ApsAutoIsfPpWeightNormal, next)
                if (liveMatchesBaseline(live, current)) preferences.put(DoubleKey.ApsAutoIsfPpWeight, nudgeDown(live, 0.0))
                sendAutoSms("PpWeightDown: ppISFwt_orig=${decimals(next, 2)}")
                carePortalNote(compactSettingNote("PP", next, 2, omitLeadingZero = true))
            }
            RemoteToggleCode.PP_UP -> {
                val current = preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal)
                val live = preferences.get(DoubleKey.ApsAutoIsfPpWeight)
                val next = nudgeUp(current, 0.15)
                preferences.put(DoubleKey.ApsAutoIsfPpWeightNormal, next)
                if (liveMatchesBaseline(live, current)) preferences.put(DoubleKey.ApsAutoIsfPpWeight, nudgeUp(live, 0.15))
                sendAutoSms("PpWeightUp: ppISFwt_orig=${decimals(next, 2)}")
                carePortalNote(compactSettingNote("PP", next, 2, omitLeadingZero = true))
            }
            RemoteToggleCode.PP_HIGH_DOWN -> {
                val next = nudgeDown(preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh), 0.0)
                preferences.put(DoubleKey.ApsAutoIsfPpWeightHigh, next)
                sendAutoSms("PpWeightHighDown: ppISFwt_high=${decimals(next, 2)}")
                carePortalNote(compactSettingNote("PH", next, 2, omitLeadingZero = true))
            }
            RemoteToggleCode.PP_HIGH_UP -> {
                val next = nudgeUp(preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh), 0.15)
                preferences.put(DoubleKey.ApsAutoIsfPpWeightHigh, next)
                sendAutoSms("PpWeightHighUp: ppISFwt_high=${decimals(next, 2)}")
                carePortalNote(compactSettingNote("PH", next, 2, omitLeadingZero = true))
            }
            RemoteToggleCode.ACCE_DOWN -> {
                val current = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal)
                val live = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
                val next = nudgeDown(current, 0.55, 0.05)
                preferences.put(DoubleKey.ApsAutoIsfBgAccelWeightNormal, next)
                if (liveMatchesBaseline(live, current)) preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, nudgeDown(live, 0.55, 0.05))
                sendAutoSms("AcceWeightDown: acceISFwt_orig=${decimals(next, 2)}")
                carePortalNote(compactSettingNote("AC", next, 2, omitLeadingZero = true))
            }
            RemoteToggleCode.ACCE_UP -> {
                val current = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal)
                val live = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
                val next = nudgeUp(current, 1.0, 0.05)
                preferences.put(DoubleKey.ApsAutoIsfBgAccelWeightNormal, next)
                if (liveMatchesBaseline(live, current)) preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, nudgeUp(live, 1.0, 0.05))
                sendAutoSms("AcceWeightUp: acceISFwt_orig=${decimals(next, 2)}")
                carePortalNote(compactSettingNote("AC", next, 2, omitLeadingZero = true))
            }
            RemoteToggleCode.ACCE_HIGH_DOWN -> {
                val next = nudgeDown(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightHigh), 0.0)
                preferences.put(DoubleKey.ApsAutoIsfBgAccelWeightHigh, next)
                sendAutoSms("AcceWeightHighDown: acceISFwt_high=${decimals(next, 2)}")
                carePortalNote(compactSettingNote("AH", next, 2, omitLeadingZero = true))
            }
            RemoteToggleCode.ACCE_HIGH_UP -> {
                val next = nudgeUp(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightHigh), 1.0)
                preferences.put(DoubleKey.ApsAutoIsfBgAccelWeightHigh, next)
                sendAutoSms("AcceWeightHighUp: acceISFwt_high=${decimals(next, 2)}")
                carePortalNote(compactSettingNote("AH", next, 2, omitLeadingZero = true))
            }
            RemoteToggleCode.HIGH_ISF_DOWN -> {
                val next = nudgeDown(preferences.get(DoubleKey.ApsAutoIsfHighBgWeight), 0.0, 0.1)
                preferences.put(DoubleKey.ApsAutoIsfHighBgWeight, next)
                sendAutoSms("HigherIsfRangeWeightDown: higher_ISFrange_weight=${decimals(next, 2)}")
                carePortalNote(compactSettingNote("HI", next, 1))
            }
            RemoteToggleCode.HIGH_ISF_UP -> {
                val next = nudgeUp(preferences.get(DoubleKey.ApsAutoIsfHighBgWeight), 2.0, 0.1)
                preferences.put(DoubleKey.ApsAutoIsfHighBgWeight, next)
                sendAutoSms("HigherIsfRangeWeightUp: higher_ISFrange_weight=${decimals(next, 2)}")
                carePortalNote(compactSettingNote("HI", next, 1))
            }
            RemoteToggleCode.MAX_LOW_DOWN -> {
                val next = nudgeDown(preferences.get(DoubleKey.ApsAutoIsfMaxLow), 1.0, 0.1)
                preferences.put(DoubleKey.ApsAutoIsfMaxLow, next)
                sendAutoSms("AutoIsfMaxLowDown: autoISF_max_low=${decimals(next, 2)}")
                carePortalNote(compactSettingNote("ML", next, 1))
            }
            RemoteToggleCode.MAX_LOW_UP -> {
                val next = nudgeUp(preferences.get(DoubleKey.ApsAutoIsfMaxLow), 3.0, 0.1)
                preferences.put(DoubleKey.ApsAutoIsfMaxLow, next)
                sendAutoSms("AutoIsfMaxLowUp: autoISF_max_low=${decimals(next, 2)}")
                carePortalNote(compactSettingNote("ML", next, 1))
            }
            RemoteToggleCode.MAX_DOWN -> {
                val next = nudgeDown(preferences.get(DoubleKey.ApsAutoIsfMax), 1.0, 0.1)
                preferences.put(DoubleKey.ApsAutoIsfMax, next)
                sendAutoSms("AutoIsfMaxNormalDown: autoISF_max=${decimals(next, 2)}")
                carePortalNote(compactSettingNote("MN", next, 1))
            }
            RemoteToggleCode.MAX_UP -> {
                val next = nudgeUp(preferences.get(DoubleKey.ApsAutoIsfMax), 3.0, 0.1)
                preferences.put(DoubleKey.ApsAutoIsfMax, next)
                sendAutoSms("AutoIsfMaxNormalUp: autoISF_max=${decimals(next, 2)}")
                carePortalNote(compactSettingNote("MN", next, 1))
            }
            RemoteToggleCode.TOD_0002_DOWN -> nudgeTodOffset(DoubleKey.ApsAutoIsfTodOffset0002, down = true, "TodOffset0002Down", "tod_offset_0002")
            RemoteToggleCode.TOD_0002_UP -> nudgeTodOffset(DoubleKey.ApsAutoIsfTodOffset0002, down = false, "TodOffset0002Up", "tod_offset_0002")
            RemoteToggleCode.TOD_0204_DOWN -> nudgeTodOffset(DoubleKey.ApsAutoIsfTodOffset0204, down = true, "TodOffset0204Down", "tod_offset_0204")
            RemoteToggleCode.TOD_0204_UP -> nudgeTodOffset(DoubleKey.ApsAutoIsfTodOffset0204, down = false, "TodOffset0204Up", "tod_offset_0204")
            RemoteToggleCode.TOD_0406_DOWN -> nudgeTodOffset(DoubleKey.ApsAutoIsfTodOffset0406, down = true, "TodOffset0406Down", "tod_offset_0406")
            RemoteToggleCode.TOD_0406_UP -> nudgeTodOffset(DoubleKey.ApsAutoIsfTodOffset0406, down = false, "TodOffset0406Up", "tod_offset_0406")
            RemoteToggleCode.TOD_0609_DOWN -> nudgeTodOffset(DoubleKey.ApsAutoIsfTodOffset0609, down = true, "TodOffset0609Down", "tod_offset_0609")
            RemoteToggleCode.TOD_0609_UP -> nudgeTodOffset(DoubleKey.ApsAutoIsfTodOffset0609, down = false, "TodOffset0609Up", "tod_offset_0609")
            RemoteToggleCode.TOD_0912_DOWN -> nudgeTodOffset(DoubleKey.ApsAutoIsfTodOffset0912, down = true, "TodOffset0912Down", "tod_offset_0912")
            RemoteToggleCode.TOD_0912_UP -> nudgeTodOffset(DoubleKey.ApsAutoIsfTodOffset0912, down = false, "TodOffset0912Up", "tod_offset_0912")
            RemoteToggleCode.TOD_1218_DOWN -> nudgeTodOffset(DoubleKey.ApsAutoIsfTodOffset1218, down = true, "TodOffset1218Down", "tod_offset_1218")
            RemoteToggleCode.TOD_1218_UP -> nudgeTodOffset(DoubleKey.ApsAutoIsfTodOffset1218, down = false, "TodOffset1218Up", "tod_offset_1218")
            RemoteToggleCode.TOD_1822_DOWN -> nudgeTodOffset(DoubleKey.ApsAutoIsfTodOffset1822, down = true, "TodOffset1822Down", "tod_offset_1822")
            RemoteToggleCode.TOD_1822_UP -> nudgeTodOffset(DoubleKey.ApsAutoIsfTodOffset1822, down = false, "TodOffset1822Up", "tod_offset_1822")
            RemoteToggleCode.TOD_2200_DOWN -> nudgeTodOffset(DoubleKey.ApsAutoIsfTodOffset2200, down = true, "TodOffset2200Down", "tod_offset_2200")
            RemoteToggleCode.TOD_2200_UP -> nudgeTodOffset(DoubleKey.ApsAutoIsfTodOffset2200, down = false, "TodOffset2200Up", "tod_offset_2200")
            RemoteToggleCode.GRAPH2 -> {
                val next = !preferences.get(BooleanKey.ApsAutoIsfShowCarbModelCurve)
                preferences.put(BooleanKey.ApsAutoIsfShowCarbModelCurve, next)
                sendAutoSms("Graph2Toggle: ${if (next) "ON" else "OFF"}")
                carePortalNote(if (next) "G2On" else "G2Off")
            }
            RemoteToggleCode.CLOUD_LOGS -> {
                maintenance.exportCoordinated("REMOTE_TT")
                sendAutoSms("CloudLogsUpload: triggered")
                carePortalNote("CLup")
            }
            RemoteToggleCode.MJ_NO -> setMjState("NOMJremains", "MJstate: NOMJremains", "MJsNO")
            RemoteToggleCode.MJ3 -> setMjState("MJ3", "MJstate: MJ3", "MJs3")
            RemoteToggleCode.MJ_ACTIVE -> setMjState("MJ active", "MJstate: MJ active", "MJsAc")
            RemoteToggleCode.MJ2 -> setMjState("MJ2", "MJstate: MJ2", "MJs2")
        }
    }

    // Manual MJ state. Written only when that value is one of the stored choices, so a short list cannot crash the loop.
    private suspend fun setMjState(value: String, sms: String, note: String) {
        val store = states()
        if (preferences.get(BooleanKey.AutomationStatesEnabled) && store.hasStateValues("MJ") && value in store.getStateValues("MJ")) {
            store.setState("MJ", value)
        }
        sendAutoSms(sms)
        carePortalNote(note)
    }

    private fun roleKeyForBlankFill(key: String): StringKey? = when (key) {
        StringKey.ApsAutoIsfStandardProfileName.key -> StringKey.ApsAutoIsfStandardProfileName
        StringKey.ApsAutoIsfLowProfileName.key -> StringKey.ApsAutoIsfLowProfileName
        StringKey.ApsAutoIsfStandard100ProfileName.key -> StringKey.ApsAutoIsfStandard100ProfileName
        StringKey.ApsAutoIsfStandard105ProfileName.key -> StringKey.ApsAutoIsfStandard105ProfileName
        StringKey.ApsAutoIsfStandard110ProfileName.key -> StringKey.ApsAutoIsfStandard110ProfileName
        StringKey.ApsAutoIsfLow70ProfileName.key -> StringKey.ApsAutoIsfLow70ProfileName
        StringKey.ApsAutoIsfLow80ProfileName.key -> StringKey.ApsAutoIsfLow80ProfileName
        StringKey.ApsAutoIsfLow90ProfileName.key -> StringKey.ApsAutoIsfLow90ProfileName
        else -> null
    }

    private suspend fun nudgeTodOffset(key: DoubleKey, down: Boolean, smsName: String, smsField: String) {
        val current = preferences.get(key)
        val next = if (down) nudgeDown(current, -2.0, 0.1) else nudgeUp(current, 2.0, 0.1)
        preferences.put(key, next)
        sendAutoSms("$smsName: $smsField=${decimals(next, 2)}")
        carePortalNote(todOffsetNote(next))
    }

    private var lastCareNoteAt = 0L

    private fun sendAutoSms(text: String) {
        smsCommunicator.sendNotificationToAllNumbers(text)
        aapsLogger.debug(LTag.APS, text)
    }

    private fun sendAutoSmsToNumbers(text: String, key: StringKey) {
        preferences.get(key).split(";")
            .map { it.replace("\\s+".toRegex(), "") }
            .filter { it.isNotEmpty() }
            .forEach { number -> smsCommunicator.sendSMS(Sms(number, text)) }
    }

    private suspend fun carePortalNote(text: String) {
        var ts = dateUtil.now()
        if (ts <= lastCareNoteAt) ts = lastCareNoteAt + 1
        lastCareNoteAt = ts
        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = TE(
                timestamp = ts,
                type = TE.Type.NOTE,
                note = text,
                duration = 60_000L,
                glucoseUnit = profileFunction.getUnits(),
            ),
            timestamp = ts,
            action = Action.CAREPORTAL,
            source = Sources.Automation,
            note = text,
            listValues = listOf(ValueWithUnit.SimpleString(text)),
        )
    }

    private fun phoneAlert(text: String) {
        notificationManager.post(id = NotificationId.AUTOISF_ALERT, text = text)
        aapsLogger.debug(LTag.APS, text)
    }

    private fun phoneNote(text: String) {
        notificationManager.post(id = NotificationId.AUTOISF_NOTE, text = text)
        aapsLogger.debug(LTag.APS, text)
    }

    private fun decimals(value: Double, places: Int): String {
        var scale = 1.0
        repeat(places) { scale *= 10.0 }
        val scaled = round(value * scale).toLong()
        val sign = if (scaled < 0) "-" else ""
        val absScaled = abs(scaled)
        val factor = scale.toLong()
        val whole = absScaled / factor
        val frac = (absScaled % factor).toString().padStart(places, '0')
        return "$sign$whole.$frac"
    }

    // Before the bolus boosts, so a temp target set later in this loop keeps its own ratio.
    private suspend fun applyOldPodBoost(now: Long, bg: Double, delta: Double, maxIob: Double, tempTargetSet: Boolean) {
        val ukfGlucose = ukfRawNow(now).glucose
        val highNow = (ukfGlucose != null && ukfGlucose > 198.2) || bg > 180.2
        val since = oldPodHighSince(now, highNow, preferences.get(LongNonKey.ApsAutoIsfOldPodHighSinceTs))
        preferences.put(LongNonKey.ApsAutoIsfOldPodHighSinceTs, since)
        val podHours = hoursSincePod(now)
        val active = preferences.get(BooleanNonKey.ApsAutoIsfOldPodInsReqBoostActive)
        if (oldPodBoostShouldStart(active, podHours, since, now, lastCycleInsulinReq, maxIob)) {
            preferences.put(BooleanNonKey.ApsAutoIsfOldPodInsReqBoostActive, true)
            sendAutoSms(
                "OldPodInsReqBoost: cannula ${decimals(podHours ?: 0.0, 1)}h " +
                    "insulinReq ${decimals(lastCycleInsulinReq ?: 0.0, 2)} -> SMB x1.3, TierC"
            )
            carePortalNote("OldPodBst")
        } else if (oldPodBoostShouldStop(active, bg, delta, podHours)) {
            preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryRatio, preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline))
            preferences.put(BooleanNonKey.ApsAutoIsfOldPodInsReqBoostActive, false)
            sendAutoSms("OldPodInsReqBoost off: BGL ${decimals(bg / 18.016, 1)}")
            carePortalNote("OldPodBstOff")
        }
        if (preferences.get(BooleanNonKey.ApsAutoIsfOldPodInsReqBoostActive) && !tempTargetSet) {
            val baseline = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline)
            preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryRatio, baseline * 1.3)
            val standard110 = preferences.get(StringKey.ApsAutoIsfStandard110ProfileName).trim()
            if (standard110.isNotBlank()) switchToStandardFor(standard110, 0, now, "AutoISF: old pod boost")
        }
    }

    // After the evening rules, so this IOB and profile write is the last one in the loop.
    private suspend fun applyStepsSteroidsOff(
        now: Long,
        bg: Double,
        delta: Double,
        cob: Double,
        iob: Double,
        steps60: Int,
        statesOn: Boolean,
    ) {
        if (!stepsSteroidsOffShouldFire(
                ready = runMarks.ready(RunMark.STEPS_STEROIDS_OFF, 5, now),
                steps60 = steps60,
                iob = iob,
                bg = bg,
                delta = delta,
                cob = cob,
            )
        ) return
        val store = states()
        if (statesOn && store.hasStateValues("Steroids")) store.setState("Steroids", "Steroids Off")
        switchToLowAtSharedTier(now, "AutoISF: steps steroids off")
        preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal))
        preferences.put(IntKey.ApsAutoIsfIobThPercent, 50)
        runMarks.mark(RunMark.STEPS_STEROIDS_OFF, now)
        sendAutoSms("Steps Steroids OFF")
        aapsLogger.debug(LTag.APS, "Steps steroids off")
    }

    private suspend fun hoursSinceSensor(now: Long): Double? {
        val last = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE) ?: return null
        return (now - last.timestamp) / 3_600_000.0
    }

    private suspend fun hoursSincePod(now: Long): Double? {
        val last = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE) ?: return null
        return (now - last.timestamp) / 3_600_000.0
    }

    // AcceUp and the exercise alert run first. The night ceiling runs before NightAcce, so a 22% write
    // keeps NightAcce closed in the same loop. Twilight runs before NightAcce. SemiTwilight is last.
    private suspend fun applyEvening(
        now: Long,
        minuteOfDay: Int,
        bg: Double,
        delta: Double,
        shortDelta: Double,
        longDelta: Double,
        profilePercent: Int,
        cob: Double,
        iob: Double,
        statesOn: Boolean,
        steps60: Int,
        steps180: Int,
    ) {
        val store = states()
        if (acceUpShouldFire(
                ready = runMarks.ready(RunMark.ACCE_UP, 5, now),
                minuteOfDay = minuteOfDay,
                acce = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight),
                bg = bg,
                profilePercent = profilePercent,
                ttActive = persistenceLayer.getTemporaryTargetActiveAt(now) != null,
                noMjRemains = statesOn && store.inState("MJ", "NOMJremains"),
                steroidsOn = statesOn && store.inState("Steroids", "SteroidsON"),
            )
        ) {
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightHigh))
            runMarks.mark(RunMark.ACCE_UP, now)
            sendAutoSms("AcceUp")
            carePortalNote("Acce")
            aapsLogger.debug(LTag.APS, "AcceUp to high weight")
        }
        if (exerciseLimitShouldFire(
                ready = runMarks.ready(RunMark.EXERCISE_LIMIT, 30, now),
                bg = bg,
                delta = delta,
                steps60 = steps60,
            )
        ) {
            runMarks.mark(RunMark.EXERCISE_LIMIT, now)
            sendAutoSms("Exercise limit Acce")
            phoneAlert("_____ST601k")
        }
        val carbsHeld = cobHasStayedUp(now, cob)
        val cap = eveningIobCap(
            ready = runMarks.ready(RunMark.EVENING_IOB_CEILING, 5, now),
            minuteOfDay = minuteOfDay,
            noMjRemains = statesOn && store.inState("MJ", "NOMJremains"),
            iobTh = preferences.get(IntKey.ApsAutoIsfIobThPercent),
            cobSustained = carbsHeld,
        )
        if (cap != null) {
            val prevEve = preferences.get(IntKey.ApsAutoIsfIobThPercent)
            preferences.put(IntKey.ApsAutoIsfIobThPercent, cap)
            runMarks.mark(RunMark.EVENING_IOB_CEILING, now)
            sendAutoSms("EveningIobCeiling: iobTH $prevEve -> $cap${if (carbsHeld) " (COB-relaxed)" else ""}")
            carePortalNote(if (carbsHeld) "EvCapR" else "EvCap")
            aapsLogger.debug(LTag.APS, "Evening IOB ceiling -> $cap")
        }
        val nightCap = nightIobCeiling(
            ready = runMarks.ready(RunMark.NIGHT_IOB_CEILING, 5, now),
            minuteOfDay = minuteOfDay,
            steps60 = steps60,
            iobTh = preferences.get(IntKey.ApsAutoIsfIobThPercent),
            acce = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight),
            cobSustained = carbsHeld,
        )
        if (nightCap != null) {
            if (nightCap.iob != null) preferences.put(IntKey.ApsAutoIsfIobThPercent, nightCap.iob)
            if (nightCap.acce != null) preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, nightCap.acce)
            runMarks.mark(RunMark.NIGHT_IOB_CEILING, now)
            sendAutoSms("NightIobCeiling: iobTH -> ${nightCap.iob ?: "unchanged"} acce -> ${nightCap.acce ?: "unchanged"}${if (carbsHeld) " (COB-relaxed)" else ""}")
            carePortalNote(if (carbsHeld) "NtCapR" else "NtCap")
            aapsLogger.debug(LTag.APS, "Night IOB ceiling iob=${nightCap.iob} acce=${nightCap.acce}")
        }
        if (stuckRisingShouldRequest(
                ready = runMarks.ready(RunMark.STUCK_RISING, 5, now),
                minuteOfDay = minuteOfDay,
                ttActive = persistenceLayer.getTemporaryTargetActiveAt(now) != null,
                steroidsOff = statesOn && store.inState("Steroids", "Steroids Off"),
                // This repo does not store a delayed-bolus delivery time, so the request stays closed.
                recentDelayedBolus = false,
                bg = bg,
                delta = delta,
                shortDelta = shortDelta,
                longDelta = longDelta,
                cob = cob,
                iob = iob,
                steps60 = steps60,
                steps180 = steps180,
                bolusAgeMinutes = minutesSinceLastPositiveNormalBolus(now),
                carbAgeMinutes = minutesSinceLastCarbs(now),
            )
        ) {
            startBrakeTarget(now, 4.2 * 18.0, "AutoISF: stuck rising 4.2", 5)
            runMarks.mark(RunMark.STUCK_RISING, now)
            sendAutoSms("StuckRisingSlowly Acce")
            aapsLogger.debug(LTag.APS, "Stuck rising 4.2 mmol for 5 min (delayed-bolus stamp is not written yet)")
        }
        if (earlyDawnShouldFire(
                ready = runMarks.ready(RunMark.EARLY_DAWN, 5, now),
                minuteOfDay = minuteOfDay,
                ttActive = persistenceLayer.getTemporaryTargetActiveAt(now) != null,
                bg = bg,
                delta = delta,
                shortDelta = shortDelta,
                longDelta = longDelta,
            )
        ) {
            startBrakeTarget(now, 4.4 * 18.0, "AutoISF: early dawn 4.4", 5)
            runMarks.mark(RunMark.EARLY_DAWN, now)
            sendAutoSms("EarlyDawnSlowRise: TT 4.4mmol@5min, g=${decimals(bg / 18.016, 1)}")
            aapsLogger.debug(LTag.APS, "Early dawn 4.4 mmol for 5 min")
        }
        val running = profileFunction.getOriginalProfileName()
        val onRole = running == preferences.get(StringKey.ApsAutoIsfLowProfileName).trim() ||
            running == preferences.get(StringKey.ApsAutoIsfStandardProfileName).trim()
        val evening = eveningThBlock(
            ready = runMarks.ready(RunMark.EVENING_TH, 5, now),
            minuteOfDay = minuteOfDay,
            bg = bg,
            delta = delta,
            iobTh = preferences.get(IntKey.ApsAutoIsfIobThPercent),
            cob = cob,
            ttActive = persistenceLayer.getTemporaryTargetActiveAt(now) != null,
            minutesSinceBolus = minutesSinceLastPositiveNormalBolus(now),
            mjActive = statesOn && store.inState("MJ", "MJ active"),
            onRoleProfile = onRole,
            profilePercent = profilePercent,
        )
        val ukf = ukfRawNow(now)
        val hp = ukf.delta5?.let { hypoPrediction2Mmol(bg, shortDelta, it, iob, cob) }
        if (evening != null) {
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, 0.45)
            preferences.put(IntKey.ApsAutoIsfIobThPercent, 45)
            if (eveningThShouldSwitchLow(minuteOfDay, hp)) {
                switchToLowAtSharedTier(now, "AutoISF: evening low")
            }
            runMarks.mark(RunMark.EVENING_TH, now)
            sendAutoSms("EveningTH CurrProf 50_0.45 Acce HP2=${if (hp == null) "--" else decimals(hp, 1)}")
            aapsLogger.debug(LTag.APS, "EveningTH block $evening")
        }
        if (twilightTh15ShouldFire(
                ready = runMarks.ready(RunMark.TWILIGHT_TH15, 5, now),
                minuteOfDay = minuteOfDay,
                steps60 = steps60,
                bg = bg,
                ttActive = persistenceLayer.getTemporaryTargetActiveAt(now) != null,
                iobTh = preferences.get(IntKey.ApsAutoIsfIobThPercent),
                steroidsOff = statesOn && store.inState("Steroids", "Steroids Off"),
                delta = delta,
            )
        ) {
            preferences.put(IntKey.ApsAutoIsfIobThPercent, 15)
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, 0.50)
            switchToLowAtSharedTier(now, "AutoISF: twilight 15")
            runMarks.mark(RunMark.TWILIGHT_TH15, now)
            sendAutoSms("TwilightTH15Acce0.50")
            aapsLogger.debug(LTag.APS, "TwilightTH15 acce 0.50 iobTH 15")
        }
        if (nightAcceShouldFire(
                ready = runMarks.ready(RunMark.NIGHT_ACCE, 5, now),
                minuteOfDay = minuteOfDay,
                iobTh = preferences.get(IntKey.ApsAutoIsfIobThPercent),
                cob = cob,
                ttActive = persistenceLayer.getTemporaryTargetActiveAt(now) != null,
                bg = bg,
                steroidsOff = statesOn && store.inState("Steroids", "Steroids Off"),
                acce = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight),
            )
        ) {
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, 0.35)
            val rescue = preferences.get(LongNonKey.ApsAutoIsfOvernightRescueUntil) > now
            if (eveningThShouldSwitchLow(minuteOfDay, hp) && !rescue) {
                switchToLowAtSharedTier(now, "AutoISF: night acce low")
            }
            preferences.put(IntKey.ApsAutoIsfIobThPercent, 22)
            restoreSmbBaselineUnlessBoosted()
            preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal))
            runMarks.mark(RunMark.NIGHT_ACCE, now)
            sendAutoSms("NightAcce_0.35TH22 HP2=${if (hp == null) "--" else decimals(hp, 1)}")
            aapsLogger.debug(LTag.APS, "NightAcce acce 0.35 iobTH 22 (settings export not written)")
        }
        val semi = semiTwilightBlock(
            ready = runMarks.ready(RunMark.SEMI_TWILIGHT, 5, now),
            minuteOfDay = minuteOfDay,
            steps180 = steps180,
            bg = bg,
            delta = delta,
            shortDelta = shortDelta,
            longDelta = longDelta,
            ttActive = persistenceLayer.getTemporaryTargetActiveAt(now) != null,
            iobTh = preferences.get(IntKey.ApsAutoIsfIobThPercent),
            acce = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight),
            steroidsOff = statesOn && store.inState("Steroids", "Steroids Off"),
        )
        if (semi == null) return
        preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, 0.50)
        preferences.put(IntKey.ApsAutoIsfIobThPercent, 16)
        restoreSmbBaselineUnlessBoosted()
        preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal))
        runMarks.mark(RunMark.SEMI_TWILIGHT, now)
        sendAutoSms("SemiTwilightAcce_0.50TH16")
        carePortalNote("Semi")
        aapsLogger.debug(LTag.APS, "SemiTwilight block $semi")
    }

    // MJ4, MJ5, and MJoff run before MoreMJ, so a clear in this loop closes MoreMJ for 65 minutes.
    // MJ2 and MJ3 advance the night cycle before MJ recent reads it. MoreMJ and the morning role step come after.
    // The morning step does not count a second change. That counter belongs to a later profile-batch rule.
    private suspend fun applyMjCycle(
        now: Long,
        minuteOfDay: Int,
        bg: Double,
        delta: Double,
        shortDelta: Double,
        profilePercent: Int,
        cob: Double,
        iob: Double,
        statesOn: Boolean,
        steps60: Int,
        steps180: Int,
    ) {
        val store = states()
        if (mj4ShouldClear(
                ready = runMarks.ready(RunMark.MJ4, 5, now),
                mj4 = statesOn && store.inState("MJ", "MJ4"),
            ) && statesOn && store.hasStateValues("MJ")
        ) {
            store.setState("MJ", "NOMJremains")
            runMarks.mark(RunMark.MJ4, now)
            aapsLogger.debug(LTag.APS, "MJ4 cleared")
        }
        if (mj5ShouldClear(
                ready = runMarks.ready(RunMark.MJ5, 5, now),
                mj5 = statesOn && store.inState("MJ", "MJ5"),
            ) && statesOn && store.hasStateValues("MJ")
        ) {
            switchToStandardAtSharedTier(now, 30)
            store.setState("MJ", "NOMJremains")
            runMarks.mark(RunMark.MJ5, now)
            aapsLogger.debug(LTag.APS, "MJ5 cleared, standard for 30 min")
        }
        if (mj2OldShouldFire(
                ready = runMarks.ready(RunMark.MJ2_OLD, 5, now),
                mjActive = statesOn && store.inState("MJ", "MJ active"),
                minuteOfDay = minuteOfDay,
            ) && statesOn && store.hasStateValues("MJ")
        ) {
            store.setState("MJ", "MJ2")
            runMarks.mark(RunMark.MJ2_OLD, now)
            aapsLogger.debug(LTag.APS, "MJ2 old")
        }
        if (mj3OldShouldFire(
                ready = runMarks.ready(RunMark.MJ3_OLD, 5, now),
                mj2 = statesOn && store.inState("MJ", "MJ2"),
                minuteOfDay = minuteOfDay,
            ) && statesOn && store.hasStateValues("MJ")
        ) {
            store.setState("MJ", "MJ3")
            runMarks.mark(RunMark.MJ3_OLD, now)
            aapsLogger.debug(LTag.APS, "MJ3 old")
        }
        val mjOff = mjOffBlock(
            ready = runMarks.ready(RunMark.MJ_OFF, 5, now),
            mj3 = statesOn && store.inState("MJ", "MJ3"),
            minuteOfDay = minuteOfDay,
            bg = bg,
        )
        if (mjOff != null && statesOn && store.hasStateValues("MJ")) {
            store.setState("MJ", "NOMJremains")
            runMarks.mark(RunMark.MJ_OFF, now)
            aapsLogger.debug(LTag.APS, "MJoff block $mjOff")
        }
        val cannula = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)
        val cannulaHours = if (cannula == null) null else (now - cannula.timestamp) / 3_600_000.0
        val ukf = ukfRawNow(now)
        val hp = ukf.delta5?.let { hypoPrediction2Mmol(bg, shortDelta, it, iob, cob) }
        val tune = mjRecentShouldTune(
            ready = runMarks.ready(RunMark.MJ_RECENT, 480, now),
            steroidsOff = statesOn && store.inState("Steroids", "Steroids Off"),
            mjCycleOn = statesOn && store.hasStateValues("MJ") && !store.inState("MJ", "NOMJremains"),
            ttActive = persistenceLayer.getTemporaryTargetActiveAt(now) != null,
            profilePercent = profilePercent,
            minuteOfDay = minuteOfDay,
            bg = bg,
            delta = delta,
            cannulaHours = cannulaHours,
        )
        if (tune) {
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, 0.50)
            preferences.put(IntKey.ApsAutoIsfIobThPercent, 70)
            val rescue = preferences.get(LongNonKey.ApsAutoIsfOvernightRescueUntil) > now
            if (mjRecentShouldSwitchLow(tune, minuteOfDay, hp, rescue)) {
                switchToLowAtSharedTier(now, "AutoISF: MJ recent low")
            }
            runMarks.mark(RunMark.MJ_RECENT, now)
            aapsLogger.debug(LTag.APS, "MJ recent acce 0.50 iobTH 70")
        }
        val more = moreMjTarget(
            ready = runMarks.ready(RunMark.MORE_MJ, 5, now),
            mjOffReady = runMarks.ready(RunMark.MJ_OFF, 65, now),
            minuteOfDay = minuteOfDay,
            acceWeight = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight),
            steps180 = steps180,
            steps60 = steps60,
            noMjRemains = statesOn && store.inState("MJ", "NOMJremains"),
            bg = bg,
            profilePercent = profilePercent,
            cob = cob,
            minutesSinceBolus = minutesSinceLastPositiveNormalBolus(now),
            alarmRecent = statesOn && store.inState("AlarmHypo", "AlarmRecent"),
            iob = iob,
            noRecentHigh = !libreRawOver12Within(now, 48),
        )
        if (more != null && statesOn && store.hasStateValues("MJ")) {
            store.setState("MJ", more)
            runMarks.mark(RunMark.MORE_MJ, now)
            aapsLogger.debug(LTag.APS, "MoreMJ -> $more")
        }
        val raw5 = rawDelta5MinMgdl(now)
        val hp1 = raw5?.let { hypoPrediction2Mmol(bg, shortDelta, it, iob, cob) }
        val samples = persistenceLayer.getBgReadingsDataFromTimeToTime(now - 8 * 3_600_000L, now, ascending = true)
            .map { it.timestamp to it.value }
        val lowRungs = lowLadderNames()
        val standardRungs = standardLadderNames()
        val lowCurrent = preferences.get(StringKey.ApsAutoIsfLowProfileName).trim()
        val standardCurrent = preferences.get(StringKey.ApsAutoIsfStandardProfileName).trim()
        val running = profileFunction.getOriginalProfileName()
        val nextIndex = morningRoleIndex(
            ready = runMarks.ready(RunMark.MORNING_ROLE_SWAP, 240, now),
            steroidsOff = statesOn && store.inState("Steroids", "Steroids Off"),
            minuteOfDay = minuteOfDay,
            hp = hp1,
            mjKnown = statesOn && store.hasStateValues("MJ"),
            noMjRemains = store.inState("MJ", "NOMJremains"),
            recentBgHigh = recentBgStaysInRange(now, 8, samples, 90.0, null),
            recentBgNormal = recentBgStaysInRange(now, 8, samples, 81.1, 126.1),
            sourceIndex = sourceRoleRung(standardCurrent, lowCurrent, running, standardRungs, lowRungs),
            ladderSize = standardRungs.size,
        )
        if (nextIndex != null && stepSharedRung(now, nextIndex)) {
            val nowLow = preferences.get(StringKey.ApsAutoIsfLowProfileName).trim()
            val nowStandard = preferences.get(StringKey.ApsAutoIsfStandardProfileName).trim()
            if (lowCurrent != nowLow || standardCurrent != nowStandard) {
                val streak = preferences.get(IntNonKey.ApsAutoIsfMorningRoleSwapChangeStreak) + 1
                preferences.put(IntNonKey.ApsAutoIsfMorningRoleSwapChangeStreak, streak)
            }
            runMarks.mark(RunMark.MORNING_ROLE_SWAP, now)
            aapsLogger.debug(LTag.APS, "Morning role step to letter $nextIndex")
        }
    }

    // Writes both role names for one letter. Switches the running profile when it was the old Low or Standard name.
    // Returns true when a name changed or the running profile was one of those roles.
    private suspend fun stepSharedRung(now: Long, index: Int, switchRunning: Boolean = true): Boolean {
        val lowRungs = lowLadderNames()
        val standardRungs = standardLadderNames()
        val lowCurrent = preferences.get(StringKey.ApsAutoIsfLowProfileName).trim()
        val standardCurrent = preferences.get(StringKey.ApsAutoIsfStandardProfileName).trim()
        val names = sharedRungNames(
            index = index,
            lowRungs = lowRungs,
            standardRungs = standardRungs,
            lowCurrent = lowCurrent,
            standardAnchor = preferences.get(StringKey.ApsAutoIsfStandard100ProfileName).trim(),
            standardCurrent = standardCurrent,
        ) ?: return false
        val (newLow, newStandard) = names
        val running = profileFunction.getOriginalProfileName()
        val previousBand = roleTierBandForIndex(sharedRoleLadderIndex(standardCurrent, lowCurrent, standardRungs, lowRungs))
        preferences.put(StringKey.ApsAutoIsfLowProfileName, newLow)
        preferences.put(StringKey.ApsAutoIsfStandardProfileName, newStandard)
        if (newLow != lowCurrent || newStandard != standardCurrent) {
            val nudge = roleTierDeliveryNudge(
                previousBand = previousBand,
                newBand = roleTierBandForIndex(index),
                smbBaseline = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline),
                mildRatio = preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio),
            )
            if (nudge != null) {
                preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryBaseline, nudge.smbBaseline)
                preferences.put(DoubleKey.ApsAutoIsfMildBoostRatio, nudge.mildRatio)
            }
        }
        keepSteroidsOff()
        val matched = running == lowCurrent || running == standardCurrent
        val target = when (running) {
            lowCurrent -> newLow
            standardCurrent -> newStandard
            else -> ""
        }
        if (switchRunning && target.isNotBlank() && running != target) {
            if (!switchToStandardFor(target, 0, now, "AutoISF: morning role")) {
                aapsLogger.debug(LTag.APS, "Morning role did not switch to $target")
            }
        }
        return newLow != lowCurrent || newStandard != standardCurrent || matched
    }

    // PrepareSet50, GentleHypo, PP50Off, then Skittles. PP50Off runs before Skittles so a recovery
    // does not undo a Skittles target started in the same loop. Its mark closes 50SetRecent for 15 minutes.
    // 50SetRecent and 50pcMakes5.7 look at the profile percent from the start of the loop.
    // ConnectPod texts the allowed numbers and any extra ConnectPod numbers.
    private suspend fun applyHypo50(
        now: Long,
        minuteOfDay: Int,
        bg: Double,
        delta: Double,
        shortDelta: Double,
        longDelta: Double,
        profilePercent: Int,
        cob: Double,
        iob: Double,
        statesOn: Boolean,
        steps30: Int,
        steps60: Int,
        livePump: Boolean,
    ) {
        val store = states()
        val cannula = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)
        val cannulaHours = if (cannula == null) null else (now - cannula.timestamp) / 3_600_000.0
        val lastConnection = activePlugin.activePump.lastDataTime.value
        val minutesSinceConnection = if (lastConnection <= 0L) Long.MAX_VALUE else (now - lastConnection) / 60_000L
        if (connectPodShouldFire(
                ready = runMarks.ready(RunMark.CONNECT_POD, 20, now),
                livePump = livePump,
                minutesSinceConnection = minutesSinceConnection,
                minuteOfDay = minuteOfDay,
                cannulaHours = cannulaHours,
            )
        ) {
            runMarks.mark(RunMark.CONNECT_POD, now)
            sendAutoSms("ConnectPod")
            sendAutoSmsToNumbers("ConnectPod", StringKey.SmsConnectPodNumbers)
        }
        val prepare = prepareSet50Block(
            ready = runMarks.ready(RunMark.PREPARE_SET50, 5, now),
            profilePercent = profilePercent,
            bg = bg,
            delta = delta,
            shortDelta = shortDelta,
            iob = iob,
            cob = cob,
            steps30 = steps30,
            minuteOfDay = minuteOfDay,
        )
        if (prepare != null) {
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, 0.07)
            preferences.put(IntKey.ApsAutoIsfIobThPercent, 50)
            preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryRatio, preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline))
            preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal))
            val lowName = preferences.get(StringKey.ApsAutoIsfLowProfileName).trim()
            if (!writeNamedPercent(now, lowName, 50, 360, "AutoISF: prepare 50%")) {
                aapsLogger.debug(LTag.APS, "PrepareSet50 did not write a profile switch")
            }
            if (statesOn && store.hasStateValues("LowBG")) store.setState("LowBG", "50recent")
            runMarks.mark(RunMark.PREPARE_SET50, now)
            sendAutoSms("prepare Set50% [b$prepare]: g=${decimals(bg / 18.016, 1)} d=${decimals(delta / 18.016, 2)}")
            carePortalNote("Set50-$prepare")
            aapsLogger.debug(LTag.APS, "PrepareSet50 block $prepare")
        }
        val ukf = ukfRawNow(now)
        val hp = ukf.delta5?.let { hypoPrediction2Mmol(bg, shortDelta, it, iob, cob) }
        val gentle = gentleHypoBlock(
            ready = runMarks.ready(RunMark.GENTLE_HYPO_RISK, 30, now),
            acceWeight = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight),
            bg = bg,
            delta = delta,
            shortDelta = shortDelta,
            profilePercent = profilePercent,
            ukfGlucose = ukf.glucose,
            ukfDelta1 = ukf.delta1,
            ukfDelta5 = ukf.delta5,
            hp = hp,
            steps60 = steps60,
        )
        if (gentle != null) {
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, 0.02)
            preferences.put(IntKey.ApsAutoIsfIobThPercent, 50)
            runMarks.mark(RunMark.GENTLE_HYPO_RISK, now)
            aapsLogger.debug(LTag.APS, "GentleHypoRisk block $gentle")
        }
        val pp50 = pp50OffBlock(
            ready = runMarks.ready(RunMark.PP50_OFF, 5, now),
            lowBgRecent = statesOn && store.inState("LowBG", "50recent"),
            minuteOfDay = minuteOfDay,
            bg = bg,
            delta = delta,
            shortDelta = shortDelta,
            longDelta = longDelta,
            iob = iob,
            cob = cob,
            cannulaHours = cannulaHours,
            minutesSinceBolus = minutesSinceLastPositiveNormalBolus(now),
            acceWeight = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight),
        )
        if (pp50 != null) {
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, 0.50)
            val currentName = profileFunction.getOriginalProfileName().trim()
            if (!writeNamedPercent(now, currentName, 100, 0, "AutoISF: PP50 off")) {
                aapsLogger.debug(LTag.APS, "PP50Off did not write a profile switch")
            }
            if (statesOn && store.hasStateValues("LowBG")) store.setState("LowBG", "NO50rec")
            runMarks.mark(RunMark.PP50_OFF, now)
            sendAutoSms("PP50.Off [b$pp50]: g=${decimals(bg / 18.016, 1)} d=${decimals(delta / 18.016, 2)}")
            carePortalNote("50ff-$pp50")
            aapsLogger.debug(LTag.APS, "PP50Off block $pp50")
        }
        val skittles = skittlesBlock(
            ready = runMarks.ready(RunMark.SKITTLES_HYPO_RISK, 5, now),
            bg = bg,
            delta = delta,
            shortDelta = shortDelta,
            longDelta = longDelta,
            iob = iob,
            cob = cob,
            profilePercent = profilePercent,
            minutesSinceBolus = minutesSinceLastPositiveNormalBolus(now),
            steroidsOff = statesOn && store.inState("Steroids", "Steroids Off"),
        )
        if (skittles != null && persistenceLayer.getTemporaryTargetActiveAt(now) == null) {
            startBrakeTarget(now, 102.7, "AutoISF: Skittles 5.7", 180)
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, 0.02)
            preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryRatio, preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline))
            preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal))
            val currentName = profileFunction.getOriginalProfileName().trim()
            if (!writeNamedPercent(now, currentName, 100, 0, "AutoISF: Skittles reset to 100%")) {
                aapsLogger.debug(LTag.APS, "Skittles did not write a profile switch")
            }
            if (statesOn && store.hasStateValues("LowBG")) store.setState("LowBG", "50recent")
            runMarks.mark(RunMark.SKITTLES_HYPO_RISK, now)
            sendAutoSms("Skittles $skittles: hypo risk — TT 5.7 set")
            aapsLogger.debug(LTag.APS, "Skittles block $skittles")
        }
        if (fiftySetRecentShouldFire(
                ready = runMarks.ready(RunMark.SET50_RECENT, 5, now),
                pp50OffReady = runMarks.ready(RunMark.PP50_OFF, 15, now),
                profilePercent = profilePercent,
                lowBgClear = statesOn && store.inState("LowBG", "NO50rec"),
            )
        ) {
            if (statesOn && store.hasStateValues("LowBG")) store.setState("LowBG", "50recent")
            runMarks.mark(RunMark.SET50_RECENT, now)
            sendAutoSms("50%Recently")
            carePortalNote("50Rec")
            aapsLogger.debug(LTag.APS, "50SetRecent")
        }
        if (fiftyPcMakes57ShouldFire(
                ready = runMarks.ready(RunMark.FIFTY_PC_MAKES_57, 10, now),
                profilePercent = profilePercent,
                ttActive = persistenceLayer.getTemporaryTargetActiveAt(now) != null,
                bg = bg,
                delta = delta,
            )
        ) {
            startBrakeTarget(now, 102.7, "AutoISF: 50% makes 5.7", 150)
            runMarks.mark(RunMark.FIFTY_PC_MAKES_57, now)
            sendAutoSms("50pc makes5.7: g=${decimals(bg / 18.016, 1)} d=${decimals(delta / 18.016, 2)}")
            carePortalNote("50pcTT")
            aapsLogger.debug(LTag.APS, "50pc makes 5.7")
        }
    }

    // Shower12 drops the IOB threshold to 12% before Usual2, so Usual2 can still raise it in the same loop.
    // Pod2 and Pod1 run before a pod-change 130% switch, so they do not undo that switch.
    // Pod2 sets Profile to PP130, switches to Standard, and sends the 78-hour text.
    // Bolus2 stays off. OldPod2 stays off.
    private suspend fun applyShowerAndPodAge(
        now: Long,
        minuteOfDay: Int,
        bg: Double,
        delta: Double,
        shortDelta: Double,
        profilePercent: Int,
        cob: Double,
        statesOn: Boolean,
    ) {
        val store = states()
        val tt = persistenceLayer.getTemporaryTargetActiveAt(now)
        val cannula = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)
        val cannulaHours = if (cannula == null) null else (now - cannula.timestamp) / 3_600_000.0
        val livePump = activePlugin.activePump !is VirtualPump
        if (shower12ShouldFire(
                ready = runMarks.ready(RunMark.SHOWER12, 5, now),
                profilePercent = profilePercent,
                iobThPercent = preferences.get(IntKey.ApsAutoIsfIobThPercent),
                steroidsOff = statesOn && store.inState("Steroids", "Steroids Off"),
                minuteOfDay = minuteOfDay,
                bg = bg,
                delta = delta,
                shortDelta = shortDelta,
                steps60 = steps60(now),
                cob = cob,
                minutesSinceBolus = minutesSinceLastPositiveNormalBolus(now),
                ttLowMgdl = tt?.lowTarget,
            )
        ) {
            preferences.put(IntKey.ApsAutoIsfIobThPercent, 12)
            runMarks.mark(RunMark.SHOWER12, now)
            sendAutoSms("Shower12: g=${decimals(bg / 18.016, 1)} iobTH=${preferences.get(IntKey.ApsAutoIsfIobThPercent)}")
            carePortalNote("Shwr12")
            aapsLogger.debug(LTag.APS, "Shower12 iobTH -> 12")
        }
        if (pod2ShouldFire(
                ready = runMarks.ready(RunMark.POD2, 10, now),
                livePump = livePump,
                cannulaHours = cannulaHours,
                minuteOfDay = minuteOfDay,
            )
        ) {
            if (statesOn && store.hasStateValues("Profile")) store.setState("Profile", "PP130")
            switchToStandardAtSharedTier(now)
            runMarks.mark(RunMark.POD2, now)
            phoneAlert("_____POD2")
            sendAutoSms("POD 78 hours")
            sendAutoSmsToNumbers("POD 78 hours", StringKey.SmsPod2Numbers)
        }
        if (pod1ShouldFire(
                ready = runMarks.ready(RunMark.POD1, 10, now),
                livePump = livePump,
                cannulaHours = cannulaHours,
                minuteOfDay = minuteOfDay,
            )
        ) {
            runMarks.mark(RunMark.POD1, now)
            phoneAlert("POD 79 h")
            sendAutoSms("_____POD1hr")
        }
    }

    // Pod change raises the profile to 130% for 60 minutes. RecentPod raises it to 130% for 5 minutes
    // and sets 4.2 mmol. HighPP130Off puts a 110% or 130% profile back on Standard at 100%.
    private suspend fun applyPodBoosts(
        now: Long,
        minuteOfDay: Int,
        bg: Double,
        delta: Double,
        profilePercent: Int,
        cob: Double,
        iob: Double,
        statesOn: Boolean,
        livePump: Boolean,
    ) {
        val store = states()
        val tt = persistenceLayer.getTemporaryTargetActiveAt(now)
        val cannula = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)
        val cannulaHours = if (cannula == null) null else (now - cannula.timestamp) / 3_600_000.0
        val libreHigh = libreRawOver12Within(now, 48)
        if (podChangeHighPp130ShouldFire(
                ready = runMarks.ready(RunMark.POD_CHANGE_HIGH_PP130, 5, now),
                livePump = livePump,
                profilePercent = profilePercent,
                libreOver12Recent = libreHigh,
                profilePp130 = statesOn && store.inState("Profile", "PP130"),
                minuteOfDay = minuteOfDay,
                bg = bg,
                delta = delta,
                cannulaHours = cannulaHours,
            )
        ) {
            if (statesOn && store.hasStateValues("Profile")) store.setState("Profile", "C100")
            switchToStandardAtSharedTier(now)
            if (writeProfilePercent(130, 60, "AutoISF: pod change 130%")) {
                preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh))
                runMarks.mark(RunMark.POD_CHANGE_HIGH_PP130, now)
                sendAutoSms("PodChangeHighPP130 Acce")
                carePortalNote("Pod130")
                aapsLogger.debug(LTag.APS, "Pod change 130% for 60 min")
            }
        }
        if (highPp130OffShouldFire(
                ready = runMarks.ready(RunMark.HIGH_PP130_OFF, 2, now),
                profilePercent = profilePercent,
                bg = bg,
                delta = delta,
                ttActive = tt != null,
            )
        ) {
            if (statesOn && store.hasStateValues("Profile")) store.setState("Profile", "C100")
            if (statesOn && store.hasStateValues("LowBG")) store.setState("LowBG", "NO50rec")
            switchToStandardAtSharedTier(now)
            runMarks.mark(RunMark.HIGH_PP130_OFF, now)
            sendAutoSms("HighPP130Off")
            carePortalNote("130Off")
            aapsLogger.debug(LTag.APS, "PP130 off -> standard at 100%")
        }
        val acceNow = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
        val acceHigh = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightHigh)
        if (recentPodOffShouldFire(
                ready = runMarks.ready(RunMark.RECENT_POD_OFF, 5, now),
                acceWeight = acceNow,
                acceHigh = acceHigh,
                ttActive = tt != null,
                podBoostRecent = runMarks.recent(RunMark.RECENT_POD, 60, now) || runMarks.recent(RunMark.OLD_POD2, 60, now),
            )
        ) {
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal))
            switchToStandardAtSharedTier(now)
            runMarks.mark(RunMark.RECENT_POD_OFF, now)
            sendAutoSms("RecentPodOff Acce")
            aapsLogger.debug(LTag.APS, "RecentPodOff -> accel normal, standard at 100%")
        }
        if (!recentPodShouldFire(
                ready = runMarks.ready(RunMark.RECENT_POD, 5, now),
                livePump = livePump,
                profilePercent = profilePercent,
                ttActive = tt != null,
                libreOver12Recent = libreHigh,
                bg = bg,
                delta = delta,
                cob = cob,
                iob = iob,
                minutesSinceBolus = minutesSinceLastPositiveNormalBolus(now),
                cannulaHours = cannulaHours,
            )
        ) return
        if (!writeProfilePercent(130, 5, "AutoISF: recent pod 130%")) {
            aapsLogger.debug(LTag.APS, "RecentPod did not write a profile switch")
            return
        }
        startBrakeTarget(now, 75.7, "AutoISF: recent pod 4.2", 5)
        preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightHigh))
        preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh))
        runMarks.mark(RunMark.RECENT_POD, now)
        sendAutoSms("RecentPod Acce")
        carePortalNote("RecPod")
        aapsLogger.debug(LTag.APS, "RecentPod 130% and 4.2 mmol for 5 min")
    }

    private suspend fun writeNamedPercent(now: Long, name: String, percent: Int, minutes: Int, note: String): Boolean {
        val trimmed = name.trim()
        val values = listOf(ValueWithUnit.Percent(percent), ValueWithUnit.Minute(minutes))
        if (trimmed.isBlank()) {
            return profileFunction.createProfileSwitch(
                durationInMinutes = minutes,
                percentage = percent,
                timeShiftInHours = 0,
                action = Action.PROFILE_SWITCH,
                source = Sources.Automation,
                note = note,
                listValues = values,
            ) != null
        }
        val iCfg = profileFunction.getRunningOrRequestedICfg() ?: return false
        val profileStore = profileRepository.profile.value ?: return false
        if (profileStore.getSpecificProfile(trimmed) == null) return false
        return profileFunction.createProfileSwitch(
            profileStore = profileStore,
            profileName = trimmed,
            durationInMinutes = minutes,
            percentage = percent,
            timeShiftInHours = 0,
            timestamp = now,
            action = Action.PROFILE_SWITCH,
            source = Sources.Automation,
            note = note,
            listValues = listOf(ValueWithUnit.SimpleString(trimmed)) + values,
            iCfg = iCfg,
        ) != null
    }

    private suspend fun writeProfilePercent(percent: Int, minutes: Int, note: String): Boolean =
        profileFunction.createProfileSwitch(
            durationInMinutes = minutes,
            percentage = percent,
            timeShiftInHours = 0,
            action = Action.PROFILE_SWITCH,
            source = Sources.Automation,
            note = note,
            listValues = listOf(ValueWithUnit.Percent(percent), ValueWithUnit.Minute(minutes)),
        ) != null

    // High6PP raises the profile to 120% for 5 minutes. High6PPoff puts it back on Standard at 100%.
    // HighOldPod, on a fresh or stale pod, sets 5.0 mmol for 5 minutes and 110% for 5 minutes.
    private suspend fun applyHigh6(
        now: Long,
        minuteOfDay: Int,
        bg: Double,
        delta: Double,
        shortDelta: Double,
        longDelta: Double,
        profilePercent: Int,
        cob: Double,
        statesOn: Boolean,
    ) {
        val store = states()
        val tt = persistenceLayer.getTemporaryTargetActiveAt(now)
        val cannula = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)
        val cannulaHours = if (cannula == null) null else (now - cannula.timestamp) / 3_600_000.0
        if (high6ppOffShouldFire(
                ready = runMarks.ready(RunMark.HIGH_6_PP_OFF, 5, now),
                profilePercent = profilePercent,
                minuteOfDay = minuteOfDay,
                bg = bg,
                delta = delta,
                ttActive = tt != null,
            )
        ) {
            switchToStandardAtSharedTier(now)
            runMarks.mark(RunMark.HIGH_6_PP_OFF, now)
            sendAutoSms("High6PPoff Acce")
            carePortalNote("off120")
            aapsLogger.debug(LTag.APS, "High6PPoff -> standard at 100%")
            return
        }
        if (high6ppShouldFire(
                ready = runMarks.ready(RunMark.HIGH_6_PP, 30, now),
                profilePercent = profilePercent,
                minuteOfDay = minuteOfDay,
                libreOver12Recent = libreRawOver12Within(now, 48),
                bg = bg,
                delta = delta,
                shortDelta = shortDelta,
                longDelta = longDelta,
                ttLowMgdl = tt?.lowTarget,
                cob = cob,
            )
        ) {
            val switched = profileFunction.createProfileSwitch(
                durationInMinutes = 5,
                percentage = 120,
                timeShiftInHours = 0,
                action = Action.PROFILE_SWITCH,
                source = Sources.Automation,
                note = "AutoISF: High6PP 120%",
                listValues = listOf(ValueWithUnit.Percent(120), ValueWithUnit.Minute(5)),
            ) != null
            if (switched) {
                preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh))
                runMarks.mark(RunMark.HIGH_6_PP, now)
                sendAutoSms("High6PP Acce")
                carePortalNote("P120")
                aapsLogger.debug(LTag.APS, "High6PP 120% for 5 min")
            }
            return
        }
        if (!highOldPodShouldFire(
                ready = runMarks.ready(RunMark.HIGH_OLD_POD, 5, now),
                profilePercent = profilePercent,
                ttActive = tt != null,
                noMjRemains = statesOn && store.inState("MJ", "NOMJremains"),
                bg = bg,
                delta = delta,
                shortDelta = shortDelta,
                longDelta = longDelta,
                minutesSinceBolus = minutesSinceLastPositiveNormalBolus(now),
                cannulaHours = cannulaHours,
            )
        ) return
        startBrakeTarget(now, 90.1, "AutoISF: HighOldPod 5.0", 5)
        val standardName = preferences.get(StringKey.ApsAutoIsfStandardProfileName).trim()
        val switched = if (standardName.isBlank()) {
            profileFunction.createProfileSwitch(
                durationInMinutes = 5,
                percentage = 110,
                timeShiftInHours = 0,
                action = Action.PROFILE_SWITCH,
                source = Sources.Automation,
                note = "AutoISF: HighOldPod 110%",
                listValues = listOf(ValueWithUnit.Percent(110), ValueWithUnit.Minute(5)),
            ) != null
        } else {
            val iCfg = profileFunction.getRunningOrRequestedICfg()
            val profileStore = profileRepository.profile.value
            if (iCfg == null || profileStore?.getSpecificProfile(standardName) == null) false
            else profileFunction.createProfileSwitch(
                profileStore = profileStore,
                profileName = standardName,
                durationInMinutes = 5,
                percentage = 110,
                timeShiftInHours = 0,
                timestamp = now,
                action = Action.PROFILE_SWITCH,
                source = Sources.Automation,
                note = "AutoISF: HighOldPod 110%",
                listValues = listOf(
                    ValueWithUnit.SimpleString(standardName),
                    ValueWithUnit.Percent(110),
                    ValueWithUnit.Minute(5),
                ),
                iCfg = iCfg,
            ) != null
        }
        if (!switched) {
            aapsLogger.debug(LTag.APS, "HighOldPod did not write a profile switch")
            return
        }
        preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal))
        preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh))
        runMarks.mark(RunMark.HIGH_OLD_POD, now)
        aapsLogger.debug(LTag.APS, "HighOldPod 5.0 mmol and 110% for 5 min")
    }

    private suspend fun libreRawOver12Within(now: Long, hours: Int): Boolean {
        val from = now - hours * 3_600_000L
        val limit = 12.0 * 18.0
        return persistenceLayer.getBgReadingsDataFromTimeToTime(from, now, ascending = false)
            .any { (it.noise ?: 0.0) > limit }
    }

    private suspend fun startBrakeTarget(now: Long, targetMgdl: Double, note: String, minutes: Int = 2) {
        if (persistenceLayer.getTemporaryTargetActiveAt(now) != null) return
        persistenceLayer.insertAndCancelCurrentTemporaryTarget(
            temporaryTarget = TT(
                timestamp = now,
                duration = T.mins(minutes.toLong()).msecs(),
                reason = TT.Reason.AUTOMATION,
                lowTarget = targetMgdl,
                highTarget = targetMgdl
            ),
            action = Action.TT,
            source = Sources.Automation,
            note = note,
            listValues = listOf(
                ValueWithUnit.TETTReason(TT.Reason.AUTOMATION),
                ValueWithUnit.Mgdl(targetMgdl),
                ValueWithUnit.Minute(minutes)
            )
        )
    }

    // Align Low Current and Standard Current to the letter already in force, then switch to that Standard name.
    // A band change nudges the SMB baseline by 0.01 and the mild ratio by 0.25. B and C share one band.
    private suspend fun switchToStandardAtSharedTier(now: Long, minutes: Int = 0) {
        switchToRoleAtSharedTier(now, toLow = false, note = "AutoISF: BasalUp", minutes = minutes)
    }

    // Same letter alignment, then switch the running profile to the Low name.
    private suspend fun switchToLowAtSharedTier(now: Long, note: String, minutes: Int = 0) {
        switchToRoleAtSharedTier(now, toLow = true, note = note, minutes = minutes)
    }

    private suspend fun switchToRoleAtSharedTier(now: Long, toLow: Boolean, note: String, minutes: Int = 0) {
        val lowRungs = lowLadderNames()
        val standardRungs = standardLadderNames()
        val lowCurrent = preferences.get(StringKey.ApsAutoIsfLowProfileName).trim()
        val standardCurrent = preferences.get(StringKey.ApsAutoIsfStandardProfileName).trim()
        val running = profileFunction.getOriginalProfileName()
        val index = sourceRoleRung(standardCurrent, lowCurrent, running, standardRungs, lowRungs).coerceAtLeast(0)
        val names = sharedRungNames(
            index = index,
            lowRungs = lowRungs,
            standardRungs = standardRungs,
            lowCurrent = lowCurrent,
            standardAnchor = preferences.get(StringKey.ApsAutoIsfStandard100ProfileName).trim(),
            standardCurrent = standardCurrent,
        ) ?: return
        val (newLow, newStandard) = names
        if (newLow != lowCurrent || newStandard != standardCurrent) {
            val previousBand = roleTierBandForIndex(sharedRoleLadderIndex(standardCurrent, lowCurrent, standardRungs, lowRungs))
            preferences.put(StringKey.ApsAutoIsfLowProfileName, newLow)
            preferences.put(StringKey.ApsAutoIsfStandardProfileName, newStandard)
            val nudge = roleTierDeliveryNudge(
                previousBand = previousBand,
                newBand = roleTierBandForIndex(index),
                smbBaseline = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline),
                mildRatio = preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio),
            )
            if (nudge != null) {
                preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryBaseline, nudge.smbBaseline)
                preferences.put(DoubleKey.ApsAutoIsfMildBoostRatio, nudge.mildRatio)
                aapsLogger.debug(LTag.APS, "Role tier nudge smb ${nudge.smbBaseline} mild ${nudge.mildRatio}")
            }
        }
        keepSteroidsOff()
        val target = if (toLow) newLow else newStandard
        if (target.isNotBlank() && profileFunction.getOriginalProfileName() != target) {
            val switched = switchToStandardFor(target, minutes, now, note)
            if (!switched) aapsLogger.debug(LTag.APS, "Role switch did not write $target")
        }
    }

    // Standard and Low role writes stay on Steroids Off. This does not turn steroids on.
    private fun keepSteroidsOff() {
        if (!preferences.get(BooleanKey.AutomationStatesEnabled)) return
        val store = states()
        if (store.hasStateValues("Steroids") && !store.inState("Steroids", "Steroids Off")) {
            store.setState("Steroids", "Steroids Off")
        }
    }

    private fun lowLadderNames(): List<String> = listOf(
        preferences.get(StringKey.ApsAutoIsfLow70ProfileName).trim(),
        preferences.get(StringKey.ApsAutoIsfLow80ProfileName).trim(),
        preferences.get(StringKey.ApsAutoIsfLow90ProfileName).trim(),
    )

    private fun standardLadderNames(): List<String> = listOf(
        preferences.get(StringKey.ApsAutoIsfStandard100ProfileName).trim(),
        preferences.get(StringKey.ApsAutoIsfStandard105ProfileName).trim(),
        preferences.get(StringKey.ApsAutoIsfStandard110ProfileName).trim(),
    )

    // A 6.8 mmol/L activity temp target, with glucose at or under 8.5 mmol/L and not rising,
    // sets the current profile to 50% for 180 minutes. The acceleration weight is left alone.
    private suspend fun applyActivityProf50(
        now: Long,
        profilePercent: Int,
        profileIsBolus: Boolean,
        lowTargetMgdl: Double?,
        bg: Double,
        delta: Double,
    ) {
        if (!activityProf50ShouldFire(
                ready = runMarks.ready(RunMark.ACTIVITY_PROF_50, 5, now),
                profilePercent = profilePercent,
                profileIsBolus = profileIsBolus,
                lowTargetMgdl = lowTargetMgdl,
                bg = bg,
                delta = delta,
            )
        ) return
        val baseline = preferences.get(IntKey.ApsAutoIsfProfilePercentNormal)
        if (50 > baseline) {
            aapsLogger.debug(LTag.APS, "Activity profile 50 left alone, 50 is above the baseline")
            return
        }
        val switched = profileFunction.createProfileSwitch(
            durationInMinutes = 180,
            percentage = 50,
            timeShiftInHours = 0,
            action = Action.PROFILE_SWITCH,
            source = Sources.Automation,
            note = "AutoISF: activity profile 50",
            listValues = listOf(
                ValueWithUnit.Percent(50),
                ValueWithUnit.Minute(180)
            )
        ) != null
        if (!switched) {
            aapsLogger.debug(LTag.APS, "Activity profile 50 did not write a switch")
            return
        }
        runMarks.mark(RunMark.ACTIVITY_PROF_50, now)
        sendAutoSms("ActivityProf50% Acce")
        carePortalNote("Ac50")
        aapsLogger.debug(LTag.APS, "Activity profile 50 for 180 min")
    }

    // Ends the 180 minute activity profile at 50%. Switches to the standard profile at 100% with no end time,
    // so the short 50% switch does not keep running. Cancels the temp target. Puts the IOB threshold back to 70
    // only when 70 is not above the saved baseline. The acceleration weight is left alone.
    private suspend fun applyActivityOff(
        now: Long,
        profilePercent: Int,
        lowTargetMgdl: Double?,
        bg: Double,
        delta: Double,
    ) {
        if (!runMarks.ready(RunMark.ACTIVITY_OFF, 5, now) || profilePercent != 50) return
        val cannula = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)
        val cannulaHours = if (cannula == null) 0.0 else (now - cannula.timestamp) / 3_600_000.0
        if (!activityOffShouldFire(
                ready = true,
                profilePercent = profilePercent,
                lowTargetMgdl = lowTargetMgdl,
                bg = bg,
                delta = delta,
                cannulaHours = cannulaHours,
                lastBolusMinutes = minutesSinceLastPositiveNormalBolus(now),
                delayedBolusPending = false,
            )
        ) return
        val standardName = preferences.get(StringKey.ApsAutoIsfStandardProfileName)
        val iCfg = profileFunction.getRunningOrRequestedICfg()
        val store = profileRepository.profile.value
        if (standardName.isBlank() || iCfg == null || store == null || store.getSpecificProfile(standardName) == null) {
            aapsLogger.debug(LTag.APS, "Activity off: standard profile is not in the store")
            return
        }
        val switched = profileFunction.createProfileSwitch(
            profileStore = store,
            profileName = standardName,
            durationInMinutes = 0,
            percentage = 100,
            timeShiftInHours = 0,
            timestamp = now,
            action = Action.PROFILE_SWITCH,
            source = Sources.Automation,
            note = "AutoISF: activity off",
            listValues = listOf(
                ValueWithUnit.SimpleString(standardName),
                ValueWithUnit.Percent(100)
            ),
            iCfg = iCfg,
        ) != null
        if (!switched) {
            aapsLogger.debug(LTag.APS, "Activity off did not write a switch")
            return
        }
        persistenceLayer.cancelCurrentTemporaryTargetIfAny(
            timestamp = now,
            action = Action.CANCEL_TT,
            source = Sources.Automation,
            note = "AutoISF: activity off",
            listValues = emptyList(),
        )
        val iobBaseline = preferences.get(IntKey.ApsAutoIsfIobThPercentNormal)
        if (70 <= iobBaseline) preferences.put(IntKey.ApsAutoIsfIobThPercent, 70)
        else aapsLogger.debug(LTag.APS, "Activity off left the IOB threshold alone, 70 is above the baseline")
        runMarks.mark(RunMark.ACTIVITY_OFF, now)
        sendAutoSms("Activity 70_0.70 0.35 Acce")
        carePortalNote("ActOff")
        aapsLogger.debug(LTag.APS, "Activity off -> $standardName at 100%")
    }

    // Switches to [profileName] at 100% for [minutes]. A zero duration has no end time. Returns false when the name is missing.
    private suspend fun switchToStandardFor(profileName: String, minutes: Int, now: Long, note: String = "AutoISF: high night"): Boolean {
        if (profileName.isBlank()) return false
        val iCfg = profileFunction.getRunningOrRequestedICfg() ?: return false
        val store = profileRepository.profile.value ?: return false
        if (store.getSpecificProfile(profileName) == null) return false
        val units = mutableListOf<ValueWithUnit>(
            ValueWithUnit.SimpleString(profileName),
            ValueWithUnit.Percent(100),
        )
        if (minutes > 0) units.add(ValueWithUnit.Minute(minutes))
        return profileFunction.createProfileSwitch(
            profileStore = store,
            profileName = profileName,
            durationInMinutes = minutes,
            percentage = 100,
            timeShiftInHours = 0,
            timestamp = now,
            action = Action.PROFILE_SWITCH,
            source = Sources.Automation,
            note = note,
            listValues = units,
            iCfg = iCfg,
        ) != null
    }

    // A falling glucose on a 100% profile. The night FastRise skip stays closed until glucose recovers.
    // The acceleration weight is not lowered. A drop to 0.07 would stay, because the restore only raises it.
    private fun applyExtra50State() {
        val store = states()
        if (!preferences.get(BooleanKey.AutomationStatesEnabled)) return
        if (store.hasStateValues("LowBG")) store.setState("LowBG", "50recent")
    }

    // Glucose has risen back through 5.5 mmol/L at a 100% profile. The night FastRise skip can run again.
    // The saved hypo-alarm time is not cleared, so the 60 minute meal floor still applies.
    private fun applyNot50Clear() {
        val store = states()
        if (!preferences.get(BooleanKey.AutomationStatesEnabled)) return
        if (store.hasStateValues("LowBG")) store.setState("LowBG", "NO50rec")
        if (store.hasStateValues("AlarmHypo")) store.setState("AlarmHypo", "NoAlarmRecent")
    }

    // Records a real hypo alarm. The mild meal-leftover floor then stays at 7.0 mmol/L for 60 minutes.
    // LowBG becomes 50recent, which also keeps the night FastRise skip closed.
    private fun applyAlarmHypoState() {
        val store = states()
        if (!preferences.get(BooleanKey.AutomationStatesEnabled)) return
        if (store.hasStateValues("LowBG")) store.setState("LowBG", "50recent")
        if (store.hasStateValues("AlarmHypo")) store.setState("AlarmHypo", "AlarmRecent")
    }

    // Puts a low IOB threshold back to 70, and an acceleration weight above 0.50 down to 0.50.
    // A target that would sit above its baseline is left alone, so the restore does not undo it next loop.
    // The post-meal weight returns to its baseline unless a boost mark is still inside 15 minutes.
    private fun applyUsual2(now: Long) {
        val iobBaseline = preferences.get(IntKey.ApsAutoIsfIobThPercentNormal)
        if (70 <= iobBaseline) preferences.put(IntKey.ApsAutoIsfIobThPercent, 70)
        val acceBaseline = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal)
        if (0.50 <= acceBaseline) preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, 0.50)
        val recentBoost = ppWeightBoostMarks.any { runMarks.recent(it, 15, now) }
        if (!recentBoost) {
            preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal))
        }
        val store = states()
        if (preferences.get(BooleanKey.AutomationStatesEnabled) && store.hasStateValues("LowBG")) {
            store.setState("LowBG", "NO50rec")
        }
    }

    private suspend fun smbCount20(now: Long): Int =
        persistenceLayer.getBolusesFromTimeToTime(now - 20 * 60_000L, now, ascending = false)
            .count { it.type == BS.Type.SMB }

    // Copies a live value into its baseline the first time that baseline has no saved value.
    // Runs before a boost write, so the baseline is the resting value and not the raised one.
    private fun seedBaselines(profilePercent: Int) {
        if (preferences.getIfExists(DoubleKey.ApsAutoIsfPpWeightNormal) == null) {
            preferences.put(DoubleKey.ApsAutoIsfPpWeightNormal, preferences.get(DoubleKey.ApsAutoIsfPpWeight))
        }
        if (preferences.getIfExists(DoubleKey.ApsAutoIsfBgAccelWeightNormal) == null) {
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeightNormal, preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight))
        }
        if (preferences.getIfExists(IntKey.ApsAutoIsfIobThPercentNormal) == null) {
            preferences.put(IntKey.ApsAutoIsfIobThPercentNormal, preferences.get(IntKey.ApsAutoIsfIobThPercent))
        }
        if (preferences.getIfExists(IntKey.ApsAutoIsfProfilePercentNormal) == null) {
            preferences.put(IntKey.ApsAutoIsfProfilePercentNormal, profilePercent)
        }
    }

    // Writes a boost only when the new value sits above the saved baseline.
    // The restore, which runs later in the same loop, sees the fresh mark and waits 15 minutes.
    private suspend fun applyBoostRaise(raise: BoostRaise, deliveryRatio: Double) {
        val iobTarget = raiseAbove(raise.iobTh, preferences.get(IntKey.ApsAutoIsfIobThPercentNormal))
        if (iobTarget != null) preferences.put(IntKey.ApsAutoIsfIobThPercent, iobTarget)
        val percentTarget = raiseAbove(raise.profilePercent, preferences.get(IntKey.ApsAutoIsfProfilePercentNormal))
        if (percentTarget != null) {
            val switched = profileFunction.createProfileSwitch(
                durationInMinutes = raise.profileMinutes,
                percentage = percentTarget,
                timeShiftInHours = 0,
                action = Action.PROFILE_SWITCH,
                source = Sources.Automation,
                note = "AutoISF: profile percent boost",
                listValues = listOf(
                    ValueWithUnit.Percent(percentTarget),
                    ValueWithUnit.Minute(raise.profileMinutes)
                )
            ) != null
            if (!switched) aapsLogger.debug(LTag.APS, "Profile percent boost did not write a switch")
        }
        if (raise.raisePpWeight) {
            preferences.put(DoubleKey.ApsAutoIsfPpWeight, preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh))
        }
        preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryRatio, deliveryRatio)
        smbBoostedThisCycle = true
    }

    // Holds 5.0 mmol for 2 minutes. Skipped when a temp target is already active.
    // While any temp target is on, the delivery-ratio restore stays off.
    private suspend fun startMildHoldTarget() {
        if (persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now()) != null) return
        val now = dateUtil.now()
        val targetMgdl = 90.1
        val minutes = 2
        persistenceLayer.insertAndCancelCurrentTemporaryTarget(
            temporaryTarget = TT(
                timestamp = now,
                duration = T.mins(minutes.toLong()).msecs(),
                reason = TT.Reason.AUTOMATION,
                lowTarget = targetMgdl,
                highTarget = targetMgdl
            ),
            action = Action.TT,
            source = Sources.Automation,
            note = "AutoISF mild boost temp target",
            listValues = listOf(
                ValueWithUnit.TETTReason(TT.Reason.AUTOMATION),
                ValueWithUnit.Mgdl(targetMgdl),
                ValueWithUnit.Minute(minutes)
            )
        )
        preferences.put(LongNonKey.ApsAutoIsfLastBmildTtAt, now)
    }

    private suspend fun iobAt(time: Long): Double {
        val profile = profileFunction.getProfile(time) ?: return 0.0
        return iobCobCalculator.calculateFromTreatmentsAndTemps(time, profile).iob
    }

    private suspend fun minutesSinceLastPositiveNormalBolus(now: Long): Int {
        val last = persistenceLayer.getBolusesFromTimeToTime(now - 24 * 60 * 60_000L, now, ascending = false)
            .firstOrNull { it.type == BS.Type.NORMAL && it.amount > 0.0 }
            ?.timestamp ?: return Int.MAX_VALUE
        return ((now - last).toDouble() / 60_000.0).toInt()
    }

    // A site change under 2 hours with glucose over 9.0 mmol/L may open the day window at any hour.
    // A Usual2 mark in the last 90 minutes opens that same window.
    private suspend fun newPodHighBypass(now: Long, bg: Double): Boolean {
        val last = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE) ?: return false
        val hours = (now - last.timestamp) / 3_600_000.0
        return hours < 2.0 && bg > 162.2
    }

    private suspend fun smbCount5(now: Long): Int =
        persistenceLayer.getBolusesFromTimeToTime(now - 5 * 60_000L, now, ascending = false)
            .count { it.type == BS.Type.SMB }

    // Average seconds between SMB deliveries in the last 5 minutes. Fewer than two means no stack.
    private suspend fun smbInterval5Sec(now: Long): Double {
        val smbs = persistenceLayer.getBolusesFromTimeToTime(now - 5 * 60_000L, now, ascending = false)
            .filter { it.type == BS.Type.SMB }
        if (smbs.size < 2) return 9999.0
        val spanSec = (smbs.first().timestamp - smbs.last().timestamp).toDouble() / 1000.0
        return spanSec / (smbs.size - 1)
    }

    private suspend fun smbSum(now: Long, windowMs: Long): Double =
        persistenceLayer.getBolusesFromTimeToTime(now - windowMs, now, ascending = false)
            .filter { it.type == BS.Type.SMB }
            .sumOf { it.amount }

    // Libre 2 and Libre 3 only, matching UKF3426's fslReally sensor check.
    private suspend fun libreActive(now: Long): Boolean {
        val newest = persistenceLayer.getBgReadingsDataFromTimeToTime(now - 15 * 60 * 1000L, now, ascending = false)
            .firstOrNull() ?: return false
        return newest.sourceSensor == SourceSensor.LIBRE_2 ||
            newest.sourceSensor == SourceSensor.LIBRE_2_NATIVE ||
            newest.sourceSensor == SourceSensor.LIBRE_3
    }

    // Lowest stored glucose in the last hour, mg/dL. 999 when the hour has no reading,
    // so the Tier 3 low brake stays closed.
    private suspend fun recentLowBgMgdl(now: Long): Double =
        persistenceLayer.getBgReadingsDataFromTimeToTime(now - 60 * 60_000L, now, ascending = false)
            .minOfOrNull { it.value } ?: 999.0

    private data class UkfRaw(val glucose: Double?, val delta1: Double?, val delta5: Double?, val delta15: Double?)

    // Libre raw (noise) through the display UKF. Newest point is glucose. Deltas are mg/dL per 5 minutes.
    private suspend fun ukfRawNow(now: Long): UkfRaw {
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(now - 60 * 60_000L, now, ascending = false)
            .filter { (it.noise ?: 0.0) > 10.0 }
            .sortedByDescending { it.timestamp }
        if (readings.isEmpty()) return UkfRaw(null, null, null, null)
        val smoothed = displayRawSmoothing.smoothForDisplay(readings.map { it.timestamp to it.noise!! })
        if (smoothed.isEmpty()) return UkfRaw(null, null, null, null)
        val glucose = smoothed[0]
        val delta1 = if (smoothed.size >= 2) {
            val minutes = (readings[0].timestamp - readings[1].timestamp) / 60_000.0
            if (minutes > 0.0) (smoothed[0] - smoothed[1]) / minutes * 5.0 else null
        } else null
        val fiveMinAgo = now - 5 * 60_000L
        val ref5 = readings.indices.minByOrNull { abs(readings[it].timestamp - fiveMinAgo) }
        val delta5 = ref5?.takeIf { it != 0 }?.let { glucose - smoothed[it] }
        val fifteenMinAgo = now - 15 * 60_000L
        val ref15 = readings.indices.minByOrNull { abs(readings[it].timestamp - fifteenMinAgo) }
        val delta15 = ref15?.takeIf { it != 0 }?.let { idx ->
            val anchor = readings[idx].timestamp
            if (abs(anchor - fifteenMinAgo) > 3 * 60_000L) return@let null
            val mins = (readings[0].timestamp - anchor) / 60_000.0
            if (mins > 0.0) (glucose - smoothed[idx]) / mins * 5.0 else null
        }
        return UkfRaw(glucose, delta1, delta5, delta15)
    }

    // mg/dL over about five minutes, from the Libre raw value stored in GV.noise.
    // FastRise sizing and the night FastRise skip use the smoothed UKF change instead.
    // This unsmoothed value is the early-morning reversal check. Null when the raw value is missing.
    private suspend fun rawDelta5MinMgdl(now: Long): Double? {
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(now - 7 * 60 * 1000L, now, ascending = false)
        if (readings.size < 2) return null
        val newest = readings[0].noise ?: return null
        val fiveMinAgo = now - 5 * 60 * 1000L
        val reference = readings.minByOrNull { abs(it.timestamp - fiveMinAgo) } ?: return null
        if (reference.timestamp == readings[0].timestamp) return null
        val previous = reference.noise ?: return null
        return newest - previous
    }

    // One-minute Libre raw change, mg/dL. Null when the raw value is missing.
    private suspend fun rawDelta1MinMgdl(now: Long): Double? {
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(now - 3 * 60 * 1000L, now, ascending = false)
        if (readings.size < 2) return null
        val newest = readings[0].noise ?: return null
        val oneMinAgo = now - 60_000L
        val reference = readings.minByOrNull { abs(it.timestamp - oneMinAgo) } ?: return null
        if (reference.timestamp == readings[0].timestamp) return null
        val previous = reference.noise ?: return null
        return newest - previous
    }

    // 15-minute Libre raw change, scaled to mg/dL per five minutes. Null when the raw value is missing.
    private suspend fun rawDelta15MinMgdl(now: Long): Double? {
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(now - 17 * 60 * 1000L, now, ascending = false)
        if (readings.size < 2) return null
        val newest = readings[0].noise ?: return null
        val fifteenMinAgo = now - 15 * 60 * 1000L
        val reference = readings.minByOrNull { abs(it.timestamp - fifteenMinAgo) } ?: return null
        if (reference.timestamp == readings[0].timestamp) return null
        val previous = reference.noise ?: return null
        return (newest - previous) / 3.0
    }

    // mg/dL per five minutes, from the two newest calibrated values.
    private suspend fun aapsDelta1MinMgdl(now: Long): Double? {
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(now - 3 * 60 * 1000L, now, ascending = false)
        if (readings.size < 2) return null
        val minutes = (readings[0].timestamp - readings[1].timestamp) / 60_000.0
        if (minutes <= 0.0) return null
        return (readings[0].value - readings[1].value) / minutes * 5.0
    }

}

/** The four AutoISF factors from one call of autoISF, when that call actually worked them out. */
class AutoIsfFactors {
    var recorded: Boolean = false
    var acceIsf: Double = 1.0
    var bgIsf: Double = 1.0
    var ppIsf: Double = 1.0
    var duraIsf: Double = 1.0
    var finalIsf: Double = 1.0
    var glucose: Double = 0.0
    var delta: Double = 0.0
    var shortAvgDelta: Double = 0.0
    var longAvgDelta: Double = 0.0
    var bgAcceleration: Double = 0.0

    fun record(
        acce: Double,
        bg: Double,
        pp: Double,
        dura: Double,
        finalFactor: Double,
        status: GlucoseStatusAutoIsf,
    ) {
        recorded = true
        acceIsf = acce
        bgIsf = bg
        ppIsf = pp
        duraIsf = dura
        finalIsf = finalFactor
        glucose = status.glucose
        delta = status.delta
        shortAvgDelta = status.shortAvgDelta
        longAvgDelta = status.longAvgDelta
        bgAcceleration = status.bgAcceleration
    }
}