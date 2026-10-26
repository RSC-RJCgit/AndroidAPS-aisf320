package app.aaps.core.objects.wizard

import app.aaps.core.data.model.CarbsType
import app.aaps.core.objects.wizard.QuickWizardEntryData.Companion.DEVICE_ALL
import app.aaps.core.objects.wizard.QuickWizardEntryData.Companion.fromJsonObject
import app.aaps.core.utils.lenientBoolean
import app.aaps.core.utils.lenientBooleanOrNull
import app.aaps.core.utils.lenientDouble
import app.aaps.core.utils.lenientInt
import app.aaps.core.utils.lenientLong
import app.aaps.core.utils.lenientString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.aaps.core.utils.lenientIntOrNull

/**
 * One quick wizard preset, as plain data.
 *
 * This replaces the live `JSONObject` the entry used to carry as its state. JSON is now only the
 * storage format at the preference edge - [fromJsonObject] on the way in, [toJsonObject] on the way
 * out - and nothing in between passes a document around.
 *
 * Every default below reproduces what the old string-keyed read produced, including the awkward ones:
 *
 * - `validTo` is 86340, i.e. 23:59, from the seeded template rather than from a reader default.
 * - `device` reads as [DEVICE_ALL] because the template stored the *string* `"all"`,
 *   which `getInt` could not parse, so the per-key default won.
 * - the `use*` flags each keep their own default; they are not uniformly NEVER.
 */
