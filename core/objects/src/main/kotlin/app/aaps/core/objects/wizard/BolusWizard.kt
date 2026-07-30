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
import app.aaps.core.interfaces.pump.ScheduledDoseSupersession
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
    // Decomposed halves of insulinFromCarbs (which is their sum) — exposed so callers (the reducedMode
    // split-bolus check below, and WizardDialog's live max-bolus-override default) can compare protein's
    // own contribution against carbs' own, without each needing to recompute carbs/ic and protein/25/ic
    // separately.
    var insulinFromCarbsOnly = 0.0
        private set
    var insulinFromProteinOnly = 0.0
        private set
    // Fat's own contribution — same pattern as protein: folded in as a carb-equivalent (fat/11, vs
    // protein's fat/25) before dividing by IC, so it shares insulinFromCarbs' unit conversion.
    var insulinFromFatOnly = 0.0
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
    private var proteinDoseScheduled = false // guard against double-callback (protein's single +120min dose)
    private var fatDoseScheduled = false     // guard against double-callback (fat's single +180min dose)

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
    // Fat input (grams), converted to a carb-equivalent (fat/11) and added on top of carbs (and protein)
    // for the insulinFromCarbs calculation only — same treatment as protein above, different divisor.
    var fat: Int = 0
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
        protein: Int = 0,
        fat: Int = 0
    ): BolusWizard {

        this.profile = profile
        this.profileName = profileName
        this.tempTarget = tempTarget
        this.carbs = carbs
        this.protein = protein
        this.fat = fat
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
        // Protein and fat are each computed as their own carb-equivalent (protein/25, fat/11) divided by
        // IC, but are NO LONGER folded into insulinFromCarbs/calculatedTotalInsulin — they're delivered
        // entirely separately, as their own single fixed-time doses (see the protein/fat scheduling in
        // commonProcessing()'s success callback: one dose at +120min for protein, one at +180min for
        // fat), independent of the immediate bolus and of the carb-split schedule below.
        ic = profile.getIc() * baseScale
        insulinFromCarbsOnly = carbs / ic
        insulinFromProteinOnly = (protein / 25.0) / ic
        insulinFromFatOnly = (fat / 11.0) / ic
        insulinFromCarbs = insulinFromCarbsOnly
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
        // insulinFromProteinOnly/insulinFromFatOnly are deliberately excluded from calculatedTotalInsulin
        // (see their own doc comments) — but a protein-only or fat-only entry (no carbs, no other
        // component) still needs to reach commonProcessing() below to schedule its delayed dose, or it
        // silently falls into the "no action selected" branch and the previewed dose never gets scheduled.
        if (calculatedTotalInsulin > 0.0 || carbs > 0.0 || insulinFromProteinOnly > 0.0 || insulinFromFatOnly > 0.0) {
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
            // Same protein/fat-only fix as confirmAndExecute()'s own guard above.
            if (insulinAfterConstraints > 0 || carbs > 0 || insulinFromProteinOnly > 0.0 || insulinFromFatOnly > 0.0) {
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
                    // Same protein/fat-only fix as confirmAndExecute()'s own guard above — otherwise a
                    // protein-only/fat-only entry never reaches commandQueue.bolus() at all, so the
                    // previewed delayed dose silently never gets scheduled.
                    if (insulin > 0 || carbs > 0 || insulinFromProteinOnly > 0.0 || insulinFromFatOnly > 0.0) {
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
                                    // Carb-split bolus: profile% at or above 100 (a boosted profile still needs
                                    // its correctly-scaled, larger recalculated dose split if it exceeds maxBolus
                                    // — excluding >100 was a bug, not intentional; only a REDUCED profile, where
                                    // the assumptions this recalculation relies on don't hold, should be excluded),
                                    // total > maxBolus, enabled per-bolus. SMBs are never blocked here — the old
                                    // equal-parts/SplitBolusBlockSmbUntil mode is gone entirely; every split is
                                    // BG-gated and IOB-delta-sized instead (see scheduleReducedPartsSplitBolus).
                                    val schedulingPct = activeProfileSwitchPct()
                                    // Superbolus (this wizard's own useSuperBolus above, OR any other still-active
                                    // one) blocks carb-split/protein/fat scheduling entirely — a superbolus already
                                    // bakes in its own specific 2h TBR-suspension tradeoff; layering split/delayed
                                    // doses on top of that window risks over-delivery neither side accounts for.
                                    val superBolusActive = loop.runningMode == RM.Mode.SUPER_BOLUS
                                    // Claims this confirmation's own supersession token — see
                                    // ScheduledDoseSupersession's own doc comment. Bumping here (rather than
                                    // only reading it) means THIS confirmation also retires any earlier
                                    // still-pending schedule (from a previous wizard confirm, a manual bolus
                                    // via InsulinDialog, or carbs entered via CarbsDialog), not just the
                                    // reverse.
                                    val myScheduleToken = ScheduledDoseSupersession.bump()
                                    // IOB right before THIS (the first/immediate) dose, from doCalc()'s own fields.
                                    val iobBeforeFirstDose = insulinFromBolusIOB + insulinFromBasalIOB
                                    // Sum of everything actually scheduled below (carb-split residual, protein,
                                    // fat) — used for the single combined CarePortal note after all three checks,
                                    // so it only counts what really got scheduled, not just what was calculated.
                                    var totalProjectedFutureSplitDoses = 0.0
                                    if (!splitBolusScheduled &&
                                        !superBolusActive &&
                                        manualSplitBolusEnabled &&
                                        schedulingPct >= 100 &&
                                        calculatedTotalInsulin > constraintChecker.getMaxBolusAllowed().value()
                                    ) {
                                        splitBolusScheduled = true
                                        val maxPart = constraintChecker.getMaxBolusAllowed().value()
                                        val intervalMins = manualSplitBolusIntervalMins
                                        val numParts = ceil(calculatedTotalInsulin / maxPart).toInt()
                                        if (numParts > 1) {
                                            val residual = calculatedTotalInsulin - maxPart // part 1 (100% of maxPart) already delivered synchronously above
                                            // Becomes the baseline the first scheduled split's IOB-delta is measured
                                            // against once maxPart (this dose) is added to it, matching "gap start =
                                            // IOB before a dose + that dose's own amount" throughout.
                                            aapsLogger.info(
                                                LTag.CORE,
                                                "ReducedSplitBolus: residual ${residual}U, first scheduled part starts from ${maxPart}U every ${intervalMins}min, SMBs allowed, BG- and IOBdelta-gated"
                                            )
                                            scheduleReducedPartsSplitBolus(residual, maxPart, iobBeforeFirstDose + maxPart, intervalMins, schedulingPct, myScheduleToken)
                                            totalProjectedFutureSplitDoses += residual
                                        }
                                    }
                                    // Shared baseline for protein's/fat's OWN IOB-delta reduction: IOB right after
                                    // the actual delivered immediate dose (insulinAfterConstraints — carbs/BG/etc
                                    // only, since protein/fat are excluded from it) joined the pool. A lot of SMBs
                                    // firing between now and each one's own +120/+180min delivery point can already
                                    // cover much of the anticipated protein/fat-driven rise — see scheduleSingleDelayedDose.
                                    val iobBaselineForDelayedDoses = iobBeforeFirstDose + insulinAfterConstraints
                                    // Protein: one single dose at +120min. Decoupled from manualSplitBolusEnabled
                                    // (the "Split bolus every" checkbox) entirely — fires whenever protein was
                                    // entered, regardless of whether carb-splitting is enabled/needed for this
                                    // bolus — its own amount, its own timing, its own gate. Still requires
                                    // schedulingPct >= 100, same reasoning as carb-splits: at any other percentage
                                    // the carb-equivalent this amount was computed from no longer reflects reality.
                                    if (!proteinDoseScheduled && !superBolusActive && schedulingPct >= 100 && insulinFromProteinOnly > 0.0) {
                                        proteinDoseScheduled = true
                                        aapsLogger.info(LTag.CORE, "DelayedDose(protein): scheduling ${insulinFromProteinOnly}U at +120min, BG- and IOBdelta-gated")
                                        scheduleSingleDelayedDose(insulinFromProteinOnly, 120, "protein", schedulingPct, iobBaselineForDelayedDoses, myScheduleToken)
                                        totalProjectedFutureSplitDoses += insulinFromProteinOnly
                                    }
                                    // Fat: one single dose at +180min. Same decoupling/preconditions as protein above
                                    // — fully independent of the carb-split schedule and of protein's own dose.
                                    if (!fatDoseScheduled && !superBolusActive && schedulingPct >= 100 && insulinFromFatOnly > 0.0) {
                                        fatDoseScheduled = true
                                        aapsLogger.info(LTag.CORE, "DelayedDose(fat): scheduling ${insulinFromFatOnly}U at +180min, BG- and IOBdelta-gated")
                                        scheduleSingleDelayedDose(insulinFromFatOnly, 180, "fat", schedulingPct, iobBaselineForDelayedDoses, myScheduleToken)
                                        totalProjectedFutureSplitDoses += insulinFromFatOnly
                                    }
                                    // Single combined CarePortal note marking that split/protein/fat dosing was just
                                    // scheduled, with the total amount still to come (e.g. "S1.30") — separate from
                                    // each individual part's own delivered-treatment note, so there's one visible
                                    // marker at the moment of scheduling itself, not just after each part lands.
                                    if (totalProjectedFutureSplitDoses > 0.0) {
                                        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                                            therapyEvent = TE(
                                                timestamp = dateUtil.now(),
                                                type = TE.Type.NOTE,
                                                glucoseUnit = profileFunction.getUnits()
                                            ).also {
                                                it.note = "S${decimalFormatter.to2Decimal(totalProjectedFutureSplitDoses)}"
                                                it.duration = T.mins(1).msecs()
                                            },
                                            action = Action.CAREPORTAL,
                                            source = if (quickWizard) Sources.QuickWizard else Sources.WizardDialog,
                                            note = "Split/protein/fat dosing scheduled",
                                            listValues = listOf()
                                        ).blockingGet()
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
        aapsLogger.info(LTag.CORE, "SplitBolus: profilePct=$pct (type=${profile?.javaClass?.simpleName})")
        return pct
    }

    // Live total active insulin (bolus + basal IOB), matching the same calculation doCalc() itself uses
    // for insulinFromBolusIOB/insulinFromBasalIOB, but re-fetched fresh at scheduling-check time rather
    // than the wizard's own calc-time snapshot — needed for the IOB-delta split-sizing check below.
    private fun currentTotalIob(): Double {
        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
        return bolusIob.iob + basalIob.basaliob
    }

    // True if the pump itself reports being suspended (not delivering insulin) — checked fresh at
    // delivery time, same pattern as the other gates. Deliberately does NOT also check
    // loop.runningMode.isSuspended() — that also covers RM.Mode.SUPER_BOLUS, which this same wizard can
    // itself set as a normal, non-alarming part of a superbolus-enabled delivery (see useSuperBolus
    // above), so using it here would false-positive and cancel every split/delayed dose whenever a
    // legitimate superbolus is in progress.
    private fun pumpUnavailable(): Boolean = activePlugin.activePump.isSuspended()

    // Creates a CarePortal note marking that a pending split/protein/fat dose was cancelled — the
    // counterpart to the "S<amount>" note created when the schedule was first set (see commonProcessing()),
    // so both the start and any early end of a split/delayed-dose sequence are visible on the graph/
    // history. `amount` is whatever's being dropped (the carb-split's remaining residual, or the
    // protein/fat dose itself); `reason` is a short human-readable description, kept out of the compact
    // on-graph label but included in the audit-log note visible in NS/UE history.
    private fun cancelDoseNote(amount: Double, reason: String) {
        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = TE(
                timestamp = dateUtil.now(),
                type = TE.Type.NOTE,
                glucoseUnit = profileFunction.getUnits()
            ).also {
                it.note = "C${decimalFormatter.to2Decimal(amount)}" // e.g. "C1.30" — 5 chars, matches the graph's 5-char note truncation
                it.duration = T.mins(1).msecs()
            },
            action = Action.CAREPORTAL,
            source = if (quickWizard) Sources.QuickWizard else Sources.WizardDialog,
            note = "Split/protein/fat dose cancelled: $reason",
            listValues = listOf()
        ).blockingGet()
    }

    // Reduced/gated carb-split delivery. Every part is gated before delivery on: not cancelled (either by
    // an explicit stop press, OR by a newer bolus/carbs entry superseding this schedule — see
    // ScheduledDoseSupersession), profile% still >=100, pump not suspended, AND a live BG safety check
    // (>=7.0mmol, not falling on either delta) — any failure cancels all further parts. SMBs are never
    // blocked (the old equal-parts/SplitBolusBlockSmbUntil mode is gone entirely).
    //
    // Dose sizing is IOB-delta driven: iobBaselineForNextGap is "IOB right before the previous dose was
    // given, plus that dose's own amount" (i.e. IOB right AFTER the previous dose joined the pool). At
    // delivery time, iobIncrease = max(0, liveIob - iobBaselineForNextGap) — only a genuine rise counts;
    // if IOB net fell during the gap (decay outpacing anything new), the dose stays at previousPartDose,
    // never gets boosted. thisDose = previousPartDose - iobIncrease; if that's <=0, the whole remaining
    // residual is dropped (not just this one part skipped) — matching the old "nil tier" in spirit, now
    // driven by IOB rather than elapsed time.
    private fun scheduleReducedPartsSplitBolus(
        remainingResidual: Double, previousPartDose: Double, iobBaselineForNextGap: Double, intervalMins: Int, schedulingPct: Int, myScheduleToken: Long,
        deliverAt: Long = dateUtil.now() + T.mins(intervalMins.toLong()).msecs()
    ) {
        if (remainingResidual <= 0) return
        val pollMs = T.mins(2).msecs()
        val delayMs = min(pollMs, max(1000L, deliverAt - dateUtil.now()))
        aapsLogger.debug(LTag.CORE, "ReducedSplitBolus: scheduling next part in ${delayMs / 1000}s, remaining residual ${remainingResidual}U, deliverAt=${dateUtil.timeString(deliverAt)}")
        Handler(Looper.getMainLooper()).postDelayed({
            if (BolusProgressData.followUpBolusCancelled) {
                aapsLogger.info(LTag.CORE, "ReducedSplitBolus: bolus was stopped — cancelling remaining ${remainingResidual}U")
                cancelDoseNote(remainingResidual, "bolus stopped")
                return@postDelayed
            }
            if (!ScheduledDoseSupersession.isCurrent(myScheduleToken)) {
                aapsLogger.info(LTag.CORE, "ReducedSplitBolus: superseded by a newer bolus/carbs entry — cancelling remaining ${remainingResidual}U")
                cancelDoseNote(remainingResidual, "superseded by newer entry")
                return@postDelayed
            }
            val pct = activeProfileSwitchPct()
            if (pct < 100) {
                aapsLogger.info(LTag.CORE, "ReducedSplitBolus: profile switch at $pct% (scheduled at $schedulingPct%) — cancelling remaining ${remainingResidual}U")
                cancelDoseNote(remainingResidual, "profile switch to $pct%")
                return@postDelayed
            }
            if (pumpUnavailable()) {
                aapsLogger.info(LTag.CORE, "ReducedSplitBolus: pump suspended — cancelling remaining ${remainingResidual}U")
                cancelDoseNote(remainingResidual, "pump suspended")
                return@postDelayed
            }
            if (loop.runningMode == RM.Mode.SUPER_BOLUS) {
                aapsLogger.info(LTag.CORE, "ReducedSplitBolus: superbolus active — cancelling remaining ${remainingResidual}U")
                cancelDoseNote(remainingResidual, "superbolus active")
                return@postDelayed
            }
            if (dateUtil.now() < deliverAt) {
                scheduleReducedPartsSplitBolus(remainingResidual, previousPartDose, iobBaselineForNextGap, intervalMins, schedulingPct, myScheduleToken, deliverAt)
                return@postDelayed
            }
            // Live BG safety gate — checked fresh at delivery time via glucoseStatusProvider, not the
            // wizard's own glucoseStatus field (that was only ever captured once, at calc time).
            val gs = glucoseStatusProvider.glucoseStatusData
            val bgOk = gs != null && gs.glucose >= 126.1 /* 7.0 mmol */ && gs.delta > -0.90 /* -0.05 mmol */ && gs.shortAvgDelta > -0.90 /* -0.05 mmol */
            if (!bgOk) {
                aapsLogger.info(LTag.CORE, "ReducedSplitBolus: BG safety check failed (g=${gs?.glucose} d=${gs?.delta} sd=${gs?.shortAvgDelta}) — cancelling remaining ${remainingResidual}U")
                cancelDoseNote(remainingResidual, "BG safety check failed")
                return@postDelayed
            }
            val liveIob = currentTotalIob()
            val iobIncrease = max(0.0, liveIob - iobBaselineForNextGap)
            val thisDose = Round.roundTo(min(previousPartDose - iobIncrease, remainingResidual), activePlugin.activePump.pumpDescription.bolusStep)
            if (thisDose <= 0) {
                aapsLogger.info(LTag.CORE, "ReducedSplitBolus: IOB rose ${iobIncrease}U since last split (baseline ${iobBaselineForNextGap}U, now ${liveIob}U) — next dose would be <=0, stopping remaining ${remainingResidual}U")
                cancelDoseNote(remainingResidual, "IOB rose ${decimalFormatter.to2Decimal(iobIncrease)}U")
                return@postDelayed
            }
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
                        else {
                            val newResidual = remainingResidual - thisDose
                            if (newResidual > 0.0)
                                scheduleReducedPartsSplitBolus(newResidual, thisDose, liveIob + thisDose, intervalMins, schedulingPct, myScheduleToken)
                        }
                    }
                })
            }
        }, delayMs)
    }

    // Protein's/fat's single fixed-time delayed dose (see the call sites, +120min/+180min respectively).
    // Gated at delivery time on the SAME combined criteria as carb splits: not cancelled (stop press or
    // superseded by a newer entry), profile% still >=100, pump not suspended, superbolus not active, and
    // the live BG safety check — any failure skips this one dose entirely (no retry, since there's only
    // ever this one delivery for this contribution).
    //
    // Dose sizing is ALSO IOB-delta reduced, same principle as scheduleReducedPartsSplitBolus: a lot of
    // SMBs (or anything else) firing between now and this delivery point may have already covered much of
    // the anticipated protein/fat-driven rise, so iobBaseline (IOB right after the immediate dose was
    // delivered — shared by both protein's and fat's own call, see the call site) is compared against the
    // live IOB at delivery time; only a genuine rise reduces the dose, and dropping to <=0 cancels it
    // entirely (no partial delivery, no retry).
    private fun scheduleSingleDelayedDose(
        dose: Double, delayMins: Int, label: String, schedulingPct: Int, iobBaseline: Double, myScheduleToken: Long,
        deliverAt: Long = dateUtil.now() + T.mins(delayMins.toLong()).msecs()
    ) {
        val pollMs = T.mins(2).msecs()
        val delayMs = min(pollMs, max(1000L, deliverAt - dateUtil.now()))
        aapsLogger.debug(LTag.CORE, "DelayedDose($label): scheduling ${dose}U in ${delayMs / 1000}s, deliverAt=${dateUtil.timeString(deliverAt)}")
        Handler(Looper.getMainLooper()).postDelayed({
            if (BolusProgressData.followUpBolusCancelled) {
                aapsLogger.info(LTag.CORE, "DelayedDose($label): bolus was stopped — cancelling ${dose}U")
                cancelDoseNote(dose, "$label dose: bolus stopped")
                return@postDelayed
            }
            if (!ScheduledDoseSupersession.isCurrent(myScheduleToken)) {
                aapsLogger.info(LTag.CORE, "DelayedDose($label): superseded by a newer bolus/carbs entry — cancelling ${dose}U")
                cancelDoseNote(dose, "$label dose: superseded by newer entry")
                return@postDelayed
            }
            val pct = activeProfileSwitchPct()
            if (pct < 100) {
                aapsLogger.info(LTag.CORE, "DelayedDose($label): profile switch at $pct% (scheduled at $schedulingPct%) — cancelling ${dose}U")
                cancelDoseNote(dose, "$label dose: profile switch to $pct%")
                return@postDelayed
            }
            if (pumpUnavailable()) {
                aapsLogger.info(LTag.CORE, "DelayedDose($label): pump suspended — cancelling ${dose}U")
                cancelDoseNote(dose, "$label dose: pump suspended")
                return@postDelayed
            }
            if (loop.runningMode == RM.Mode.SUPER_BOLUS) {
                aapsLogger.info(LTag.CORE, "DelayedDose($label): superbolus active — cancelling ${dose}U")
                cancelDoseNote(dose, "$label dose: superbolus active")
                return@postDelayed
            }
            if (dateUtil.now() < deliverAt) {
                scheduleSingleDelayedDose(dose, delayMins, label, schedulingPct, iobBaseline, myScheduleToken, deliverAt)
                return@postDelayed
            }
            val gs = glucoseStatusProvider.glucoseStatusData
            val bgOk = gs != null && gs.glucose >= 126.1 /* 7.0 mmol */ && gs.delta > -0.90 /* -0.05 mmol */ && gs.shortAvgDelta > -0.90 /* -0.05 mmol */
            if (!bgOk) {
                aapsLogger.info(LTag.CORE, "DelayedDose($label): BG safety check failed (g=${gs?.glucose} d=${gs?.delta} sd=${gs?.shortAvgDelta}) — cancelling ${dose}U")
                cancelDoseNote(dose, "$label dose: BG safety check failed")
                return@postDelayed
            }
            val liveIob = currentTotalIob()
            val iobIncrease = max(0.0, liveIob - iobBaseline)
            val thisDose = Round.roundTo(dose - iobIncrease, activePlugin.activePump.pumpDescription.bolusStep)
            if (thisDose <= 0) {
                aapsLogger.info(LTag.CORE, "DelayedDose($label): IOB rose ${iobIncrease}U since the immediate bolus (baseline ${iobBaseline}U, now ${liveIob}U) — dose would be <=0, cancelling ${dose}U")
                cancelDoseNote(dose, "$label dose: IOB rose ${decimalFormatter.to2Decimal(iobIncrease)}U")
                return@postDelayed
            }
            DetailedBolusInfo().apply {
                eventType = TE.Type.CORRECTION_BOLUS
                insulin = thisDose
                notes = "Delayed $label dose"
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
