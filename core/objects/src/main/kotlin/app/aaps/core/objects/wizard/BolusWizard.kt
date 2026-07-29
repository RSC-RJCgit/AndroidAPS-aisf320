package app.aaps.core.objects.wizard

import android.annotation.SuppressLint
import android.content.Context
import android.text.Spanned
import app.aaps.core.data.model.BCR
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.determineCorrectBolusStepSize
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.LongKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.objects.extensions.formatColor
import app.aaps.core.objects.extensions.highValueToUnitsToString
import app.aaps.core.objects.extensions.lowValueToUnitsToString
import app.aaps.core.objects.extensions.round
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.utils.HtmlHelper
import app.aaps.core.utils.JsonHelper
import android.os.Handler
import android.os.Looper
import java.util.Calendar
import java.util.LinkedList
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class BolusWizard @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val rxBus: RxBus,
    private val preferences: Preferences,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val constraintChecker: ConstraintsChecker,
    private val activePlugin: ActivePlugin,
    private val commandQueue: CommandQueue,
    private val loop: Loop,
    private val iobCobCalculator: IobCobCalculator,
    private val dateUtil: DateUtil,
    private val config: Config,
    private val uel: UserEntryLogger,
    private val automation: Automation,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val uiInteraction: UiInteraction,
    private val persistenceLayer: PersistenceLayer,
    private val decimalFormatter: DecimalFormatter,
    private val processedDeviceStatusData: ProcessedDeviceStatusData
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
        private set
    var trend = 0.0
        private set

    private var accepted = false

    // Per-bolus split bolus settings (set by WizardDialog before confirmAndExecute)
    var manualSplitBolusEnabled: Boolean = false
    var manualSplitBolusIntervalMins: Int = 7
    private var splitBolusScheduled = false  // guard against double-callback

    // Result
    var calculatedTotalInsulin: Double = 0.0
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

    // Input
    lateinit var profile: Profile
    lateinit var profileName: String
    var tempTarget: TT? = null
    var carbs: Int = 0
    // Protein input (grams), converted to a carb-equivalent (protein/25) and added on top of carbs for
    // the insulinFromCarbs calculation only — the raw carbs field itself (what actually gets recorded
    // on the delivered treatment) is untouched, so treatment history still reflects the real carbs eaten.
    var protein: Int = 0
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
    private var carbTime: Int = 0
    private var quickWizard: Boolean = true
    var usePercentage: Boolean = false
    var positiveIOBOnly: Boolean = false

    fun doCalc(
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
        quickWizard: Boolean = false,
        positiveIOBOnly: Boolean = false,
        protein: Int = 0
    ): BolusWizard {

        this.profile = profile
        this.profileName = profileName
        this.tempTarget = tempTarget
        this.carbs = carbs
        this.protein = protein
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
        this.quickWizard = quickWizard
        this.usePercentage = usePercentage
        this.totalPercentage = totalPercentage
        this.positiveIOBOnly = positiveIOBOnly

        // QuickWizard/wizard correction: when the ACTIVE profile percentage is above 100%, calculate
        // the bolus on the 100% (base) profile instead of the boosted rates. The profile scales IC and
        // ISF by 100/percentage, so multiplying both back by percentage/100 recovers the base values.
        // Below 100% the profile is left as-is (only the boost case is capped, per request). This does
        // NOT touch the user's manual percentageCorrection from the wizard dialog.
        val activePct = if (profile is ProfileSealed.EPS) profile.value.originalPercentage else profile.percentage
        val baseScale = if (activePct > 100) activePct / 100.0 else 1.0

        // Insulin from BG
        sens = profileUtil.fromMgdlToUnits(profile.getIsfMgdlForCarbs(dateUtil.now(), "BolusWizard", config, processedDeviceStatusData)) * baseScale
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
        glucoseStatus?.let {
            if (useTrend) {
                trend = it.shortAvgDelta
                insulinFromTrend = profileUtil.fromMgdlToUnits(trend) * 3 / sens
            }
        }

        // Insulin from carbs — base-100 IC when the active profile is boosted (see baseScale above).
        // Protein is folded in as a carb-equivalent (protein/25) added on top of the entered carbs
        // before dividing by IC — the raw carbs field itself stays untouched (used elsewhere for the
        // actual recorded treatment), only this insulin calculation sees the combined value.
        ic = profile.getIc() * baseScale
        insulinFromCarbs = (carbs + protein / 25.0) / ic
        insulinFromCOB = if (useCob) (cob / ic) else 0.0

        // Insulin from IOB
        // IOB calculation
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
            var timeAfter1h = System.currentTimeMillis()
            timeAfter1h += T.hours(1).msecs()
            insulinFromSuperBolus += profile.getBasal(timeAfter1h)
        }

        // Total
        calculatedTotalInsulin = insulinFromBG + insulinFromTrend + insulinFromCarbs + calculatedTotalIOB + insulinFromCorrection + insulinFromSuperBolus + insulinFromCOB

        val percentage = if (usePercentage) totalPercentage else percentageCorrection.toDouble()

        // Percentage adjustment
        totalBeforePercentageAdjustment = calculatedTotalInsulin
        if (calculatedTotalInsulin >= 0) {
            calculatedTotalInsulin = calculatedTotalInsulin * percentage / 100.0
            if (usePercentage)
                calcCorrectionWithConstraints()
            else
                calcPercentageWithConstraints()
            if (usePercentage)  //Should be updated after calcCorrectionWithConstraints and calcPercentageWithConstraints to have correct synthesis in WizardInfo
                this.percentageCorrection = Round.roundTo(totalPercentage, 1.0).toInt()
        } else {
            carbsEquivalent = (-calculatedTotalInsulin) * ic
            calculatedTotalInsulin = 0.0
            calculatedPercentage = percentageCorrection
            calculatedCorrection = 0.0
        }

        val bolusStep = activePlugin.activePump.pumpDescription.bolusStep
        calculatedTotalInsulin = Round.roundTo(calculatedTotalInsulin, bolusStep)

        insulinAfterConstraints = constraintChecker.applyBolusConstraints(ConstraintObject(calculatedTotalInsulin, aapsLogger)).value()

        aapsLogger.debug(this.toString())
        return this
    }

    fun createBolusCalculatorResult(): BCR {
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
            wereCarbsUsed = cob > 0,
            carbsInsulin = insulinFromCarbs,
            otherCorrection = correction,
            wasSuperbolusUsed = useSuperBolus,
            superbolusInsulin = insulinFromSuperBolus,
            wasTempTargetUsed = useTT,
            totalInsulin = calculatedTotalInsulin,
            percentageCorrection = percentageCorrection,
            profileName = profileName,
            note = notes
        )
    }

    private fun confirmMessageAfterConstraints(context: Context, advisor: Boolean, quickWizardEntry: QuickWizardEntry? = null): Spanned {

        val actions: LinkedList<String> = LinkedList()
        if (insulinAfterConstraints > 0) {
            val pct = if (percentageCorrection != 100) " ($percentageCorrection%)" else ""
            actions.add(
                rh.gs(app.aaps.core.ui.R.string.bolus) + ": " + rh.gs(app.aaps.core.ui.R.string.format_insulin_units, insulinAfterConstraints).formatColor
                    (context, rh, app.aaps.core.ui.R.attr.bolusColor) + pct
            )
        }
        if (carbs > 0 && !advisor) {
            var timeShift = ""
            if (carbTime > 0) {
                timeShift += " (+" + rh.gs(app.aaps.core.ui.R.string.mins, carbTime) + ")"
            } else if (carbTime < 0) {
                timeShift += " (" + rh.gs(app.aaps.core.ui.R.string.mins, carbTime) + ")"
            }
            actions.add(
                rh.gs(app.aaps.core.ui.R.string.carbs) + ": " + rh.gs(app.aaps.core.ui.R.string.format_carbs, carbs)
                    .formatColor(context, rh, app.aaps.core.ui.R.attr.carbsColor) + timeShift
            )
        }
        if (insulinFromCOB > 0) {
            actions.add(
                rh.gs(app.aaps.core.ui.R.string.cobvsiob) + ": " + rh.gs(
                    app.aaps.core.ui.R.string.formatsignedinsulinunits,
                    -insulinFromBolusIOB - insulinFromBasalIOB + insulinFromCOB + insulinFromBG
                ).formatColor(
                    context, rh, app.aaps.core.ui.R.attr
                        .cobAlertColor
                )
            )
            val absorptionRate = iobCobCalculator.ads.slowAbsorptionPercentage(60)
            if (absorptionRate > .25)
                actions.add(rh.gs(app.aaps.core.ui.R.string.slowabsorptiondetected, rh.gac(context, app.aaps.core.ui.R.attr.cobAlertColor), (absorptionRate * 100).toInt()))
        }
        if (abs(insulinAfterConstraints - calculatedTotalInsulin) > activePlugin.activePump.pumpDescription.pumpType.determineCorrectBolusStepSize(insulinAfterConstraints))
            actions.add(
                rh.gs(app.aaps.core.ui.R.string.bolus_constraint_applied_warn, calculatedTotalInsulin, insulinAfterConstraints)
                    .formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor)
            )
        if (config.AAPSCLIENT && insulinAfterConstraints > 0)
            actions.add(rh.gs(app.aaps.core.ui.R.string.bolus_recorded_only).formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor))
        if (useAlarm && !advisor && carbs > 0 && carbTime > 0)
            actions.add(rh.gs(app.aaps.core.ui.R.string.alarminxmin, carbTime).formatColor(context, rh, app.aaps.core.ui.R.attr.infoColor))
        if (advisor)
            actions.add(rh.gs(app.aaps.core.ui.R.string.advisoralarm).formatColor(context, rh, app.aaps.core.ui.R.attr.infoColor))

        if (quickWizardEntry != null) {
            val eCarbsYesNo = JsonHelper.safeGetInt(quickWizardEntry.storage, "useEcarbs", QuickWizardEntry.NO)
            if (eCarbsYesNo == QuickWizardEntry.YES) {
                val timeOffset = JsonHelper.safeGetInt(quickWizardEntry.storage, "time", 0)
                val duration = JsonHelper.safeGetInt(quickWizardEntry.storage, "duration", 0)
                val carbs2 = JsonHelper.safeGetInt(quickWizardEntry.storage, "carbs2", 0)

                if (carbs2 > 0) {
                    val ecarbsMessage = rh.gs(app.aaps.core.ui.R.string.format_carbs, carbs2) + "/" + duration + "h (+" + timeOffset + "min)"

                    actions.add(
                        rh.gs(app.aaps.core.ui.R.string.uel_extended_carbs) + ": " + ecarbsMessage.formatColor(context, rh, app.aaps.core.ui.R.attr.infoColor)
                    )
                }
            }
        }

        return HtmlHelper.fromHtml(actions.joinToString("<br/>"))
    }

    fun confirmAndExecute(ctx: Context, quickWizardEntry: QuickWizardEntry? = null) {
        if (calculatedTotalInsulin > 0.0 || carbs > 0.0) {
            if (accepted) {
                aapsLogger.debug(LTag.UI, "guarding: already accepted")
                return
            }
            accepted = true
            if (calculatedTotalInsulin > 0.0)
                automation.removeAutomationEventBolusReminder()
            if (carbs > 0.0)
                automation.removeAutomationEventEatReminder()
            if (preferences.get(BooleanKey.OverviewUseBolusAdvisor) && profileUtil.convertToMgdl(bg, profile.units) > 180 && carbs > 0 && carbTime >= 0)
                OKDialog.showYesNoCancel(
                    ctx, rh.gs(app.aaps.core.ui.R.string.bolus_advisor), rh.gs(app.aaps.core.ui.R.string.bolus_advisor_message),
                    { bolusAdvisorProcessing(ctx) },
                    { commonProcessing(ctx, quickWizardEntry) }
                )
            else
                commonProcessing(ctx, quickWizardEntry)
        } else {
            // Record zero bolus + wizard result — shows on graph and syncs to NS (e.g. IOB covered BG)
            val zeroBolus = BS(
                timestamp = dateUtil.now(),
                amount = 0.0,
                type = BS.Type.NORMAL,
                notes = notes?.ifEmpty { "Wizard: 0U (IOB/COB covered)" } ?: "Wizard: 0U (IOB/COB covered)"
            )
            persistenceLayer.insertOrUpdateBolus(zeroBolus, Action.BOLUS, Sources.WizardDialog, zeroBolus.notes).blockingGet()
            persistenceLayer.insertOrUpdateBolusCalculatorResult(createBolusCalculatorResult()).blockingGet()
            OKDialog.show(ctx, rh.gs(app.aaps.core.ui.R.string.boluswizard), rh.gs(app.aaps.core.ui.R.string.no_action_selected))
        }
    }

    private fun bolusAdvisorProcessing(ctx: Context) {
        val confirmMessage = confirmMessageAfterConstraints(ctx, advisor = true)
        OKDialog.showConfirmation(ctx, rh.gs(app.aaps.core.ui.R.string.boluswizard), confirmMessage, {
            DetailedBolusInfo().apply {
                eventType = TE.Type.CORRECTION_BOLUS
                insulin = insulinAfterConstraints
                carbs = 0.0
                context = ctx
                mgdlGlucose = profileUtil.convertToMgdl(bg, profile.units)
                glucoseType = TE.MeterType.MANUAL
                carbTime = 0
                bolusCalculatorResult = createBolusCalculatorResult()
                notes = this@BolusWizard.notes
                uel.log(
                    action = Action.BOLUS_ADVISOR,
                    source = if (quickWizard) Sources.QuickWizard else Sources.WizardDialog,
                    note = notes,
                    listValues = listOf(
                        ValueWithUnit.TEType(eventType),
                        ValueWithUnit.Insulin(insulinAfterConstraints)
                    )
                )
                if (insulin > 0) {
                    commandQueue.bolus(this, object : Callback() {
                        override fun run() {
                            if (!result.success) {
                                uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                            } else
                                automation.scheduleAutomationEventEatReminder()
                        }
                    })
                }
            }
        })
    }

    fun explainShort(): String {
        var message = rh.gs(app.aaps.core.ui.R.string.wizard_explain_calc, ic, sens)
        message += "\n" + rh.gs(app.aaps.core.ui.R.string.wizard_explain_carbs, insulinFromCarbs)
        if (useTT && tempTarget != null) {
            val tt = if (tempTarget?.lowTarget == tempTarget?.highTarget) tempTarget?.lowValueToUnitsToString(profile.units, decimalFormatter)
            else rh.gs(
                app.aaps.core.ui.R.string.wizard_explain_tt_to,
                tempTarget?.lowValueToUnitsToString(profile.units, decimalFormatter),
                tempTarget?.highValueToUnitsToString(profile.units, decimalFormatter)
            )
            message += "\n" + rh.gs(app.aaps.core.ui.R.string.wizard_explain_tt, tt)
        }
        if (useCob) message += "\n" + rh.gs(app.aaps.core.ui.R.string.wizard_explain_cob, cob, insulinFromCOB)
        if (useBg) message += "\n" + rh.gs(app.aaps.core.ui.R.string.wizard_explain_bg, insulinFromBG)
        if (includeBolusIOB) message += "\n" + rh.gs(app.aaps.core.ui.R.string.wizard_explain_iob, -insulinFromBolusIOB - insulinFromBasalIOB)
        if (useTrend) message += "\n" + rh.gs(app.aaps.core.ui.R.string.wizard_explain_trend, insulinFromTrend)
        if (useSuperBolus) message += "\n" + rh.gs(app.aaps.core.ui.R.string.wizard_explain_superbolus, insulinFromSuperBolus)
        if (percentageCorrection != 100) {
            message += "\n" + rh.gs(app.aaps.core.ui.R.string.wizard_explain_percent, totalBeforePercentageAdjustment, percentageCorrection, calculatedTotalInsulin)
        }
        return message
    }

    @SuppressLint("CheckResult")
    private fun commonProcessing(ctx: Context, quickWizardEntry: QuickWizardEntry? = null) {
        val profile = profileFunction.getProfile() ?: return
        val pump = activePlugin.activePump
        val now = dateUtil.now()

        val confirmMessage = confirmMessageAfterConstraints(ctx, advisor = false, quickWizardEntry)
        OKDialog.showConfirmation(ctx, rh.gs(app.aaps.core.ui.R.string.boluswizard), confirmMessage, {
            if (insulinAfterConstraints > 0 || carbs > 0) {
                if (useSuperBolus) {
                    if (loop.allowedNextModes().contains(RM.Mode.SUPER_BOLUS)) {
                        loop.handleRunningModeChange(
                            durationInMinutes = 2 * 60,
                            profile = profile,
                            newRM = RM.Mode.SUPER_BOLUS,
                            action = Action.SUPERBOLUS_TBR,
                            source = Sources.WizardDialog
                        )
                        rxBus.send(EventRefreshOverview("WizardDialog"))
                    }

                    if (pump.pumpDescription.tempBasalStyle == PumpDescription.ABSOLUTE) {
                        commandQueue.tempBasalAbsolute(0.0, 120, true, profile, PumpSync.TemporaryBasalType.NORMAL, object : Callback() {
                            override fun run() {
                                if (!result.success) {
                                    uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.temp_basal_delivery_error), app.aaps.core.ui.R.raw.boluserror)
                                }
                            }
                        })
                    } else {
                        commandQueue.tempBasalPercent(0, 120, true, profile, PumpSync.TemporaryBasalType.NORMAL, object : Callback() {
                            override fun run() {
                                if (!result.success) {
                                    uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.temp_basal_delivery_error), app.aaps.core.ui.R.raw.boluserror)
                                }
                            }
                        })
                    }
                }
                DetailedBolusInfo().apply {
                    eventType = TE.Type.BOLUS_WIZARD
                    insulin = insulinAfterConstraints
                    carbs = this@BolusWizard.carbs.toDouble()
                    context = ctx
                    mgdlGlucose = profileUtil.convertToMgdl(bg, profile.units)
                    glucoseType = TE.MeterType.MANUAL
                    carbsTimestamp = now + T.mins(this@BolusWizard.carbTime.toLong()).msecs()
                    bolusCalculatorResult = createBolusCalculatorResult()
                    notes = this@BolusWizard.notes
                    if (insulin > 0 || carbs > 0) {
                        val action = when {
                            insulinAfterConstraints == 0.0 -> Action.CARBS
                            carbs == 0.0                   -> Action.BOLUS
                            else                           -> Action.TREATMENT
                        }
                        uel.log(
                            action = action,
                            source = if (quickWizard) Sources.QuickWizard else Sources.WizardDialog,
                            note = notes,
                            listValues = listOfNotNull(
                                ValueWithUnit.TEType(eventType),
                                ValueWithUnit.Insulin(insulinAfterConstraints).takeIf { insulinAfterConstraints != 0.0 },
                                ValueWithUnit.Gram(this@BolusWizard.carbs).takeIf { this@BolusWizard.carbs != 0 },
                                ValueWithUnit.Minute(carbTime).takeIf { carbTime != 0 }
                            )
                        )
                        commandQueue.bolus(this, object : Callback() {
                            override fun run() {
                                if (!result.success) {
                                    uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                                } else {
                                    if (useAlarm && carbs > 0 && carbTime > 0) {
                                        automation.scheduleTimeToEatReminder(T.mins(carbTime.toLong()).secs().toInt())
                                    }
                                    // Schedule the DELAYED BOLUS (not the equal-parts split bolus) when enabled and
                                    // profile is 50%. The SMB preference is deliberately NOT a gate (that was the
                                    // original spec's error): instead SMBs are actively blocked below for the
                                    // delayed-check window, so the delayed dose and SMBs can never double-dose.
                                    // Also scheduled when the immediate dose is 0 but carbs were entered
                                    // (e.g. IOB swallowed the 50% dose).
                                    // Must use activeProfileSwitchPct(), not profile?.percentage directly:
                                    // when the active profile is a ProfileSealed.EPS, .percentage is always
                                    // 100 (baked into the rates) — see activeProfileSwitchPct()'s own comment
                                    // below. Using the raw property here silently missed every EPS-backed 50%
                                    // switch (confirmed via log: a 2026-07-25 delayed bolus never scheduled
                                    // because of exactly this — the adjacent EqualSplitBolus log line in the
                                    // same callback correctly read profilePct=50/type=EPS at the same instant).
                                    val delayedProfilePct = activeProfileSwitchPct()
                                    if ((insulinAfterConstraints > 0 || carbs > 0) &&
                                        preferences.get(BooleanKey.WizardDelayedBolusEnabled) &&
                                        delayedProfilePct == 50
                                    ) {
                                        // FullRequired uses normal IC (= 2× the 50%-profile IC) and includes IOB correction
                                        val normalIc = ic / 2.0
                                        val fullRequired = (carbs / normalIc + insulinFromBolusIOB) * percentageCorrection / 100.0
                                        // Block SMBs for the whole delayed-check window (8 × 10 min + margin).
                                        // DelayedBolusWorker releases the block early on delivery/give-up/cancel.
                                        // Must stay in lock-step with DelayedBolusWorker's own max-attempts (8):
                                        // if this were shorter, SMBs would resume mid-window while the delayed
                                        // dose could still land later, risking a double-dose neither side accounts for.
                                        preferences.put(LongKey.DelayedBolusBlockSmbUntil, dateUtil.now() + T.mins(85).msecs())
                                        aapsLogger.info(LTag.CORE, "Delayed bolus: scheduling WorkManager — profile=${delayedProfilePct}% dose=${insulinAfterConstraints}U fullRequired=${fullRequired}U (normalIC=$normalIc IOB=${insulinFromBolusIOB}U pct=${percentageCorrection}%), SMBs blocked 85 min")
                                        DelayedBolusWorker.enqueue(ctx, insulinAfterConstraints, fullRequired, attempt = 1)
                                        // Careportal marker: delayed-bolus onset (checks follow as Db10/Db20/.../Db80)
                                        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                                            therapyEvent = TE(
                                                timestamp = dateUtil.now(),
                                                type = TE.Type.NOTE,
                                                glucoseUnit = profileFunction.getUnits()
                                            ).also {
                                                it.note = "Db0"
                                                it.duration = T.mins(5).msecs()
                                            },
                                            action = Action.CAREPORTAL,
                                            source = if (quickWizard) Sources.QuickWizard else Sources.WizardDialog,
                                            note = "Delayed bolus scheduled",
                                            listValues = listOf()
                                        ).blockingGet()
                                    }
                                    // Equal-parts split bolus: profile% at or above 100 (a boosted profile still
                                    // needs its correctly-scaled, larger recalculated dose split if it exceeds
                                    // maxBolus — excluding >100 was a bug, not intentional; only a REDUCED
                                    // profile, where the assumptions this recalculation relies on don't hold,
                                    // should be excluded), total > maxBolus, enabled per-bolus.
                                    val schedulingPct = activeProfileSwitchPct()
                                    if (!splitBolusScheduled &&
                                        manualSplitBolusEnabled &&
                                        schedulingPct >= 100 &&
                                        calculatedTotalInsulin > constraintChecker.getMaxBolusAllowed().value()
                                    ) {
                                        splitBolusScheduled = true
                                        val maxPart = constraintChecker.getMaxBolusAllowed().value()
                                        val intervalMins = manualSplitBolusIntervalMins
                                        val numParts = ceil(calculatedTotalInsulin / maxPart).toInt()
                                        if (numParts > 1) {
                                            val totalPlannedDurationMins = (numParts - 1) * intervalMins
                                            // Reduced/gated mode: long interval (>10min), or protein's own insulin
                                            // contribution at least matches carbs' own — (protein/25)/ic >= carbs/ic,
                                            // which simplifies to protein/25 >= carbs since ic cancels out of both
                                            // sides. These are cases where a fixed rapid equal-parts split is less
                                            // appropriate than a slower, BG-gated trickle: SMBs are left unblocked
                                            // (the point is to let them help instead), and each part is deliberately
                                            // smaller than a full maxPart share — scaled down further the longer the
                                            // overall planned span is — trading completeness of the calculated total
                                            // for safety margin when circumstances suggest less certainty.
                                            val insulinFromProteinOnly = (protein / 25.0) / ic
                                            val insulinFromCarbsOnly = carbs / ic
                                            val reducedMode = intervalMins > 10 || insulinFromProteinOnly >= insulinFromCarbsOnly
                                            if (reducedMode) {
                                                val reducedFraction = when {
                                                    totalPlannedDurationMins <= 20 -> 0.50
                                                    totalPlannedDurationMins <= 30 -> 0.25
                                                    totalPlannedDurationMins <= 40 -> 0.10
                                                    else -> 0.0
                                                }
                                                val residual = calculatedTotalInsulin - maxPart // part 1 already delivered synchronously above
                                                if (reducedFraction > 0.0) {
                                                    val partDose = Round.roundTo(maxPart * reducedFraction, activePlugin.activePump.pumpDescription.bolusStep)
                                                    aapsLogger.info(
                                                        LTag.CORE,
                                                        "ReducedSplitBolus: residual ${residual}U, ${partDose}U every ${intervalMins}min " +
                                                            "(${(reducedFraction * 100).toInt()}% of a full part, planned span ${totalPlannedDurationMins}min), SMBs allowed, BG-gated"
                                                    )
                                                    scheduleReducedPartsSplitBolus(residual, partDose, intervalMins, schedulingPct)
                                                } else {
                                                    aapsLogger.info(
                                                        LTag.CORE,
                                                        "ReducedSplitBolus: planned span ${totalPlannedDurationMins}min exceeds 40min — residual ${residual}U not delivered, no further parts scheduled"
                                                    )
                                                }
                                            } else {
                                                val blockMs = T.mins(totalPlannedDurationMins.toLong()).msecs()
                                                preferences.put(LongKey.SplitBolusBlockSmbUntil, dateUtil.now() + blockMs)
                                                val lastPart = Round.roundTo(calculatedTotalInsulin - (numParts - 1) * maxPart, activePlugin.activePump.pumpDescription.bolusStep)
                                                val splitDesc = if (lastPart < maxPart)
                                                    "${numParts - 1}×${maxPart}U + ${lastPart}U"
                                                else
                                                    "${numParts}×${maxPart}U"
                                                aapsLogger.info(LTag.CORE, "EqualSplitBolus: scheduled $splitDesc every ${intervalMins}min, SMBs blocked ${totalPlannedDurationMins}min")
                                                scheduleEqualPartsSplitBolus(calculatedTotalInsulin, maxPart, intervalMins, 2, numParts, schedulingPct)
                                            }
                                        }
                                    }
                                }
                            }
                        })
                    }
                    bolusCalculatorResult?.let { persistenceLayer.insertOrUpdateBolusCalculatorResult(it).blockingGet() }
                    // Record zero bolus on graph/NS when insulin is 0 (e.g. IOB covers BG, only carbs logged)
                    if (insulinAfterConstraints == 0.0) {
                        val zeroBolus = BS(
                            timestamp = now,
                            amount = 0.0,
                            type = BS.Type.NORMAL,
                            notes = notes?.ifEmpty { "Wizard: 0U (IOB covered)" } ?: "Wizard: 0U (IOB covered)"
                        )
                        persistenceLayer.insertOrUpdateBolus(zeroBolus, Action.BOLUS, Sources.WizardDialog, zeroBolus.notes).blockingGet()
                    }
                }
            }
            if (quickWizardEntry != null) {
                scheduleECarbsFromQuickWizard(ctx, quickWizardEntry)
            }
        })
    }

    // Returns the active profile switch percentage. EPS bakes percentage into rates (pct=100),
    // so we must read originalPercentage from the raw EPS value — same approach as OpenAPSAutoISFPlugin.
    private fun activeProfileSwitchPct(): Int {
        val profile = profileFunction.getProfile()
        val pct = if (profile is ProfileSealed.EPS) profile.value.originalPercentage else profile?.percentage ?: 100
        aapsLogger.info(LTag.CORE, "EqualSplitBolus: profilePct=$pct (type=${profile?.javaClass?.simpleName})")
        return pct
    }

    private fun scheduleEqualPartsSplitBolus(totalDose: Double, maxPart: Double, intervalMins: Int, partNumber: Int, totalParts: Int, schedulingPct: Int, deliverAt: Long = dateUtil.now() + T.mins(intervalMins.toLong()).msecs()) {
        if (partNumber > totalParts) return
        val partDose = Round.roundTo(
            min(maxPart, totalDose - (partNumber - 1) * maxPart),
            activePlugin.activePump.pumpDescription.bolusStep
        )
        if (partDose <= 0) return
        // Poll every 2 min so profile% changes are caught quickly, not just at delivery time
        val pollMs = T.mins(2).msecs()
        val delayMs = min(pollMs, max(1000L, deliverAt - dateUtil.now()))
        aapsLogger.debug(LTag.CORE, "EqualSplitBolus: scheduling part $partNumber/$totalParts in ${delayMs/1000}s, deliverAt=${dateUtil.timeString(deliverAt)}")
        Handler(Looper.getMainLooper()).postDelayed({
            if (BolusProgressData.followUpBolusCancelled) {
                preferences.put(LongKey.SplitBolusBlockSmbUntil, 0L)
                aapsLogger.info(LTag.CORE, "EqualSplitBolus: bolus was stopped — cancelling part $partNumber/$totalParts, SMBs unblocked")
                return@postDelayed
            }
            val pct = activeProfileSwitchPct()
            aapsLogger.info(LTag.CORE, "EqualSplitBolus: poll for part $partNumber/$totalParts — profileSwitch%=$pct, scheduledAt=$schedulingPct%, deliverAt=${dateUtil.timeString(deliverAt)}, now=${dateUtil.timeString(dateUtil.now())}")
            // Absolute, not relative to schedulingPct: only cancel on a drop into genuinely reduced
            // (sub-100%) territory — e.g. a hypo-driven switch to 50% — which is a real safety concern.
            // A drop from a boosted percentage (say 110%) back down to plain 100% is NOT a risk state;
            // 100% is normal baseline, not "reduced", so it shouldn't abort an in-flight residual.
            // schedulingPct is kept only for the log line above, not for this comparison.
            if (pct < 100) {
                preferences.put(LongKey.SplitBolusBlockSmbUntil, 0L)
                aapsLogger.info(LTag.CORE, "EqualSplitBolus: profile switch at $pct% (scheduled at $schedulingPct%) — cancelling part $partNumber/$totalParts, SMBs unblocked")
                return@postDelayed
            }
            if (dateUtil.now() < deliverAt) {
                // Interval not elapsed yet — poll again
                scheduleEqualPartsSplitBolus(totalDose, maxPart, intervalMins, partNumber, totalParts, schedulingPct, deliverAt)
                return@postDelayed
            }
            DetailedBolusInfo().apply {
                eventType = TE.Type.CORRECTION_BOLUS
                insulin = partDose
                notes = "Split bolus part $partNumber/$totalParts"
                uel.log(
                    action = Action.BOLUS,
                    source = Sources.WizardDialog,
                    note = notes,
                    listValues = listOf(ValueWithUnit.Insulin(partDose))
                )
                commandQueue.bolus(this, object : Callback() {
                    override fun run() {
                        if (!result.success)
                            uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                        else
                            scheduleEqualPartsSplitBolus(totalDose, maxPart, intervalMins, partNumber + 1, totalParts, schedulingPct)
                    }
                })
            }
        }, delayMs)
    }

    // Reduced/gated split-bolus delivery — used instead of scheduleEqualPartsSplitBolus when the
    // interval is long (>10min) or protein's own contribution dominates (see the reducedMode check at
    // the call site). Unlike the equal-parts scheduler, every part is a FIXED size (partDose, already
    // scaled down from a full maxPart share) rather than sized to exactly complete the calculated
    // total — deliberately trades completeness for caution. Delivers partDose repeatedly every
    // intervalMins until remainingResidual is exhausted, gated before EACH delivery on: not cancelled,
    // profile% still >=100 (same check as the equal-parts scheduler), AND a live BG safety check
    // (>=7.0mmol, not falling on either delta) — any failure cancels all further parts. SMBs are never
    // blocked in this mode (see the call site — SplitBolusBlockSmbUntil is deliberately not set).
    private fun scheduleReducedPartsSplitBolus(
        remainingResidual: Double, partDose: Double, intervalMins: Int, schedulingPct: Int,
        deliverAt: Long = dateUtil.now() + T.mins(intervalMins.toLong()).msecs()
    ) {
        if (remainingResidual <= 0 || partDose <= 0) return
        val pollMs = T.mins(2).msecs()
        val delayMs = min(pollMs, max(1000L, deliverAt - dateUtil.now()))
        aapsLogger.debug(LTag.CORE, "ReducedSplitBolus: scheduling ${partDose}U in ${delayMs / 1000}s, remaining residual ${remainingResidual}U, deliverAt=${dateUtil.timeString(deliverAt)}")
        Handler(Looper.getMainLooper()).postDelayed({
            if (BolusProgressData.followUpBolusCancelled) {
                aapsLogger.info(LTag.CORE, "ReducedSplitBolus: bolus was stopped — cancelling remaining ${remainingResidual}U")
                return@postDelayed
            }
            val pct = activeProfileSwitchPct()
            if (pct < 100) {
                aapsLogger.info(LTag.CORE, "ReducedSplitBolus: profile switch at $pct% (scheduled at $schedulingPct%) — cancelling remaining ${remainingResidual}U")
                return@postDelayed
            }
            if (dateUtil.now() < deliverAt) {
                scheduleReducedPartsSplitBolus(remainingResidual, partDose, intervalMins, schedulingPct, deliverAt)
                return@postDelayed
            }
            // Live BG safety gate — checked fresh at delivery time via glucoseStatusProvider, not the
            // wizard's own glucoseStatus field (that was only ever captured once, at calc time).
            val gs = glucoseStatusProvider.glucoseStatusData
            val bgOk = gs != null && gs.glucose >= 126.1 /* 7.0 mmol */ && gs.delta > -0.90 /* -0.05 mmol */ && gs.shortAvgDelta > -0.90 /* -0.05 mmol */
            if (!bgOk) {
                aapsLogger.info(LTag.CORE, "ReducedSplitBolus: BG safety check failed (g=${gs?.glucose} d=${gs?.delta} sd=${gs?.shortAvgDelta}) — cancelling remaining ${remainingResidual}U")
                return@postDelayed
            }
            val thisDose = Round.roundTo(min(partDose, remainingResidual), activePlugin.activePump.pumpDescription.bolusStep)
            if (thisDose <= 0) return@postDelayed
            DetailedBolusInfo().apply {
                eventType = TE.Type.CORRECTION_BOLUS
                insulin = thisDose
                notes = "Reduced split bolus part"
                uel.log(
                    action = Action.BOLUS,
                    source = Sources.WizardDialog,
                    note = notes,
                    listValues = listOf(ValueWithUnit.Insulin(thisDose))
                )
                commandQueue.bolus(this, object : Callback() {
                    override fun run() {
                        if (!result.success)
                            uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                        else
                            scheduleReducedPartsSplitBolus(remainingResidual - thisDose, partDose, intervalMins, schedulingPct)
                    }
                })
            }
        }, delayMs)
    }

    private fun scheduleECarbsFromQuickWizard(ctx: Context, quickWizardEntry: QuickWizardEntry) {
        val eCarbsYesNo = JsonHelper.safeGetInt(quickWizardEntry.storage, "useEcarbs", QuickWizardEntry.NO)
        if (eCarbsYesNo == QuickWizardEntry.YES) {
            val timeOffset = JsonHelper.safeGetInt(quickWizardEntry.storage, "time", 0)
            val duration = JsonHelper.safeGetInt(quickWizardEntry.storage, "duration", 0)
            val carbs2 = JsonHelper.safeGetInt(quickWizardEntry.storage, "carbs2", 0)

            val currentTime = Calendar.getInstance().timeInMillis
            val eventTime: Long = currentTime + (timeOffset * 60000)

            if (carbs2 > 0) {
                val detailedBolusInfo = DetailedBolusInfo()
                detailedBolusInfo.eventType = TE.Type.CORRECTION_BOLUS
                detailedBolusInfo.carbs = carbs2.toDouble()
                detailedBolusInfo.context = ctx
                detailedBolusInfo.notes = quickWizardEntry.storage.get("buttonText").toString()
                detailedBolusInfo.carbsDuration = T.hours(duration.toLong()).msecs()
                detailedBolusInfo.carbsTimestamp = eventTime
                uel.log(
                    action = Action.EXTENDED_CARBS,
                    source = Sources.QuickWizard,
                    note = quickWizardEntry.storage.get("buttonText").toString(),
                    listValues = listOfNotNull(
                        ValueWithUnit.Timestamp(eventTime),
                        ValueWithUnit.Gram(carbs2),
                        ValueWithUnit.Minute(timeOffset).takeIf { timeOffset != 0 },
                        ValueWithUnit.Hour(duration).takeIf { duration != 0 }
                    )
                )
                commandQueue.bolus(detailedBolusInfo, object : Callback() {
                    override fun run() {
                        if (!result.success) {
                            uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                            /* } else {
                                 val messageECarbs =
                                     rh.gs(app.aaps.core.ui.R.string.uel_extended_carbs) + "\n" + "@" + dateUtil.timeString(eventTime) + " " + carbs2 + "g/" + duration + "h"
                                 ToastUtils.Long.infoToast(result.context, messageECarbs)*/
                        }
                    }
                })
            }

        }
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

}
