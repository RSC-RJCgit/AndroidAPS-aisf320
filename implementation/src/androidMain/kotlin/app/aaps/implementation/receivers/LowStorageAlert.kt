package app.aaps.implementation.receivers

import app.aaps.core.data.time.AppTimeZone
import app.aaps.core.data.time.javaZone
import android.content.Context
import android.os.Environment
import android.os.StatFs
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
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.maintenance.deleteOldInstallers
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Cleans up old APKs and reports storage on the real-pump phone. */
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
        val now = dateUtil.now()
        val localTime = Instant.ofEpochMilli(now).atZone(AppTimeZone.javaZone())
        val localDay = localTime.toLocalDate().toEpochDay()
        val weeklyDue = localTime.dayOfWeek == DayOfWeek.SUNDAY && localTime.hour >= 12 &&
            localDay > preferences.get(LongNonKey.LastWeeklyApkCleanupDay)
        val freeBefore = if (weeklyDue) null else freeStorageGiB()
        val cleanupDue = weeklyDue || (freeBefore != null && freeBefore < 10.0)
        if (cleanupDue) {
            deleteOldInstallers(
                context, preferences.get(StringKey.AapsDirectoryUri),
                preferences.get(StringNonKey.InstallerArchiveFolders), aapsLogger
            )
        }
        val freeGiB = if (cleanupDue) freeStorageGiB() else freeBefore
        if (weeklyDue) {
            preferences.put(LongNonKey.LastWeeklyApkCleanupDay, localDay)
            val text = if (freeGiB == null) "Weekly storage: free space unavailable"
            else String.format(Locale.US, "Weekly storage: %.1fGB free", freeGiB)
            sendStorageSms(text, smsTargets(), "Weekly storage")
        }

        if (freeGiB == null) return
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
        val targets = smsTargets()
        if (!notified) {
            notificationManager.post(NotificationId.LOW_STORAGE, text = text, validMinutes = 0)
            preferences.put(BooleanNonKey.LowStorageNotified, true)
            if (targets.isEmpty()) aapsLogger.warn(LTag.CORE, "Low storage SMS skipped: Battery-alert numbers is empty")
        }
        if (sent || targets.isEmpty() || now - lastSmsAttemptAt in 0 until T.mins(15).msecs()) return

        lastSmsAttemptAt = now
        if (sendStorageSms(text, targets, "Low storage") > 0) preferences.put(BooleanNonKey.LowStorageTargetedSmsSent, true)
    }

    private fun smsTargets(): List<String> = preferences.get(StringKey.SmsBattAlertNumbers).split(";")
        .map { it.filterNot(Char::isWhitespace) }
        .filter { it.isNotEmpty() }
        .distinct()

    private fun sendStorageSms(text: String, targets: List<String>, label: String): Int {
        if (targets.isEmpty()) {
            aapsLogger.warn(LTag.CORE, "$label SMS skipped: Battery-alert numbers is empty")
            return 0
        }
        val accepted = targets.count { number ->
            try {
                smsCommunicator.sendSMS(Sms(number, text))
            } catch (error: Exception) {
                aapsLogger.error(LTag.CORE, "Could not send $label SMS", error)
                false
            }
        }
        val result = "$label SMS accepted for $accepted of ${targets.size} Battery-alert numbers"
        if (accepted == targets.size) aapsLogger.info(LTag.CORE, result) else aapsLogger.warn(LTag.CORE, result)
        return accepted
    }

    private companion object {

        fun freeStorageGiB(): Double? =
            runCatching { StatFs(Environment.getDataDirectory().path).availableBytes / BYTES_PER_GIB }.getOrNull()

        const val BYTES_PER_GIB = 1024.0 * 1024.0 * 1024.0
    }
}
