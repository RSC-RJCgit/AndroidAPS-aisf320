package app.aaps.core.interfaces.aps

/** Called only after a profile has actually become effective, including a timed switch's reversion. */
interface ProfileRoleSynchronizer {
    fun alignEffectiveProfileRoles(profileName: String)
}

/** A role comes from a unique configured name, never from a profile's percentage. */
data class ProfileRoleMatch(val index: Int, val low: Boolean)

fun configuredProfileRole(name: String, standard: List<String>, low: List<String>): ProfileRoleMatch? {
    if (name.isBlank()) return null
    val matches = standard.mapIndexedNotNull { index, entry ->
        if (entry.isNotBlank() && entry == name) ProfileRoleMatch(index, false) else null
    } + low.mapIndexedNotNull { index, entry ->
        if (entry.isNotBlank() && entry == name) ProfileRoleMatch(index, true) else null
    }
    return matches.singleOrNull()
}

/** Prefer the actual base profile. Saved Current roles are a fallback only when they name known ladder entries. */
fun configuredRoleRung(running: String, standardCurrent: String, lowCurrent: String, standard: List<String>, low: List<String>): Int {
    configuredProfileRole(running, standard, low)?.let { return it.index }
    // A duplicate configured name is ambiguous, rather than evidence for a fallback tier.
    if (running.isNotBlank() && (standard + low).any { it == running }) return -1
    val standardRole = configuredProfileRole(standardCurrent, standard, low)?.takeIf { !it.low }
    val lowRole = configuredProfileRole(lowCurrent, standard, low)?.takeIf { it.low }
    return listOfNotNull(standardRole?.index, lowRole?.index).maxOrNull() ?: -1
}


