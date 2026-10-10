package app.aaps.core.interfaces.sync

import app.aaps.core.interfaces.nsclient.NSAlarm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plugin providing communication with Nightscout server
 */
interface NsClient : Sync {

    /**
     * Live WebSocket connection state. Distinct from [Sync.connected] (which reports HTTP API
     * reachability): this flips false the instant a WS disconnect event lands so consumers
     * (e.g. AAPSCLIENT scene gating) can react before the next HTTP cycle would notice.
     *
     * Default exposes a static always-false flow for impls that don't carry a real WS layer.
     */
    val wsConnectedFlow: StateFlow<Boolean>
        get() = MutableStateFlow(false).asStateFlow()

    /**
     * Wall-clock (ms epoch) when the local client last received a devicestatus batch from the
     * master, or `0L` if none has been seen yet. This does not end the live link.
     *
     * Default exposes a static `0L` flow — impls without a WS / NS receive pipeline don't
     * carry this signal and shouldn't appear reachable on its strength.
     */
    val lastDevicestatusReceivedAt: StateFlow<Long>
        get() = MutableStateFlow(0L).asStateFlow()

    /**
     * Derived "master is reachable for remote control / config edits" signal. On a client it stays
     * up while the pair is saved, this client is still on the master's list, and remote control is
     * on. Silence does not end it. A down websocket does not end it. A missed command does not end
     * it. It ends when someone unpairs, the master removes this client, or remote control is turned
     * off. It is short-circuited to always-`true` on a master device. Consumers — scene gating and
     * client→master config edits — read this instead of reassembling the raw signals, and share one
     * computed flow.
     *
     * Default exposes a static always-`true` flow: impls without a pairing pipeline must never
     * lock controls on its strength.
     */
    val masterReachable: StateFlow<Boolean>
        get() = MutableStateFlow(true).asStateFlow()

    /**
     * STABLE pairing signal — distinct from the transient [masterReachable]. True on a master; on a
     * client true only once a Client-Control pairing exists (NsClientControlClientId non-empty). It
     * flips only on an explicit pair/unpair, so it is safe to drive persistent chrome (bottom-nav
     * tabs, Manage entries, search visibility) off it without the flapping that [masterReachable]
     * exhibits. Mutating/command UI is HIDDEN when this is false because an unpaired client's signed
     * commands are silently dropped; [masterReachable] then handles the paired-but-offline case by
     * disabling (not hiding) those controls.
     *
     * Default exposes a static always-`true` flow: impls without a pairing concept must never hide UI.
     */
    val masterOrPairedClientFlow: StateFlow<Boolean>
        get() = MutableStateFlow(true).asStateFlow()

    /**
     * Whether the master currently ALLOWS remote (client-control) commands — its "stop/allow communication"
     * switch (NsClientAllowClientControl), synced master→client. Always `true` on a master. On a client: `true`
     * unless it is paired AND the master has the switch off (so an unpaired client shows the generic
     * unreachable message, not this one). Folded into [masterReachable] so a disabled switch blocks commands
     * fast instead of letting them silently time out, and surfaced to the UI so the offline banner can show a
     * distinct "remote control disabled" message. Default always-`true`.
     */
    val masterControlAllowed: StateFlow<Boolean>
        get() = MutableStateFlow(true).asStateFlow()

    /**
     * Master-side count of ACTIVE paired clients (roster entries that have completed pairing; pending
     * offers are excluded). Always `0` on a client. Drives the SetupWizard "Client control" status line
     * so the user can see how many devices are paired without opening the Authorized clients screen.
     *
     * Default exposes a static `0` flow: impls without a client-control roster have nothing to count.
     */
    val pairedClientCountFlow: StateFlow<Int>
        get() = MutableStateFlow(0).asStateFlow()

    /**
     * Actively probe master liveness (and pull any config missed while out of contact). On a client
     * this fires a signed Client-Control ping — whose authenticated ACK ("pong") refreshes
     * [masterReachable] far faster than waiting for the next devicestatus heartbeat — and re-fetches
     * the running-config docs. Rate-limited and a no-op when WS is down / on a master. Call it when an
     * offline-gated screen appears so the banner clears promptly if the master is in fact online.
     *
     * Default no-op: impls without a Client-Control channel have nothing to probe.
     */
    fun requestMasterProbe() {}

    /**
     * NS URL
     */
    val address: String

    /**
     * Set plugin in paused state
     */
    fun pause(newState: Boolean)

    /**
     * Initiate new round of upload/download
     *
     * @param reason identification of caller
     */
    fun resend(reason: String)

    /**
     * Used data sync selector
     */
    val dataSyncSelector: DataSyncSelector

    /**
     * Version of NS server
     * @return Returns detected version of NS server
     */
    fun detectedNsVersion(): String?

    enum class Collection { ENTRIES, TREATMENTS, FOODS, PROFILE, SETTINGS }

    /**
     * First load downloads all data; next loads use srvModified for sync.
     *
     * @return true while inside the first load
     */
    fun isFirstLoad(collection: Collection): Boolean = true

    /**
     * Update newest loaded timestamp for entries collection (first load)
     * Update newest srvModified (sync loads)
     *
     * @param latestReceived timestamp
     *
     */
    fun updateLatestBgReceivedIfNewer(latestReceived: Long)

    /**
     * Update newest loaded timestamp for treatments collection (first load)
     * Update newest srvModified (sync loads)
     *
     * @param latestReceived timestamp
     *
     */
    fun updateLatestTreatmentReceivedIfNewer(latestReceived: Long)

    /**
     * Send alarm confirmation to NS
     *
     * @param originalAlarm alarm to be cleared
     * @param silenceTimeInMilliseconds silence alarm for specified duration
     */
    fun handleClearAlarm(originalAlarm: NSAlarm, silenceTimeInMilliseconds: Long)

    /**
     * Clear synchronization status
     *
     * Next synchronization will start from scratch
     */
    suspend fun resetToFullSync()
}