package app.aaps.implementation.maintenance

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.LoggerUtils
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
import app.aaps.implementation.maintenance.cloud.CloudConstants
import app.aaps.implementation.maintenance.cloud.CloudStorageManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * AutoISF csv, text and settings, plus the 30 hour user-entries csv.
 * Written next to the logs, and uploaded when cloud storage is on.
 */
@SingleIn(AppScope::class)
@Inject
class HistoryFilesWriter(
    private val dateUtil: DateUtil,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val decimalFormatter: DecimalFormatter,
    private val loggerUtils: LoggerUtils,
    private val aapsLogger: AAPSLogger,
    private val cloudStorageManager: CloudStorageManager,
    private val preferences: Preferences,
    private val userEntryPresentationHelper: UserEntryPresentationHelper
) {

    suspend fun writeAndUpload(trigger: String, now: Long = dateUtil.now()) {
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
            aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=AIV_LOCAL result=FAILURE reason=no log directory")
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
                aapsLogger.error(LTag.CORE, "Export $trigger could not write ${file.name}", e)
            }
        }
        aapsLogger.info(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=AIV_LOCAL result=${if (written == files.size) "SUCCESS" else "FAILURE"} files=$written/${files.size}")
        upload(trigger, files.map { it.first }.filter { it.exists() })
    }

    private suspend fun upload(trigger: String, files: List<File>) {
        if (files.isEmpty() || !cloudStorageManager.isCloudStorageActive()) return
        val provider = cloudStorageManager.getActiveProvider() ?: return
        var uploaded = 0
        for (file in files) {
            val mime = if (file.name.endsWith(".csv")) "text/csv" else "text/plain"
            val id = provider.uploadFileToPath(file.name, file.readBytes(), mime, CloudConstants.CLOUD_PATH_AIV)
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
