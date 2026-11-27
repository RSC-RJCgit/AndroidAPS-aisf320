package app.aaps.core.objects.wizard

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.format.NumberFormat
import app.aaps.core.data.model.BCR
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.BolusWizardData
import app.aaps.core.data.model.IDs
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.time.AppTimeZone
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.data.ui.ConfirmationLine
import app.aaps.core.data.ui.ConfirmationRole
import app.aaps.core.data.ui.confirmationLines
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.alerts.EatReminder
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.bolus.MasterActionExecutor
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.aps.IsfForCarbs
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.objects.runningMode.PumpCommandGate
import app.aaps.core.objects.runningMode.RunningModeGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Clock

class BolusWizard(
    private val aapsLogger: AAPSLogger,
    private val rh: TextResolver,
    private val preferences: Preferences,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val constraintChecker: ConstraintsChecker,
    private val loop: Loop,
    private val iobCobCalculator: IobCobCalculator,
    private val dateUtil: DateUtil,
    private val config: Config,
    private val uel: UserEntryLogger,
    private val automation: Automation,
    private val eatReminder: EatReminder,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val persistenceLayer: PersistenceLayer,
    private val runningModeGuard: RunningModeGuard,
    private val ch: ConcentrationHelper,
    private val masterActionExecutor: MasterActionExecutor,
    private val activePlugin: ActivePlugin,
    private val appScope: CoroutineScope
) {

    var timeStamp = dateUtil.now()

    // Intermediate
    var sens = 0.0
        private set
    var ic = 0.0
        private set
    var glucoseStatus: GlucoseStatus? = null
        private set
    private var targetBGLow = 0.0
    private var targetBGHigh = 0.0
    private var bgDiff = 0.0
    var insulinFromBG = 0.0
        private set
    var insulinFromCarbs = 0.0
        private set
    var insulinFromBolusIOB = 0.0
        private set
    var insulinFromBasalIOB = 0.0
        private set
    var insulinFromCorrection = 0.0
        private set
    var insulinFromSuperBolus = 0.0
        private set
    var insulinFromCOB = 0.0
        private set
    var insulinFromTrend = 0.0
    /** True when a rising trend was left out because the carbs were eaten earlier (see doCalc). */
    var trendLeftOut = false
        private set
    var trend = 0.0
        private set

    private var accepted = false

    // Result
    var calculatedTotalInsulin: Double = 0.0
        private set

    // Raw sum before the negative-total clamp (calculatedTotalInsulin = 0.0 branch); equals
    // calculatedTotalInsulin when non-negative. Used by the wear correction buttons so they
    // spend the right number of steps recovering to 0 before going positive.
    var unclampedCalculatedInsulin: Double = 0.0
        private set
    var totalBeforePercentageAdjustment: Double = 0.0
        private set
    var carbsEquivalent: Double = 0.0
        private set
    var insulinAfterConstraints: Double = 0.0
        private set
    var calculatedPercentage: Int = 100
        private set
    var calculatedCorrection: Double = 0.0
        private set
    private var carbsHalvedByRecent50: Boolean = false
    var warsawPlan: WarsawFpuPlan? = null
        private set
    /** The protein/fat inputs of the last [doCalc], so [sizeWarsawAtStandard] can size the plan again at another ratio. */
    private data class WarsawInputs(val protein: Int, val fat: Int, val durationHours: Double, val intervalMinutes: Int)
    private var warsawInputs: WarsawInputs? = null

    /**
     * On a 50% profile (2026-11-17, per explicit request, as the 3426 app), sizes the protein/fat series at the Standard
     * profile's 100% carb ratio, as the delayed bolus sizes what it held back. Null keeps this profile's ratio with the 50%
     * taken off. Call right after [doCalc], before anything adds later units to the plan.
     */
    fun sizeWarsawAtStandard(standardIc: Double?) {
        if (activeProfilePct != 50 || warsawPlan == null) return
        val inputs = warsawInputs ?: return
        val useIc = standardIc?.takeIf { it > 0.0 } ?: return
        warsawPlan = warsawFpuPlan(inputs.protein, inputs.fat, useIc, inputs.durationHours, inputs.intervalMinutes)
            ?.copy(sizingIc = useIc, sizingProfile = preferences.get(StringKey.ApsAutoIsfStandardProfileName).trim())
        aapsLogger.info(LTag.CORE, "Warsaw at 50% profile: sized at the Standard IC $useIc, ${warsawPlan?.totalInsulin} U")
    }

    // 2026-11-28, per explicit request (same as 3426): what this wizard's carb split and protein/fat parts were sized at (IC at
    // 100% and its profile), for their resize when the profile changed by the time they are due (see ProfileIcResize). Saved
    // with each pending part. 0 / "" never resizes.
    private var splitSizingIc = 0.0
    private var splitSizingProfile = ""
    private var fpuSizingIc = 0.0
    private var fpuSizingProfile = ""

    /** [profileIcResizeFactor] against the running profile's IC at 100% at the bolus's time of day, with its note text. */
    private suspend fun partIcResize(sizingProfile: String, sizingIc: Double, upOnly: Boolean = false): Pair<Double, String> {
        val profile = profileFunction.getProfile() ?: return 1.0 to ""
        val runningIc = profile.getIcTimeFromMidnight(MidnightTime.secondsFromMidnight(timeStamp, AppTimeZone.current())) *
            profileSwitchPercent(profile) / 100.0
        val running = profileFunction.getOriginalProfileName()
        val factor = profileIcResizeFactor(sizingProfile, sizingIc, running, runningIc, upOnly)
        if (factor != 1.0) aapsLogger.info(LTag.CORE, "Follow-up resize x$factor: sized at IC $sizingIc on $sizingProfile, running $running IC $runningIc")
        return factor to profileIcResizeNote(factor, sizingProfile, running)
    }
    var warsawIobBaseline: Double = 0.0
        private set

    /** Set by the dialog: a protein or fat dose that fails a check at its hour is checked again every 10 minutes instead of being dropped. */
    var warsawRetryLater: Boolean = true
    private var hpSafetyApplied: Boolean = false
    private var hpSafetyOriginal: Double = 0.0
    private var hpSafetyAdjusted: Double = 0.0
    private var hpSafetyCobRemoved: Double = 0.0
    var walkingSoonHeldUnits: Double = 0.0
        private set
    var rapidFallForSplit: Boolean = false
        private set
    /** Give the dose over max bolus later, in parts (v3's split box, 2026-10-22). Off gives max bolus only. */
    var splitOverMax: Boolean = true
    /** Minutes between the usual split parts. */
    var splitIntervalMinutes: Int = SPLIT_LEFTOVER_INTERVAL_MINUTES
    /**
     * Insulin the falling-BG caution held back (its halved wizard percent), given with the split parts so the follow-up
     * reaches the normal wizard percent (2026-11-14, per explicit request). See [holdForFollowUp].
     */
    var splitHeldUnits: Double = 0.0
        private set
    /** The calculator's own dose when [useFixedInsulin] replaced it, else null. */
    var suggestedInsulin: Double? = null
        private set
    /**
     * The low-BG recent-entry rule ([WizardRecentEntry], from the 3426 app, 2026-11-14) held at this calculation: BG under 6.0
     * since a bolus or carbs in the last hour. Only reported here; the Bolus Wizard starts its COB box unticked and a
     * QuickWizard press cuts its max bolus to 80% from it.
     */
    var lowBgRecentEntryRule: Boolean = false
        private set
    /** Profile percent and the calculator's immediate percent at this calculation, for the delayed bolus. */
    private var activeProfilePct: Int = 100
    private var immediatePercent: Double = 100.0
    /** Carb insulin held back for a [DelayedBolus], set by [prepareDelayedBolus]; null when nothing is held back. */
    var delayedPlan: DelayedBolusPlan? = null
        private set

    /** Immutable snapshot of the computed result, built at the end of [doCalc] (additive — the legacy
     *  fields above stay). The shared bolus path consumes this instead of reaching into individual fields. */
    lateinit var data: BolusWizardData
        private set

    // Input
    lateinit var profile: Profile
    lateinit var profileName: String
    var tempTarget: TT? = null
    var carbs: Int = 0
    var cob: Double = 0.0
    var bg: Double = 0.0
    private var correction: Double = 0.0
    var percentageCorrection: Int = 100
    private var totalPercentage: Double = 100.0
    private var useBg: Boolean = false
    private var useCob: Boolean = false
    private var includeBolusIOB: Boolean = false
    private var includeBasalIOB: Boolean = false
    private var useSuperBolus: Boolean = false
    private var useTT: Boolean = false
    private var useTrend: Boolean = false
    private var useAlarm = false
    var notes: String = ""
    // Readable since 2026-11-14: a QuickWizard press can date its carbs from a rise (CarbTimeFromRise), so the commit takes
    // the carb time from here rather than from the button.
    var carbTime: Int = 0
        private set
    var usePercentage: Boolean = false
    var positiveIOBOnly: Boolean = false
    private var source: Sources = Sources.WizardDialog

    suspend fun doCalc(
        profile: Profile,
        profileName: String,
        tempTarget: TT?,
        carbs: Int,
        cob: Double,
        bg: Double,
        correction: Double,
        percentageCorrection: Int = 100,
        useBg: Boolean,
        useCob: Boolean,
        includeBolusIOB: Boolean,
        includeBasalIOB: Boolean,
        useSuperBolus: Boolean,
        useTT: Boolean,
        useTrend: Boolean,
        useAlarm: Boolean,
        notes: String = "",
        carbTime: Int = 0,
        usePercentage: Boolean = false,
        totalPercentage: Double = 100.0,
        positiveIOBOnly: Boolean = false,
        source: Sources = Sources.WizardDialog,
        protein: Int = 0,
        fat: Int = 0,
        warsawDurationHours: Double = 5.0,
        walkingSoon: Boolean = false,
        fpuIntervalMinutes: Int = 30,
    ): BolusWizard {

        this.profile = profile
        this.profileName = profileName
        this.tempTarget = tempTarget
        this.carbs = carbs
        this.cob = cob
        this.bg = bg
        this.correction = correction
        this.percentageCorrection = percentageCorrection
        this.useBg = useBg
        this.useCob = useCob
        this.includeBolusIOB = includeBolusIOB
        this.includeBasalIOB = includeBasalIOB
        this.useSuperBolus = useSuperBolus
        this.useTT = useTT
        this.useTrend = useTrend
        this.useAlarm = useAlarm
        this.notes = notes
        this.carbTime = carbTime
        this.usePercentage = usePercentage
        this.totalPercentage = totalPercentage
        this.positiveIOBOnly = positiveIOBOnly
        this.source = source

        // Insulin from BG. Above 100% this uses the 100% sensitivity. The dialog percent is not changed.
        val activePct = if (profile is ProfileSealed.EPS) profile.value.originalPercentage else profile.percentage
        val baseScale = wizardProfileBaseScale(activePct)
        val now = dateUtil.now()
        // 2026-11-24, per explicit request: last 3 h of loop ISF, only values not above the profile ISF,
        // else the profile ISF. The 24 h ISF for carbs (still used for COB) averaged in lows and gave 7.9
        // against a working ISF of about 2.3 mmol/U during a high. See IsfForCarbs.recentAtOrBelowProfileMgdl.
        sens = profileUtil.fromMgdlToUnits(IsfForCarbs.load(persistenceLayer, now, now).wizardIsfMgdl(profile, now)) * baseScale
        targetBGLow = profileUtil.fromMgdlToUnits(profile.getTargetLowMgdl())
        targetBGHigh = profileUtil.fromMgdlToUnits(profile.getTargetHighMgdl())
        if (useTT && tempTarget != null) {
            targetBGLow = profileUtil.fromMgdlToUnits(tempTarget.lowTarget)
            targetBGHigh = profileUtil.fromMgdlToUnits(tempTarget.highTarget)
        }
        if (useBg && bg > 0) {
            bgDiff = when {
                bg in targetBGLow..targetBGHigh -> 0.0
                bg <= targetBGLow               -> bg - targetBGLow
                else                            -> bg - targetBGHigh
            }
            insulinFromBG = bgDiff / sens
        }

        // Insulin from 15 min trend
        glucoseStatus = glucoseStatusProvider.glucoseStatusData
        rapidFallForSplit = splitRapidFall(glucoseStatus?.delta, glucoseStatus?.shortAvgDelta, glucoseStatus?.longAvgDelta)
        glucoseStatus?.let {
            if (useTrend) {
                trend = it.shortAvgDelta
                insulinFromTrend = profileUtil.fromMgdlToUnits(trend) * 3 / sens
                // 2026-10-30, per explicit request: carbs eaten earlier (a minus carb time) already make part of a rising
                // trend, while the carbs part still counts all of them. A rising trend is left out then; a falling one stays.
                if (carbs > 0 && carbTime < 0 && insulinFromTrend > 0.0) {
                    trendLeftOut = true
                    insulinFromTrend = 0.0
                }
            }
        }

        // Insulin from carbs. Above 100% this uses the 100% carb ratio.
        // A recent low halves only this carb insulin.
        // Protein and fat stay out of the immediate bolus. They are spread later, one dose per hour.
        ic = profile.getIc() * baseScale
        // 2026-11-17, per explicit request (as the 3426 app): a 50% profile keeps the protein/fat series. It is sized here at
        // this profile's own ratio with the 50% taken off, and [sizeWarsawAtStandard] then resizes it at the Standard profile.
        warsawInputs = WarsawInputs(protein, fat, warsawDurationHours, fpuIntervalMinutes)
        warsawPlan = warsawFpuPlan(protein, fat, if (activePct == 50) ic / 2.0 else ic, warsawDurationHours, fpuIntervalMinutes)
            // 2026-11-28, per explicit request: what the series is sized at, for its resize when due (see ProfileIcResize)
            ?.copy(sizingIc = if (activePct == 50) ic / 2.0 else ic, sizingProfile = profileFunction.getOriginalProfileName())
        if (!warsawAllowedAt(activePct) || useSuperBolus || loop.runningMode() == RM.Mode.SUPER_BOLUS) warsawPlan = null
        insulinFromCarbs = carbs / ic
        val status = glucoseStatus
        val wizardBgMgdl = if (bg > 0.0) profileUtil.convertToMgdl(bg, profile.units) else 0.0
        carbsHalvedByRecent50 = recent50ShouldHalveCarbs(
            profilePercent = activePct,
            lowBgRecent = lowBgIsRecent50(
                preferences.get(BooleanKey.AutomationStatesEnabled),
                preferences.get(StringNonKey.AutomationCurrentStates),
            ),
            wizardBgMgdl = wizardBgMgdl,
            glucoseMgdl = status?.glucose ?: 0.0,
            delta = status?.delta ?: 0.0,
            shortDelta = status?.shortAvgDelta ?: 0.0,
            longDelta = status?.longAvgDelta ?: 0.0,
            hasGlucose = status != null,
        )
        if (carbsHalvedByRecent50) insulinFromCarbs /= 2.0
        // From the 3426 app (2026-11-14): only noted here. BG is the wizard BG when one is used, else the latest CGM value.
        lowBgRecentEntryRule = WizardRecentEntry.applies(persistenceLayer, now, if (wizardBgMgdl > 0.0) wizardBgMgdl else status?.glucose)
        activeProfilePct = activePct
        delayedPlan = null
        insulinFromCOB = if (useCob) (cob / ic) else 0.0

        // Insulin from IOB calculation
        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()

        insulinFromBolusIOB = if (includeBolusIOB) bolusIob.iob else 0.0
        insulinFromBasalIOB = if (includeBasalIOB) basalIob.basaliob else 0.0

        var calculatedTotalIOB = insulinFromBolusIOB + insulinFromBasalIOB
        calculatedTotalIOB = if (positiveIOBOnly && calculatedTotalIOB < 0.0) 0.0 else -calculatedTotalIOB

        // Insulin from correction
        insulinFromCorrection = if (usePercentage) 0.0 else correction

        // Insulin from superbolus for 2h. Get basal rate now and after 1h
        if (useSuperBolus) {
            insulinFromSuperBolus = profile.getBasal()
            var timeAfter1h = Clock.System.now().toEpochMilliseconds()
            timeAfter1h += T.hours(1).msecs()
            insulinFromSuperBolus += profile.getBasal(timeAfter1h)
        }

        // Total
        // Suggestion components (BG, Trend, Carbs, COB) are scaled by percentage.
        // Fact components (IOB, Direct Correction) are NOT scaled — they represent
        // actual measured or explicit values, not wizard suggestions.
        // This prevents the compounding overdose bug when splitting carb entries (#2561):
        // previously, IOB was scaled down by percentage, under-counting active insulin.
        val scaledComponents = insulinFromBG + insulinFromTrend + insulinFromCarbs + insulinFromCOB
        val unscaledComponents = calculatedTotalIOB + insulinFromCorrection

        val standingPct = if (usePercentage) totalPercentage else percentageCorrection.toDouble()
        val percentage = walkingSoonImmediatePercent(walkingSoon, standingPct)
        immediatePercent = percentage
        val walkingSoonCutApplied = walkingSoon && percentage < standingPct - 0.5
        this.walkingSoonHeldUnits = walkingSoonHeldUnits(walkingSoon, standingPct, scaledComponents)

        totalBeforePercentageAdjustment = scaledComponents + unscaledComponents
        calculatedTotalInsulin = scaledComponents * percentage / 100.0 + unscaledComponents
        var preClamp = calculatedTotalInsulin  // save before constraint calcs or negative clamp

        // Percentage adjustment
        if (calculatedTotalInsulin >= 0) {
            if (usePercentage)
                calcCorrectionWithConstraints()
            else
                calcPercentageWithConstraints()
            if (usePercentage)  //Should be updated after calcCorrectionWithConstraints and calcPercentageWithConstraints to have correct synthesis in WizardInfo
                this.percentageCorrection = Round.roundTo(totalPercentage, 1.0).toInt()
            preClamp = calculatedTotalInsulin  // update after constraint calcs (may have changed)
        } else {
            carbsEquivalent = (-calculatedTotalInsulin) * ic
            calculatedTotalInsulin = 0.0
            calculatedPercentage = percentageCorrection
            calculatedCorrection = 0.0
            // preClamp stays as the original negative value
        }

        val positiveIob = (bolusIob.iob + basalIob.basaliob).coerceAtLeast(0.0)
        val bgMgdl = if (bg > 0.0) profileUtil.convertToMgdl(bg, profile.units) else 0.0
        val deltaMgdl = glucoseStatus?.delta ?: 0.0
        val shortMgdl = glucoseStatus?.shortAvgDelta ?: 0.0
        val bgMmol = bgMgdl * Constants.MGDL_TO_MMOLL
        val deltaMmol = deltaMgdl * Constants.MGDL_TO_MMOLL
        val shortMmol = shortMgdl * Constants.MGDL_TO_MMOLL
        val hp = wizardHpCut(
            dose = calculatedTotalInsulin,
            insulinFromCob = insulinFromCOB,
            percentage = percentage,
            projectedHp = wizardProjectedHp(bgMgdl, positiveIob, calculatedTotalInsulin, deltaMgdl, shortMgdl),
            bgMmol = bgMmol,
            deltaMmol = deltaMmol,
            shortDeltaMmol = shortMmol,
            positiveIob = positiveIob,
        )
        hpSafetyApplied = hp.applied
        hpSafetyCobRemoved = hp.cobRemoved
        if (hp.applied) {
            hpSafetyOriginal = calculatedTotalInsulin
            calculatedTotalInsulin = hp.dose
            hpSafetyAdjusted = calculatedTotalInsulin
        }
        // The Wz133 rise boost (x1.33 when BG > 8 and rising) was removed on 2026-10-16, per explicit request.
        if (preClamp >= 0.0) preClamp = calculatedTotalInsulin

        // The step of the pump, converted by the concentration, so the rounded value matches the pump grid.
        val bolusStep = ch.bolusStep(calculatedTotalInsulin)
        calculatedTotalInsulin = Round.roundTo(calculatedTotalInsulin, bolusStep)
        unclampedCalculatedInsulin = Round.roundTo(preClamp, bolusStep)

        insulinAfterConstraints = constraintChecker.applyBolusConstraints(ConstraintObject(calculatedTotalInsulin, aapsLogger)).value()
        warsawIobBaseline = (insulinFromBolusIOB + insulinFromBasalIOB).coerceAtLeast(0.0) + insulinAfterConstraints

        data = BolusWizardData(
            timeStamp = timeStamp,
            carbs = carbs,
            cob = cob,
            sens = sens,
            ic = ic,
            trend = trend,
            insulinFromBG = insulinFromBG,
            insulinFromCarbs = insulinFromCarbs,
            insulinFromBolusIOB = insulinFromBolusIOB,
            insulinFromBasalIOB = insulinFromBasalIOB,
            insulinFromCorrection = insulinFromCorrection,
            insulinFromSuperBolus = insulinFromSuperBolus,
            insulinFromCOB = insulinFromCOB,
            insulinFromTrend = insulinFromTrend,
            calculatedTotalInsulin = calculatedTotalInsulin,
            totalBeforePercentageAdjustment = totalBeforePercentageAdjustment,
            carbsEquivalent = carbsEquivalent,
            insulinAfterConstraints = insulinAfterConstraints,
            calculatedPercentage = calculatedPercentage,
            calculatedCorrection = calculatedCorrection,
            percentageCorrection = percentageCorrection,
            useBg = useBg,
            useCob = useCob,
            includeBolusIOB = includeBolusIOB,
            includeBasalIOB = includeBasalIOB,
            useTT = useTT,
            useTrend = useTrend,
            useSuperBolus = useSuperBolus
        )

        aapsLogger.debug(this.toString())
        return this
    }

    private fun calculatorNote(base: String, eCarbsGrams: Int, eCarbsDelayMinutes: Int, eCarbsDurationHours: Int): String {
        val extras = mutableListOf<String>()
        if (eCarbsGrams > 0) {
            extras.add(rh.gs(InterfacesStrings.wizard_carbs_split, carbs, eCarbsGrams))
            extras.add(rh.gs(InterfacesStrings.wizard_ecarbs, eCarbsGrams, eCarbsDurationHours, eCarbsDelayMinutes))
        }
        if (carbTime != 0) extras.add(rh.gs(InterfacesStrings.wizard_carbs_time, carbTime))
        if (trendLeftOut) extras.add(rh.gs(InterfacesStrings.wizard_trend_left_out, carbTime))
        if (carbsHalvedByRecent50) extras.add(rh.gs(InterfacesStrings.wizard_carbs_halved))
        if (lowBgRecentEntryRule) extras.add(rh.gs(InterfacesStrings.wizard_recent_entry))
        delayedPlan?.let { extras.add(rh.gs(InterfacesStrings.wizard_delayed_pending, it.heldUnits, it.heldBy.text, it.ic)) }
        if (hpSafetyApplied) extras.add(rh.gs(InterfacesStrings.wizard_hp_safety, hpSafetyOriginal, hpSafetyAdjusted, hpSafetyCobRemoved))
        warsawPlan?.let { plan ->
            extras.add(rh.gs(InterfacesStrings.wizard_warsaw_plan, plan.totalInsulin, plan.numDoses, plan.durationMinutes / 60))
        }
        // Saved with the calculation, so the report shows what was given at once and what follows in parts.
        if (calculatedTotalInsulin - insulinAfterConstraints >= 0.005 && insulinAfterConstraints > 0.0)
            extras.add(rh.gs(InterfacesStrings.wizard_given_now_max_bolus, insulinAfterConstraints))
        extras.addAll(splitLeftoverTexts())
        return extras.fold(base) { acc, line ->
            if (acc.isEmpty()) line else rh.gs(InterfacesStrings.wizard_notes_join, acc, line)
        }
    }

    /**
     * "Remaining x U, one part every n min" for the insulin over max bolus and the insulin held back for walking soon.
     * The confirm screen shows these lines and the calculation note saves them. Empty when no part will follow.
     */
    fun splitLeftoverTexts(): List<String> {
        if (!::profile.isInitialized || useSuperBolus || profileSwitchPercent(profile) < 100) return emptyList()
        val step = ch.bolusStep(max(insulinAfterConstraints, walkingSoonHeldUnits).coerceAtLeast(0.05))
        val aboveMax = if (splitOverMax) splitLeftover(calculatedTotalInsulin + splitHeldUnits, insulinAfterConstraints, step) ?: 0.0 else 0.0
        val walkLeft = if (walkingSoonHeldUnits >= step / 2.0) walkingSoonHeldUnits else 0.0
        val usualLeft = if (rapidFallForSplit) walkLeft else walkLeft + aboveMax
        val rapidLeft = if (rapidFallForSplit) aboveMax else 0.0
        return listOfNotNull(
            rh.gs(InterfacesStrings.wizard_split_leftover, usualLeft, splitIntervalMinutes).takeIf { usualLeft >= step / 2.0 },
            rh.gs(InterfacesStrings.wizard_split_leftover, rapidLeft, SPLIT_RAPID_FALL_INTERVAL_MINUTES).takeIf { rapidLeft >= step / 2.0 }
        )
    }

    /** Add insulin that the wizard held back, as later hourly doses. */
    fun addLaterUnits(units: Double) {
        warsawPlan = warsawPlanPlusUnits(warsawPlan, units)
    }

    /**
     * The insulin the falling-BG caution held back ("Give rest later"). 2026-11-14, per explicit request: when the split
     * runs (split box ticked, profile at 100% or more, no superbolus) it goes with the split parts, so the follow-up
     * reaches the normal wizard percent (e.g. 80%) instead of stopping at the halved one; each part keeps its own BG
     * check. Otherwise it is added to the later hourly doses, as before. Set [splitOverMax] first.
     * [withSplit] false (the calculator's "with the carb split" box unticked) always uses the hourly doses.
     */
    fun holdForFollowUp(units: Double, withSplit: Boolean = true) {
        if (units <= 0.0) return
        if (withSplit && splitOverMax && !useSuperBolus && ::profile.isInitialized && profileSwitchPercent(profile) >= 100) splitHeldUnits += units
        else addLaterUnits(units)
    }

    /**
     * Queue the protein and fat doses. Each one is checked again at its own hour, and is looked at every 2 minutes
     * until then, so a later confirm cancels the waiting series at once.
     *
     * When [retryLater] is on (the default), a dose that fails a check at its hour is not cancelled. It is checked
     * again every 10 minutes and given the first time every check passes. It is dropped only when the next re-check
     * would fall inside the last 10 minutes before the next dose is due, or 30 minutes after its own hour for the
     * last dose. Off, a failed check drops the dose, as before.
     *
     * Care portal notes: "D1.30" (the amount) the first time a dose is deferred, and "C1.30" when a dose is dropped.
     */
    /** [allowProfile50]: the calculator's series also runs on a 50% profile (2026-11-17); the Tier 3 automation's does not. */
    suspend fun scheduleWarsawDoses(plan: WarsawFpuPlan, iobBaseline: Double, source: Sources, retryLater: Boolean = true, allowProfile50: Boolean = false) {
        if (plan.numDoses <= 0) return
        if (loop.runningMode() == RM.Mode.SUPER_BOLUS) return
        val profile = profileFunction.getProfile() ?: return
        if (!warsawPercentOk(profileSwitchPercent(profile), allowProfile50)) return
        val token = WarsawScheduleGate.next()
        val scheduledAt = dateUtil.now()
        fpuSizingIc = plan.sizingIc
        fpuSizingProfile = plan.sizingProfile
        aapsLogger.info(
            LTag.CORE,
            "Warsaw: ${plan.numDoses} doses of ${plan.perDoseInsulin} U over ${plan.durationMinutes} min, retry=$retryLater"
        )
        val step = ch.bolusStep(plan.perDoseInsulin)
        val parts = (1..plan.numDoses).map { warsawPartUnits(plan.totalInsulin, plan.numDoses, it, step) }
        for (index in 1..plan.numDoses) {
            val planned = parts[index - 1]
            if (planned <= 0.0) continue
            val delayMin = warsawDoseDelayMinutes(index, plan.numDoses, plan.durationMinutes, plan.intervalMinutes)
            val firstDueAt = scheduledAt + delayMin * 60_000L
            val nextIndex = (index + 1..plan.numDoses).firstOrNull { parts[it - 1] > 0.0 }
            val nextDueAt = nextIndex?.let { scheduledAt + warsawDoseDelayMinutes(it, plan.numDoses, plan.durationMinutes, plan.intervalMinutes) * 60_000L }
            appScope.launch { runWarsawDose(planned, iobBaseline, source, token, firstDueAt, nextDueAt, retryLater, "fpu$index of ${plan.numDoses}", allowProfile50 = allowProfile50) }
        }
    }

    private suspend fun runWarsawDose(
        planned: Double,
        iobBaseline: Double,
        source: Sources,
        token: Long,
        firstDueAt: Long,
        nextDueAt: Long?,
        retryLater: Boolean,
        label: String,
        initialDueAt: Long = firstDueAt,
        allowProfile50: Boolean = false,
        deferralNotedAtStart: Boolean = false,
    ) {
        // 2026-11-22, per explicit request: saved while it waits, so it survives an app restart (see PendingFollowUps). A dose that
        // ends normally is removed; one the app was killed in stays saved and is taken back at the next start.
        val pendingKey = PendingFollowUps.newKey(token, "fpu")
        runWarsawDoseChecks(planned, iobBaseline, source, token, firstDueAt, nextDueAt, retryLater, label, initialDueAt, allowProfile50, deferralNotedAtStart, pendingKey)
        PendingFollowUps.remove(preferences, pendingKey)
    }

    private suspend fun runWarsawDoseChecks(
        planned: Double,
        iobBaseline: Double,
        source: Sources,
        token: Long,
        firstDueAt: Long,
        nextDueAt: Long?,
        retryLater: Boolean,
        label: String,
        initialDueAt: Long,
        allowProfile50: Boolean,
        deferralNotedAtStart: Boolean,
        pendingKey: String,
    ) {
        var dueAt = initialDueAt
        var deferralNoted = deferralNotedAtStart
        while (true) {
            if (trioLater == null) PendingFollowUps.save(preferences, pendingKey, pendingEntry("fpu", token, source) {
                put("planned", planned); put("iobBaseline", iobBaseline); put("firstDueAt", firstDueAt); nextDueAt?.let { put("nextDueAt", it) }
                put("retryLater", retryLater); put("label", label); put("dueAt", dueAt); put("allowProfile50", allowProfile50)
                put("deferralNoted", deferralNoted); put("fpuSizingIc", fpuSizingIc); put("fpuSizingProfile", fpuSizingProfile)
            })
            // Wait for the due time in steps of at most 2 minutes, so a newer confirm cancels this dose at once.
            while (true) {
                if (!WarsawScheduleGate.isCurrent(token)) {
                    // Only the note. A newer entry replacing the waiting series is not a dose that was calculated and
                    // dropped, so it gets no 0 U bolus row and no calculator record on the graph or in Treatments.
                    warsawNote("C", planned, "superseded by a newer entry", source, fpuWhat(label, planned))
                    return
                }
                // 2026-11-20, per explicit request: super heavy activity at an earlier part's due time cancelled this meal's FPU parts
                if (SuperHeavyFpuCancel.isCancelled(token)) {
                    val why = "super heavy activity (steps) earlier"
                    aapsLogger.info(LTag.CORE, "Warsaw dose $label: $why, cancelling $planned U")
                    warsawNote("C", planned, why, source, fpuWhat(label, planned))
                    recordCancelledDose(label, planned, why, iobBaseline, source, useIobRise = false)
                    return
                }
                val wait = dueAt - dateUtil.now()
                if (wait <= 0L) break
                delay(min(wait, 120_000L))
            }
            if (dateUtil.now() > warsawRetryDeadlineMs(firstDueAt, nextDueAt)) {
                val expired = "retry window expired"
                warsawNote("C", planned, expired, source, fpuWhat(label, planned))
                recordCancelledDose(label, planned, expired, iobBaseline, source, useIobRise = false)
                return
            }
            // 2026-11-20, per explicit request: only super heavy activity cancels; it cancels this part and the meal's parts
            // still waiting. Walking, activity and heavy activity only cut each part as it comes due (tryDeliverWarsawDose).
            if (superHeavyNow()) {
                PendingFollowUps.saveSuperHeavyCancel(preferences, token)
                val why = "super heavy activity (steps)"
                aapsLogger.info(LTag.CORE, "Warsaw dose $label: $why, cancelling $planned U and the FPU parts still waiting")
                warsawNote("C", planned, why, source, fpuWhat(label, planned))
                recordCancelledDose(label, planned, why, iobBaseline, source, useIobRise = false)
                return
            }
            // Unsaved before the dose can reach the pump, so an app killed during the bolus can never give it twice. A deferral
            // saves it again at the top of the loop.
            PendingFollowUps.remove(preferences, pendingKey)
            val reason = tryDeliverWarsawDose(planned, iobBaseline, source, allowProfile50) { remaining, nextBaseline ->
                appScope.launch {
                    val now = dateUtil.now()
                    if (warsawCanRetry(now, firstDueAt, nextDueAt)) {
                        runWarsawDose(remaining, nextBaseline, source, token, firstDueAt, nextDueAt, retryLater, label,
                            initialDueAt = now + WARSAW_RETRY_MINUTES * 60_000L, allowProfile50 = allowProfile50)
                    } else {
                        val expired = "insulinReq-limited remainder expired"
                        warsawNote("C", remaining, expired, source, fpuWhat(label, remaining))
                        recordCancelledDose(label, remaining, expired, nextBaseline, source, useIobRise = false)
                    }
                }
            } ?: return
            val now = dateUtil.now()
            // An IOB-rise hold (2026-11-20) waits and is checked again like the insulinReq cap
            val capWait = reason.startsWith("insulinReq") || reason.startsWith("IOB rose")
            if ((retryLater || capWait) && warsawCanRetry(now, firstDueAt, nextDueAt)) {
                aapsLogger.info(LTag.CORE, "Warsaw dose deferred: $reason, checking again in $WARSAW_RETRY_MINUTES min")
                if (!deferralNoted) {
                    warsawNote("D", planned, "checked again every $WARSAW_RETRY_MINUTES min: $reason", source, fpuWhat(label, planned))
                    deferralNoted = true
                }
                dueAt = now + WARSAW_RETRY_MINUTES * 60_000L
            } else {
                aapsLogger.info(LTag.CORE, "Warsaw dose dropped: $reason")
                val dropReason = if (retryLater || capWait) "$reason, not delivered within the retry window" else reason
                warsawNote("C", planned, dropReason, source, fpuWhat(label, planned))
                recordCancelledDose(label, planned, dropReason, iobBaseline, source, useIobRise = false)
                return
            }
        }
    }

    /**
     * 2026-10-19, per explicit request: the factor a delayed dose (FPU or carb split part) is multiplied by while walking,
     * see [delayedDoseWalkingFactor]. The cut part is dropped. No step row in the last 20 minutes gives 1 (not walking).
     * Watch rows hold S10/S15 as totals, so the bins are S10 - S5 and S15 - S10. Rows from Live's loop result
     * ("openaps://") already hold the bins.
     */
    private suspend fun walkingFactorNow(): Double =
        if (highFastRiseNow()) 1.0 else walkingStepsNow()?.let { delayedDoseWalkingFactor(it[0], it[1], it[2], it[3], it[4]) } ?: 1.0

    /** Super heavy activity now (see [delayedDoseSuperHeavy]); false with no step row in the last 20 minutes. */
    private suspend fun superHeavyNow(): Boolean =
        !highFastRiseNow() && (walkingStepsNow()?.let { delayedDoseSuperHeavy(it[0], it[1], it[2], it[3], it[4]) } ?: false)

    /** See [followUpHighFastRise]: no steps cut on a later dose during a high fast rise (2026-11-27). */
    private fun highFastRiseNow(): Boolean = glucoseStatusProvider.glucoseStatusData.let {
        followUpHighFastRise(it?.glucose, it?.delta, it?.shortAvgDelta)
    }

    /** S5, the S10 and S15 bins, S30, S60; null with no step row in the last 20 minutes. */
    private suspend fun walkingStepsNow(): List<Int>? {
        val now = dateUtil.now()
        val sc = persistenceLayer.getLastStepsCountFromTimeToTime(now - 20 * 60_000L, now) ?: return null
        val liveRow = sc.device.startsWith("openaps://")
        val s10Bin = if (liveRow) sc.steps10min else max(0, sc.steps10min - sc.steps5min)
        val s15Bin = if (liveRow) sc.steps15min else max(0, sc.steps15min - sc.steps10min)
        return listOf(sc.steps5min, s10Bin, s15Bin, sc.steps30min, sc.steps60min)
    }

    /** Null when the dose was handed to the pump. Otherwise the reason it was not given now. */
    private fun warsawPercentOk(percent: Int, allowProfile50: Boolean): Boolean =
        if (allowProfile50) warsawAllowedAt(percent) else percent >= 100

    private suspend fun tryDeliverWarsawDose(
        planned: Double, iobBaseline: Double, source: Sources, allowProfile50: Boolean, onCappedRemainder: (Double, Double) -> Unit
    ): String? {
        if (loop.runningMode() == RM.Mode.SUPER_BOLUS) return "superbolus active"
        val profile = profileFunction.getProfile() ?: return "no profile"
        val percent = profileSwitchPercent(profile)
        if (!warsawPercentOk(percent, allowProfile50)) return if (allowProfile50) "profile at $percent%" else "profile under 100%"
        val status = glucoseStatusProvider.glucoseStatusData
        if (!warsawDoseBgAllows(status?.glucose, status?.delta, status?.shortAvgDelta)) return "glucose check failed"
        val liveIob = iobCobCalculator.calculateFromTreatmentsAndTemps(dateUtil.now(), profile).iob
        // 2026-11-20, per explicit request: wait while IOB rose more than FPU_IOB_RISE_HOLD_U in the last 15 minutes
        val iob15MinAgo = iobCobCalculator.calculateFromTreatmentsAndTemps(dateUtil.now() - 15 * 60_000L, profile).iob
        if (fpuIobRiseHolds(liveIob, iob15MinAgo)) return "IOB rose ${Round.roundTo(liveIob - iob15MinAgo, 0.01)} U in 15 min"
        val step = ch.bolusStep(planned)
        // 2026-10-18, per explicit request: no IOB-rise cut. The insulinReq cap below already counts all insulin on board
        // (less the same-cycle SMB), so cutting an IOB rise as well counted it twice.
        // 2026-10-19, per explicit request: cut while walking. The cut part is dropped: capRemaining below uses the cut dose.
        // 2026-11-28, per explicit request: [planned] stays in the units it was sized at; on another profile it is resized for
        // this dose (see ProfileIcResize). What the cap leaves goes back in the sized units.
        val (icFactor, icNote) = partIcResize(fpuSizingProfile, fpuSizingIc)
        val sizedPlanned = Round.roundTo(planned * icFactor, step)
        if (icNote.isNotEmpty()) aapsLogger.info(LTag.CORE, "Warsaw dose:$icNote $planned U -> $sizedPlanned U")
        val walkFactor = walkingFactorNow()
        val walkingPlanned = if (walkFactor < 1.0) Round.roundTo(sizedPlanned * walkFactor, step) else sizedPlanned
        // 2026-11-20, per explicit request: the full insulinReq cap, not half the gap; the rest is checked again (onCappedRemainder)
        // 2026-11-26, per explicit request (same as 3426): no insulinReq cap at BG 7.5 or more with HP1 6.0 or more
        val dose = (if (followUpCapWaived()) walkingPlanned else cappedFollowUpDose(walkingPlanned, step, halfReduction = false))
            ?: return "insulinReq missing or stale"
        if (dose <= 0.0) return "insulinReq cap is below one pump step"
        val capRemaining = Round.roundTo(max(0.0, walkingPlanned - dose), 0.001)
        if (walkFactor < 1.0)
            aapsLogger.info(LTag.CORE, "Warsaw dose: ${delayedDoseWalkingLevel(walkFactor)} (steps), dose x$walkFactor, ${Round.roundTo(sizedPlanned - walkingPlanned, 0.001)} U dropped")
        deliverLaterDose(dose, source, rh.gs(InterfacesStrings.wizard_warsaw_dose), "Warsaw dose") {
            preferences.put(LongNonKey.ApsAutoIsfLastDelayedBolusAt, dateUtil.now())
            if (capRemaining >= step / 2.0) onCappedRemainder(capRemaining / icFactor, liveIob + dose)
        }
        return null
    }

    /**
     * Later doses to Trio (2026-11-07, per explicit request): set before scheduling, and every later dose of this calculation
     * then goes to Trio by its remote command instead of the pump or an AAPS master, after the same checks. See [TrioLaterTarget].
     */
    var trioLater: TrioLaterTarget? = null

    /** Hands a later dose over: to Trio when [trioLater] is set, else to the pump or AAPS master as before */
    private suspend fun deliverLaterDose(dose: Double, source: Sources, treatmentNote: String, what: String, onSuccess: () -> Unit) {
        val trio = trioLater
        if (trio == null) {
            masterActionExecutor.deliverInsulin(
                insulin = dose,
                note = null,
                source = source,
                onError = { failure -> aapsLogger.info(LTag.CORE, "$what not delivered: ${failure.comment}") },
                treatmentNote = treatmentNote,
                onSuccess = onSuccess,
            )
            return
        }
        val failed = trio.sendBolus(dose)
        if (failed == null) {
            aapsLogger.info(LTag.CORE, "$what: $dose U sent to Trio")
            onSuccess()
        } else {
            aapsLogger.info(LTag.CORE, "$what not sent to Trio: $failed")
            warsawNote("C", dose, "not sent to Trio: $failed", source, "$what of ${ofBolus()}: ${warsawNoteAmount(dose)} U")
        }
    }

    /**
     * See [insReqCapWaived]: BG 7.5 mmol or more and a fresh HP1 of 6.0 or more give a carb-split or protein/fat part without
     * the insulinReq cap. For Trio its lowest prediction over the next hour stands in for HP1, as for the HP1 hold.
     */
    private fun followUpCapWaived(): Boolean {
        val now = dateUtil.now()
        val glucose = glucoseStatusProvider.glucoseStatusData?.glucose
        trioLater?.let { trio ->
            val (at, value) = trio.lowestPrediction() ?: return false
            return insReqCapWaived(glucose, value, at, now)
        }
        val at = preferences.get(LongNonKey.ApsAutoIsfLastCycleHp1At)
        val hp = preferences.get(LongNonKey.ApsAutoIsfLastCycleHp1MilliMmol)
        if (at != preferences.get(LongNonKey.ApsAutoIsfLastCycleHp1At)) return false
        return insReqCapWaived(glucose, hp, at, now)
    }

    private fun cappedFollowUpDose(candidate: Double, step: Double, halfReduction: Boolean = true): Double? {
        // Trio's own insulinReq, from the loop status it uploads, when the doses go to Trio
        trioLater?.let { trio ->
            val (at, requirement) = trio.insulinReq() ?: return null
            return followUpInsulinCap(candidate, requirement, at, dateUtil.now(), step, halfReduction)
        }
        val at = preferences.get(LongNonKey.ApsAutoIsfFollowUpInsulinReqAt)
        val requirement = preferences.get(LongNonKey.ApsAutoIsfFollowUpInsulinReqMilliU)
        if (at != preferences.get(LongNonKey.ApsAutoIsfFollowUpInsulinReqAt)) return null
        return followUpInsulinCap(candidate, requirement, at, dateUtil.now(), step, halfReduction)
    }

    private var lastWarsawNoteAt = 0L

    /**
     * A cancelled dose is also written as a real 0 U bolus row and a calculator record, so it shows in the Treatments list and
     * (with a pump id) uploads to Nightscout, where a Client shows it. The same three records as the 3426 app writes.
     * The bolus table is only uploaded for rows that carry a pump id, so the row gets its own timestamp as the pump id,
     * with the active pump's type and serial.
     */
    private var lastZeroRowAt = 0L

    private fun zeroBolusIds(timestamp: Long): IDs {
        val pump = activePlugin.activePump
        return IDs(pumpId = timestamp, pumpType = pump.model(), pumpSerial = pump.serialNumber())
    }

    private suspend fun recordCancelledDose(label: String, amount: Double, reason: String, iobBaseline: Double, source: Sources, useIobRise: Boolean = true) {
        // Doses for Trio: the care portal note says it; no 0 U pump bolus row is written to Trio's Nightscout site
        if (trioLater != null) {
            aapsLogger.info(LTag.CORE, "Trio later dose cancelled, no 0 U row: $label $amount U, $reason")
            return
        }
        val profile = profileFunction.getProfile() ?: run {
            aapsLogger.info(LTag.CORE, "Cancelled dose row not written, no profile: $label $amount U, $reason")
            return
        }
        val now = dateUtil.now()
        val timestamp = max(now, lastZeroRowAt + 1)
        lastZeroRowAt = timestamp
        val liveIob = if (useIobRise) iobCobCalculator.calculateFromTreatmentsAndTemps(now, profile).iob else 0.0
        val calculation = followUpCalculation(timestamp, label, amount, iobBaseline, liveIob, "Cancelled: $reason", source, useIobRise)
        val notes = "$label: cancelled ($reason)"
        val bolus = BS(
            timestamp = timestamp,
            amount = 0.0,
            type = BS.Type.NORMAL,
            notes = notes,
            ids = zeroBolusIds(timestamp),
            iCfg = profile.iCfg
        )
        persistenceLayer.insertOrUpdateBolus(bolus, Action.BOLUS, source, notes)
        persistenceLayer.insertOrUpdateBolusCalculatorResult(calculation)
    }

    /** Records the inputs used by this follow-up. FPU doses do not use an IOB-rise correction. */
    private suspend fun followUpCalculation(
        timestamp: Long,
        label: String,
        baseDose: Double,
        iobBaseline: Double,
        liveIob: Double,
        detail: String,
        source: Sources,
        useIobRise: Boolean = true,
    ): BCR {
        val gs = glucoseStatusProvider.glucoseStatusData
        val iobIncrease = if (useIobRise) max(0.0, liveIob - iobBaseline) else 0.0
        val iobNote = if (useIobRise)
            "total IOB baseline ${warsawNoteAmount(iobBaseline)}U, " +
                "now ${warsawNoteAmount(liveIob)}U, rise ${warsawNoteAmount(iobIncrease)}U; " +
                "IOB row represents the rise in total IOB. "
        else "FPU: no IOB-rise correction. "
        return BCR(
            timestamp = timestamp,
            targetBGLow = 0.0, targetBGHigh = 0.0,
            isf = 0.0, ic = ic,
            bolusIOB = iobIncrease, wasBolusIOBUsed = useIobRise,
            basalIOB = 0.0, wasBasalIOBUsed = false,
            glucoseValue = gs?.glucose ?: 0.0, wasGlucoseUsed = false,
            glucoseDifference = 0.0, glucoseInsulin = 0.0,
            glucoseTrend = 0.0, wasTrendUsed = false, trendInsulin = 0.0,
            cob = 0.0, wasCOBUsed = false, cobInsulin = 0.0,
            carbs = 0.0, wereCarbsUsed = false, carbsInsulin = 0.0,
            otherCorrection = baseDose,
            wasSuperbolusUsed = false, superbolusInsulin = 0.0,
            wasTempTargetUsed = false, totalInsulin = 0.0,
            percentageCorrection = 100, profileName = profileFunction.getProfileName(),
            note = "$label (${if (source == Sources.QuickWizard) "QuickWizard" else "WizardDialog"}): " +
                "base ${warsawNoteAmount(baseDose)}U, " +
                iobNote +
                "calculated 0.00U after residual cap/pump-step rounding. " +
                "BG is a safety gate, not a correction. " +
                "Gate ${gateGlucoseText(gs?.glucose, gs?.delta, gs?.shortAvgDelta, profileFunction.getUnits())}. " + detail
        )
    }

    /** Which bolus a follow-up dose belongs to: the time of this calculation. */
    private fun ofBolus(): String = "the ${dateUtil.timeString(timeStamp)} bolus"

    /** A carb split cancel always drops all the rest of the split. */
    private fun splitCancelWhat(amount: Double): String = "Carb split of ${ofBolus()}: all ${warsawNoteAmount(amount)} U still to come"

    private fun fpuWhat(label: String, dose: Double): String = "Protein/fat $label of ${ofBolus()}: ${warsawNoteAmount(dose)} U"

    /**
     * A one minute care portal note, "D1.30" for a deferred dose or "C1.30" for a dropped one. The graph shows only those
     * 5 characters; the rest of the note says which bolus it belongs to and what it is (2026-10-22, per explicit request).
     */
    private suspend fun warsawNote(prefix: String, amount: Double, text: String, source: Sources, what: String) {
        val timestamp = max(dateUtil.now(), lastWarsawNoteAt + 1)
        lastWarsawNoteAt = timestamp
        val label = "$prefix${warsawNoteAmount(amount)}"
        val noteText = "$label $what, ${if (prefix == "D") "waiting" else "cancelled"}: $text"
        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = TE(
                timestamp = timestamp,
                type = TE.Type.NOTE,
                note = noteText,
                duration = 60_000L,
                glucoseUnit = profileFunction.getUnits(),
            ),
            timestamp = timestamp,
            action = Action.CAREPORTAL,
            source = source,
            note = "$what. ${if (prefix == "D") "Waiting" else "Cancelled"}: $text",
            listValues = listOf(ValueWithUnit.SimpleString(label)),
        )
    }

    /**
     * Schedule the insulin that was above max bolus, and the insulin walking soon held back.
     * The immediate bolus already delivered the first part.
     * Temporary profile, pump, low-HP1 and glucose restrictions hold delivery until a later check.
     * The usual checks are 7 minutes apart and expire after 150 minutes.
     * When all three deltas are under -0.1 mmol/L, the max-bolus leftover uses 25 minute checks
     * for 2.5 hours. Walking soon always uses the usual checks.
     * A newer bolus cancels whatever is still waiting.
     */
    /** A bolus that is not a wizard confirm still replaces any leftover series that is waiting. */
    fun cancelLeftoverSplit() {
        SplitScheduleGate.next()
    }

    /** Cancels the fat/protein doses still waiting (2026-11-07, per explicit request: Cancel later Trio parts) */
    fun cancelWarsawDoses() {
        WarsawScheduleGate.next()
    }

    /**
     * Works out the [DelayedBolus] plan of this calculation (2026-11-14, per explicit request, from the 3426 app). Call after
     * [doCalc] and any [useFixedInsulin], before the confirmation and the saved calculation are built. [standardIc] is the
     * Standard profile's carb ratio now, at 100%: the Low profile is for hypo risk with no carbs, so once it is safe to give
     * the rest of a meal it is dosed as on Standard. Null (no Standard profile set or found) falls back to this profile's own
     * carb ratio with the 50% taken off.
     */
    suspend fun prepareDelayedBolus(standardIc: Double?) {
        delayedPlan = null
        if (!preferences.get(BooleanKey.WizardDelayedBolusEnabled) || carbs <= 0 || suggestedInsulin != null) return
        if (useSuperBolus || loop.runningMode() == RM.Mode.SUPER_BOLUS) return
        val heldBy = when {
            activeProfilePct == 50 -> DelayedHeldBy.PROFILE_50
            carbsHalvedByRecent50  -> DelayedHeldBy.RECENT_LOW
            else                   -> return
        }
        val standard = standardIc?.takeIf { it > 0.0 }
        val fromStandard = standard != null
        val useIc = standard ?: if (activeProfilePct == 50) ic / 2.0 else ic
        val held = DelayedBolus.heldUnits(carbs, useIc, insulinFromCarbs, immediatePercent)
        if (held < ch.bolusStep(max(held, 0.05)) / 2.0) return
        val sizingProfile = if (fromStandard) preferences.get(StringKey.ApsAutoIsfStandardProfileName).trim() else profileFunction.getOriginalProfileName()
        delayedPlan = DelayedBolusPlan(heldBy, Round.roundTo(held, 0.001), carbs, useIc, fromStandard, sizingProfile)
        aapsLogger.info(
            LTag.CORE,
            "Delayed bolus plan: ${heldBy.label} held=${Round.roundTo(held, 0.001)}U carbs=${carbs}g IC=$useIc " +
                "(${if (fromStandard) "Standard profile" else "own profile, Standard not found"}) carbInsulinNow=$insulinFromCarbs pct=$immediatePercent"
        )
    }

    /**
     * Starts the checks of a [DelayedBolus] after its bolus was given. A newer delayed bolus replaces one still waiting.
     * ActivityOff reads [LongNonKey.WizardDelayedBolusPendingUntil] while it waits.
     */
    suspend fun scheduleDelayedBolus(plan: DelayedBolusPlan, source: Sources) {
        val token = DelayedScheduleGate.next()
        val now = dateUtil.now()
        preferences.put(LongNonKey.WizardDelayedBolusPendingUntil, now + DelayedBolus.PENDING_MINUTES * 60_000L)
        aapsLogger.info(LTag.CORE, "Delayed bolus: ${plan.heldUnits}U held back by ${plan.heldBy.label}, checks every ${DelayedBolus.POLL_MINUTES} min")
        delayedNote("Db0 ${warsawNoteAmount(plan.heldUnits)} U held back by ${plan.heldBy.text}, checked every ${DelayedBolus.POLL_MINUTES} min", source)
        launchDelayedCheck(plan, source, token, attempt = 1, deliveredSoFar = 0.0, dueAt = now + DelayedBolus.POLL_MINUTES * 60_000L)
    }

    private fun launchDelayedCheck(plan: DelayedBolusPlan, source: Sources, token: Long, attempt: Int, deliveredSoFar: Double, dueAt: Long) {
        // 2026-11-22, per explicit request: saved until this check ends, so it survives an app restart (see PendingFollowUps)
        val pendingKey = PendingFollowUps.newKey(token, "delayed")
        val entry = if (trioLater != null) null else pendingEntry("delayed", token, source) {
            put("heldBy", plan.heldBy.name); put("heldUnits", plan.heldUnits); put("carbs", plan.carbs); put("planIc", plan.ic)
            put("fromStandardProfile", plan.fromStandardProfile); put("attempt", attempt); put("deliveredSoFar", deliveredSoFar); put("dueAt", dueAt)
            put("sizingProfile", plan.sizingProfile)
        }
        appScope.launch {
            entry?.let { PendingFollowUps.save(preferences, pendingKey, it) }
            waitAndRunDelayedCheck(plan, source, token, attempt, deliveredSoFar, dueAt, pendingKey)
            PendingFollowUps.remove(preferences, pendingKey)
        }
    }

    private suspend fun waitAndRunDelayedCheck(
        plan: DelayedBolusPlan, source: Sources, token: Long, attempt: Int, deliveredSoFar: Double, dueAt: Long, pendingKey: String
    ) {
        // In steps of at most 2 minutes, so a newer delayed bolus stops this one at once
        while (true) {
            if (!DelayedScheduleGate.isCurrent(token)) {
                delayedNote("Db${DelayedBolus.checkMinutes(attempt)} replaced by a newer delayed bolus", source)
                return
            }
            val wait = dueAt - dateUtil.now()
            if (wait <= 0L) break
            delay(min(wait, 120_000L))
        }
        runDelayedCheck(plan, source, token, attempt, deliveredSoFar, pendingKey)
    }

    /** What every saved check carries besides its own arguments: kind, schedule token, source and the bolus time its notes use. */
    private fun pendingEntry(kind: String, token: Long, source: Sources, fill: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("kind", kind); put("token", token); put("source", source.name); put("bolusTime", timeStamp)
        fill()
    }

    /**
     * Takes back one check saved before an app restart (2026-11-22, per explicit request, see PendingFollowUps) and starts it
     * exactly where it was. All the usual checks run when it is due; one whose window ended while the app was down is cancelled
     * with its usual note.
     */
    fun resumePendingPart(entry: JsonObject) {
        fun d(k: String) = entry[k]?.jsonPrimitive?.doubleOrNull ?: 0.0
        fun l(k: String) = entry[k]?.jsonPrimitive?.longOrNull ?: 0L
        fun i(k: String) = entry[k]?.jsonPrimitive?.intOrNull ?: 0
        fun b(k: String, default: Boolean) = entry[k]?.jsonPrimitive?.booleanOrNull ?: default
        fun s(k: String) = entry[k]?.jsonPrimitive?.contentOrNull ?: ""
        l("bolusTime").takeIf { it > 0L }?.let { timeStamp = it }
        val source = Sources.entries.firstOrNull { it.name == s("source") } ?: Sources.WizardDialog
        val token = l("token")
        when (s("kind")) {
            "fpu"     -> {
                aapsLogger.info(LTag.CORE, "Warsaw dose ${s("label")}: resumed after an app restart, ${d("planned")} U")
                fpuSizingIc = d("fpuSizingIc"); fpuSizingProfile = s("fpuSizingProfile")
                appScope.launch {
                    runWarsawDose(
                        d("planned"), d("iobBaseline"), source, token, l("firstDueAt"), entry["nextDueAt"]?.jsonPrimitive?.longOrNull,
                        b("retryLater", true), s("label"), initialDueAt = l("dueAt"), allowProfile50 = b("allowProfile50", false),
                        deferralNotedAtStart = b("deferralNoted", false)
                    )
                }
            }
            "split"   -> {
                aapsLogger.info(LTag.CORE, "Split leftover resumed after an app restart, ${d("remaining")} U left")
                splitSizingIc = d("splitSizingIc"); splitSizingProfile = s("splitSizingProfile")
                launchSplitPart(
                    d("remaining"), d("previousPart"), d("iobBaseline"), token, source, l("deliverAt"), l("deadline"), i("unsafeCount"),
                    i("intervalMinutes"), i("unsafeLimit"), b("walkingCutAllowed", true)
                )
            }
            "delayed" -> {
                val heldBy = DelayedHeldBy.entries.firstOrNull { it.name == s("heldBy") }
                if (heldBy == null) {
                    aapsLogger.error(LTag.CORE, "Delayed bolus check not resumed, unknown hold: $entry")
                    return
                }
                aapsLogger.info(LTag.CORE, "Delayed bolus check ${i("attempt")} resumed after an app restart")
                launchDelayedCheck(
                    DelayedBolusPlan(heldBy, d("heldUnits"), i("carbs"), d("planIc"), b("fromStandardProfile", true), s("sizingProfile")),
                    source, token, i("attempt"), d("deliveredSoFar"), l("dueAt")
                )
            }
            else      -> aapsLogger.error(LTag.CORE, "Pending follow-up check of unknown kind dropped: $entry")
        }
    }

    private suspend fun runDelayedCheck(plan: DelayedBolusPlan, source: Sources, token: Long, attempt: Int, deliveredSoFar: Double, pendingKey: String) {
        val minutes = DelayedBolus.checkMinutes(attempt)
        val db = "Db$minutes"
        val left = max(0.0, plan.heldUnits - deliveredSoFar)
        val notGiven = "${warsawNoteAmount(left)} U held back by ${plan.heldBy.text} is not given"

        suspend fun finish(note: String) {
            delayedNote(note, source)
            if (DelayedScheduleGate.isCurrent(token)) preferences.put(LongNonKey.WizardDelayedBolusPendingUntil, 0L)
        }

        suspend fun checkAgain(reason: String) {
            aapsLogger.info(LTag.CORE, "Delayed bolus $db: $reason")
            if (attempt < DelayedBolus.MAX_ATTEMPTS) {
                delayedNote("$db wait: $reason", source)
                launchDelayedCheck(plan, source, token, attempt + 1, deliveredSoFar, dateUtil.now() + DelayedBolus.POLL_MINUTES * 60_000L)
            } else finish("$db end: $reason after $minutes min; $notGiven")
        }

        if (loop.runningMode() == RM.Mode.SUPER_BOLUS || runningModeGuard.rejectionMessage(PumpCommandGate.CommandKind.BOLUS) != null) {
            checkAgain("pump will not take a bolus")
            return
        }
        val now = dateUtil.now()
        val gs = glucoseStatusProvider.glucoseStatusData
        if (!DelayedBolus.gateOk(gs?.glucose, gs?.delta, gs?.shortAvgDelta, gs?.longAvgDelta, gs?.let { now - it.date })) {
            val reason = when {
                gs == null                                -> "no BG"
                now - gs.date > DelayedBolus.GATE_MAX_AGE_MS -> "BG ${(now - gs.date) / 60_000L} min old"
                else                                      -> "BG not over 5.0 and rising (${gateGlucoseText(gs.glucose, gs.delta, gs.shortAvgDelta, profileFunction.getUnits())})"
            }
            checkAgain(reason)
            return
        }
        // 2026-11-26, per explicit request: wait while HP1 is below 4.0 mmol/L, or missing or stale, as the carb split does
        // (25 Nov: Db45 and Db50 gave 2.7 U while HP1 was 3.4-3.5 and the carb split waited on it; BG then fell to 3.9). For Trio, its lowest prediction over the next hour stands in, as for the split. Only ever holds a check.
        val hpAt = preferences.get(LongNonKey.ApsAutoIsfLastCycleHp1At)
        val hpValue = preferences.get(LongNonKey.ApsAutoIsfLastCycleHp1MilliMmol)
        val hpCheck = trioLater?.let { trio -> trio.lowestPrediction()?.let { (at, value) -> splitHpCheck(value, at, now) } ?: SplitHpCheck.Wait }
            ?: if (hpAt == preferences.get(LongNonKey.ApsAutoIsfLastCycleHp1At)) splitHpCheck(hpValue, hpAt, now) else SplitHpCheck.Wait
        if (hpCheck != SplitHpCheck.Allow) {
            checkAgain(if (hpCheck == SplitHpCheck.Hold) "HP1 below 4.0 mmol/L" else "HP1 missing or stale")
            return
        }
        val cob = iobCobCalculator.getCobInfo("DelayedBolus").displayCob ?: 0.0
        // 2026-11-27, per explicit request (same as 3426): no carbs-on-board shrink. The amount held back is for carbs already entered
        // with this bolus, absorbed or not (27 Nov Db50: x0.41 dropped 0.49 U at BG 10.5 and rising). COB is still logged.
        val cobFraction = 1.0
        // 2026-11-28, per explicit request (same as 3426): the held part was sized at plan.ic on plan.sizingProfile; on another
        // profile it is resized (see ProfileIcResize). Sized at the Standard IC, a weaker (Low) profile does not shrink it.
        val (icFactor, icNote) = partIcResize(plan.sizingProfile, plan.ic, upOnly = plan.fromStandardProfile)
        val raw = DelayedBolus.checkDose(plan.heldUnits * icFactor, deliveredSoFar, cobFraction)
        val step = ch.bolusStep(max(raw, 0.05))
        if (raw < step / 2.0) {
            aapsLogger.info(LTag.CORE, "Delayed bolus $db: covered, COB $cob of ${plan.carbs} g leaves $raw U")
            finish("$db covered: carbs on board ${Round.roundTo(cob, 0.1)} of ${plan.carbs} g leave nothing to give")
            return
        }
        // Cut while walking, as the other later doses; the cut part is dropped
        val walkFactor = walkingFactorNow()
        val walked = Round.roundTo(raw * walkFactor, step)
        // 2026-11-27, per explicit request (same as 3426): no insulinReq cap at BG 6.5 or more with HP1 6.0 or more (as the carb
        // split), or at the first check after the hypo wait with BG 6.0 or more and rising (the whole remainder at once).
        val (releaseHpAt, releaseHp) = trioLater?.lowestPrediction() ?: (hpAt to hpValue)
        val capped = if (followUpCapWaived() || delayedFullRelease(gs?.glucose, gs?.delta, gs?.shortAvgDelta, releaseHp, releaseHpAt, now))
            walked else cappedFollowUpDose(walked, step)
        if (capped == null) {
            checkAgain("insulinReq missing or stale")
            return
        }
        if (capped <= 0.0) {
            checkAgain("insulinReq cap is below one pump step")
            return
        }
        // Only insulinReq keeps the series going after a dose: carbs absorbed and walking are real reasons to give less
        val insulinReqLimited = capped < walked - step / 2.0
        val continues = insulinReqLimited && attempt < DelayedBolus.MAX_ATTEMPTS
        aapsLogger.info(
            LTag.CORE,
            "Delayed bolus $db: giving ${capped}U (held ${plan.heldUnits}U, given before ${deliveredSoFar}U, COB x${Round.roundTo(cobFraction, 0.01)}, " +
                "walking x$walkFactor, insulinReq-limited=$insulinReqLimited)$icNote"
        )
        delayedNote("$db ${warsawNoteAmount(capped)} U${if (continues) ", insulinReq-limited, the rest checked again in ${DelayedBolus.POLL_MINUTES} min" else ""}", source)
        // Unsaved before the dose can reach the pump, so an app killed during the bolus can never give it twice
        PendingFollowUps.remove(preferences, pendingKey)
        deliverLaterDose(capped, source, rh.gs(InterfacesStrings.wizard_delayed_dose, minutes, plan.heldBy.text), "Delayed bolus") {
            preferences.put(LongNonKey.ApsAutoIsfLastDelayedBolusAt, dateUtil.now())
            if (continues) launchDelayedCheck(plan, source, token, attempt + 1, deliveredSoFar + capped, dateUtil.now() + DelayedBolus.POLL_MINUTES * 60_000L)
            else if (DelayedScheduleGate.isCurrent(token)) preferences.put(LongNonKey.WizardDelayedBolusPendingUntil, 0L)
        }
    }

    /** A care portal note of a delayed bolus check: "Db5 wait: ...", "Db15 0.80 U", "Db80 end: ...", as in the 3426 app. */
    private suspend fun delayedNote(text: String, source: Sources) {
        val timestamp = max(dateUtil.now(), lastWarsawNoteAt + 1)
        lastWarsawNoteAt = timestamp
        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = TE(
                timestamp = timestamp,
                type = TE.Type.NOTE,
                note = text,
                duration = 60_000L,
                glucoseUnit = profileFunction.getUnits(),
            ),
            timestamp = timestamp,
            action = Action.CAREPORTAL,
            source = source,
            note = "Delayed bolus check: $text",
            listValues = listOf(ValueWithUnit.SimpleString(text.substringBefore(' '))),
        )
    }

    suspend fun scheduleLeftoverSplit(
        requested: Double,
        delivered: Double,
        iobBaseline: Double,
        source: Sources,
        rapidFall: Boolean = false,
        walkingSoonHeld: Double = 0.0,
        splitOverMax: Boolean = true,
        intervalMinutes: Int = SPLIT_LEFTOVER_INTERVAL_MINUTES,
    ) {
        val token = SplitScheduleGate.next()
        val profile = profileFunction.getProfile() ?: return
        if (profileSwitchPercent(profile) < 100) return
        // 2026-11-28, per explicit request (same as 3426): the split is sized at this profile's IC at 100% (it runs at 100% or more)
        splitSizingIc = profile.getIcTimeFromMidnight(MidnightTime.secondsFromMidnight(timeStamp, AppTimeZone.current())) * profileSwitchPercent(profile) / 100.0
        splitSizingProfile = profileFunction.getOriginalProfileName()
        val step = ch.bolusStep(max(delivered, walkingSoonHeld).coerceAtLeast(0.05))
        val aboveMax = if (splitOverMax) splitLeftover(requested, delivered, step) ?: 0.0 else 0.0
        val walkLeft = Round.roundTo(walkingSoonHeld, 0.001).let { held ->
            if (held >= step / 2.0) held else 0.0
        }
        val usualLeft = Round.roundTo(if (rapidFall) walkLeft else walkLeft + aboveMax, 0.001)
        val rapidLeft = if (rapidFall) aboveMax else 0.0
        val now = dateUtil.now()
        if (usualLeft >= step / 2.0) {
            aapsLogger.info(LTag.CORE, "Split leftover ${usualLeft} U every $intervalMinutes min")
            launchSplitPart(
                remaining = usualLeft,
                previousPart = if (delivered > 0.0) delivered else usualLeft,
                iobBaseline = iobBaseline,
                token = token,
                source = source,
                deliverAt = now + intervalMinutes * 60_000L,
                deadline = now + SPLIT_LEFTOVER_DEADLINE_MINUTES * 60_000L,
                unsafeCount = 0,
                intervalMinutes = intervalMinutes,
                unsafeLimit = SPLIT_UNSAFE_CANCEL_COUNT,
                // 2026-10-19, per explicit request: insulin walking soon held back is not cut again for walking.
                walkingCutAllowed = walkLeft <= 0.0,
            )
        }
        if (rapidLeft >= step / 2.0) {
            aapsLogger.info(LTag.CORE, "Split leftover ${rapidLeft} U every $SPLIT_RAPID_FALL_INTERVAL_MINUTES min")
            launchSplitPart(
                remaining = rapidLeft,
                previousPart = delivered,
                iobBaseline = iobBaseline,
                token = token,
                source = source,
                deliverAt = now + SPLIT_RAPID_FALL_INTERVAL_MINUTES * 60_000L,
                deadline = now + SPLIT_RAPID_FALL_DEADLINE_MINUTES * 60_000L,
                unsafeCount = 0,
                intervalMinutes = SPLIT_RAPID_FALL_INTERVAL_MINUTES,
                unsafeLimit = SPLIT_RAPID_FALL_UNSAFE_CANCEL_COUNT,
                walkingCutAllowed = true,
            )
        }
    }

    /** The leftover split was dropped: one care portal note ("C1.30"), a 0 U bolus row and a calculator record. */
    private suspend fun cancelSplitPart(remaining: Double, reason: String, iobBaseline: Double, source: Sources) {
        warsawNote("C", remaining, reason, source, splitCancelWhat(remaining))
        recordCancelledDose("Carb split", remaining, reason, iobBaseline, source)
    }

    private fun launchSplitPart(
        remaining: Double,
        previousPart: Double,
        iobBaseline: Double,
        token: Long,
        source: Sources,
        deliverAt: Long,
        deadline: Long,
        unsafeCount: Int,
        intervalMinutes: Int,
        unsafeLimit: Int,
        walkingCutAllowed: Boolean,
    ) {
        val step = ch.bolusStep(previousPart)
        if (remaining < step / 2.0) return
        // 2026-11-22, per explicit request: saved until this check ends, so it survives an app restart (see PendingFollowUps)
        val pendingKey = PendingFollowUps.newKey(token, "split")
        val entry = if (trioLater != null) null else pendingEntry("split", token, source) {
            put("remaining", remaining); put("previousPart", previousPart); put("iobBaseline", iobBaseline); put("deliverAt", deliverAt)
            put("deadline", deadline); put("unsafeCount", unsafeCount); put("intervalMinutes", intervalMinutes); put("unsafeLimit", unsafeLimit)
            put("walkingCutAllowed", walkingCutAllowed); put("splitSizingIc", splitSizingIc); put("splitSizingProfile", splitSizingProfile)
        }
        appScope.launch {
            entry?.let { PendingFollowUps.save(preferences, pendingKey, it) }
            runSplitCheck(remaining, previousPart, iobBaseline, token, source, deliverAt, deadline, unsafeCount, intervalMinutes, unsafeLimit, walkingCutAllowed, step, pendingKey)
            PendingFollowUps.remove(preferences, pendingKey)
        }
    }

    private suspend fun runSplitCheck(
        remaining: Double,
        previousPart: Double,
        iobBaseline: Double,
        token: Long,
        source: Sources,
        deliverAt: Long,
        deadline: Long,
        unsafeCount: Int,
        intervalMinutes: Int,
        unsafeLimit: Int,
        walkingCutAllowed: Boolean,
        step: Double,
        pendingKey: String,
    ) {
            val wait = (deliverAt - dateUtil.now()).coerceAtLeast(1_000L)
            delay(min(wait, 120_000L))
            if (!SplitScheduleGate.isCurrent(token)) {
                // Only the note, for the same reason as the Warsaw doses above.
                warsawNote("C", remaining, "superseded by newer entry", source, splitCancelWhat(remaining))
                return
            }
            val now = dateUtil.now()
            if (now < deliverAt) {
                launchSplitPart(remaining, previousPart, iobBaseline, token, source, deliverAt, deadline, unsafeCount, intervalMinutes, unsafeLimit, walkingCutAllowed)
                return
            }
            if (now > deadline) {
                aapsLogger.info(LTag.CORE, "Split leftover cancelled: time limit passed with ${remaining} U left")
                cancelSplitPart(remaining, "time limit passed", iobBaseline, source)
                return
            }
            suspend fun holdThisCheck(reason: String) {
                aapsLogger.info(LTag.CORE, "Split leftover waiting: $reason, ${remaining} U left")
                warsawNote("D", remaining, "$reason; check again in $intervalMinutes min", source, splitCancelWhat(remaining))
                launchSplitPart(
                    remaining, previousPart, iobBaseline, token, source,
                    now + intervalMinutes * 60_000L, deadline, unsafeCount, intervalMinutes, unsafeLimit, walkingCutAllowed
                )
            }
            val profile = profileFunction.getProfile()
            if (profile == null || profileSwitchPercent(profile) < 100) {
                holdThisCheck("profile missing or under 100%")
                return
            }
            // No pump on this phone when the doses go to Trio: only the superbolus check applies
            if (loop.runningMode() == RM.Mode.SUPER_BOLUS || (trioLater == null && runningModeGuard.rejectionMessage(PumpCommandGate.CommandKind.BOLUS) != null)) {
                holdThisCheck("pump will not take a bolus")
                return
            }
            val hpAt = preferences.get(LongNonKey.ApsAutoIsfLastCycleHp1At)
            val hpValue = preferences.get(LongNonKey.ApsAutoIsfLastCycleHp1MilliMmol)
            // For Trio: the lowest of Trio's own predictions over the next hour stands in for HP1
            val hpCheck = trioLater?.let { trio -> trio.lowestPrediction()?.let { (at, value) -> splitHpCheck(value, at, now) } ?: SplitHpCheck.Wait }
                ?: if (hpAt == preferences.get(LongNonKey.ApsAutoIsfLastCycleHp1At)) splitHpCheck(hpValue, hpAt, now) else SplitHpCheck.Wait
            when (hpCheck) {
                SplitHpCheck.Hold -> {
                    holdThisCheck("HP1 below 4.0 mmol/L")
                    return
                }
                SplitHpCheck.Wait -> {
                    aapsLogger.info(LTag.CORE, "Split leftover waiting: HP1 missing or stale, ${remaining} U left")
                    launchSplitPart(remaining, previousPart, iobBaseline, token, source, now + 120_000L, deadline, unsafeCount, intervalMinutes, unsafeLimit, walkingCutAllowed)
                    return
                }
                SplitHpCheck.Allow -> Unit
            }
            val status = glucoseStatusProvider.glucoseStatusData
            when (splitBgCheck(status?.glucose, status?.delta, status?.shortAvgDelta)) {
                SplitBgCheck.Missing -> {
                    aapsLogger.info(LTag.CORE, "Split leftover waiting: no fresh glucose, ${remaining} U left")
                    launchSplitPart(remaining, previousPart, iobBaseline, token, source, now + 120_000L, deadline, unsafeCount, intervalMinutes, unsafeLimit, walkingCutAllowed)
                }
                SplitBgCheck.Unsafe  -> {
                    val count = unsafeCount + 1
                    if (count >= unsafeLimit) {
                        aapsLogger.info(LTag.CORE, "Split leftover cancelled: glucose unsafe $count times, ${remaining} U left")
                        cancelSplitPart(remaining, "glucose unsafe $count times", iobBaseline, source)
                    } else {
                        aapsLogger.info(LTag.CORE, "Split leftover waiting: glucose unsafe $count of $unsafeLimit, ${remaining} U left")
                        // A note for each check that gave nothing, so the graph can dot it (2026-11-18, per explicit request)
                        warsawNote("D", remaining, "glucose unsafe $count of $unsafeLimit; check again in $intervalMinutes min", source, splitCancelWhat(remaining))
                        launchSplitPart(
                            remaining, previousPart, iobBaseline, token, source,
                            now + intervalMinutes * 60_000L, deadline, count, intervalMinutes, unsafeLimit,
                            walkingCutAllowed,
                        )
                    }
                }
                SplitBgCheck.Allowed -> {
                    val liveIob = iobCobCalculator.calculateFromTreatmentsAndTemps(now, profile).iob
                    // 2026-10-18: no IOB credit, see splitBolusBudget. The insulinReq cap counts insulin on board.
                    // 2026-11-28, per explicit request (same as 3426): [remaining] stays in the units it was sized at; on another
                    // profile it is resized for this part (see ProfileIcResize), and what is left goes back in the sized units
                    val (icFactor, icNote) = partIcResize(splitSizingProfile, splitSizingIc)
                    if (icNote.isNotEmpty()) aapsLogger.info(LTag.CORE, "Split leftover part:$icNote")
                    val budget = splitBolusBudget(remaining * icFactor, previousPart, step)
                    val reducedRemaining = budget.remaining
                    // 2026-10-19, per explicit request: cut while walking. The cut part is dropped from the total. Not for a
                    // series that holds insulin walking soon kept back: walking soon already cut the first bolus.
                    val walkFactor = if (walkingCutAllowed) walkingFactorNow() else 1.0
                    val partDose = if (walkFactor < 1.0) Round.roundTo(budget.dose * walkFactor, step) else budget.dose
                    val walkingCut = Round.roundTo(max(0.0, budget.dose - partDose), 0.001)
                    // 2026-11-26, per explicit request (same as 3426): no insulinReq cap at BG 7.5 or more with HP1 6.0 or more
                    val cappedDose = if (followUpCapWaived()) partDose else cappedFollowUpDose(partDose, step)
                    val dose = cappedDose ?: 0.0
                    if (reducedRemaining < step / 2.0) return
                    if (dose <= 0.0) {
                        val reason = if (cappedDose == null) "insulinReq missing or stale" else "insulinReq cap is below one pump step"
                        aapsLogger.info(LTag.CORE, "Split leftover waiting: $reason, ${reducedRemaining} U left")
                        val retryMinutes = if (budget.dose > 0.0) 2 else intervalMinutes
                        launchSplitPart(
                            reducedRemaining / icFactor, previousPart, max(iobBaseline, liveIob), token, source,
                            now + retryMinutes * 60_000L, deadline, 0, intervalMinutes, unsafeLimit,
                            walkingCutAllowed,
                        )
                        try {
                            warsawNote("D", reducedRemaining, "$reason; check again in $retryMinutes min", source, splitCancelWhat(reducedRemaining))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            aapsLogger.error(LTag.CORE, "Split leftover: could not record check", error)
                        }
                        return
                    }
                    if (walkFactor < 1.0)
                        aapsLogger.info(LTag.CORE, "Split leftover part: ${delayedDoseWalkingLevel(walkFactor)} (steps), part x$walkFactor, $walkingCut U dropped")
                    val leftAfterPart = Round.roundTo(max(0.0, reducedRemaining - dose - walkingCut), 0.001)
                    // Unsaved before the dose can reach the pump, so an app killed during the bolus can never give it twice
                    PendingFollowUps.remove(preferences, pendingKey)
                    deliverLaterDose(dose, source, rh.gs(InterfacesStrings.wizard_split_leftover, leftAfterPart, intervalMinutes), "Split leftover part") {
                        preferences.put(LongNonKey.ApsAutoIsfLastDelayedBolusAt, dateUtil.now())
                        val left = leftAfterPart
                        if (left >= step / 2.0) {
                            launchSplitPart(
                                remaining = left / icFactor,   // back in the sized units (2026-11-28)
                                // The next part keeps the size it had before the walking cut.
                                previousPart = dose + walkingCut,
                                iobBaseline = liveIob + dose,
                                token = token,
                                source = source,
                                deliverAt = dateUtil.now() + intervalMinutes * 60_000L,
                                deadline = deadline,
                                unsafeCount = 0,
                                intervalMinutes = intervalMinutes,
                                unsafeLimit = unsafeLimit,
                                walkingCutAllowed = walkingCutAllowed,
                            )
                        }
                    }
                }
            }
    }

    /**
     * Insulin-only mode of the calculator (2026-11-01): the dose the user typed is given, not the suggestion.
     * Call after [doCalc], with max bolus set as for the calculation. The suggestion stays in [suggestedInsulin],
     * and a calculation result made before this call keeps the calculator's own figures. Max bolus and the split
     * over max bolus apply to the typed dose. The held walking-soon part and the rapid-fall split do not.
     */
    fun useFixedInsulin(units: Double) {
        suggestedInsulin = calculatedTotalInsulin
        calculatedTotalInsulin = Round.roundTo(units.coerceAtLeast(0.0), ch.bolusStep(units.coerceAtLeast(0.0)))
        unclampedCalculatedInsulin = calculatedTotalInsulin
        carbsEquivalent = 0.0
        walkingSoonHeldUnits = 0.0
        rapidFallForSplit = false
        insulinAfterConstraints = constraintChecker.applyBolusConstraints(ConstraintObject(calculatedTotalInsulin, aapsLogger)).value()
        warsawIobBaseline = (insulinFromBolusIOB + insulinFromBasalIOB).coerceAtLeast(0.0) + insulinAfterConstraints
    }

    fun createBolusCalculatorResult(
        eCarbsGrams: Int = 0,
        eCarbsDelayMinutes: Int = 0,
        eCarbsDurationHours: Int = 0,
    ): BCR {
        val unit = profileFunction.getUnits()
        return BCR(
            timestamp = dateUtil.now(),
            targetBGLow = profileUtil.convertToMgdl(targetBGLow, unit),
            targetBGHigh = profileUtil.convertToMgdl(targetBGHigh, unit),
            isf = profileUtil.convertToMgdl(sens, unit),
            ic = ic,
            bolusIOB = insulinFromBolusIOB,
            wasBolusIOBUsed = includeBolusIOB,
            basalIOB = insulinFromBasalIOB,
            wasBasalIOBUsed = includeBasalIOB,
            glucoseValue = profileUtil.convertToMgdl(bg, unit),
            wasGlucoseUsed = useBg && bg > 0,
            glucoseDifference = bgDiff,
            glucoseInsulin = insulinFromBG,
            glucoseTrend = profileUtil.fromMgdlToUnits(trend, unit),
            wasTrendUsed = useTrend,
            trendInsulin = insulinFromTrend,
            cob = cob,
            wasCOBUsed = useCob,
            cobInsulin = insulinFromCOB,
            carbs = carbs.toDouble(),
            wereCarbsUsed = carbs > 0,
            carbsInsulin = insulinFromCarbs,
            otherCorrection = correction,
            wasSuperbolusUsed = useSuperBolus,
            superbolusInsulin = insulinFromSuperBolus,
            wasTempTargetUsed = useTT,
            totalInsulin = calculatedTotalInsulin,
            percentageCorrection = percentageCorrection,
            profileName = profileName,
            note = calculatorNote(notes, eCarbsGrams, eCarbsDelayMinutes, eCarbsDurationHours)
        )
    }

    fun buildConfirmationLines(
        advisor: Boolean,
        quickWizardEntry: QuickWizardEntry? = null,
        eCarbsGrams: Int = 0,
        eCarbsDelayMinutes: Int = 0,
        eCarbsDurationHours: Int = 0,
        forcedRecordOnly: Boolean = false
    ): List<ConfirmationLine> =
        confirmationLines {
            if (insulinAfterConstraints > 0) {
                val insulin = rh.gs(InterfacesStrings.format_insulin_units, insulinAfterConstraints)
                line(
                    ConfirmationRole.BOLUS,
                    rh.gs(
                        InterfacesStrings.confirmation_line,
                        rh.gs(InterfacesStrings.bolus),
                        if (percentageCorrection != 100) rh.gs(InterfacesStrings.insulin_with_percentage, insulin, percentageCorrection) else insulin
                    )
                )
            }
            if (carbs > 0 && !advisor) {
                val carbsText = rh.gs(InterfacesStrings.format_carbs, carbs)
                val carbsWithTime = when {
                    carbTime > 0 -> rh.gs(InterfacesStrings.carbs_with_time_later, carbsText, rh.gs(InterfacesStrings.mins, carbTime))
                    carbTime < 0 -> rh.gs(InterfacesStrings.value_with_detail, carbsText, rh.gs(InterfacesStrings.mins, carbTime))
                    else         -> carbsText
                }
                line(
                    ConfirmationRole.CARBS,
                    rh.gs(InterfacesStrings.confirmation_line, rh.gs(InterfacesStrings.carbs), carbsWithTime)
                )
            }
            if (insulinFromCOB > 0) {
                line(
                    ConfirmationRole.COB,
                    rh.gs(
                        InterfacesStrings.confirmation_line,
                        rh.gs(InterfacesStrings.cobvsiob),
                        rh.gs(
                            InterfacesStrings.formatsignedinsulinunits,
                            -insulinFromBolusIOB - insulinFromBasalIOB + insulinFromCOB + insulinFromBG
                        )
                    )
                )
                val absorptionRate = iobCobCalculator.ads.slowAbsorptionPercentage(60)
                if (absorptionRate > .25) {
                    line(ConfirmationRole.COB, rh.gs(InterfacesStrings.slowabsorptiondetected_plain, (absorptionRate * 100).toInt()))
                }
            }
            if (abs(insulinAfterConstraints - calculatedTotalInsulin) > ch.bolusStep(insulinAfterConstraints)) {
                line(ConfirmationRole.WARNING, rh.gs(InterfacesStrings.bolus_constraint_applied_warn, calculatedTotalInsulin, insulinAfterConstraints))
            }
            if ((config.AAPSCLIENT || forcedRecordOnly) && insulinAfterConstraints > 0) {
                line(ConfirmationRole.WARNING, rh.gs(InterfacesStrings.bolus_recorded_only))
            }
            if (useAlarm && !advisor && carbs > 0 && carbTime > 0) {
                line(ConfirmationRole.INFO, rh.gs(InterfacesStrings.alarminxmin, carbTime))
            }
            if (advisor) {
                line(ConfirmationRole.INFO, rh.gs(InterfacesStrings.advisoralarm))
            }

            if (quickWizardEntry != null) {
                // The extended carbs the button really gives: its meal type or its own fixed amount, none when fat and
                // protein replace them (2026-10-22).
                // Same line as the wizard's own extended carbs.
                val carbs2 = quickWizardEntry.eCarbsGrams()
                if (carbs2 > 0) {
                    line(
                        ConfirmationRole.INFO,
                        rh.gs(InterfacesStrings.wizard_ecarbs, carbs2, quickWizardEntry.eCarbsDurationHours(), quickWizardEntry.eCarbsDelayMinutes())
                    )
                }
            }
            if (eCarbsGrams > 0) {
                line(ConfirmationRole.INFO, rh.gs(InterfacesStrings.wizard_ecarbs, eCarbsGrams, eCarbsDurationHours, eCarbsDelayMinutes))
            }
            if (carbsHalvedByRecent50) {
                line(ConfirmationRole.INFO, rh.gs(InterfacesStrings.wizard_carbs_halved))
            }
            if (lowBgRecentEntryRule) {
                line(ConfirmationRole.INFO, rh.gs(InterfacesStrings.wizard_recent_entry))
            }
            delayedPlan?.let { plan ->
                line(ConfirmationRole.INFO, rh.gs(InterfacesStrings.wizard_delayed_pending, plan.heldUnits, plan.heldBy.text, plan.ic))
            }
            if (hpSafetyApplied) {
                line(
                    ConfirmationRole.INFO,
                    rh.gs(InterfacesStrings.wizard_hp_safety, hpSafetyOriginal, hpSafetyAdjusted, hpSafetyCobRemoved)
                )
            }
            warsawPlan?.let { plan ->
                line(
                    ConfirmationRole.INFO,
                    rh.gs(InterfacesStrings.wizard_warsaw_plan, plan.totalInsulin, plan.numDoses, plan.durationMinutes / 60)
                )
            }
            splitLeftoverTexts().forEach { line(ConfirmationRole.INFO, it) }
        }

    fun buildWizardDetail(): EventData.WizardDetail {
        val ttLabel = if (useTT && tempTarget != null) {
            val fmt = NumberFormat.DECIMAL_1_UP_TO_2
            val low = fmt.format(profileUtil.fromMgdlToUnits(tempTarget!!.lowTarget))
            val high = fmt.format(profileUtil.fromMgdlToUnits(tempTarget!!.highTarget))
            if (low == high) low else "$low-$high"
        } else null
        return EventData.WizardDetail(
            totalInsulin = calculatedTotalInsulin,
            unclampedInsulin = unclampedCalculatedInsulin,
            carbs = carbs,
            insulinFromBG = insulinFromBG,
            insulinFromTrend = insulinFromTrend,
            insulinFromCOB = insulinFromCOB,
            insulinFromCarbs = insulinFromCarbs,
            insulinFromBolusIOB = insulinFromBolusIOB,
            insulinFromBasalIOB = insulinFromBasalIOB,
            includeBolusIOB = includeBolusIOB,
            includeBasalIOB = includeBasalIOB,
            percentageCorrection = percentageCorrection,
            cob = cob,
            tempTargetLabel = ttLabel,
            ic = ic,
            sens = sens,
        )
    }

    private fun calcPercentageWithConstraints() {
        calculatedPercentage = 100
        if (totalBeforePercentageAdjustment != insulinFromCorrection)
            calculatedPercentage = (calculatedTotalInsulin / (totalBeforePercentageAdjustment - insulinFromCorrection) * 100).toInt()
        calculatedPercentage = max(calculatedPercentage, 10)
        calculatedPercentage = min(calculatedPercentage, 250)
    }

    private fun calcCorrectionWithConstraints() {
        calculatedCorrection = totalBeforePercentageAdjustment * totalPercentage / percentageCorrection - totalBeforePercentageAdjustment
        //Apply constraints
        calculatedCorrection = min(constraintChecker.getMaxBolusAllowed().value(), calculatedCorrection)
        calculatedCorrection = max(-constraintChecker.getMaxBolusAllowed().value(), calculatedCorrection)
    }

    // --- Compose-friendly methods (no Context/attrs dependency) ---

    /**
     * Pure-logic SSOT for whether the high-BG bolus advisor ("correct now, eat later?") should be offered:
     * the pref is on, BG > 180 mg/dL, carbs are present, and the carb time isn't in the past. Used by the
     * wizard-dialog / quick-wizard prepare path on both master and client.
     */
    fun needsBolusAdvisor(): Boolean =
        preferences.get(BooleanKey.OverviewUseBolusAdvisor) &&
            profileUtil.convertToMgdl(bg, profile.units) > 180 &&
            carbs > 0 &&
            carbTime >= 0

    /**
     * Records the wizard entry without a pump: bolus, carbs, eCarbs and the bolus calculator result go
     * straight to the database. For a master that cannot deliver (pump not initialized, or the running mode
     * forbids a bolus); the caller has already shown that the entry is only recorded. A delivered wizard
     * bolus does not come here: it goes to the master's [app.aaps.core.interfaces.bolus.MasterActionExecutor.confirm].
     */
    suspend fun executeRecordOnly(eCarbsGrams: Int = 0, eCarbsDelayMinutes: Int = 0, eCarbsDurationHours: Int = 0) {
        if (accepted) {
            aapsLogger.debug(LTag.UI, "guarding: already accepted")
            return
        }
        accepted = true
        if (calculatedTotalInsulin > 0.0)
            automation.removeAutomationEventBolusReminder()
        if (carbs > 0.0)
            automation.removeAutomationEventEatReminder()

        // Named to avoid shadowing the `profile` property, which is the profile the dose was computed
        // against — the two are not interchangeable and were easy to confuse while it was shadowed.
        val runningProfile = profileFunction.getProfile() ?: return
        val now = dateUtil.now()

        if (insulinAfterConstraints > 0 || carbs > 0) {
            val action = when {
                insulinAfterConstraints == 0.0 -> Action.CARBS
                carbs == 0                     -> Action.BOLUS
                else                           -> Action.TREATMENT
            }
            val bolusCalculatorResult = createBolusCalculatorResult(eCarbsGrams, eCarbsDelayMinutes, eCarbsDurationHours)
            // No pump delivery to wait for, so the "time to eat" timer starts now. A delivered wizard bolus
            // starts it in confirm(), once the pump finished the bolus.
            if (useAlarm && carbs > 0 && carbTime > 0) {
                eatReminder.start(T.mins(carbTime.toLong()).msecs())
            }
            uel.log(
                action = action,
                source = source,
                note = notes,
                listValues = listOfNotNull(
                    ValueWithUnit.TEType(TE.Type.BOLUS_WIZARD),
                    ValueWithUnit.Insulin(insulinAfterConstraints).takeIf { insulinAfterConstraints != 0.0 },
                    ValueWithUnit.Gram(carbs).takeIf { carbs != 0 },
                    ValueWithUnit.Minute(carbTime).takeIf { carbTime != 0 }
                )
            )
            // The profile the dose was computed against owns the iCfg to record. It is null only for
            // a ProfileSealed.Pure caller, in which case the running profile — already resolved and
            // non-null above — is the insulin this bolus is actually being delivered with.
            val recordIcfg = profile.iCfg ?: runningProfile.iCfg
            val detailedBolusInfo = DetailedBolusInfo().apply {
                insulin = insulinAfterConstraints
                carbs = this@BolusWizard.carbs.toDouble()
                carbsTimestamp = now + T.mins(this@BolusWizard.carbTime.toLong()).msecs()
                notes = this@BolusWizard.notes
            }
            if (insulinAfterConstraints > 0) {
                persistenceLayer.insertOrUpdateBolus(
                    bolus = detailedBolusInfo.createBolus(recordIcfg),
                    action = action,
                    source = source,
                    note = if (notes.isNotEmpty()) rh.gs(InterfacesStrings.confirmation_line, rh.gs(InterfacesStrings.record), notes) else rh.gs(InterfacesStrings.record)
                )
            }
            if (carbs > 0) {
                persistenceLayer.insertOrUpdateCarbs(
                    carbs = detailedBolusInfo.createCarbs(),
                    action = action,
                    source = source,
                    note = notes.ifEmpty { rh.gs(InterfacesStrings.record) }
                )
            }
            persistenceLayer.insertOrUpdateBolusCalculatorResult(bolusCalculatorResult)
        }
        if (eCarbsGrams > 0) {
            recordECarbs(eCarbsGrams, eCarbsDelayMinutes, eCarbsDurationHours)
        }
    }

    private suspend fun recordECarbs(eCarbsGrams: Int, delayMinutes: Int, durationHours: Int) {
        // delayMinutes is already the total delay from now — the caller folds the meal carbTime into it.
        // Do NOT add carbTime again here or the eCarbs record lands carbTime minutes too late.
        val eventTime = dateUtil.now() + T.mins(delayMinutes.toLong()).msecs()
        uel.log(
            action = Action.EXTENDED_CARBS,
            source = Sources.WizardDialog,
            note = notes,
            listValues = listOfNotNull(
                ValueWithUnit.Timestamp(eventTime),
                ValueWithUnit.Gram(eCarbsGrams),
                ValueWithUnit.Minute(delayMinutes).takeIf { delayMinutes != 0 },
                ValueWithUnit.Hour(durationHours).takeIf { durationHours != 0 }
            )
        )
        val detailedBolusInfo = DetailedBolusInfo().apply {
            carbs = eCarbsGrams.toDouble()
            carbsDuration = T.hours(durationHours.toLong()).msecs()
            carbsTimestamp = eventTime
            notes = this@BolusWizard.notes
        }
        persistenceLayer.insertOrUpdateCarbs(
            carbs = detailedBolusInfo.createCarbs(),
            action = Action.EXTENDED_CARBS,
            source = Sources.WizardDialog,
            note = notes.ifEmpty { rh.gs(InterfacesStrings.record) }
        )
    }
}
