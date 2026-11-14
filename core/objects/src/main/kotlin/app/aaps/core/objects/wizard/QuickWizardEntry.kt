package app.aaps.core.objects.wizard

import app.aaps.annotations.OpenForTesting
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.CarbsType
import app.aaps.core.data.model.RM
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.valueToUnits
import app.aaps.core.objects.utils.StepCountSource
import app.aaps.core.utils.JsonHelper.safeGetBoolean
import app.aaps.core.utils.JsonHelper.safeGetDouble
import app.aaps.core.utils.JsonHelper.safeGetInt
import app.aaps.core.utils.JsonHelper.safeGetString
import app.aaps.core.utils.MidnightUtils
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.min

class QuickWizardEntry @Inject constructor(
    aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val profileFunction: ProfileFunction,
    private val loop: Loop,
    private val iobCobCalculator: IobCobCalculator,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val bolusWizardProvider: Provider<BolusWizard>,
    private val stepCountSource: StepCountSource
) {

    // for mock
    @OpenForTesting
    class Time {

        fun secondsFromMidnight(): Int = MidnightUtils.secondsFromMidnight()

    }

    var time = Time()

    lateinit var storage: JSONObject
    var position: Int = -1

    companion object {

        const val YES = 0
        const val NO = 1
        const val POSITIVE_ONLY = 2
        const val NEGATIVE_ONLY = 3
        const val AUTO = 4
        const val DEVICE_ALL = 0
        const val DEVICE_PHONE = 1
        const val DEVICE_WATCH = 2
        const val DEFAULT = 0
        const val CUSTOM = 1
    }

    init {
        val guid = UUID.randomUUID().toString()
        val emptyData = """{
                "guid": "$guid",
                "buttonText": "",
                "carbs": 0,
                "validFrom": 0,
                "validTo": 86340,
                "device": "all",
                "usePercentage": "default",
                "percentage": 100
            }""".trimMargin()
        try {
            storage = JSONObject(emptyData)
        } catch (e: JSONException) {
            aapsLogger.error("Unhandled exception", e)
        }
    }

    /*
        {
            guid: string,
            device: string, // (phone, watch, all)
            buttonText: "Meal",
            carbs: 36,
            validFrom: 8 * 60 * 60, // seconds from midnight
            validTo: 9 * 60 * 60,   // seconds from midnight
            useBG: 0,
            useCOB: 0,
            useBolusIOB: 0,
            useBasalIOB: 0,
            useTrend: 0,
            useSuperBolus: 0,
            useTemptarget: 0
            usePercentage: string, // default, custom
            percentage: int,
            protein: int,  // grams, fixed per button -- see protein()/fat()
            fat: int,      // grams, fixed per button
            warsawDurationHours: double,  // protein+fat extended-series duration, default 5.0
        }
     */
    fun from(entry: JSONObject, position: Int): QuickWizardEntry {
        // TODO set guid if missing for migration
        storage = entry
        this.position = position
        return this
    }

    fun isActive(): Boolean = time.secondsFromMidnight() >= validFrom() && time.secondsFromMidnight() <= validTo() && forDevice(DEVICE_PHONE)

    fun doCalc(profile: Profile, profileName: String, lastBG: InMemoryGlucoseValue): BolusWizard {
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
        //BG
        var bg = 0.0
        if (useBG() == YES) {
            bg = lastBG.valueToUnits(profileFunction.getUnits())
        }
        // COB
        val cob =
            if (useCOB() == YES) iobCobCalculator.getCobInfo("QuickWizard COB").displayCob ?: 0.0
            else 0.0
        // IOB
        var uIOB = false
        if (useIOB() == YES) {
            uIOB = true
        }

        var uPositiveIOBOnly = false
        if (usePositiveIOBOnly() == YES) {
            uPositiveIOBOnly = true
        }
        // SuperBolus
        var superBolus = false
        if (useSuperBolus() == YES && preferences.get(BooleanKey.OverviewUseSuperBolus)) {
            superBolus = true
        }
        if (loop.runningMode == RM.Mode.SUPER_BOLUS) superBolus = false
        // Trend
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        var trend = false
        if (useTrend() == YES) {
            trend = true
        } else if (useTrend() == POSITIVE_ONLY && glucoseStatus != null && glucoseStatus.shortAvgDelta > 0) {
            trend = true
        } else if (useTrend() == NEGATIVE_ONLY && glucoseStatus != null && glucoseStatus.shortAvgDelta < 0) {
            trend = true
        }
        // 2026-10-22: same rules as the Bolus Wizard dialog. "Unreliable SMBs" caps wiz% at 90 for this calculation (never
        // raises a lower button %), and WizardDropTrendCaution halves it while BG is falling or IOB is high for the rise.
        val basePercentage = if (usePercentage() == DEFAULT) preferences.get(IntKey.OverviewBolusPercentage) else percentage()
        var percentage = if (unreliableSmb()) min(basePercentage, MealFpuFromCarbs.UNRELIABLE_WIZ_PERCENT) else basePercentage
        val dropTrendCaution =
            WizardDropTrendCaution.appliesNow(lastBG.value, glucoseStatusProvider, preferences, iobCobCalculator, persistenceLayer, dateUtil)
        val percentBeforeCaution = percentage
        if (dropTrendCaution) percentage = min(percentage, WizardDropTrendCaution.scaledWizPercent(percentage))
        // Auto fat/protein: only when the button has no protein or fat of its own (like a manual edit in the dialog).
        val (autoFat, autoProtein) =
            if (autoFpu() && protein() == 0 && fat() == 0)
                MealFpuFromCarbs.fatProtein(carbs().toDouble(), unreliableSmb(), smallMeal(), carbsType().eCarbsPercent)
            else fat() to protein()
        // 2026-09-26, per explicit request: partway up a rise the carbs are dated back from the rise onset (CarbTimeFromRise), same as the
        // Bolus Wizard dialog's pre-fill. Only for a button with carbs and no carb time of its own, and only while the Settings -> Overview
        // switch is on (default). The offset shows in the confirmation dialog and is appended to the note.
        val riseCarbTime =
            if (carbTime() == 0 && carbs() > 0 && preferences.get(BooleanKey.OverviewQuickWizardCarbTimeFromRise))
                CarbTimeFromRise.estimate(persistenceLayer, dateUtil.now())?.offsetMinutes
            else null
        val wizard = bolusWizardProvider.get().doCalc(
            profile,
            profileName,
            tempTarget,
            carbsNow(),
            cob,
            bg,
            // The button's own correction in units, + or - (2026-11-12, per explicit request, from v4). Max bolus still caps it.
            directCorrection(),
            percentage,
            true,
            useCOB() == YES,
            uIOB, //always use or don't both bolus
            uIOB, // & basal IOB
            superBolus,
            useTempTarget() == YES,
            trend,
            useAlarm() == YES,
            buttonText() + (riseCarbTime?.let { " [carb time from rise $it min]" } ?: ""),
            riseCarbTime ?: carbTime(),
            quickWizard = true,
            positiveIOBOnly = uPositiveIOBOnly,
            // Fixed per-button protein/fat (added 2026-09-16). Unlike split-bolus below, these ARE real
            // doCalc() parameters -- doCalc() uses them internally to compute insulinFromProteinOnly/
            // insulinFromFatOnly (protein*0.4/ic, fat*0.9/ic) itself, so they must go in here, not be set
            // on the returned wizard afterward (that would be too late -- those two fields are already
            // computed by the time doCalc() returns). Previously QuickWizard had no way to supply these
            // at all, so the Warsaw-FPU extended series could never engage for a QuickWizard-triggered
            // bolus regardless of BolusWizard's own logic.
            protein = if (splitFpuBolus()) autoProtein else 0,
            fat = if (splitFpuBolus()) autoFat else 0,
            // An explicit choice wins; Auto follows the calculator step and BG rules.
            walkingSoon = when (useWalkingSoon()) {
                YES -> true
                NO -> false
                else -> WizardActivitySteps.walkingSoonDefault(stepCountSource, dateUtil.now(), lastBG.value,
                    glucoseStatus?.delta ?: 0.0) ?: false
            }
        ) //tbc, ok if only quickwizard, but if other sources elsewhere use Sources.QuickWizard
        // Split-bolus (carb-split-over-max-bolus) isn't a doCalc() parameter -- it only affects later
        // scheduling (splitProjectionNote()/scheduleSplitProteinFatDoses(), both called from
        // commonProcessing(), never from doCalc() itself), same as WizardDialog sets it on its own wizard
        // instance post-calc (see WizardDialog.kt). Set here so a QuickWizard button configured for it
        // gets the same carb-split-into-repeated-doses behavior as a dialog bolus with the checkbox ticked.
        wizard.manualSplitBolusEnabled = useSplitBolus() == YES
        wizard.manualSplitBolusIntervalMins = splitBolusIntervalMins()
        // Protein+fat extended-series duration (2026-09-16): a direct per-button setting, same
        // reasoning/timing as split-bolus above -- warsawFpuPlan() only runs later, on demand, so this
        // is safe to set post-doCalc() (unlike protein/fat themselves further up).
        wizard.warsawDurationHours = warsawDurationHours()
        wizard.fpuIntervalMinutes = fpuIntervalMinutes()
        wizard.delayFpuInsteadOfCancel = retryFpuLater()
        // A fourth DelayedBolusWorker reason, as in the dialog: the held-back part can follow once BG rises.
        wizard.dropTrendCautionApplied = dropTrendCaution
        // The calculator's remembered "Give it with the carb split" choice (2026-11-14)
        wizard.giveRestWithSplit = preferences.get(BooleanKey.WizardGiveRestWithSplit)
        // Give rest later (2026-11-12, per explicit request, from v4): what the caution cut from the % is given later in the
        // protein/fat series, each part with the usual BG and IOB checks.
        if (dropTrendCaution && giveRestLater()) wizard.addLaterUnits(wizard.percentCutHeldBack(percentBeforeCaution.toDouble()))
        // Meal type (2026-11-05): with eCarbs (split FPU off) the cake/pizza share is entered as extended carbs on OK.
        val type = carbsType()
        wizard.mealECarbs = if (splitFpuBolus()) 0 else type.eCarbs(carbs())
        wizard.mealECarbsDelayMinutes = type.eCarbsDelayMinutes
        wizard.mealECarbsDurationHours = type.eCarbsDurationHours
        wizard.mealTypeName = if (type == CarbsType.BREAD) "" else type.name.lowercase().replaceFirstChar { it.uppercase() }
        return wizard
    }

    fun guid(): String = safeGetString(storage, "guid", "")

    fun device(): Int = safeGetInt(storage, "device", DEVICE_ALL)

    fun forDevice(device: Int) = device() == device || device() == DEVICE_ALL

    fun buttonText(): String = safeGetString(storage, "buttonText", "")

    fun carbs(): Int = safeGetInt(storage, "carbs")

    fun protein(): Int = safeGetInt(storage, "protein")

    fun fat(): Int = safeGetInt(storage, "fat")

    fun warsawDurationHours(): Double = safeGetDouble(storage, "warsawDurationHours", 5.0)

    fun validFromDate(): Long = dateUtil.secondsOfTheDayToMillisecondsOfHoursAndMinutes(validFrom())

    fun validToDate(): Long = dateUtil.secondsOfTheDayToMillisecondsOfHoursAndMinutes(validTo())

    fun validFrom(): Int = safeGetInt(storage, "validFrom")

    fun validTo(): Int = safeGetInt(storage, "validTo")

    fun useBG(): Int = safeGetInt(storage, "useBG", YES)

    fun useCOB(): Int = safeGetInt(storage, "useCOB", NO)

    fun useIOB(): Int = safeGetInt(storage, "useIOB", YES)

    fun usePositiveIOBOnly(): Int = safeGetInt(storage, "usePositiveIOBOnly", NO)

    fun useTrend(): Int = safeGetInt(storage, "useTrend", NO)

    fun useSuperBolus(): Int = safeGetInt(storage, "useSuperBolus", NO)

    fun useTempTarget(): Int = safeGetInt(storage, "useTempTarget", NO)

    fun usePercentage(): Int = safeGetInt(storage, "usePercentage", CUSTOM)

    fun percentage(): Int = safeGetInt(storage, "percentage", 100)

    fun storedUseEcarbs(): Int = safeGetInt(storage, "useEcarbs", NO)

    // A cake or pizza meal type replaces the button's own fixed extended carbs (as v4).
    fun useEcarbs(): Int = if (splitFpuBolus() || mealTypeApplies()) NO else storedUseEcarbs()

    /** Meal type (2026-11-05, per explicit request: v4's Bread / Cake / Pizza). A button saved before it is Bread. */
    fun carbsType(): CarbsType = CarbsType.fromName(safeGetString(storage, "carbsType", CarbsType.BREAD.name))

    private fun mealTypeApplies(): Boolean = carbsType() != CarbsType.BREAD

    /** Carbs given and bolused now: the meal type's share. Unreliable SMBs with split FPU doses keep them all, as v4. */
    fun carbsNow(): Int = if (splitFpuBolus() && unreliableSmb()) carbs() else carbsType().carbsNow(carbs())

    fun carbs2(): Int = safeGetInt(storage, "carbs2")

    fun time(): Int = safeGetInt(storage, "time")

    fun duration(): Int = safeGetInt(storage, "duration")

    fun carbTime(): Int = safeGetInt(storage, "carbTime")

    fun useAlarm(): Int = safeGetInt(storage, "useAlarm", NO)

    // Walking soon can be forced on/off or chosen from current steps and BG.
    fun useWalkingSoon(): Int = safeGetInt(storage, "useWalkingSoon", AUTO)

    fun useSplitBolus(): Int = safeGetInt(storage, "useSplitBolus", if (preferences.get(BooleanKey.ApsAutoIsfSplitBolusEnabled)) YES else NO)

    fun splitBolusIntervalMins(): Int = safeGetInt(storage, "splitBolusIntervalMins", preferences.get(IntKey.ApsAutoIsfSplitBolusInterval)).coerceIn(1, 60)

    fun splitFpuBolus(): Boolean = safeGetBoolean(storage, "splitFpuBolus", storedUseEcarbs() != YES)

    fun fpuIntervalMinutes(): Int = safeGetInt(storage, "fpuIntervalMinutes", 30).coerceIn(30, 60)

    fun retryFpuLater(): Boolean = safeGetBoolean(storage, "retryFpuLater", true)

    // From v4 (2026-11-12, per explicit request). A button saved before these existed: give rest later on, no own max
    // bolus (0 = the saved max bolus), no correction.
    fun giveRestLater(): Boolean = safeGetBoolean(storage, "giveRestLater", true)

    fun maxBolus(): Double = safeGetDouble(storage, "maxBolus", 0.0).coerceIn(0.0, 60.0)

    fun directCorrection(): Double = safeGetDouble(storage, "directCorrection", 0.0)

    // Bolus Wizard boxes (2026-10-22). A button saved before these existed follows the "Bolus calculator defaults" settings.
    fun unreliableSmb(): Boolean = optionOrDefault("useUnreliableSmb", BooleanKey.WizardDefaultUnreliableSmbs)

    fun smallMeal(): Boolean = optionOrDefault("useSmallMeal", BooleanKey.WizardDefaultSmallMeal)

    fun autoFpu(): Boolean = optionOrDefault("useAutoFpu", BooleanKey.WizardDefaultAutoFpu)

    private fun optionOrDefault(name: String, default: BooleanKey): Boolean =
        if (storage.has(name)) safeGetInt(storage, name, NO) == YES else preferences.get(default)
}
