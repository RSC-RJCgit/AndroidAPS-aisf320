package app.aaps.plugins.sync.nsclientV3

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * iOS (2026-10-18, per explicit request): when no new BG has come for 2 minutes, load from Nightscout again. The readings
 * normally come every minute, so 2 minutes is one missed reading. With the websocket on, a missed message is otherwise
 * not loaded until the socket reconnects. Setting: [BooleanKey.IosReloadWhenNoBg] (on by default). Works only while
 * the app runs; in the background that needs the keep-alive.
 */
@SingleIn(AppScope::class)
@Inject
class IosNoBgReload(
    private val aapsLogger: AAPSLogger,
    private val persistenceLayer: PersistenceLayer,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val nsClient: NSClientV3Plugin
) {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var lastReloadAt = 0L
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                delay(30_000L)
                if (!preferences.get(BooleanKey.IosReloadWhenNoBg) || !nsClient.isEnabled()) continue
                val now = dateUtil.now()
                val lastBgAt = persistenceLayer.getLastGlucoseValue()?.timestamp
                if (noBgReloadDue(lastBgAt, lastReloadAt, now)) {
                    lastReloadAt = now
                    aapsLogger.debug(LTag.NSCLIENT, "No BG for ${(now - (lastBgAt ?: now)) / 1000} s, reloading from Nightscout")
                    nsClient.executeLoop("NO_BG_2MIN")
                }
            }
        }
    }
}
