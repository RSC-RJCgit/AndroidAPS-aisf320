package app.aaps.implementation.maintenance

import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.LoggerUtils
import app.aaps.core.interfaces.maintenance.AutomaticExport
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.maintenance.Maintenance
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ExportPasswordDataStore
import app.aaps.core.interfaces.userEntry.UserEntryPresentationHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.maintenance.cloud.CloudConstants
import app.aaps.implementation.maintenance.cloud.CloudStorageManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val decimalFormatter: DecimalFormatter,
    private val loggerUtils: LoggerUtils,
    private val aapsLogger: AAPSLogger,
    private val cloudStorageManager: CloudStorageManager,
    private val maintenance: Maintenance,
    private val importExportPrefs: ImportExportPrefs,
    private val exportPasswordDataStore: ExportPasswordDataStore,
    private val userEntryPresentationHelper: UserEntryPresentationHelper
) : AutomaticExport {

    override suspend fun runIfDue() {
        try {
            val now = dateUtil.now()
            if (preferences.get(LongNonKey.LastAutoIsfHistoryExport) < now - T.hours(6).msecs()) {
                preferences.put(LongNonKey.LastAutoIsfHistoryExport, now)
                writeHistoryFiles(now)
                exportSettingsIfEnabled()
            }
            if (preferences.get(BooleanKey.MaintenanceAutoExportLogsToCloud) &&
                preferences.get(LongNonKey.LastCloudLogExport) < now - T.hours(6).msecs()
            ) {
                preferences.put(LongNonKey.LastCloudLogExport, now)
                val uploaded = maintenance.uploadLogsToCloud()
                aapsLogger.info(LTag.CORE, "EXPORT_STATUS trigger=AUTOMATIC_6H component=LOGS result=${if (uploaded) "SUCCESS" else "FAILURE"}")
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Automatic export failed", e)
        }
    }

    private suspend fun writeHistoryFiles(now: Long) {
        val units = profileFunction.getUnits()
        val rows = persistenceLayer.getAutoIsfValuesFromTimeToTime(now - AUTO_ISF_EXPORT_WINDOW_MS, now)
            .sortedByDescending { it.timestamp }
            .map { row ->
                autoIsfExportCells(
                    row = row,
                    timeText = { dateUtil.timeString(it) },
                    glucoseText = { profileUtil.fromMgdlToStringInUnits(it, units) },
                    deltaText = { profileUtil.fromMgdlToSignedStringInUnits(it, units) },
                    format2 = { decimalFormatter.to2Decimal(it) }
                )
            }
        val entries = persistenceLayer.getUserEntryFilteredDataFromTime(now - USER_ENTRIES_EXPORT_WINDOW_MS)
        val stamp = LocalDateTime.now().format(STAMP)
        val dir = File(loggerUtils.logDirectory)
        if (!dir.exists() && !dir.mkdirs()) {
            aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=AUTOMATIC_6H component=AIV_LOCAL result=FAILURE reason=no log directory")
            return
        }
        val files = listOf(
            File(dir, "AutoISF_$stamp.csv") to autoIsfExportCsv(rows),
            File(dir, "AutoISF_$stamp.txt") to autoIsfExportText(rows),
            File(dir, "AutoISF_settings_$stamp.txt") to autoIsfSettingsText(autoIsfSettings()),
            File(dir, "UserEntries_30h_$stamp.csv") to userEntryPresentationHelper.userEntriesToCsv(entries)
        )
        var written = 0
        for ((file, text) in files) {
            try {
                file.writeText(text)
                written++
            } catch (e: Exception) {
                aapsLogger.error(LTag.CORE, "Automatic export could not write ${file.name}", e)
            }
        }
        aapsLogger.info(LTag.CORE, "EXPORT_STATUS trigger=AUTOMATIC_6H component=AIV_LOCAL result=${if (written == files.size) "SUCCESS" else "FAILURE"} files=$written/${files.size}")
        uploadHistoryFiles(files.map { it.first }.filter { it.exists() })
    }

    private suspend fun uploadHistoryFiles(files: List<File>) {
        if (files.isEmpty() || !cloudStorageManager.isCloudStorageActive()) return
        val provider = cloudStorageManager.getActiveProvider() ?: return
        var uploaded = 0
        for (file in files) {
            val mime = if (file.name.endsWith(".csv")) "text/csv" else "text/plain"
            val id = provider.uploadFileToPath(file.name, file.readBytes(), mime, CloudConstants.CLOUD_PATH_AIV)
                ?: provider.uploadFile(file.name, file.readBytes(), mime)
            if (id != null) uploaded++
            else aapsLogger.error(LTag.CORE, "Automatic export cloud upload failed for ${file.name}")
        }
        aapsLogger.info(LTag.CORE, "EXPORT_STATUS trigger=AUTOMATIC_6H component=AIV_CLOUD result=${if (uploaded == files.size) "SUCCESS" else "FAILURE"} files=$uploaded/${files.size}")
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

    private fun autoIsfSettings(): List<Pair<String, String>> {
        val lines = mutableListOf<Pair<String, String>>()
        for (key in BooleanKey.entries) if (isAutoIsfKey(key.key)) lines += key.key to preferences.get(key).toString()
        for (key in DoubleKey.entries) if (isAutoIsfKey(key.key)) lines += key.key to decimalFormatter.to2Decimal(preferences.get(key))
        for (key in IntKey.entries) if (isAutoIsfKey(key.key)) lines += key.key to preferences.get(key).toString()
        for (key in StringKey.entries) if (isAutoIsfKey(key.key)) lines += key.key to preferences.get(key)
        return lines
    }

    private fun isAutoIsfKey(key: String): Boolean = key.contains("autoisf", ignoreCase = true)

    companion object {

        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
    }
}
