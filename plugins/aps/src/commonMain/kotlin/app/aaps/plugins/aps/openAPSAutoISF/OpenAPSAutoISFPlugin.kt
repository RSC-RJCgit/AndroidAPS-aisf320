package app.aaps.plugins.aps.openAPSAutoISF

import androidx.collection.LongSparseArray
import androidx.collection.forEach
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.AIV
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TE
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
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
    private val tddCalculator: TddCalculator
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
        val smb10 = smbSum(now, 10 * 60 * 1000L)
        val sub75Note = updateSub75Mark(runMarks, now, glucoseStatus.glucose, glucoseStatus.delta, smb10)
        if (sub75Note == "arm") aapsLogger.debug(LTag.APS, "sc7.5 cooldown armed, 10 min SMB $smb10")
        if (sub75Note == "clear") aapsLogger.debug(LTag.APS, "sc7.5 cooldown cleared")
        val uamRecent = runMarks.recent(RunMark.UAM_BST, 20, now)
        val minuteOfDay = Instant.fromEpochMilliseconds(now).toLocalDateTime(TimeZone.currentSystemDefault()).let { it.hour * 60 + it.minute }
        val stepSample = persistenceLayer.getLastStepsCountFromTimeToTime(now - 30 * 60 * 1000L, now)
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
                rawDelta5 = raw5 ?: -9999.0,
                iob = iobData.iob,
                smbSum10 = smb10,
                lowBgRecent = statesOn && states().inState("LowBG", "50recent"),
                mjActive = statesOn && states().inState("MJ", "MJ active"),
                steps5 = stepSample?.steps5min ?: 0,
                steps30 = stepSample?.steps30min ?: 0,
            )
        ) {
            runMarks.mark(RunMark.NIGHT_FR_SKIP, now)
            aapsLogger.debug(LTag.APS, "NightFrSkip marked")
        }
        applyDeliveryRestore(
            now = now,
            tempTargetSet = isTempTarget,
            mealCob = mealData.mealCOB,
        )
        markBolusBoosts(
            now = now,
            profilePercent = profile_percentage,
            tempTargetSet = isTempTarget,
            minuteOfDay = minuteOfDay,
            bg = glucoseStatus.glucose,
            delta = glucoseStatus.delta,
            shortDelta = glucoseStatus.shortAvgDelta,
            longDelta = glucoseStatus.longAvgDelta,
            rawDelta5 = raw5 ?: -9999.0,
            cob = mealData.mealCOB,
            iob = iobData.iob,
            statesOn = statesOn,
            steps5 = stepSample?.steps5min ?: 0,
            steps30 = stepSample?.steps30min ?: 0,
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
            rawDelta5Mgdl = raw5 ?: 0.0,
            aapsDelta1Mgdl = aapsDelta1MinMgdl(now) ?: 0.0,
            hour = Instant.fromEpochMilliseconds(now).toLocalDateTime(TimeZone.currentSystemDefault()).hour,
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
                rawDelta5 = raw5,
                longAvgDelta = glucoseStatus.longAvgDelta,
            ),
            nightFrSkipActive = runMarks.recent(RunMark.NIGHT_FR_SKIP, 2, now),
            uamBoostRecent = uamRecent,
            uamBstMinutesAgo = runMarks.minutesAgo(RunMark.UAM_BST, now) ?: Int.MAX_VALUE,
            sub75Cooldown = runMarks.recent(RunMark.SUB75, 10, now),
            smbIntervalSec = smbInterval5Sec(now),
            smbStackStart = preferences.get(LongNonKey.ApsAutoIsfSmbStackStart),
        ).also {
            determineBasalAutoISF.smbStackStartToStore?.let { start ->
                preferences.put(LongNonKey.ApsAutoIsfSmbStackStart, start)
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
        val stepSample = persistenceLayer.getLastStepsCountFromTimeToTime(nowMs - 60 * 60 * 1000L, nowMs)
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
            BooleanKey.ApsAutoIsfBoostAutomationsEnabled,
            BooleanKey.ApsAutoIsfCustomAutomationsEnabled,
            DoubleKey.ApsAutoIsfSmbDeliveryBaseline,
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

    // Steps a watch stored in the last hour. No sample means 0, so the quiet morning cap can apply.
    private suspend fun steps60(now: Long): Int =
        persistenceLayer.getLastStepsCountFromTimeToTime(now - 60 * 60 * 1000L, now)?.steps60min ?: 0

    private suspend fun steps30(now: Long): Int =
        persistenceLayer.getLastStepsCountFromTimeToTime(now - 30 * 60 * 1000L, now)?.steps30min ?: 0

    private suspend fun steps5(now: Long): Int =
        persistenceLayer.getLastStepsCountFromTimeToTime(now - 5 * 60 * 1000L, now)?.steps5min ?: 0

    private suspend fun steps15(now: Long): Int =
        persistenceLayer.getLastStepsCountFromTimeToTime(now - 15 * 60 * 1000L, now)?.steps15min ?: 0

    // The 3-hour count is stored on the sample, so the lookback is the sample age, not a second sum.
    private suspend fun steps180(now: Long): Int =
        persistenceLayer.getLastStepsCountFromTimeToTime(now - 180 * 60 * 1000L, now)?.steps180min ?: 0

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
            )
        ) {
            preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryRatio, resting)
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
            aapsLogger.debug(LTag.APS, "SMB delivery ratio down to $hardStackTarget while SMBs are stacking")
        }
    }

    // Marks BolusGiven, BolusGivenBg3, or BolusGivenMild when the 3.2.1 rise gates pass.
    // A strong mark raises the IOB threshold to 71 and, unless caution applies, the profile percent to 110 for 2 minutes.
    // Both marks raise the post-meal weight. A value that is not above its baseline is left alone.
    // The acceleration weight and the SMB delivery ratio are not written.
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
    ) {
        seedBaselines(profilePercent)
        val boostOn = preferences.get(BooleanKey.ApsAutoIsfBoostAutomationsEnabled)
        val baseline = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline)
        val profileName = profileFunction.getOriginalProfileName()
        val lowName = preferences.get(StringKey.ApsAutoIsfLowProfileName)
        val onLowProfile = profileName == lowName
        val mjActive = statesOn && states().inState("MJ", "MJ active")
        val rawDelta1 = rawDelta1MinMgdl(now) ?: -9999.0
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
        if (bg3 && !blocked) {
            runMarks.mark(RunMark.BOLUS_GIVEN, now)
            runMarks.mark(RunMark.BOLUS_GIVEN_BG3, now)
            applyBoostRaise(boostRaises(strong = true, caution = caution))
            aapsLogger.debug(LTag.APS, "BolusGiven bg3 marked")
        } else if (mild) {
            runMarks.mark(RunMark.BOLUS_GIVEN_MILD, now)
            applyBoostRaise(boostRaises(strong = false, caution = false))
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
            applyBoostRaise(boostRaises(strong = false, caution = false))
            aapsLogger.debug(LTag.APS, "BolusGivenMildFailsafe marked")
        } else if (blocked) {
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
            aapsLogger.debug(LTag.APS, "Extra50 block $extraBlock")
        }
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
            earlyAfterShower = false,
        )
        if (usualBlock != null) {
            runMarks.mark(RunMark.USUAL2, now)
            applyUsual2(now)
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
        aapsLogger.debug(LTag.APS, "High night -> $standardName for 30 min")
    }

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
        aapsLogger.debug(LTag.APS, "Activity off -> $standardName at 100%")
    }

    // Switches to [profileName] at 100% for [minutes]. Returns false when the name is missing.
    private suspend fun switchToStandardFor(profileName: String, minutes: Int, now: Long): Boolean {
        if (profileName.isBlank()) return false
        val iCfg = profileFunction.getRunningOrRequestedICfg() ?: return false
        val store = profileRepository.profile.value ?: return false
        if (store.getSpecificProfile(profileName) == null) return false
        return profileFunction.createProfileSwitch(
            profileStore = store,
            profileName = profileName,
            durationInMinutes = minutes,
            percentage = 100,
            timeShiftInHours = 0,
            timestamp = now,
            action = Action.PROFILE_SWITCH,
            source = Sources.Automation,
            note = "AutoISF: high night",
            listValues = listOf(
                ValueWithUnit.SimpleString(profileName),
                ValueWithUnit.Percent(100),
                ValueWithUnit.Minute(minutes)
            ),
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
    private suspend fun applyBoostRaise(raise: BoostRaise) {
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
    // A Usual2 mark in the last 90 minutes opens that same window. Shower12 is not written, so that early path stays closed.
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

    // mg/dL over about five minutes, from the Libre raw value stored in GV.noise.
    // Null when that raw value is missing. The caller then passes 0, so the FastRise gate stays closed.
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