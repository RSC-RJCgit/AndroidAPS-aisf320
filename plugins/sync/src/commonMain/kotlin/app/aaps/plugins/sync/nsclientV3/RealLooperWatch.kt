package app.aaps.plugins.sync.nsclientV3

import app.aaps.core.nssdk.localmodel.devicestatus.NSDeviceStatus

/** How long a real looper's last device status keeps a Virtual-pump master from uploading to that site (2026-10-30). */
internal const val REAL_LOOPER_HOLD_MS = 24 * 60 * 60 * 1000L

/** Why a Virtual-pump master (`APS && !PUMPDRIVERS`) uploads nothing to its main Nightscout site (2026-10-30, per explicit request). */
enum class MainSiteUploadBlock {

    /** It reads BG from that site, see `uploadSiteIsBgSite`. */
    BG_SITE,

    /** A real looping phone uploads there, see [isRealLooperStatus]. */
    REAL_LOOPER,

    /** Not known yet: the first device-status load after a start or a site change has not finished. */
    NOT_CHECKED
}

/**
 * A device status from another phone that loops a real pump (2026-10-30, per explicit request): another AAPS
 * (`openaps://` and not [ownDevice]), Trio or Loop (`loop://`), with a pump section. A Virtual pump uploads no pump section
 * unless "upload status" is on, and then its configuration names the Virtual pump, so another Virtual master never counts.
 * Clients upload no device status.
 */
internal fun isRealLooperStatus(status: NSDeviceStatus, ownDevice: String): Boolean {
    val device = status.device ?: return false
    val looper = device == "Trio" || device.startsWith("loop://", ignoreCase = true) ||
        (device.startsWith("openaps://", ignoreCase = true) && !device.equals(ownDevice, ignoreCase = true))
    if (!looper || status.pump == null) return false
    val configuredPump = status.configuration?.get("pump")?.toString().orEmpty()
    return !configuredPump.contains("virtual", ignoreCase = true)
}
