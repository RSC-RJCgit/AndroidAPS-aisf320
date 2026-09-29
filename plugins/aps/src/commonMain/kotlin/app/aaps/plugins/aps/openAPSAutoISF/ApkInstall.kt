package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.configuration.Constants

// aisf321UK_797 -> 797. Null when the text is not that form. 100 to 9999 only.
internal fun apkFeatureNumber(text: String?): Int? {
    if (text.isNullOrBlank()) return null
    val fromName = Regex("""aisf321UK_(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    val fromLine = Regex("""(?:^|\r?\n)nnn=(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    return (fromName ?: fromLine)?.takeIf { it in 100..9999 }
}

internal enum class AutoApkChoice {
    DEFER_BOOST,
    STAGE_MISS,
    COOLDOWN,
    NO_NNN,
    NOT_NEWER,
    NO_PERMISSION,
    INSTALL,
}

// Quiet check every 15 minutes. A boost that is on, or that fired in the last 5 minutes, waits.
// The 45 minute gap is from the last install start, not from this check.
internal fun autoApkChoice(
    boostActive: Boolean,
    stageOk: Boolean,
    lastInstallAt: Long,
    now: Long,
    incomingN: Int?,
    currentN: Int?,
    shizukuGranted: Boolean,
): AutoApkChoice = when {
    boostActive -> AutoApkChoice.DEFER_BOOST
    !stageOk -> AutoApkChoice.STAGE_MISS
    lastInstallAt > 0L && now - lastInstallAt < 45L * 60_000L -> AutoApkChoice.COOLDOWN
    incomingN == null || currentN == null -> AutoApkChoice.NO_NNN
    incomingN <= currentN -> AutoApkChoice.NOT_NEWER
    !shizukuGranted -> AutoApkChoice.NO_PERMISSION
    else -> AutoApkChoice.INSTALL
}

data class StageOutcome(
    val ok: Boolean,
    val copiedByApp: Boolean,
    val detail: String,
    val featureNumber: Int?,
)

data class StagedApk(
    val path: String,
    val bytes: Long,
    val featureNumber: Int?,
    val versionName: String?,
)

/**
 * Copies a pump APK into place, and can install it with Shizuku.
 *
 * Android does the file and shell work. Any other platform says it is not on this phone,
 * so the caller can write that down instead of pretending an install started.
 */
interface ApkInstallFront {

    fun stageNewest(): StageOutcome
    fun newestStaged(): StagedApk?
    fun runningVersionName(): String?
    fun shizukuRunning(): Boolean
    fun shizukuGranted(): Boolean
    fun requestShizukuPermission()
    fun installStaged(): Pair<Boolean, String>
    fun attemptAdbStart(port: Int): Pair<Boolean, String>
    fun listenForShizukuGrant(onGranted: () -> Unit)
}

// Virtual pump only. Carbs in the calculation are not recorded. A real pump never reaches the queue.
internal fun pseudoWizardReady(
    virtualPump: Boolean,
    ukfMgdl: Double,
    bgAcceleration: Double,
    shortAvgDelta: Double,
    allBgHigh: Boolean,
    duraActiveMinutes: Double,
    acceActiveMinutes: Double,
    mealCob: Double,
    noTempTarget: Boolean,
    steps60: Int,
    bgMgdl: Double,
    hp: Double?,
    lastNormalBolusMinutes: Int,
    cooldownReady: Boolean,
    bolusInQueue: Boolean,
): Boolean {
    val mmol = Constants.MMOLL_TO_MGDL
    val stepsOk = steps60 < 800 || (bgMgdl > 11.0 * mmol && steps60 < 1500)
    val adaptationOk = duraActiveMinutes > 5.0 && acceActiveMinutes > 4.0
    val highBypass = hp != null && hp > 7.5 && bgMgdl > 10.0 * mmol
    return virtualPump &&
        ukfMgdl > 12.0 * mmol &&
        bgAcceleration > 3.0 &&
        shortAvgDelta > 0.5 * mmol &&
        allBgHigh &&
        (adaptationOk || highBypass) &&
        mealCob == 0.0 &&
        noTempTarget &&
        stepsOk &&
        hp != null && hp > 7.5 &&
        lastNormalBolusMinutes >= 120 &&
        cooldownReady &&
        !bolusInQueue
}

// Used only to size the wizard sum. The treatment stores zero carbs.
internal fun pseudoWizardCalculationCarbs(bgMgdl: Double): Int {
    val mmol = Constants.MMOLL_TO_MGDL
    return when {
        bgMgdl > 13.0 * mmol -> 20
        bgMgdl > 11.0 * mmol -> 15
        else -> 10
    }
}
