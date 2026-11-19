package app.aaps.plugins.sync.nsclientV3

import app.aaps.core.nssdk.localmodel.devicestatus.NSDeviceStatus

/** How long a real looper's last device status keeps a Virtual-pump master from uploading to that site (2026-11-19, from v4). */
internal const val REAL_LOOPER_HOLD_MS = 24 * 60 * 60 * 1000L

/**
 * Why a full AAPS on the Virtual pump uploads nothing to its main Nightscout site (2026-11-19, per explicit request, ported from v4's
 * 2026-10-30 guard). Before this, v3 only warned once a day (UploadBrake.warnIfVirtualUploads), so a Virtual phone set to Live's site
 * wrote its simulated treatments, temp targets and coded notes there, where Live acts on them.
 */
enum class MainSiteUploadBlock {

    /** It reads its BG from that site (Nightscout BG source, not from the Live connection). */
    BG_SITE,

    /** A real looping phone uploads there, see [isRealLooperStatus]. */
    REAL_LOOPER,

    /** Not known yet: the first 24 hour look at the site's device statuses has not finished. */
    NOT_CHECKED
}

/**
 * A device status from another phone that loops a real pump: another AAPS (`openaps://` and not [ownDevice]), Trio or Loop
 * (`loop://`), with a pump section. A Virtual pump uploads no pump section unless "upload status" is on, and then its configuration
 * names the Virtual pump, so another Virtual master never counts. Clients upload no device status. The same rule as v4.
 */
internal fun isRealLooperStatus(status: NSDeviceStatus, ownDevice: String): Boolean {
    val device = status.device ?: return false
    val looper = device == "Trio" || device.startsWith("loop://", ignoreCase = true) ||
        (device.startsWith("openaps://", ignoreCase = true) && !device.equals(ownDevice, ignoreCase = true))
    if (!looper || status.pump == null) return false
    val configuredPump = status.configuration?.pump.orEmpty()
    return !configuredPump.contains("virtual", ignoreCase = true)
}
