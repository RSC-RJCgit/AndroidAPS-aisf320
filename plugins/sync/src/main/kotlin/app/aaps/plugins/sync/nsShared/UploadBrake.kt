package app.aaps.plugins.sync.nsShared

import app.aaps.core.data.time.T
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNewNotification
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.LongKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * True for a full AAPS (not the AAPSClient app) running on VirtualPump. AAPSClient is excluded because its pump also reports as virtual.
 */
fun ActivePlugin.fullAapsOnVirtualPump(config: Config): Boolean = !config.AAPSCLIENT && activePump is VirtualPump

/**
 * 2026-09-27, per explicit request: a follower phone is a full AAPS on VirtualPump or an AAPSClient. Either kind can have a second NS
 * connection set up (the app's own "secondary NS" fields) that points at Live's own NS site -- that is the only thing that connection is
 * ever used for on a follower phone. When it is configured, Live-derived reads (device status, the notes Virtual/Client act on, the
 * profile store, and now optionally glucose) come from it instead of from whichever site is this phone's own main one, and a Client's
 * outgoing instructions (temporary targets, profile switches, running-mode changes) go to it too, so Live reads them from the site it is
 * actually watching.
 */
fun ActivePlugin.isFollowerPhone(config: Config): Boolean = config.AAPSCLIENT || fullAapsOnVirtualPump(config)

fun Preferences.liveConnectionConfigured(): Boolean =
    get(BooleanKey.NsClientSecondaryEnabled) && get(StringKey.NsClientSecondaryUrl).trim().isNotEmpty()

/**
 * Temp basal / extended bolus receive brake (2026-09-26, per explicit request): a full AAPS on VirtualPump simulates its own temp basals, so
 * it never takes Live's real ones from Nightscout, whatever the "Receive TBR/EB" switch says (26 Sep 13:54: basal IOB 4.41 on Virtual against
 * 1.78 on Live, from Live's Omnipod Dash temp basals stacking on Virtual's own). Everyone else keeps the old rule: the switch, AAPSClient,
 * or a full sync.
 */
fun ActivePlugin.acceptsNsTbrEb(config: Config, switchOn: Boolean, doFullSync: Boolean = false): Boolean =
    if (fullAapsOnVirtualPump(config)) false else switchOn || config.AAPSCLIENT || doFullSync

/**
 * Upload warning (2026-09-27, per explicit request; replaces the 24 Sep upload brake): uploading to the primary Nightscout site is allowed
 * on Virtual, but its data is simulated, so if that site is the loop phone's own the two records mix. When a full AAPS on VirtualPump has
 * "Upload data to NS" on, raise one urgent notification a day naming the site, so it can be checked. No blocking.
 */
fun warnIfVirtualUploads(activePlugin: ActivePlugin, config: Config, preferences: Preferences, rxBus: RxBus, dateUtil: DateUtil) {
    if (!activePlugin.fullAapsOnVirtualPump(config) || !preferences.get(BooleanKey.NsClientUploadData)) return
    val now = dateUtil.now()
    val last = preferences.get(LongKey.NsClientVirtualUploadWarnedAt)
    if (last != 0L && now - last < T.days(1).msecs()) return
    preferences.put(LongKey.NsClientVirtualUploadWarnedAt, now)
    rxBus.send(
        EventNewNotification(
            Notification(
                9014,
                "Virtual phone is set to upload to Nightscout site ${preferences.get(StringKey.NsClientUrl)}. Check this is NOT the loop phone's upload site.",
                Notification.URGENT
            )
        )
    )
}
