package app.aaps.core.interfaces.trio

/**
 * Sends commands to a Trio phone, the way LoopFollow does (2026-10-29, per explicit request): an encrypted push through
 * Apple, to the Trio that uploads to this app's Nightscout site.
 *
 * Trio does the real checks. It refuses a command with the wrong shared secret or one older than 10 minutes, and a bolus
 * goes through its own max bolus and max IOB. Nothing comes back, so [send] can only say that Apple took the push.
 */
interface TrioRemoteControl {

    /** What is set up, and what is still missing, read fresh each time */
    fun setup(): TrioRemoteSetup

    suspend fun send(command: TrioRemoteCommand): TrioRemoteResult
}

sealed interface TrioRemoteCommand {

    data class TempTarget(val targetMgdl: Int, val durationMinutes: Int) : TrioRemoteCommand
    data object CancelTempTarget : TrioRemoteCommand

    /** [eatenAt] is when the meal was eaten, or null for now */
    data class Meal(val carbs: Int, val fat: Int, val protein: Int, val eatenAt: Long?) : TrioRemoteCommand
    data class Bolus(val units: Double) : TrioRemoteCommand
    data class StartOverride(val name: String) : TrioRemoteCommand
    data object CancelOverride : TrioRemoteCommand
}

sealed interface TrioRemoteResult {

    /** Apple took the push. Whether Trio did what it said shows in Nightscout a little later. */
    data object Sent : TrioRemoteResult

    /** [reason] is a setup problem, or Apple's answer such as "BadDeviceToken" */
    data class Failed(val reason: String) : TrioRemoteResult
}

/** An override saved in Trio. Only its name is sent; Trio knows the rest. */
data class TrioOverridePreset(val name: String, val percentage: Double?, val durationMinutes: Int?, val targetMgdl: Double?)

data class TrioRemoteSetup(
    val enabled: Boolean,
    val missing: List<Missing>,
    /** From Trio's profile on this app's Nightscout site; null until a Trio profile has been read */
    val trio: TrioPushDetails?,
    val overridePresets: List<TrioOverridePreset>
) {

    val ready: Boolean get() = enabled && missing.isEmpty()

    enum class Missing { USER, SHARED_SECRET, KEY_ID, APNS_KEY, TRIO_PROFILE, TEAM_ID }
}

/** What Trio uploads so it can be reached. None of it is secret. */
data class TrioPushDetails(val deviceToken: String, val bundleId: String, val teamId: String, val production: Boolean)
