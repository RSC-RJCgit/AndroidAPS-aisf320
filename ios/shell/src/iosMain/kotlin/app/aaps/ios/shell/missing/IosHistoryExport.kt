package app.aaps.ios.shell.missing

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
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
import app.aaps.core.objects.autoisf.cells
import app.aaps.core.objects.autoisf.loadAutoIsfHistoryRows
import app.aaps.implementation.maintenance.USER_ENTRIES_EXPORT_WINDOW_MS
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
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile

/**
 * The AutoISF history files: what the phone's `HistoryFilesWriter` writes, for iOS.
 *
 * Four files (history csv and txt, the AutoISF settings, 30 hours of user entries) go to `logs` in the app's
 * Documents folder, which the Files app shows, and are uploaded to the cloud folder when cloud storage is
 * active. A separate type so [IosMaintenance] stays constructible without the database, which is how its
 * test builds it.
 */
interface IosHistoryExport {

    suspend fun writeAndUpload(trigger: String)
}

/** Writes nothing. What a test, or a build with no export bound, gets. */
object NoIosHistoryExport : IosHistoryExport {

    override suspend fun writeAndUpload(trigger: String) = Unit
}

@OptIn(ExperimentalForeignApi::class)
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosHistoryExportImpl(
    private val dateUtil: DateUtil,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val decimalFormatter: DecimalFormatter,
    private val aapsLogger: AAPSLogger,
    private val cloudStorageManager: CloudStorageManager,
    private val preferences: Preferences,
    private val userEntryPresentationHelper: UserEntryPresentationHelper,
    private val config: Config,
    private val activePlugin: ActivePlugin
) : IosHistoryExport {

    override suspend fun writeAndUpload(trigger: String) {
        val now = dateUtil.now()
        // The same rows and columns as the table on screen (2026-10-21, per explicit request)
        val rows = loadAutoIsfHistoryRows(now, persistenceLayer, profileFunction, profileUtil, dateUtil, decimalFormatter, config, preferences, activePlugin)
            .map { it.cells() }
        val entries = persistenceLayer.getUserEntryFilteredDataFromTime(now - USER_ENTRIES_EXPORT_WINDOW_MS)
        val formatter = NSDateFormatter()
        formatter.dateFormat = "yyyy-MM-dd_HHmmss"
        val stamp = formatter.stringFromDate(NSDate())
        val scope = exportScopeName(preferences.get(StringKey.GeneralInstanceName), "iPhone")
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String
        if (documents == null) {
            aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=AIV_LOCAL result=FAILURE reason=no documents directory")
            return
        }
        val dir = "$documents/logs"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val files = listOf(
            "$dir/${exportNamedFile("AutoISF", scope, stamp, "csv")}" to autoIsfExportCsv(rows),
            "$dir/${exportNamedFile("AutoISF", scope, stamp, "txt")}" to autoIsfExportText(rows),
            "$dir/${exportNamedFile("AutoISF_settings", scope, stamp, "txt")}" to autoIsfSettingsText(autoIsfSettings()),
            "$dir/${exportNamedFile("UserEntries_30h", scope, stamp, "csv")}" to userEntryPresentationHelper.userEntriesToCsv(entries)
        )
        val written = mutableListOf<Pair<String, String>>()
        for ((path, text) in files) {
            val ok = NSString.create(string = text).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
            if (ok) written += path to text else aapsLogger.error(LTag.CORE, "Export $trigger could not write $path")
        }
        aapsLogger.info(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=AIV_LOCAL result=${if (written.size == files.size) "SUCCESS" else "FAILURE"} files=${written.size}/${files.size}")
        upload(trigger, written, scope)
    }

    private suspend fun upload(trigger: String, files: List<Pair<String, String>>, scope: String) {
        if (files.isEmpty() || !cloudStorageManager.isCloudStorageActive()) return
        val provider = cloudStorageManager.getActiveProvider() ?: return
        val path = if (scope.isEmpty()) CloudConstants.CLOUD_PATH_AIV else "${CloudConstants.CLOUD_PATH_AIV}_$scope"
        var uploaded = 0
        for ((filePath, text) in files) {
            val name = filePath.substringAfterLast('/')
            val bytes = text.encodeToByteArray()
            val mime = if (name.endsWith(".csv")) "text/csv" else "text/plain"
            val id = provider.uploadFileToPath(name, bytes, mime, path) ?: provider.uploadFile(name, bytes, mime)
            if (id != null) uploaded++
            else aapsLogger.error(LTag.CORE, "Export $trigger cloud upload failed for $name")
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
}