data class QuickWizardEntryData(
    val guid: String = "",
    val buttonText: String = "",
    val device: Int = DEVICE_ALL,
    val mode: Int = 0,
    val insulin: Double = 0.0,
    val carbs: Int = 0,
    val validFrom: Int = 0,
    val validTo: Int = 0,
    val useBG: Int = ALWAYS,
    val useCOB: Int = NEVER,
    val useIOB: Int = ALWAYS,
    val usePositiveIOBOnly: Int = NEVER,
    val useTrend: Int = NEVER,
    val useSuperBolus: Int = NEVER,
    val useTempTarget: Int = NEVER,
    val percentage: Int = 100,
    val useEcarbs: Int = NEVER,
    val carbs2: Int = 0,
    val time: Int = 0,
    val duration: Int = 0,
    val carbTime: Int = 0,
    val useAlarm: Int = NEVER,
    val lastUsed: Long = 0,
    val protein: Int = 0,
    val fat: Int = 0,
    /** Hours of protein and fat doses. 0 to 24. Hours past 8 are not delivered. */
    val warsawDurationHours: Double = 5.0,
    /** Max bolus for this button only. 0 keeps the saved safety max. */
    val maxBolus: Double = 0.0,
    // The Bolus Wizard's own options (2026-10-21, per explicit request), so a button gives the same dose as the wizard.
    // A null switch follows the wizard's default setting when the button is used; the editor writes the value it shows.
    /** Automatic fat and protein from the carbs (fat 0.35 x, protein 0.4 x). */
    val autoFpu: Boolean? = null,
    /** Halves the automatic fat and protein. */
    val smallMeal: Boolean? = null,
    /** Larger fat and protein, wizard percent 90 and the saved max bolus. */
    val unreliableSmb: Boolean? = null,
    /** [ALWAYS], [NEVER] or [AUTO]: from the steps, as the wizard sets its box. */
    val walkingSoon: Int = AUTO,
    /** A protein or fat dose that is not safe at its hour is checked again every 10 minutes instead of cancelled. */
    val retryFpuLater: Boolean = true,
    /** The part the falling-BG caution removes is given later, as hourly doses. */
    val giveRestLater: Boolean = true,
    /** Insulin added to (or taken from) the calculated dose, in units. */
    val directCorrection: Double = 0.0,
    /** Give the dose over max bolus later, in parts. Null follows the calculator setting (2026-10-22). */
    val splitOverMax: Boolean? = null,
    /** Minutes between those parts. Null follows the calculator setting. */
    val splitIntervalMinutes: Int? = null,
    /** Meal type, a [CarbsType] name, as in the Bolus Wizard (2026-10-22). A button saved before this is BREAD. */
    val carbsType: String = CarbsType.BREAD.name,
) {

    companion object {

        // The flag values themselves. They live here rather than on `QuickWizardEntry` because that
        // class is still Android only. `QuickWizardEntry` re-exposes each one under the same name, so
        // the 38 existing call sites that write `QuickWizardEntry.ALWAYS` keep working unchanged.
        const val ALWAYS = 0
        const val NEVER = 1
        const val POSITIVE_ONLY = 2
        const val NEGATIVE_ONLY = 3
        /** Set from the live data when the button is used (walking soon). */
        const val AUTO = 4
        const val DEVICE_ALL = 0
        const val DEVICE_PHONE = 1
        const val DEVICE_WATCH = 2
        const val COOLDOWN_MILLIS = 1_800_000L // 1/2 hour

        /**
         * Reads a stored preset.
         *
         * The lenient readers are what keep old documents working: `getInt` accepted a quoted number
         * and truncated a fraction, so `36`, `"36"`, `36.9` and `"36.9"` all read back as 36, and an
         * unreadable value fell to the per-key default rather than throwing.
         */
        fun fromJsonObject(json: JsonObject): QuickWizardEntryData =
            QuickWizardEntryData(
                guid = json.lenientString("guid"),
                buttonText = json.lenientString("buttonText"),
                device = json.lenientInt("device", DEVICE_ALL),
                mode = json.lenientInt("mode", 0),
                insulin = json.lenientDouble("insulin", 0.0),
                carbs = json.lenientInt("carbs"),
                validFrom = json.lenientInt("validFrom"),
                validTo = json.lenientInt("validTo"),
                useBG = json.lenientInt("useBG", ALWAYS),
                useCOB = json.lenientInt("useCOB", NEVER),
                useIOB = json.lenientInt("useIOB", ALWAYS),
                usePositiveIOBOnly = json.lenientInt("usePositiveIOBOnly", NEVER),
                useTrend = json.lenientInt("useTrend", NEVER),
                useSuperBolus = json.lenientInt("useSuperBolus", NEVER),
                useTempTarget = json.lenientInt("useTempTarget", NEVER),
                percentage = json.lenientInt("percentage", 100),
                useEcarbs = json.lenientInt("useEcarbs", NEVER),
                carbs2 = json.lenientInt("carbs2"),
                time = json.lenientInt("time"),
                duration = json.lenientInt("duration"),
                carbTime = json.lenientInt("carbTime"),
                useAlarm = json.lenientInt("useAlarm", NEVER),
                lastUsed = json.lenientLong("lastUsed"),
                protein = json.lenientInt("protein"),
                fat = json.lenientInt("fat"),
                warsawDurationHours = json.lenientDouble("warsawDurationHours", 5.0),
                maxBolus = json.lenientDouble("maxBolus", 0.0),
                autoFpu = json.lenientBooleanOrNull("autoFpu"),
                smallMeal = json.lenientBooleanOrNull("smallMeal"),
                unreliableSmb = json.lenientBooleanOrNull("unreliableSmb"),
                walkingSoon = json.lenientInt("walkingSoon", AUTO),
                retryFpuLater = json.lenientBoolean("retryFpuLater", true),
                giveRestLater = json.lenientBoolean("giveRestLater", true),
                directCorrection = json.lenientDouble("directCorrection", 0.0),
                splitOverMax = json.lenientBooleanOrNull("splitOverMax"),
                splitIntervalMinutes = json.lenientIntOrNull("splitIntervalMinutes"),
                carbsType = json.lenientString("carbsType").ifEmpty { CarbsType.BREAD.name },
            )
    }

    /**
     * Writes the preset back.
     *
     * Every key is written, unlike the old document which only held the keys the editor had touched.
     * That is safe because each reader default equals the value written here for an untouched field,
     * and it removes the "is this key absent or really zero" question from the stored text.
     */
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("guid", guid)
            put("buttonText", buttonText)
            put("device", device)
            put("mode", mode)
            put("insulin", insulin)
            put("carbs", carbs)
            put("validFrom", validFrom)
            put("validTo", validTo)
            put("useBG", useBG)
            put("useCOB", useCOB)
            put("useIOB", useIOB)
            put("usePositiveIOBOnly", usePositiveIOBOnly)
            put("useTrend", useTrend)
            put("useSuperBolus", useSuperBolus)
            put("useTempTarget", useTempTarget)
            put("percentage", percentage)
            put("useEcarbs", useEcarbs)
            put("carbs2", carbs2)
            put("time", time)
            put("duration", duration)
            put("carbTime", carbTime)
            put("useAlarm", useAlarm)
            put("lastUsed", lastUsed)
            put("protein", protein)
            put("fat", fat)
            put("warsawDurationHours", warsawDurationHours)
            put("maxBolus", maxBolus)
            autoFpu?.let { put("autoFpu", it) }
            smallMeal?.let { put("smallMeal", it) }
            unreliableSmb?.let { put("unreliableSmb", it) }
            put("walkingSoon", walkingSoon)
            put("retryFpuLater", retryFpuLater)
            put("giveRestLater", giveRestLater)
            put("directCorrection", directCorrection)
            splitOverMax?.let { put("splitOverMax", it) }
            splitIntervalMinutes?.let { put("splitIntervalMinutes", it) }
            put("carbsType", carbsType)
        }
}
