package app.aaps.plugins.sync.nsclientV3.workers

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TE

/**
 * What the secondary Nightscout download keeps.
 *
 * Manual boluses and carbs come in only when their receive switches are on. SMBs and priming never do:
 * those were delivered on the other phone and must not be copied onto this one. Device events come in
 * only when that option is on, and a Note only when it is one of the messages the other phone sends on purpose.
 */
internal fun secondaryBolusAccepted(type: BS.Type): Boolean = type == BS.Type.NORMAL

internal fun secondaryTherapyEventAccepted(type: TE.Type, note: String?): Boolean {
    if (type !in secondaryDeviceEventTypes) return false
    if (type != TE.Type.NOTE) return true
    val raw = note.orEmpty()
    val trimmed = raw.trim()
    return raw.startsWith("StLow ") ||
        raw.startsWith("StorageLow ") ||
        trimmed == "ADesk" ||
        trimmed == "AckDesk" ||
        trimmed == "MJ active" ||
        trimmed.startsWith("AcNS") ||
        trimmed.startsWith("AcTT") ||
        trimmed.startsWith("AcLT") ||
        trimmed.startsWith("SetRole ")
}

/** Once the secondary site is on, step counts come from it. The two addresses are not compared. */
internal fun stepsFromPrimarySite(secondaryEnabled: Boolean): Boolean = !secondaryEnabled

/**
 * Glucose comes from the second Nightscout when "Get BG from this connection" is on.
 * This is the full app and a client. The second site can stay on for treatments and the
 * profile without also supplying glucose. The main site stays the one used for pairing.
 * One glucose source: the main site and xDrip do not write at the same time.
 */
internal fun glucoseFromSecondarySite(secondaryEnabled: Boolean, bgFromThisConnection: Boolean): Boolean =
    secondaryEnabled && bgFromThisConnection

private val secondaryDeviceEventTypes = setOf(
    TE.Type.SENSOR_CHANGE,
    TE.Type.SENSOR_STARTED,
    TE.Type.CANNULA_CHANGE,
    TE.Type.INSULIN_CHANGE,
    TE.Type.PUMP_BATTERY_CHANGE,
    TE.Type.NOTE
)
