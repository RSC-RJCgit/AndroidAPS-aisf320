package app.aaps.implementation.notifications

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.receivers.ReceiverStatusStore
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.implementation.ImplementationStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Phone battery alarm on iOS (2026-10-18, per explicit request): under 50% and falling 5% or more in 30 min, see
 * [batteryFallAlarmDue]. Checks once a minute while the app runs, which in the background needs the keep-alive
 * ([IosBackgroundKeepAlive]). No alarm while charging.
 */
@SingleIn(AppScope::class)
@Inject
class IosBatteryFallAlarm(
    private val aapsLogger: AAPSLogger,
    private val receiverStatusStore: ReceiverStatusStore,
    private val notificationManager: NotificationManager,
    private val rh: TextResolver,
    private val dateUtil: DateUtil
) {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val samples = ArrayDeque<Pair<Long, Int>>()
    private var lastAlarmAt = 0L
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                check()
                delay(60_000L)
            }
        }
    }

    private fun check() {
        val now = dateUtil.now()
        samples.addLast(now to receiverStatusStore.batteryLevel)
        while (samples.isNotEmpty() && now - samples.first().first > BATTERY_FALL_WINDOW_MS) samples.removeFirst()
        if (receiverStatusStore.isCharging) return
        val fall = batteryFallAlarmDue(samples.toList(), now, lastAlarmAt) ?: return
        lastAlarmAt = now
        val text = rh.gs(ImplementationStrings.ios_battery_falling, samples.last().second, fall)
        aapsLogger.debug(LTag.CORE, text)
        notificationManager.post(id = NotificationId.PHONE_BATTERY_FALL, text = text)
    }
}
