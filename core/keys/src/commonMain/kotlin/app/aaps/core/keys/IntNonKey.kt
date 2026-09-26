package app.aaps.core.keys

import app.aaps.core.keys.interfaces.IntNonPreferenceKey

@Suppress("SpellCheckingInspection")
enum class IntNonKey(
    override val key: String,
    override val defaultValue: Int,
) : IntNonPreferenceKey {

    ObjectivesManualEnacts("ObjectivesmanualEnacts", 0),
    TddCycleOffset("tdd_cycle_offset", 0),

    // How many morning role changes have happened. Cleared after a profile-batch step down. Local only.
    ApsAutoIsfMorningRoleSwapChangeStreak("autoisf_morning_roleswap_change_streak", 0),

    // Minutes before LibreSpecial trusts the new point fully.
    FslMaxSmoothGap("Exp1SmoothGap", 20),
}