package app.aaps.plugins.aps.openAPSAutoISF

// The MJ state a CarePortal note from the loop phone asks this phone to take, or null when the note is not one of them.
// "MJ active" and "NOMJremains" come from the MJ buttons. MJsAc, MJsNO, MJs2 and MJs3 come from the manual MJ state row
// on List 1 (2026-10-08: the live phone set MJ3 at 12:25 with "MJs3", and the virtual phone stayed on "MJ active" all evening).
internal fun mjStateForRelayNote(note: String?): String? = when (note?.trim()) {
    "MJ active", "MJsAc" -> "MJ active"
    "NOMJremains", "MJsNO" -> "NOMJremains"
    "MJs2" -> "MJ2"
    "MJs3" -> "MJ3"
    else -> null
}
