package app.aaps.implementation.receivers

import android.content.Context
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.smsCommunicator.Sms
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.Locale

/** Warns on the real-pump phone even when closed loop has stopped. */
@SingleIn(AppScope::class)
@Inject
class LowStorageAlert(
    private val config: Config,
    private val activePlugin: ActivePlugin,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val notificationManager: NotificationManager,
    private val smsCommunicator: SmsCommunicator,
    private val aapsLogger: AAPSLogger
) {

    private var lastSmsAttemptAt = 0L

    @Synchronized
    fun check(context: Context) {
        if (!config.APS || config.AAPSCLIENT || activePlugin.activePumpInternal is VirtualPump) return
        val filesDir = context.filesDir
        if (!filesDir.isDirectory) return
        val freeGiB = filesDir.usableSpace / (1024.0 * 1024.0 * 1024.0)
        val notified = preferences.get(BooleanNonKey.LowStorageNotified)
        val sent = preferences.get(BooleanNonKey.LowStorageTargetedSmsSent)
        if (freeGiB >= 10.5) {
            if (notified) {
                preferences.put(BooleanNonKey.LowStorageNotified, false)
                notificationManager.dismiss(NotificationId.LOW_STORAGE)
            }
            if (sent) preferences.put(BooleanNonKey.LowStorageTargetedSmsSent, false)
            lastSmsAttemptAt = 0L
            return
        }
        if (freeGiB >= 10.0 || (notified && sent)) return

        val text = String.format(Locale.US, "StLow %.1fGB free", freeGiB)
        val targets = preferences.get(StringKey.SmsBattAlertNumbers).split(";")
            .map { it.filterNot(Char::isWhitespace) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (!notified) {
            notificationManager.post(NotificationId.LOW_STORAGE, text = text, validMinutes = 0)
            preferences.put(BooleanNonKey.LowStorageNotified, true)
            if (targets.isEmpty()) aapsLogger.warn(LTag.CORE, "Low storage SMS skipped: Battery-alert numbers is empty")
        }
        val now = dateUtil.now()
        if (sent || targets.isEmpty() || now - lastSmsAttemptAt in 0 until T.mins(15).msecs()) return

        lastSmsAttemptAt = now
        val accepted = targets.count { number ->
            try {
                smsCommunicator.sendSMS(Sms(number, text))
            } catch (error: Exception) {
                aapsLogger.error(LTag.CORE, "Could not send low storage SMS", error)
                false
            }
        }
        if (accepted > 0) preferences.put(BooleanNonKey.LowStorageTargetedSmsSent, true)
        val result = "Low storage SMS accepted for $accepted of ${targets.size} Battery-alert numbers"
        if (accepted == targets.size) aapsLogger.info(LTag.CORE, result) else aapsLogger.warn(LTag.CORE, result)
    }
}
