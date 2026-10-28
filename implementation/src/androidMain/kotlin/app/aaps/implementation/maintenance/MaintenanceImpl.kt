package app.aaps.implementation.maintenance

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.LoggerUtils
import app.aaps.core.interfaces.maintenance.CloudStorageProvider
import app.aaps.core.interfaces.maintenance.ExportResult
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.maintenance.Maintenance
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sync.NsClient
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.CoreUiStrings
import app.aaps.implementation.maintenance.cloud.CloudConstants
import app.aaps.implementation.maintenance.cloud.CloudStorageManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File
import java.io.IOException
import java.util.Arrays
import kotlinx.coroutines.CancellationException

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class MaintenanceImpl(
    private val context: Context,
    private val rh: ResourceHelper,
    private val preferences: Preferences,
    private val nsClient: NsClient,
    private val aapsLogger: AAPSLogger,
    private val config: Config,
    private val fileListProvider: FileListProvider,
    private val loggerUtils: LoggerUtils,
    private val cloudStorageManager: CloudStorageManager,
    private val historyFilesWriter: HistoryFilesWriter,
) : Maintenance {

    override suspend fun executeSendLogs(): ExportResult {
        val amount = preferences.get(IntKey.MaintenanceLogsAmount)
        val logs = getLogFiles(amount)
        if (logs.isEmpty()) return ExportResult(localSuccess = false)
        val zipFile = try {
            createLogZip(context.cacheDir, constructName(), logs)
        } catch (e: Exception) {
            aapsLogger.error("Failed to prepare log ZIP", e)
            return ExportResult(localSuccess = false)
        }

        val logEmail = preferences.get(BooleanNonKey.ExportLogEmailEnabled)
        val logCloud = preferences.get(BooleanNonKey.ExportLogCloudEnabled)
        val isCloudActive = cloudStorageManager.isCloudStorageActive()
        val cloudEnabled = logCloud && isCloudActive

        var emailSuccess: Boolean? = null
        var cloudSuccess: Boolean? = null

        if (logEmail || !cloudEnabled) {
            emailSuccess = try {
                val recipient = preferences.get(StringKey.MaintenanceEmail)
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", zipFile)
                val emailIntent = sendMail(uri, recipient, "Log Export")
                context.startActivity(emailIntent)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                aapsLogger.error("Failed to launch email intent", e)
                false
            }
        }

        if (cloudEnabled) {
            cloudSuccess = performCloudLogUpload(DocumentFile.fromFile(zipFile))
        }

        return ExportResult(localSuccess = emailSuccess, cloudSuccess = cloudSuccess)
    }

    override suspend fun uploadLogsToCloud(): Boolean {
        if (!cloudStorageManager.isCloudStorageActive()) return false
        val amount = preferences.get(IntKey.MaintenanceLogsAmount)
        val logs = getLogFiles(amount)
        if (logs.isEmpty()) return false
        val zipFile = fileListProvider.ensureTempDirExists()?.createFile("application/zip", constructName()) ?: return false
        zipLogs(zipFile, logs)
        return performCloudLogUpload(zipFile)
    }

    override suspend fun exportCoordinated(trigger: String, withLogs: Boolean) {
        historyFilesWriter.writeAndUpload(trigger)
        if (!withLogs) return
        val uploaded = uploadLogsToCloud()
        aapsLogger.info(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=LOGS result=${if (uploaded) "SUCCESS" else "FAILURE"}")
    }

    override fun deleteLogs(keep: Int) {
        val logDir = File(loggerUtils.logDirectory)
        val files = logDir.listFiles { _: File?, name: String ->
            (name.startsWith("AndroidAPS") && name.endsWith(".zip"))
        }
        val autotuneFiles = logDir.listFiles { _: File?, name: String ->
            (name.startsWith("autotune") && name.endsWith(".zip"))
        }
        val keepIndex = keep - 1
        if (autotuneFiles != null && autotuneFiles.isNotEmpty()) {
            Arrays.sort(autotuneFiles) { f1: File, f2: File -> f2.name.compareTo(f1.name) }
            var delAutotuneFiles = listOf(*autotuneFiles)
            if (keepIndex < delAutotuneFiles.size) {
                delAutotuneFiles = delAutotuneFiles.subList(keepIndex, delAutotuneFiles.size)
                for (file in delAutotuneFiles) {
                    file.delete()
                }
            }
        }
        if (files == null || files.isEmpty()) return
        Arrays.sort(files) { f1: File, f2: File -> f2.name.compareTo(f1.name) }
        var delFiles = listOf(*files)
        if (keepIndex < delFiles.size) {
            delFiles = delFiles.subList(keepIndex, delFiles.size)
            for (file in delFiles) {
                file.delete()
            }
        }
        val exportDir = fileListProvider.ensureTempDirExists()
        exportDir?.listFiles()?.let { expFiles ->
            for (file in expFiles) file.delete()
        }
    }

    private suspend fun performCloudLogUpload(zipFile: DocumentFile): Boolean {
        return try {
            val provider = cloudStorageManager.getActiveProvider() ?: return false
            val bytes = context.contentResolver.openInputStream(zipFile.uri)?.use { it.readBytes() } ?: return false
            provider.getOrCreateFolderPath(CloudConstants.CLOUD_PATH_LOGS)?.let { provider.setSelectedFolderId(it) }
            var uploadedFileId = provider.uploadFileToPath(zipFile.name ?: "logs.zip", bytes, "application/zip", CloudConstants.CLOUD_PATH_LOGS)
            if (uploadedFileId == null) {
                uploadedFileId = provider.uploadFile(zipFile.name ?: "logs.zip", bytes, "application/zip")
            }
            uploadCurrentLog(provider)
            uploadedFileId != null
        } catch (e: Exception) {
            aapsLogger.error("Cloud log upload failed", e)
            false
        }
    }

    private suspend fun uploadCurrentLog(provider: CloudStorageProvider) {
        val current = File(loggerUtils.logDirectory).listFiles { file ->
            file.name.startsWith("AndroidAPS") && file.name.endsWith(".log") && !file.name.endsWith(".zip")
        }?.maxByOrNull { it.lastModified() } ?: return
        val id = provider.uploadFileToPath(current.name, current.readBytes(), "text/plain", CloudConstants.CLOUD_PATH_LOGS)
            ?: provider.uploadFile(current.name, current.readBytes(), "text/plain")
        if (id == null) aapsLogger.error(LTag.CORE, "Current log upload failed for ${current.name}")
    }

    /**
     * Returns a list of log files. The number of returned logs is given via the amount parameter.
     * The log files are sorted by the name descending.
     */
    internal fun getLogFiles(amount: Int): List<File> {
        aapsLogger.debug("getting $amount logs from directory ${loggerUtils.logDirectory}")
        val logDir = File(loggerUtils.logDirectory)
        val files = logDir.listFiles { _: File?, name: String ->
            (name.startsWith("AndroidAPS")
                && (name.endsWith(".log")
                || name.endsWith(".zip") && !name.endsWith(loggerUtils.suffix)))
        } ?: emptyArray()
        Arrays.sort(files) { f1: File, f2: File -> f2.name.compareTo(f1.name) }
        val result = listOf(*files)
        val toIndex = if (amount > result.size) result.size else amount
        return result.subList(0, toIndex)
    }

    private fun zipLogs(zipFile: DocumentFile, files: List<File>): DocumentFile {
        aapsLogger.debug("creating zip ${zipFile.name}")
        try {
            zip(zipFile, files)
        } catch (e: IOException) {
            aapsLogger.error("Cannot retrieve zip", e)
        }
        return zipFile
    }

    /** AndroidAPS_LOG_ + Long Time + .log.zip */
    private fun constructName(): String =
        "AndroidAPS_LOG_" + System.currentTimeMillis() + loggerUtils.suffix

    private fun zip(zipFile: DocumentFile, files: List<File>) {
        val output = context.contentResolver.openOutputStream(zipFile.uri, "w") ?: throw IOException("Cannot open log ZIP")
        writeLogZip(output, files)
    }

    @Suppress("SameParameterValue")
    private fun sendMail(attachmentUri: Uri, recipient: String, subject: String): Intent {
        val builder = StringBuilder()
        builder.append("ADD TIME OF EVENT HERE: " + System.lineSeparator())
        builder.append("ADD ISSUE DESCRIPTION OR GITHUB ISSUE REFERENCE NUMBER: " + System.lineSeparator())
        builder.append("-------------------------------------------------------" + System.lineSeparator())
        builder.append("(Please remember this will send only very recent logs." + System.lineSeparator())
        builder.append("If you want to provide logs for event older than a few hours," + System.lineSeparator())
        builder.append("you have to do it manually)" + System.lineSeparator())
        builder.append("-------------------------------------------------------" + System.lineSeparator())
        builder.append(rh.gs(config.appName) + " " + config.VERSION + System.lineSeparator())
        if (config.AAPSCLIENT) builder.append("NSCLIENT" + System.lineSeparator())
        builder.append("Build: " + config.BUILD_VERSION + System.lineSeparator())
        builder.append("Remote: " + config.REMOTE + System.lineSeparator())
        builder.append("Flavor: " + config.FLAVOR + config.BUILD_TYPE + System.lineSeparator())
        builder.append(rh.gs(CoreUiStrings.value_with_unit, rh.gs(CoreUiStrings.configbuilder_nightscoutversion_label), nsClient.detectedNsVersion() ?: "UNKNOWN") + System.lineSeparator())
        if (config.isEngineeringMode()) builder.append(rh.gs(CoreUiStrings.engineering_mode_enabled))
        val body = builder.toString()
        aapsLogger.debug("sending email to $recipient with subject $subject")
        return logEmailIntent(context.packageManager, attachmentUri, recipient, subject, body)
    }
}
