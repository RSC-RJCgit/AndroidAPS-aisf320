package app.aaps.plugins.aps.openAPSAutoISF

internal data class RoleNudge(
    val smbBaseline: Double,
    val mildRatio: Double,
)

// The Low profile for a 50% reduction. When the running profile sits on a Standard or Low rung, it is the Low rung of that
// same letter, so the reduction follows the tier you are running even if the saved roles lag behind. Otherwise it is Low current.
internal fun lowNameForRunning(running: String, standardRungs: List<String>, lowRungs: List<String>, lowCurrent: String): String {
    val index = ladderIndexOf(running, standardRungs).takeIf { it >= 0 } ?: ladderIndexOf(running, lowRungs)
    val rung = lowRungs.getOrNull(index)?.trim().orEmpty()
    return rung.ifBlank { lowCurrent }
}

// A hypo-driven drop to a lower tier is skipped when the event fell between 01:00 and 07:00,
// or when the 60 minute step count is over 1000.
internal fun hypoTierDropBlocked(eventMinuteOfDay: Int, steps60: Int): Boolean =
    steps60 > 1000 || inHypoIgnoreWindow(eventMinuteOfDay)

// Overnight lows are often sensor compression, so hypo events between 01:00 and 07:00 are ignored.
internal fun inHypoIgnoreWindow(minuteOfDay: Int): Boolean = minuteInWindow(minuteOfDay, 60, 7 * 60)

// Whether the name saved when Tier C switched on may be written back to a role.
// The live role must still hold the name Tier C gave it, so a later change is kept. A blank [tierCName]
// means Tier C left that role alone. The saved name must also still be a stored profile.
internal fun tierCRoleRestorable(saved: String, live: String, tierCName: String, savedInStore: Boolean): Boolean =
    saved.isNotBlank() && savedInStore && live == tierCName.ifBlank { saved }

// Which saved name in [rungs] matches [name]. Blank names do not match. -1 when none do.
internal fun ladderIndexOf(name: String, rungs: List<String>): Int {
    if (name.isBlank()) return -1
    return rungs.indexOfFirst { it.isNotBlank() && it == name }
}

// 0 is letter A. 1 is letter B or C. B and C share one band, so B to C does not nudge twice.
internal fun roleTierBandForIndex(index: Int): Int = if (index <= 0) 0 else 1

// The letter already stored on Standard Current and Low Current.
// A name that is not on its ladder is ignored. Both missing means letter A.
internal fun sharedRoleLadderIndex(standardName: String, lowName: String, standardRungs: List<String>, lowRungs: List<String>): Int {
    val standardIndex = ladderIndexOf(standardName, standardRungs)
    val lowIndex = ladderIndexOf(lowName, lowRungs)
    if (standardIndex < 0 && lowIndex < 0) return 0
    if (standardIndex < 0) return lowIndex
    if (lowIndex < 0) return standardIndex
    return maxOf(standardIndex, lowIndex)
}

// The letter in force. The running profile wins when its bare name is on a ladder,
// even when Low Current still points at an older name.
// -1 only when neither Current nor the running name is on a ladder.
internal fun sourceRoleRung(
    standardName: String,
    lowName: String,
    runningName: String,
    standardRungs: List<String>,
    lowRungs: List<String>,
): Int {
    val standardIndex = ladderIndexOf(standardName, standardRungs)
    val lowIndex = ladderIndexOf(lowName, lowRungs)
    val runningLowIndex = ladderIndexOf(runningName, lowRungs)
    val runningStandardIndex = ladderIndexOf(runningName, standardRungs)
    return when {
        runningName == lowName && lowIndex >= 0 -> lowIndex
        runningName == standardName && standardIndex >= 0 -> standardIndex
        runningLowIndex >= 0 -> runningLowIndex
        runningStandardIndex >= 0 -> runningStandardIndex
        standardIndex >= 0 && lowIndex >= 0 -> maxOf(standardIndex, lowIndex)
        standardIndex >= 0 -> standardIndex
        lowIndex >= 0 -> lowIndex
        else -> -1
    }
}

internal fun runningOnLowLadder(runningName: String, lowCurrent: String, lowRungs: List<String>): Boolean {
    if (runningName.isBlank()) return false
    if (runningName == lowCurrent) return true
    return ladderIndexOf(runningName, lowRungs) >= 0
}

// The profile names for one letter. A blank ladder slot uses the fallback current name.
// Null when either side is still blank.
internal fun sharedRungNames(
    index: Int,
    lowRungs: List<String>,
    standardRungs: List<String>,
    lowCurrent: String,
    standardAnchor: String,
    standardCurrent: String,
): Pair<String, String>? {
    if (index !in lowRungs.indices || index !in standardRungs.indices) return null
    val low = lowRungs[index].ifBlank { lowCurrent }
    if (low.isBlank()) return null
    val standard = standardRungs[index].ifBlank { standardAnchor }.ifBlank { standardCurrent }
    if (standard.isBlank()) return null
    return low to standard
}

// Off the ladder, a step up starts at letter A. A step down stays off the ladder.
// On the ladder, the move stops at the first letter and the last letter.
internal fun ladderStepIndex(currentIndex: Int, ladderSize: Int, stepUp: Boolean): Int {
    if (currentIndex == -1) return if (stepUp) 0 else -1
    if (ladderSize <= 0) return -1
    return (currentIndex + if (stepUp) 1 else -1).coerceIn(0, ladderSize - 1)
}

// Moves the SMB baseline by 0.01 and the mild ratio by 0.25 when the band changes.
// A to B/C goes up. B/C to A goes down. The same band, including B to C, returns null.
internal fun roleTierDeliveryNudge(previousBand: Int, newBand: Int, smbBaseline: Double, mildRatio: Double): RoleNudge? {
    if (previousBand == newBand) return null
    if (previousBand == 0 && newBand == 1) {
        return RoleNudge(
            smbBaseline = (smbBaseline + 0.01).coerceAtMost(0.5),
            mildRatio = (mildRatio + 0.25).coerceAtMost(1.0),
        )
    }
    if (previousBand == 1 && newBand == 0) {
        return RoleNudge(
            smbBaseline = (smbBaseline - 0.01).coerceAtLeast(0.1),
            mildRatio = (mildRatio - 0.25).coerceAtLeast(0.1),
        )
    }
    return null
}
