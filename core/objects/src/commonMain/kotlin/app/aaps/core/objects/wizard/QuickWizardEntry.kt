package app.aaps.core.objects.wizard

import app.aaps.core.data.model.CarbsType
import app.aaps.annotations.OpenForTesting
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.RM
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.valueToUnits
import app.aaps.core.utils.MidnightUtils
import kotlin.math.min
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import app.aaps.core.keys.IntKey

enum class QuickWizardMode(val value: Int) {
    WIZARD(0),
    INSULIN(1),
    CARBS(2);

    companion object {

        fun fromValue(value: Int) = entries.firstOrNull { it.value == value } ?: WIZARD
    }
}

class QuickWizardEntry(
    aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val profileFunction: ProfileFunction,
    private val loop: Loop,
    private val iobCobCalculator: IobCobCalculator,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    // For the walking-soon default from the steps, the same as the Bolus Wizard (2026-10-21)
    private val config: Config,
    private val activePlugin: ActivePlugin,
    // Plain factories rather than javax.inject.Provider - see QuickWizard for why.
    private val bolusWizardProvider: () -> BolusWizard,
    private val quickWizardProvider: () -> QuickWizard
) {

    // for mock
    @OpenForTesting
    class Time {

        fun secondsFromMidnight(): Int = MidnightUtils.secondsFromMidnight()

    }

    var time = Time()

    /**
     * The preset itself, as plain data.
     *
     * Not aliased to the element inside [QuickWizard]'s array: anything that changes [data] has to
     * hand the entry back to [QuickWizard] to be written, so a field write is never a silent store.
     */
    var data: QuickWizardEntryData = QuickWizardEntryData(guid = randomGuid(), validTo = 86340)
    var position: Int = -1

    companion object {

        // Defined once in QuickWizardEntryData, which is common code. Re-exposed here so
        // `QuickWizardEntry.ALWAYS` style call sites keep working.
        const val ALWAYS = QuickWizardEntryData.ALWAYS
        const val NEVER = QuickWizardEntryData.NEVER
        const val POSITIVE_ONLY = QuickWizardEntryData.POSITIVE_ONLY
        const val NEGATIVE_ONLY = QuickWizardEntryData.NEGATIVE_ONLY
        const val AUTO = QuickWizardEntryData.AUTO
        const val DEVICE_ALL = QuickWizardEntryData.DEVICE_ALL
        const val DEVICE_PHONE = QuickWizardEntryData.DEVICE_PHONE
        const val DEVICE_WATCH = QuickWizardEntryData.DEVICE_WATCH
        const val COOLDOWN_MILLIS = QuickWizardEntryData.COOLDOWN_MILLIS

        @OptIn(ExperimentalUuidApi::class)
        fun randomGuid(): String = Uuid.random().toString()
    }

    fun from(entry: QuickWizardEntryData, position: Int): QuickWizardEntry {
        data = entry
        this.position = position
        return this
    }

    fun isActive(): Boolean {
        val now = time.secondsFromMidnight()
        val inTimeRange = if (validTo() >= validFrom()) now in validFrom()..validTo()
        else now >= validFrom() || now <= validTo() // wraps midnight
        if (!inTimeRange || !forDevice(DEVICE_PHONE)) return false
        val timeRangeSeconds = if (validTo() >= validFrom()) validTo() - validFrom()
        else (86400 - validFrom()) + validTo()
        val needsCooldown = timeRangeSeconds < 4 * 3600
        return !needsCooldown || (dateUtil.now() - lastUsed() > COOLDOWN_MILLIS)
    }

    suspend fun doCalc(profile: Profile, profileName: String, lastBG: InMemoryGlucoseValue): BolusWizard {
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
        //BG
        var bg = 0.0
        if (useBG() == ALWAYS) {
            bg = lastBG.valueToUnits(profileFunction.getUnits())
        }
        // COB
        val cob =
            if (useCOB() == ALWAYS) iobCobCalculator.getCobInfo("QuickWizard COB").displayCob ?: 0.0
            else 0.0
        // IOB
        var uIOB = false
        if (useIOB() == ALWAYS) {
            uIOB = true
        }

        var uPositiveIOBOnly = false
        if (usePositiveIOBOnly() == ALWAYS) {
            uPositiveIOBOnly = true
        }
        // SuperBolus
        var superBolus = false
        if (useSuperBolus() == ALWAYS && preferences.get(BooleanKey.OverviewUseSuperBolus)) {
            superBolus = true
        }
        if (loop.runningMode() == RM.Mode.SUPER_BOLUS) superBolus = false
        // Trend
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        var trend = false
        if (useTrend() == ALWAYS) {
            trend = true
        } else if (useTrend() == POSITIVE_ONLY && glucoseStatus != null && glucoseStatus.shortAvgDelta > 0) {
            trend = true
        } else if (useTrend() == NEGATIVE_ONLY && glucoseStatus != null && glucoseStatus.shortAvgDelta < 0) {
            trend = true
        }
        // 2026-10-21, per explicit request: the same meal extras, falling-BG caution, walking soon and later doses as the
        // Bolus Wizard (WizardDialogViewModel), so a button gives the dose the wizard would give with the same options.
        val extra = mealExtra(
            fpuInstead = autoFpu(),
            unreliableSmb = unreliableSmb(),
            carbs = carbs(),
            typedFat = fat(),
            typedProtein = protein(),
            percentage = percentage(),
            extendedCarbPercent = carbsType().eCarbsPercent,
            smallMeal = smallMeal(),
        )
        val bgMgdl = lastBG.value
        val percentage = if (dropTrendCautionNow(bgMgdl, glucoseStatusProvider, preferences, iobCobCalculator, persistenceLayer, dateUtil))
            min(extra.percentage, WizardDropTrendCaution.scaledWizPercent(extra.percentage))
        else extra.percentage
        val walking = when (walkingSoon()) {
            ALWAYS -> true
            NEVER  -> false
            else   -> {
                val (steps5, steps30, steps15) = walkingSoonStepCounts(config, preferences, activePlugin, persistenceLayer, dateUtil)
                walkingSoonDefault(steps5, steps30, bgMgdl, glucoseStatus?.delta ?: 0.0, steps15) ?: false
            }
        }
        val wizard = bolusWizardProvider().doCalc(
            profile,
            profileName,
            tempTarget,
            carbsNow(),
            cob,
            bg,
            directCorrection(),
            percentage,
            true,
            useCOB() == ALWAYS,
            uIOB, //always use or don't both bolus
            uIOB, // & basal IOB
            superBolus,
            useTempTarget() == ALWAYS,
            trend,
            useAlarm() == ALWAYS,
            buttonText(),
            carbTime(),
            positiveIOBOnly = uPositiveIOBOnly,
            source = Sources.QuickWizard,
            protein = extra.proteinGrams,
            fat = extra.fatGrams,
            warsawDurationHours = warsawDurationHours(),
            walkingSoon = walking,
        ) //tbc, ok if only quickwizard, but if other sources elsewhere use Sources.QuickWizard
        wizard.warsawRetryLater = retryFpuLater()
        if (giveRestLater() && extra.percentage > percentage) {
            val scaled = wizard.insulinFromBG + wizard.insulinFromTrend + wizard.insulinFromCarbs + wizard.insulinFromCOB
            wizard.addLaterUnits(scaled * (extra.percentage - percentage) / 100.0)
        }
        return wizard
    }

    // The Bolus Wizard's options. A switch the editor never wrote follows the wizard's default setting. Unreliable SMBs
    // win over automatic fat and protein, as in the wizard.
    fun unreliableSmb(): Boolean = data.unreliableSmb ?: preferences.get(BooleanKey.WizardDefaultUnreliableSmbs)

    fun autoFpu(): Boolean = (data.autoFpu ?: preferences.get(BooleanKey.WizardDefaultAutoFpu)) && !unreliableSmb()

    fun smallMeal(): Boolean = data.smallMeal ?: preferences.get(BooleanKey.WizardDefaultSmallMeal)

    fun walkingSoon(): Int = data.walkingSoon

    fun retryFpuLater(): Boolean = data.retryFpuLater

    fun giveRestLater(): Boolean = data.giveRestLater

    fun directCorrection(): Double = data.directCorrection

    fun splitOverMax(): Boolean = data.splitOverMax ?: preferences.get(BooleanKey.WizardSplitOverMaxBolus)

    fun splitIntervalMinutes(): Int = (data.splitIntervalMinutes ?: preferences.get(IntKey.WizardSplitIntervalMinutes)).coerceIn(1, 60)

    fun mode(): QuickWizardMode = QuickWizardMode.fromValue(data.mode)

    fun insulin(): Double = data.insulin

    fun guid(): String = data.guid

    fun device(): Int = data.device

    fun forDevice(device: Int) = device() == device || device() == DEVICE_ALL

    fun buttonText(): String = data.buttonText

    fun carbs(): Int = data.carbs

    fun protein(): Int = data.protein

    fun fat(): Int = data.fat

    fun warsawDurationHours(): Double = data.warsawDurationHours

    fun maxBolus(): Double = data.maxBolus

    fun validFrom(): Int = data.validFrom

    fun validTo(): Int = data.validTo

    fun useBG(): Int = data.useBG

    fun useCOB(): Int = data.useCOB

    fun useIOB(): Int = data.useIOB

    fun usePositiveIOBOnly(): Int = data.usePositiveIOBOnly

    fun useTrend(): Int = data.useTrend

    fun useSuperBolus(): Int = data.useSuperBolus

    fun useTempTarget(): Int = data.useTempTarget

    fun percentage(): Int = data.percentage

    fun useEcarbs(): Int = data.useEcarbs

    fun carbs2(): Int = data.carbs2

    /**
     * The extended-carbs amount this preset really delivers: [carbs2] when eCarbs are switched on, 0 when they
     * are off. Every caller needs the switch together with the amount, so the two stay in one place.
     */
    fun eCarbsGrams(): Int = when {
        // As in the wizard, automatic fat and protein (or unreliable SMBs) send the slow part as later doses instead
        mode() == QuickWizardMode.WIZARD && (autoFpu() || unreliableSmb()) -> 0
        mealTypeApplies()                                                    -> carbs() * carbsType().eCarbsPercent / 100
        useEcarbs() == ALWAYS                                                -> carbs2()
        else                                                                 -> 0
    }

    /** Minutes until the extended carbs start: the meal type's, else the button's own [time]. */
    fun eCarbsDelayMinutes(): Int = if (mealTypeApplies()) carbsType().eCarbsDelayMinutes else time()

    /** Hours the extended carbs last: the meal type's, else the button's own [duration]. */
    fun eCarbsDurationHours(): Int = if (mealTypeApplies()) carbsType().eCarbsDurationHours else duration()

    /** Meal type of a calculator button (2026-10-22). CARBS and INSULIN buttons keep their own fixed extended carbs. */
    fun carbsType(): CarbsType = if (mode() == QuickWizardMode.WIZARD) CarbsType.fromName(data.carbsType) else CarbsType.BREAD

    private fun mealTypeApplies(): Boolean = carbsType() != CarbsType.BREAD

    /**
     * The carbs given and bolused now: the meal type's share, as in the wizard. Unreliable SMBs keep all of them now,
     * because the slow part becomes fat and protein doses instead.
     */
    fun carbsNow(): Int =
        if (mode() == QuickWizardMode.WIZARD && unreliableSmb()) carbs()
        else carbs() * carbsType().carbsPercent / 100

    fun time(): Int = data.time

    fun duration(): Int = data.duration

    fun carbTime(): Int = data.carbTime

    fun useAlarm(): Int = data.useAlarm

    fun lastUsed(): Long = data.lastUsed

    fun markAsUsed() {
        data = data.copy(lastUsed = dateUtil.now())
        // The write-back is explicit now. It used to work only because `storage` WAS the element
        // inside QuickWizard's array, so a plain save() picked the new value up on its own.
        quickWizardProvider().addOrUpdate(this)
    }
}
