package app.aaps.plugins.sync.nsclientV3.workers

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TE

/**
 * What the secondary Nightscout download keeps.
 *
 * Manual boluses and carbs always come in. SMBs never do: those were delivered on the other
 * phone and must not be copied onto this one. Device events come in only when that option is on,
 * and a Note only when it is one of the messages the other phone sends on purpose.
 */
internal fun secondaryBolusAccepted(type: BS.Type): Boolean = type != BS.Type.SMB

internal fun secondaryTherapyEventAccepted(type: TE.Type, note: String?): Boolean {
    if (type !in secondaryDeviceEventTypes) return false
    if (type != TE.Type.NOTE) return true
    val raw = note.orEmpty()
    val trimmed = raw.trim()
    return raw.startsWith("StLow ") ||
        raw.startsWith("StorageLow ") ||
        trimmed == "ADesk" ||
        trimmed == "AckDesk" ||
        trimmed.startsWith("AcNS") ||
        trimmed.startsWith("AcTT") ||
        trimmed.startsWith("AcLT") ||
        trimmed.startsWith("SetRole ")
}

private val secondaryDeviceEventTypes = setOf(
    TE.Type.SENSOR_CHANGE,
    TE.Type.SENSOR_STARTED,
    TE.Type.CANNULA_CHANGE,
    TE.Type.INSULIN_CHANGE,
    TE.Type.PUMP_BATTERY_CHANGE,
    TE.Type.NOTE
)
