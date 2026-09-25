package app.aaps.plugins.aps.openAPSAutoISF

internal data class RoleNudge(
    val smbBaseline: Double,
    val mildRatio: Double,
)

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
