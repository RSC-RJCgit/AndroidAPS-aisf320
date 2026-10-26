package app.aaps.ui.compose.wizardDialog

import androidx.compose.runtime.Immutable
import app.aaps.core.data.model.CarbsType
import app.aaps.core.data.model.GlucoseUnit

@Immutable
data class WizardDialogUiState(
    /**
     * False until the ViewModel has loaded the real values. The screen is drawn from the defaults
     * below before that, and they say "no BG", so nothing should be decided from them.
     */
    val initialized: Boolean = false,

    // User inputs
    val bg: Double = 0.0,
    val carbs: Int = 0,
    val protein: Int = 0,
    val fat: Int = 0,
    val warsawDurationHours: Double = 5.0,
    val carbsType: CarbsType = CarbsType.BREAD,
    val percentage: Int = 100,
    val directCorrection: Double = 0.0,
    val carbTime: Int = 0,
    val notes: String = "",
    val selectedProfileIndex: Int = 0,

    // Toggles
    val useBg: Boolean = true,
    val useTT: Boolean = false,
    val useTrend: Boolean = false,
    val useIOB: Boolean = true,
    val useCOB: Boolean = false,
    val walkingSoon: Boolean = false,
    /** Ticked accepts automatic fat and protein later. Fat is 0.35 times the carb grams. Protein is 0.4 times. Unticked leaves typed grams. Grams above 0 still schedule hourly doses. Off by default. */
    val fpuInstead: Boolean = false,
    /** Ticked: a protein or fat dose that is not safe at its hour is checked again every 10 minutes and given when it is, until 10 minutes before the next dose. Unticked: it is cancelled. On by default. */
    val delayFpuInsteadOfCancel: Boolean = true,
    /** Ticked delays the part of the bolus the fall caution removed, as hourly boluses. On by default. Unticked, that part is not given. */
    val giveRestLater: Boolean = true,
    /** Same later doses, with larger fat and protein, wizard percent 90, and the saved max bolus. */
    val unreliableSmb: Boolean = false,
    /** Halves the automatic fat and protein. With unreliableSmb ticked too, the two cancel to the average. */
    val smallMeal: Boolean = true,
    /** v3's split box (2026-10-22): give the dose over max bolus later, in parts, every [splitIntervalMinutes]. */
    val splitOverMax: Boolean = true,
    val splitIntervalMinutes: Int = 7,
    val alarmChecked: Boolean = false,
    val calculationExpanded: Boolean = false,

    // Config (set once in init)
    val maxCarbs: Int = 0,
    val maxBolus: Double = 0.0,
    val bolusStep: Double = 0.1,
    val units: GlucoseUnit = GlucoseUnit.MGDL,
    val profileNames: List<String> = emptyList(),
    val showNotes: Boolean = false,
    val hasTempTarget: Boolean = false,
    val useBolusAdvisor: Boolean = false,
    val simpleMode: Boolean = false,
    val carbsButtonIncrement1: Int = 0,
    val carbsButtonIncrement2: Int = 0,
    val carbsButtonIncrement3: Int = 0,

    // Calculation results (updated on every recalc)
    val insulinFromBG: Double = 0.0,
    val insulinFromTrend: Double = 0.0,
    val insulinFromCarbs: Double = 0.0,
    val insulinFromCOB: Double = 0.0,
    val insulinFromBolusIOB: Double = 0.0,
    val insulinFromBasalIOB: Double = 0.0,
    val insulinFromCorrection: Double = 0.0,
    val trendDetail: String = "",
    val totalInsulin: Double = 0.0,
    val totalBeforePercentage: Double = 0.0,
    val insulinAfterConstraints: Double = 0.0,
    val carbsEquivalent: Double = 0.0,
    val calculatedPercentage: Int = 100,
    val constraintApplied: Boolean = false,
    val isf: Double = 0.0,
    val ic: Double = 0.0,
    val currentCOB: Double = 0.0,
    val totalIOB: Double = 0.0,
    val trend: Double = 0.0,
    val targetBGLow: Double = 0.0,
    val targetBGHigh: Double = 0.0,
    val hasResult: Boolean = false,
    val okVisible: Boolean = false,

    // Carbs type split (computed from carbs + carbsType)
    val effectiveCarbs: Int = 0,
    val eCarbs: Int = 0,
    val eCarbsDelayMinutes: Int = 0,
    val eCarbsDurationHours: Int = 0,

    // BG source info
    val hasBgData: Boolean = false,
    val bgAgeMinutes: Int = 0,

    /** True when the entry must be recorded only (no pump delivery): pump unavailable or the mode forbids bolus. (A client delivers via the master, not record-only.) */
    val forcedRecordOnly: Boolean = false,
    /** An SMB is being delivered on this phone (always false on a client: its master decides). */
    val smbBeingDelivered: Boolean = false,
    /** The user has changed the BG field. From then on it keeps their value and no longer follows new readings. */
    val bgEdited: Boolean = false
)

/**
 * A new sensor reading came in. The BG field follows it until the user has edited the field: the BG is sent to the
 * master with the other inputs, so a field filled when the wizard opened would dose on a BG from minutes ago.
 */
fun WizardDialogUiState.withNewReading(bg: Double, ageMinutes: Int): WizardDialogUiState =
    if (bgEdited) this else copy(bg = bg, bgAgeMinutes = ageMinutes, hasBgData = true)

/**
 * OK waits: the pump would give this bolus, and an SMB is being delivered right now (#5381). The master calculates
 * the dose after the SMB has finished, with the SMB in IOB.
 */
val WizardDialogUiState.waitsForSmb: Boolean
    get() = smbBeingDelivered && totalInsulin > 0.0 && !forcedRecordOnly

val WizardDialogUiState.isMgdl: Boolean
    get() = units == GlucoseUnit.MGDL

val WizardDialogUiState.bgRange: ClosedFloatingPointRange<Double>
    get() = if (isMgdl) 0.0..500.0 else 0.0..30.0

val WizardDialogUiState.bgStep: Double
    get() = if (isMgdl) 1.0 else 0.1
