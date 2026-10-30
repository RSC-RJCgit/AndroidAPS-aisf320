package app.aaps.plugins.sync.nsclientV3.workers

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TE
import app.aaps.core.keys.sameNightscoutSite

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
 * While the second site only supplies raw ([rawFromSecondarySite], iOS) the switch is hidden and counts as off.
 */
internal fun glucoseFromSecondarySite(secondaryEnabled: Boolean, bgFromThisConnection: Boolean, rawFromSecondary: Boolean = false): Boolean =
    secondaryEnabled && bgFromThisConnection && !rawFromSecondary

/** The second site fills raw where this phone's own reading has none (iOS only, see SecondaryRawStore). */
internal fun rawFromSecondarySite(secondaryEnabled: Boolean, rawFromSecondary: Boolean): Boolean =
    secondaryEnabled && rawFromSecondary

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

/** Same Nightscout site, ignoring http/https, letter case, a trailing slash and anything after `?`. */
internal fun sameNsSite(a: String, b: String): Boolean = sameNightscoutSite(a, b)

/**
 * True when a Virtual-pump master (iOS "AAPS", Android "virtual", desktop client 0) would upload to the site it reads
 * BG from (2026-10-30, per explicit request). It must never: that site belongs to a real looping phone.
 *
 * - BG read from the main site: the NSClient BG source with glucose not taken from the second site (second site off,
 *   its "BG from this connection" off, or it only fills raw).
 * - The second site switched on and the same site as the main one, whatever it supplies.
 *
 * A BG source on the phone itself (xDrip, a Dexcom app) is not a Nightscout site, so only the second case applies.
 * Never on a real master or a client: [virtualOnlyMaster] is `APS && !PUMPDRIVERS`.
 */
internal fun uploadSiteIsBgSite(
    virtualOnlyMaster: Boolean,
    nsClientBgSource: Boolean,
    secondaryEnabled: Boolean,
    bgFromSecondarySwitch: Boolean,
    rawFromSecondary: Boolean,
    primaryUrl: String,
    secondaryUrl: String
): Boolean = virtualOnlyMaster && (
    (nsClientBgSource && !glucoseFromSecondarySite(secondaryEnabled, bgFromSecondarySwitch, rawFromSecondary)) ||
        (secondaryEnabled && sameNsSite(primaryUrl, secondaryUrl))
    )

private val secondaryDeviceEventTypes = setOf(
    TE.Type.SENSOR_CHANGE,
    TE.Type.SENSOR_STARTED,
    TE.Type.CANNULA_CHANGE,
    TE.Type.INSULIN_CHANGE,
    TE.Type.PUMP_BATTERY_CHANGE,
    TE.Type.NOTE
)
