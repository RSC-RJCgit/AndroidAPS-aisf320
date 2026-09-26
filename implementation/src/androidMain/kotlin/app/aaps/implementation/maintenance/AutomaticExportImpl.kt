package app.aaps.implementation.maintenance

import app.aaps.core.data.time.T
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.AutomaticExport
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.maintenance.Maintenance
import app.aaps.core.interfaces.protection.ExportPasswordDataStore
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Every six hours, writes the AutoISF history files next to the logs.
 * When cloud storage is on, it uploads those files too.
 * The log zip goes to cloud only when the six-hour log switch is on. It does not open email.
 * Settings are written only when unattended settings export is on and the password is still valid.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class AutomaticExportImpl(
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger,
    private val historyFilesWriter: HistoryFilesWriter,
    private val maintenance: Maintenance,
    private val importExportPrefs: ImportExportPrefs,
    private val exportPasswordDataStore: ExportPasswordDataStore,
) : AutomaticExport {

    override suspend fun runIfDue() {
        try {
            val now = dateUtil.now()
            val historyDue = preferences.get(LongNonKey.LastAutoIsfHistoryExport) < now - T.hours(6).msecs()
            val logsDue = preferences.get(BooleanKey.MaintenanceAutoExportLogsToCloud) &&
                preferences.get(LongNonKey.LastCloudLogExport) < now - T.hours(6).msecs()
            if (logsDue) {
                preferences.put(LongNonKey.LastCloudLogExport, now)
                preferences.put(LongNonKey.LastAutoIsfHistoryExport, now)
                maintenance.exportCoordinated("AUTOMATIC_6H")
                exportSettingsIfEnabled()
            } else if (historyDue) {
                preferences.put(LongNonKey.LastAutoIsfHistoryExport, now)
                historyFilesWriter.writeAndUpload("AUTOMATIC_6H", now)
                exportSettingsIfEnabled()
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Automatic export failed", e)
        }
    }

    private fun exportSettingsIfEnabled() {
        if (!preferences.get(BooleanKey.MaintenanceEnableExportSettingsAutomation)) return
        if (!exportPasswordDataStore.exportPasswordStoreEnabled()) {
            aapsLogger.info(LTag.CORE, "EXPORT_STATUS trigger=AUTOMATIC_6H component=SETTINGS result=FAILURE reason=disabled")
            return
        }
        val (password, expired, _) = exportPasswordDataStore.getPasswordFromDataStore()
        if (password.isEmpty() || expired) {
            aapsLogger.info(LTag.CORE, "EXPORT_STATUS trigger=AUTOMATIC_6H component=SETTINGS result=FAILURE reason=password")
            return
        }
        val ok = importExportPrefs.exportSharedPreferencesNonInteractive(password)
        aapsLogger.info(LTag.CORE, "EXPORT_STATUS trigger=AUTOMATIC_6H component=SETTINGS result=${if (ok) "SUCCESS" else "FAILURE"}")
    }

}
