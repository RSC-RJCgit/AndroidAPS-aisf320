package app.aaps.plugins.sync.nsclientV3.workers

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TE

/**
 * What the secondary Nightscout download keeps.
 *
 * Clients and the Virtual Pump master take manual boluses and carbs whenever this connection is enabled;
 * real pumps require their receive switches. SMBs and priming never come in:
 * those were delivered on the other phone and must not be copied onto this one. Device events come in
 * only when that option is on, and a Note only when it is one of the messages the other phone sends on purpose.
 */
internal fun secondaryBolusAccepted(type: BS.Type): Boolean = type == BS.Type.NORMAL

/** An enabled secondary connection supplies meals automatically to clients and the Virtual Pump master. */
internal fun secondaryMealTreatmentsAccepted(receiveEnabled: Boolean, isClient: Boolean, isVirtualMaster: Boolean): Boolean =
    receiveEnabled || isClient || isVirtualMaster

internal fun secondaryTherapyEventAccepted(type: TE.Type, note: String?, isClient: Boolean = false): Boolean {
    if (type !in secondaryDeviceEventTypes) return false
    if (type != TE.Type.NOTE) return true
    // A client mirrors the other phone, so it takes every note that phone wrote, as the 3426 client does.
    if (isClient) return true
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

/**
 * Whether BG may be uploaded to the main site (2026-10-18).
 *
 * Normally not with the NSClient BG source: that BG came from the main site, so it is already there. The exception is
 * a full phone that takes BG from the second (Live) site: those readings are stored without a Nightscout id, so they are
 * new to the main site, and without this the main site (aapsfour) never got any BG. Never on a client, and never when
 * both addresses are the same site, which would send each reading back to where it came from.
 * Readings that came from the main site keep their Nightscout id and are skipped by the uploader anyway.
 */
internal fun bgUploadAllowed(
    uploadOn: Boolean,
    nsClientBgSource: Boolean,
    bgFromLiveSite: Boolean,
    isClient: Boolean,
    primaryUrl: String,
    secondaryUrl: String
): Boolean = when {
    !uploadOn         -> false
    !nsClientBgSource -> true
    else              -> bgFromLiveSite && !isClient && !sameNsSite(primaryUrl, secondaryUrl)
}

/** Same Nightscout site, ignoring http/https, letter case and a trailing slash. */
internal fun sameNsSite(a: String, b: String): Boolean {
    fun plain(url: String) = url.trim().lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/')
    return plain(a).isNotEmpty() && plain(a) == plain(b)
}

private val secondaryDeviceEventTypes = setOf(
    TE.Type.SENSOR_CHANGE,
    TE.Type.SENSOR_STARTED,
    TE.Type.CANNULA_CHANGE,
    TE.Type.INSULIN_CHANGE,
    TE.Type.PUMP_BATTERY_CHANGE,
    TE.Type.NOTE
)
