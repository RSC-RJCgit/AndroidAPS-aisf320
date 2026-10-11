package app.aaps.desktop.shell.platform

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.userEntry.UserEntryPresentationHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.maintenance.AUTO_ISF_EXPORT_WINDOW_MS
import app.aaps.implementation.maintenance.DesktopFolders
import app.aaps.implementation.maintenance.USER_ENTRIES_EXPORT_WINDOW_MS
import app.aaps.implementation.maintenance.autoIsfExportCells
import app.aaps.implementation.maintenance.autoIsfExportCsv
import app.aaps.implementation.maintenance.autoIsfExportText
import app.aaps.implementation.maintenance.autoIsfSettingsText
import app.aaps.implementation.maintenance.cloud.CloudConstants
import app.aaps.implementation.maintenance.cloud.CloudStorageManager
import app.aaps.implementation.maintenance.exportNamedFile
import app.aaps.implementation.maintenance.exportScopeName
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The AutoISF history files: what the phone's `HistoryFilesWriter` writes, for the desktop.
 *
 * Four files (history csv and txt, the AutoISF settings, 30 hours of user entries) go to `AAPS/logs`, and are
 * uploaded to the cloud folder when cloud storage is active. A separate type so [DesktopMaintenance] stays
 * constructible without the database, which is how its test builds it.
 */
interface HistoryExport {

    suspend fun writeAndUpload(trigger: String)
}

/** Writes nothing. What a test, or a build with no export bound, gets. */
object NoHistoryExport : HistoryExport {

    override suspend fun writeAndUpload(trigger: String) = Unit
}

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class DesktopHistoryExport(
    private val dateUtil: DateUtil,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val decimalFormatter: DecimalFormatter,
    private val aapsLogger: AAPSLogger,
    private val cloudStorageManager: CloudStorageManager,
    private val preferences: Preferences,
    private val userEntryPresentationHelper: UserEntryPresentationHelper
) : HistoryExport {

    override suspend fun writeAndUpload(trigger: String) {
        val now = dateUtil.now()
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
        val scope = exportScopeName(preferences.get(StringKey.GeneralPatientName), "Desktop")
        val dir = File(DesktopFolders.root, "logs")
        if (!dir.exists() && !dir.mkdirs()) {
            aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=AIV_LOCAL result=FAILURE reason=no log directory")
            return
        }
        val files = listOf(
            File(dir, exportNamedFile("AutoISF", scope, stamp, "csv")) to autoIsfExportCsv(rows),
            File(dir, exportNamedFile("AutoISF", scope, stamp, "txt")) to autoIsfExportText(rows),
            File(dir, exportNamedFile("AutoISF_settings", scope, stamp, "txt")) to autoIsfSettingsText(autoIsfSettings()),
            File(dir, exportNamedFile("UserEntries_30h", scope, stamp, "csv")) to userEntryPresentationHelper.userEntriesToCsv(entries)
        )
        var written = 0
        for ((file, text) in files) {
            try {
                file.writeText(text)
                written++
            } catch (e: Exception) {
                aapsLogger.error(LTag.CORE, "Export $trigger could not write ${file.name}", e)
            }
        }
        aapsLogger.info(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=AIV_LOCAL result=${if (written == files.size) "SUCCESS" else "FAILURE"} files=$written/${files.size}")
        upload(trigger, files.map { it.first }.filter { it.exists() }, scope)
    }

    private suspend fun upload(trigger: String, files: List<File>, scope: String) {
        if (files.isEmpty() || !cloudStorageManager.isCloudStorageActive()) return
        val provider = cloudStorageManager.getActiveProvider() ?: return
        val path = if (scope.isEmpty()) CloudConstants.CLOUD_PATH_AIV else "${CloudConstants.CLOUD_PATH_AIV}_$scope"
        var uploaded = 0
        for (file in files) {
            val mime = if (file.name.endsWith(".csv")) "text/csv" else "text/plain"
            val id = provider.uploadFileToPath(file.name, file.readBytes(), mime, path)
                ?: provider.uploadFile(file.name, file.readBytes(), mime)
            if (id != null) uploaded++
            else aapsLogger.error(LTag.CORE, "Export $trigger cloud upload failed for ${file.name}")
        }
        aapsLogger.info(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=AIV_CLOUD result=${if (uploaded == files.size) "SUCCESS" else "FAILURE"} files=$uploaded/${files.size}")
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
