package app.aaps.plugins.configuration.maintenance

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.LoggerUtils
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.nsclient.NSSettingsStatus
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.validators.DefaultEditTextValidator
import app.aaps.core.validators.EditTextValidator
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveStringPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.configuration.R
import app.aaps.plugins.configuration.activities.DaggerAppCompatActivityWithResult
import app.aaps.plugins.configuration.maintenance.cloud.CloudConstants
import app.aaps.plugins.configuration.maintenance.cloud.CloudStorageManager
import app.aaps.plugins.configuration.maintenance.cloud.StorageTypes
import app.aaps.plugins.configuration.maintenance.cloud.ExportOptionsDialog
import app.aaps.ui.dialogs.AutoIsfHistoryExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Arrays
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MaintenancePlugin @Inject constructor(
    private val context: Context,
    rh: ResourceHelper,
    private val preferences: Preferences,
    private val nsSettingsStatus: NSSettingsStatus,
    aapsLogger: AAPSLogger,
    private val config: Config,
    private val fileListProvider: FileListProvider,
    private val loggerUtils: LoggerUtils,
    private val uel: UserEntryLogger,
    private val cloudStorageManager: CloudStorageManager,
    private val exportOptionsDialog: ExportOptionsDialog,
    private val autoIsfHistoryExporter: AutoIsfHistoryExporter
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .fragmentClass(MaintenanceFragment::class.java.name)
        .alwaysEnabled(true)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_maintenance)
        .pluginName(R.string.maintenance)
        .shortName(R.string.maintenance_shortname)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .preferencesVisibleInSimpleMode(false)
        .description(R.string.description_maintenance),
    aapsLogger, rh
) {

    /** [alsoExportAiv] defaults to true for the two truly on-demand callers -- the Maintenance screen's
     *  own button, and CloudLogsUploadTT's remote trigger in OpenAPSAutoISFPlugin.kt -- which previously
     *  only touched log files, so an AAPSClient/remote-triggered log export never carried AIV data along
     *  with it. Pass false from KeepAliveWorker's own exportLogsToCloudIfDue(): that automatic path
     *  already runs alongside exportAutoIsfHistoryIfDue() in the same 6h worker cycle, which
     *  independently (and unconditionally, unlike this cloud-log path's own
     *  MaintenanceAutoExportLogsToCloud gate) exports AIV every cycle already -- without the flag this
     *  would fire the AIV export twice per cycle whenever that preference is enabled. */
    fun sendLogs(alsoExportAiv: Boolean = true, trigger: String = "MANUAL") {
        aapsLogger.info(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=STARTED")
        val amount = preferences.get(IntKey.MaintenanceLogsAmount)
        val logs = getLogFiles(amount)
        val zipFile = fileListProvider.ensureTempDirExists()?.createFile("application/zip", constructName())
        if (zipFile == null) {
            aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=FAILURE reason=ZIP_CREATE")
            return
        }
        aapsLogger.debug("zipFile: ${zipFile.name}")
        val zip = zipLogs(zipFile, logs)
        saveLogsLocally(zip, trigger)

        if (alsoExportAiv) {
            // Off the UI thread, same CoroutineScope(Dispatchers.IO).launch pattern as
            // sendLogsToCloudDrive() below -- self-contained (queries persistenceLayer itself), no data
            // to pass in. buildCombinedExport() always covers its own fixed 30h window regardless of
            // caller, so nothing further to widen here.
            CoroutineScope(Dispatchers.IO).launch {
                val now = System.currentTimeMillis()
                val writtenFiles = autoIsfHistoryExporter.exportLast6Hours(now)
                autoIsfHistoryExporter.buildCombinedExport(now)
                if (writtenFiles.size == 3)
                    aapsLogger.info(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=AIV_LOCAL result=SUCCESS files=${writtenFiles.size}")
                else
                    aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=AIV_LOCAL result=FAILURE files=${writtenFiles.size}/3")
            }
        }

        // Check export destination preference (master switch or individual setting)
        if ((exportOptionsDialog.isLogCloudEnabled()) && 
            cloudStorageManager.isCloudStorageActive()) {
            // Send to Cloud Drive
            sendLogsToCloudDrive(zip, trigger)
        } else {
            // Send via email (default behavior)
            val recipient = preferences.get(StringKey.MaintenanceEmail)
            val attachmentUri = zip.uri
            val emailIntent: Intent = this.sendMail(attachmentUri, recipient, "Log Export")
            aapsLogger.debug("sending emailIntent")
            context.startActivity(emailIntent)
        }
    }

    /** Persistent local copy of every zip sendLogs() creates, mirroring how AutoIsfHistoryExporter
     *  keeps a local aapsLogs/<PatientName> copy of AIV data rather than relying on the cloud upload
     *  alone. Runs unconditionally (every sendLogs() call -- manual button, TT remote trigger, and the
     *  automatic 6h KeepAliveWorker cycle alike), independent of the cloud-upload preference and of
     *  cloud upload success/failure, since local save answers a different question (do we have a copy
     *  at all) than "did it reach the cloud".
     *
     *  Deliberately plain File under Environment's public Documents path (fileListProvider.logsPath),
     *  NOT the SAF-backed ensureTempDirExists()/AapsDirectoryUri route the zip itself and the cloud
     *  upload use -- that grant does not survive an app reinstall or data clear, which is exactly the
     *  failure mode that left Client's cloud log export silently dead after its reinstall (2026-08-09)
     *  while its local AIV exports kept working the whole time via this same plain-File route.
     *
     *  Scoped per patient (logs/<PatientName>/, matching the cloud path's logs_<PatientName> naming)
     *  so multiple devices exporting into a shared Google-Drive-synced Documents folder don't collide.
     *  Retention capped at localLogsKeepCount (28, ~1 week at the 6h automatic cadence) -- each zip is
     *  several MB to low tens of MB, so unlike the AIV text/CSV exports this would otherwise grow
     *  unbounded. */
    private fun saveLogsLocally(zipFile: DocumentFile, trigger: String) {
        val localLogsKeepCount = 28
        try {
            val bytes = context.contentResolver.openInputStream(zipFile.uri)?.use { it.readBytes() }
            if (bytes == null) {
                aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=LOG_LOCAL result=FAILURE reason=ZIP_READ")
                return
            }
            if (bytes.size < 1024) {
                aapsLogger.error(
                    LTag.CORE,
                    "EXPORT_STATUS trigger=$trigger component=LOG_LOCAL result=FAILURE reason=ZIP_UNDER_1KB bytes=${bytes.size}"
                )
                return
            }
            val patientName = preferences.get(StringKey.GeneralPatientName).trim()
            val dir = (if (patientName.isNotEmpty()) File(fileListProvider.logsPath, patientName) else fileListProvider.logsPath)
                .also { it.mkdirs() }
            File(dir, zipFile.name ?: constructName()).writeBytes(bytes)
            aapsLogger.debug("Logs saved locally to ${dir.absolutePath}")
            aapsLogger.info(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=LOG_LOCAL result=SUCCESS bytes=${bytes.size}")

            val existing = dir.listFiles { _, name -> name.startsWith("AndroidAPS") && name.endsWith(".zip") } ?: return
            if (existing.size > localLogsKeepCount) {
                Arrays.sort(existing) { f1: File, f2: File -> f2.name.compareTo(f1.name) }
                existing.drop(localLogsKeepCount).forEach { it.delete() }
            }
        } catch (e: Exception) {
            aapsLogger.error("Error saving logs locally", e)
            aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=LOG_LOCAL result=FAILURE reason=EXCEPTION", e)
        }
    }

    fun deleteLogs(keep: Int) {
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

    /**
     * returns a list of log files. The number of returned logs is given via the amount
     * parameter.
     *
     * The log files are sorted by the name descending.
     *
     * @param amount
     * @return
     */
    fun getLogFiles(amount: Int): List<File> {
        aapsLogger.debug("getting $amount logs from directory ${loggerUtils.logDirectory}")
        val logDir = File(loggerUtils.logDirectory)
        val files = logDir.listFiles { _: File?, name: String ->
            (name.startsWith("AndroidAPS")
                && (name.endsWith(".log")
                || name.endsWith(".zip") && !name.endsWith(loggerUtils.suffix)))
        } ?: emptyArray()
        Arrays.sort(files) { f1: File, f2: File -> f2.name.compareTo(f1.name) }
        val result = listOf(*files)
        var toIndex = amount
        if (toIndex > result.size) {
            toIndex = result.size
        }
        aapsLogger.debug("returning sublist 0 to $toIndex")
        return result.subList(0, toIndex)
    }

    fun zipLogs(zipFile: DocumentFile, files: List<File>): DocumentFile {
        aapsLogger.debug("creating zip ${zipFile.name}")
        try {
            zip(zipFile, files)
        } catch (e: IOException) {
            aapsLogger.error("Cannot retrieve zip", e)
        }
        return zipFile
    }

    /**
     * construct the name of zip file which is used to export logs.
     *
     * The name is constructed using the following scheme:
     * AndroidAPS_LOG_ + Long Time + .log.zip
     *
     * @return
     */
    private fun constructName(): String {
        return "AndroidAPS_LOG_" + System.currentTimeMillis() + loggerUtils.suffix
    }

    private fun zip(zipFile: DocumentFile, files: List<File>) {
        val bufferSize = 2048
        val out = ZipOutputStream(BufferedOutputStream(FileOutputStream(context.contentResolver.openFileDescriptor(zipFile.uri, "w")?.fileDescriptor)))
        for (file in files) {
            val data = ByteArray(bufferSize)
            FileInputStream(file).use { fileInputStream ->
                BufferedInputStream(fileInputStream, bufferSize).use { origin ->
                    val entry = ZipEntry(file.name)
                    out.putNextEntry(entry)
                    var count: Int
                    while (origin.read(data, 0, bufferSize).also { count = it } != -1) {
                        out.write(data, 0, count)
                    }
                }
            }
        }
        out.close()
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
        builder.append(rh.gs(R.string.configbuilder_nightscoutversion_label) + " " + nsSettingsStatus.getVersion() + System.lineSeparator())
        if (config.isEngineeringMode()) builder.append(rh.gs(R.string.engineering_mode_enabled))
        return sendMail(attachmentUri, recipient, subject, builder.toString())
    }

    /**
     * send a mail with the given file to the recipients with the given subject.
     *
     * the returned intent should be used to really send the mail using
     *
     * startActivity(Intent.createChooser(emailIntent , "Send email..."));
     *
     * @param attachmentUri
     * @param recipient
     * @param subject
     * @param body
     *
     * @return
     */
    private fun sendMail(
        attachmentUri: Uri,
        recipient: String,
        subject: String,
        body: String
    ): Intent {
        aapsLogger.debug("sending email to $recipient with subject $subject")
        val emailIntent = Intent(Intent.ACTION_SEND)
        emailIntent.type = "text/plain"
        emailIntent.putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, subject)
        emailIntent.putExtra(Intent.EXTRA_TEXT, body)
        aapsLogger.debug("put path $attachmentUri")
        emailIntent.putExtra(Intent.EXTRA_STREAM, attachmentUri)
        emailIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return emailIntent
    }

    private fun sendLogsToCloudDrive(zipFile: DocumentFile, trigger: String) {
        try {
            aapsLogger.debug("Sending logs to cloud storage")
            
            // Read zip file contents
            val inputStream = context.contentResolver.openInputStream(zipFile.uri)
            val bytes = inputStream?.use { it.readBytes() }
            
            if (bytes != null) {
                if (bytes.size < 1024) {
                    aapsLogger.error(
                        LTag.CORE,
                        "EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=FAILURE reason=ZIP_UNDER_1KB bytes=${bytes.size}"
                    )
                    ToastUtils.errorToast(context, rh.gs(R.string.logs_upload_failed))
                    return
                }
                // Upload to cloud storage
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val provider = cloudStorageManager.getActiveProvider()
                        if (provider == null) {
                            aapsLogger.error("No active cloud provider")
                            fallbackToEmailLogs(zipFile)
                            return@launch
                        }

                        // Scope the logs folder per patient, e.g. "/AAPS/export/logs_<PatientName>".
                        // Falls back to the plain CLOUD_PATH_LOGS when no patient name is configured.
                        val patientName = preferences.get(StringKey.GeneralPatientName).trim()
                        val logsPath = if (patientName.isNotEmpty()) "${CloudConstants.CLOUD_PATH_LOGS}_$patientName" else CloudConstants.CLOUD_PATH_LOGS

                        // First set selected folder, then try path upload
                        provider.getOrCreateFolderPath(logsPath)?.let {
                            provider.setSelectedFolderId(it)
                        }

                        // No "uploading..."/"success" toasts by user request — routine cloud export
                        // progress isn't user-actionable. Failure toasts below are kept.
                        var uploadedFileId = provider.uploadFileToPath(
                            zipFile.name ?: "logs.zip",
                            bytes,
                            "application/zip",
                            logsPath
                        )
                        if (uploadedFileId == null) {
                            uploadedFileId = provider.uploadFile(zipFile.name ?: "logs.zip", bytes, "application/zip")
                        }

                        if (uploadedFileId != null) {
                            aapsLogger.debug("Logs successfully uploaded to cloud storage: $uploadedFileId")
                            aapsLogger.info(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=SUCCESS")
                        } else {
                            aapsLogger.error("Failed to upload logs to cloud storage")
                            aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=FAILURE reason=UPLOAD")
                            ToastUtils.errorToast(context, rh.gs(R.string.logs_upload_failed))
                            
                            // Fallback to email
                            fallbackToEmailLogs(zipFile)
                        }
                    } catch (e: Exception) {
                        aapsLogger.error("Error uploading logs to cloud storage", e)
                        aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=FAILURE reason=EXCEPTION", e)
                        ToastUtils.errorToast(context, rh.gs(R.string.logs_upload_error))
                        
                        // Fallback to email
                        fallbackToEmailLogs(zipFile)
                    }
                }
            } else {
                aapsLogger.error("Failed to read zip file contents")
                aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=FAILURE reason=ZIP_READ")
                fallbackToEmailLogs(zipFile)
            }
        } catch (e: Exception) {
            aapsLogger.error("Error preparing logs for cloud upload", e)
            aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=FAILURE reason=PREPARE", e)
            fallbackToEmailLogs(zipFile)
        }
    }
    
    private fun fallbackToEmailLogs(zipFile: DocumentFile) {
        aapsLogger.debug("Falling back to email for log sending")
        val recipient = preferences.get(StringKey.MaintenanceEmail)
        val attachmentUri = zipFile.uri
        val emailIntent: Intent = this.sendMail(attachmentUri, recipient, "Log Export")
        aapsLogger.debug("sending emailIntent")
        context.startActivity(emailIntent)
    }

    fun selectAapsDirectory(activity: DaggerAppCompatActivityWithResult) {
        try {
            uel.log(Action.SELECT_DIRECTORY, Sources.Maintenance)
            activity.accessTree?.launch(null)
        } catch (_: Exception) {
            ToastUtils.errorToast(activity, "Unable to launch activity. This is an Android issue")
        }
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null && !(requiredKey == "data_choice_setting" || requiredKey == "unattended_export_setting")) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "maintenance_settings"
            title = rh.gs(R.string.maintenance_settings)
            initialExpandedChildrenCount = 0
            addPreference(
                AdaptiveStringPreference(
                    ctx = context, stringKey = StringKey.MaintenanceEmail, dialogMessage = R.string.maintenance_email, title = R.string.maintenance_email,
                    validatorParams = DefaultEditTextValidator.Parameters(testType = EditTextValidator.TEST_EMAIL)
                )
            )
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.MaintenanceLogsAmount, title = R.string.maintenance_amount))
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context, booleanKey = BooleanKey.MaintenanceAutoExportLogsToCloud,
                    title = R.string.auto_export_logs_to_cloud_title,
                    summary = R.string.auto_export_logs_to_cloud_summary
                )
            )
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "data_choice_setting"
                title = rh.gs(R.string.data_choices)
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.MaintenanceEnableFabric, title = R.string.fabric_upload))
                addPreference(AdaptiveStringPreference(ctx = context, stringKey = StringKey.MaintenanceIdentification, title = R.string.identification))
            })

            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "unattended_export_setting"
                title = rh.gs(R.string.unattended_settings_export)
                addPreference(
                    AdaptiveSwitchPreference(
                        ctx = context, booleanKey = BooleanKey.MaintenanceEnableExportSettingsAutomation,
                        title = R.string.unattended_settings_export,
                        summary = R.string.unattended_settings_export_summary
                    )
                )
                // addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.AutoExportPasswordExpiryDays,
                //     title = R.string.unattended_settings_export_password_expiry,
                //     summary = R.string.unattended_settings_export_password_expiry_summary
                //     )
                // )
            })
        }
    }
}
