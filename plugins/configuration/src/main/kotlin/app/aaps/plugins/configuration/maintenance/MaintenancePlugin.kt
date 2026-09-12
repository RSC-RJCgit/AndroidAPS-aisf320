package app.aaps.plugins.configuration.maintenance

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.ExportScriptDebugStatus
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.LoggerUtils
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.nsclient.NSSettingsStatus
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.NoteTimestampAllocator
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.toast.ToastUtils
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
import java.util.concurrent.TimeUnit
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
    private val autoIsfHistoryExporter: AutoIsfHistoryExporter,
    private val persistenceLayer: PersistenceLayer
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

    /** [alsoExportAiv] defaults to true for combined manual/remote requests. Those requests export and
     *  upload AIV first, then re-enter with false from the cloud-completion callback to perform only the
     *  log portion. The automatic and long-press paths likewise call the false continuation after their
     *  own AIV work, preventing duplicate AIV files while preserving strict result-note ordering. */
    fun sendLogs(alsoExportAiv: Boolean = true, trigger: String = "MANUAL") {
        // AIV backup is an independent part of a manual/remote export. Start it before checking for
        // source log files so NO_SOURCE_LOGS, ZIP_CREATE, or another log-only failure cannot suppress
        // the CSV/TXT/settings files, combined file, or their cloud upload. The log-only continuation
        // is invoked from the AIV cloud callback, enforcing AVLs/AVLf -> AVCs/AVCf -> log-result order.
        if (alsoExportAiv) {
            CoroutineScope(Dispatchers.IO).launch {
                val now = System.currentTimeMillis()
                val writtenFiles = autoIsfHistoryExporter.exportLast6Hours(now)
                autoIsfHistoryExporter.buildCombinedExport(now)
                val expect = AutoIsfHistoryExporter.AIV_EXPORT_FILE_COUNT
                if (writtenFiles.size == expect)
                    aapsLogger.info(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=AIV_LOCAL result=SUCCESS files=${writtenFiles.size}")
                else
                    aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=AIV_LOCAL result=FAILURE files=${writtenFiles.size}/$expect")
                ExportScriptDebugStatus.add(
                    if (writtenFiles.size == expect) "EXPORT_STATUS trigger=$trigger component=AIV_LOCAL result=SUCCESS files=$expect"
                    else "EXPORT_STATUS trigger=$trigger component=AIV_LOCAL result=FAILURE files=${writtenFiles.size}/$expect"
                )
                autoIsfHistoryExporter.addExportCarePortalNote(if (writtenFiles.size == expect) "AVLs" else "AVLf")
                uploadAivFilesToCloud(writtenFiles, trigger) {
                    sendLogs(alsoExportAiv = false, trigger = trigger)
                }
            }
            return
        }

        val startedStatus = "EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=STARTED"
        aapsLogger.info(LTag.CORE, startedStatus)
        ExportScriptDebugStatus.add(startedStatus)

        val amount = preferences.get(IntKey.MaintenanceLogsAmount)
        val logs = getLogFiles(amount)
        if (logs.isEmpty()) {
            val status = "EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=FAILURE reason=NO_SOURCE_LOGS"
            aapsLogger.error(LTag.CORE, status)
            ExportScriptDebugStatus.add(status)
            addCloudLogCarePortalNote(trigger, success = false)
            ToastUtils.errorToast(context, rh.gs(R.string.logs_upload_no_source))
            return
        }
        // Zip with a plain File under logsPath first. The old SAF temp createFile() path is what
        // produced ZIP_CREATE / LOGF1 on Client after its 2026-08-09 reinstall killed AapsDirectoryUri,
        // while local AIV File writes (and Virtual's still-valid grant) kept working.
        val zipName = constructName()
        val localDir = localLogsDir()
        val localZip = File(localDir, zipName)
        try {
            zipLogsToFile(localZip, logs)
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=FAILURE reason=ZIP_CREATE", e)
            ExportScriptDebugStatus.add("EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=FAILURE reason=ZIP_CREATE")
            addCloudLogCarePortalNote(trigger, success = false)
            return
        }
        aapsLogger.debug("zipFile: ${localZip.name}")
        saveLogsLocally(localZip, trigger)
        if (localZip.length() < 1024) {
            addCloudLogCarePortalNote(trigger, success = false)
            return
        }

        // Check export destination preference (master switch or individual setting)
        if ((exportOptionsDialog.isLogCloudEnabled()) &&
            cloudStorageManager.isCloudStorageActive()) {
            sendLogsToCloudDrive(localZip, trigger)
        } else {
            val status = "EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=FAILURE reason=CLOUD_NOT_ENABLED"
            aapsLogger.error(LTag.CORE, status)
            ExportScriptDebugStatus.add(status)
            addCloudLogCarePortalNote(trigger, success = false)
        }
    }

    /** Persistent local copy of every zip sendLogs() creates, mirroring how AutoIsfHistoryExporter
     *  keeps a local aapsLogs/<PatientName> copy of AIV data rather than relying on the cloud upload
     *  alone. Runs unconditionally (every sendLogs() call -- manual button, TT remote trigger, and the
     *  automatic 6h KeepAliveWorker cycle alike), independent of the cloud-upload preference and of
     *  cloud upload success/failure, since local save answers a different question (do we have a copy
     *  at all) than "did it reach the cloud".
     *
     *  Deliberately plain File under Environment's public Documents path (fileListProvider.logsPath).
     *  sendLogs() now zips here first and uploads those bytes, because the SAF-backed
     *  ensureTempDirExists()/AapsDirectoryUri grant does not survive an app reinstall or data clear --
     *  the failure mode that left Client's cloud log export silently dead after its reinstall
     *  (2026-08-09) while its local AIV exports kept working via this same plain-File route.
     *
     *  Scoped per patient (logs/<PatientName>/, matching the cloud path's logs_<PatientName> naming)
     *  so multiple devices exporting into a shared Google-Drive-synced Documents folder don't collide.
     *  Retention capped at localLogsKeepCount (28, ~1 week at the 6h automatic cadence) -- each zip is
     *  several MB to low tens of MB, so unlike the AIV text/CSV exports this would otherwise grow
     *  unbounded. */
    /** GeneralPatientName with the phone model appended so per-device export folders never collide
     *  when two phones share a patient name. Sanitised to [A-Za-z0-9]; empty stays empty. Kept in
     *  step with AutoIsfHistoryExporter.scopedExportName() and ImportExportPrefsImpl so the local
     *  logs dir and the Drive aiv_/logs_ folder paths all use the same suffix (added 2026-09-09). */
    private fun scopedExportName(): String =
        preferences.get(StringKey.GeneralPatientName).trim().let { base ->
            if (base.isEmpty()) base
            else "${base}_${android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9]"), "")}"
        }

    private fun localLogsDir(): File {
        val patientName = scopedExportName()
        return (if (patientName.isNotEmpty()) File(fileListProvider.logsPath, patientName) else fileListProvider.logsPath)
            .also { it.mkdirs() }
    }

    private fun zipLogsToFile(zipFile: File, files: List<File>) {
        val bufferSize = 2048
        val usedEntryNames = mutableSetOf<String>()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { out ->
            val data = ByteArray(bufferSize)
            for (file in files) {
                // findSourceLogFiles() can legitimately return two different files with the same base
                // name (e.g. AndroidAPS.log from the main log dir AND from the scoped-storage
                // app-specific fallback dir) -- ZipOutputStream throws "duplicate entry" on the second
                // identical entry name, which used to abort the whole export before it ever reached the
                // cloud upload step (2026-09-11: this is what silently broke Export/Send Logs and the
                // ISF-long-press log trigger on Live while the separate AIV export kept working fine).
                // Disambiguate with the parent directory name first so the source stays visible in the
                // zip; fall back to a numeric suffix if that still collides.
                var entryName = file.name
                if (!usedEntryNames.add(entryName)) {
                    entryName = "${file.parentFile?.name ?: "dup"}_${file.name}"
                    var suffix = 2
                    while (!usedEntryNames.add(entryName)) {
                        entryName = "${file.parentFile?.name ?: "dup"}_${suffix}_${file.name}"
                        suffix++
                    }
                }
                FileInputStream(file).use { fileInputStream ->
                    BufferedInputStream(fileInputStream, bufferSize).use { origin ->
                        out.putNextEntry(ZipEntry(entryName))
                        var count: Int
                        while (origin.read(data, 0, bufferSize).also { count = it } != -1) {
                            out.write(data, 0, count)
                        }
                    }
                }
            }
        }
    }

    private fun saveLogsLocally(zipFile: File, trigger: String) {
        val localLogsKeepCount = 28
        try {
            val bytes = zipFile.length()
            if (bytes < 1024) {
                aapsLogger.error(
                    LTag.CORE,
                    "EXPORT_STATUS trigger=$trigger component=LOG_LOCAL result=FAILURE reason=ZIP_UNDER_1KB bytes=$bytes"
                )
                ExportScriptDebugStatus.add("EXPORT_STATUS trigger=$trigger component=LOG_LOCAL result=FAILURE reason=ZIP_UNDER_1KB bytes=$bytes")
                return
            }
            val dir = zipFile.parentFile ?: localLogsDir()
            aapsLogger.debug("Logs saved locally to ${dir.absolutePath}")
            aapsLogger.info(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=LOG_LOCAL result=SUCCESS bytes=$bytes")
            ExportScriptDebugStatus.add("EXPORT_STATUS trigger=$trigger component=LOG_LOCAL result=SUCCESS bytes=$bytes")

            val existing = dir.listFiles { _, name -> name.startsWith("AndroidAPS") && name.endsWith(".zip") } ?: return
            if (existing.size > localLogsKeepCount) {
                Arrays.sort(existing) { f1: File, f2: File -> f2.name.compareTo(f1.name) }
                existing.drop(localLogsKeepCount).forEach { it.delete() }
            }
        } catch (e: Exception) {
            aapsLogger.error("Error saving logs locally", e)
            aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=LOG_LOCAL result=FAILURE reason=EXCEPTION", e)
            ExportScriptDebugStatus.add("EXPORT_STATUS trigger=$trigger component=LOG_LOCAL result=FAILURE reason=EXCEPTION")
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
        // Directory resolution + scoped-storage listing fallback live in LoggerUtils.findSourceLogFiles()
        // (shared with AutoIsfHistoryExporter.exportUkfCheckText(), which needs the same multi-directory
        // search rather than trusting loggerUtils.logDirectory alone).
        val result = loggerUtils.findSourceLogFiles()
        val toIndex = minOf(amount, result.size)
        aapsLogger.debug("found ${result.size} source log file(s): ${result.joinToString { "${it.name}(${it.length()}B)" }}; returning 0 to $toIndex")
        return result.subList(0, toIndex)
    }

    /** Upload AIV CSV/TXT/settings files for manual, remote, and scheduled exports through one path. */
    fun uploadAivFilesToCloud(files: List<File>, trigger: String, onComplete: (() -> Unit)? = null) {
        if (files.isEmpty()) {
            ExportScriptDebugStatus.add("EXPORT_STATUS trigger=$trigger component=AIV_CLOUD result=FAILURE reason=NO_FILES")
            autoIsfHistoryExporter.addExportCarePortalNote("AVCf")
            onComplete?.invoke()
            return
        }
        if (!cloudStorageManager.isCloudStorageActive()) {
            ExportScriptDebugStatus.add("EXPORT_STATUS trigger=$trigger component=AIV_CLOUD result=FAILURE reason=CLOUD_NOT_ENABLED")
            autoIsfHistoryExporter.addExportCarePortalNote("AVCf")
            onComplete?.invoke()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val provider = cloudStorageManager.getActiveProvider()
                if (provider == null) {
                    ExportScriptDebugStatus.add("EXPORT_STATUS trigger=$trigger component=AIV_CLOUD result=FAILURE reason=NO_ACTIVE_PROVIDER")
                    autoIsfHistoryExporter.addExportCarePortalNote("AVCf")
                    return@launch
                }
                val patientName = scopedExportName()
                val aivPath = if (patientName.isNotEmpty()) "${CloudConstants.CLOUD_PATH_AIV}_$patientName" else CloudConstants.CLOUD_PATH_AIV
                provider.getOrCreateFolderPath(aivPath)?.let { provider.setSelectedFolderId(it) }
                var uploaded = 0
                files.forEach { file ->
                    val mimeType = if (file.name.endsWith(".csv")) "text/csv" else "text/plain"
                    val bytes = file.readBytes()
                    var uploadedFileId = provider.uploadFileToPath(file.name, bytes, mimeType, aivPath)
                    if (uploadedFileId == null) uploadedFileId = provider.uploadFile(file.name, bytes, mimeType)
                    if (uploadedFileId != null) uploaded++
                    else aapsLogger.error(LTag.CORE, "AIV cloud upload failed for ${file.name}")
                }
                val status = if (uploaded == files.size)
                    "EXPORT_STATUS trigger=$trigger component=AIV_CLOUD result=SUCCESS files=$uploaded"
                else
                    "EXPORT_STATUS trigger=$trigger component=AIV_CLOUD result=FAILURE files=$uploaded/${files.size}"
                if (uploaded == files.size) aapsLogger.info(LTag.CORE, status) else aapsLogger.error(LTag.CORE, status)
                ExportScriptDebugStatus.add(status)
                autoIsfHistoryExporter.addExportCarePortalNote(if (uploaded == files.size) "AVCs" else "AVCf")
            } catch (e: Exception) {
                aapsLogger.error(LTag.CORE, "AIV cloud upload error", e)
                ExportScriptDebugStatus.add("EXPORT_STATUS trigger=$trigger component=AIV_CLOUD result=FAILURE reason=EXCEPTION")
                autoIsfHistoryExporter.addExportCarePortalNote("AVCf")
            } finally {
                onComplete?.invoke()
            }
        }
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

    private fun sendLogsToCloudDrive(zipFile: File, trigger: String) {
        try {
            aapsLogger.debug("Sending logs to cloud storage")
            val bytes = zipFile.readBytes()
            if (bytes.size < 1024) {
                aapsLogger.error(
                    LTag.CORE,
                    "EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=FAILURE reason=ZIP_UNDER_1KB bytes=${bytes.size}"
                )
                ExportScriptDebugStatus.add("EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=FAILURE reason=ZIP_UNDER_1KB bytes=${bytes.size}")
                addCloudLogCarePortalNote(trigger, success = false)
                ToastUtils.errorToast(context, rh.gs(R.string.logs_upload_under_1kb))
                return
            }
            val zipName = zipFile.name.ifBlank { "logs.zip" }
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val provider = cloudStorageManager.getActiveProvider()
                    if (provider == null) {
                        aapsLogger.error("No active cloud provider")
                        aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=FAILURE reason=NO_ACTIVE_PROVIDER")
                        ExportScriptDebugStatus.add("EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=FAILURE reason=NO_ACTIVE_PROVIDER")
                        addCloudLogCarePortalNote(trigger, success = false)
                        return@launch
                    }

                    // Scope the logs folder per patient, e.g. "/AAPS/export/logs_<PatientName>".
                    // Falls back to the plain CLOUD_PATH_LOGS when no patient name is configured.
                    val patientName = scopedExportName()
                    val logsPath = if (patientName.isNotEmpty()) "${CloudConstants.CLOUD_PATH_LOGS}_$patientName" else CloudConstants.CLOUD_PATH_LOGS

                    provider.getOrCreateFolderPath(logsPath)?.let {
                        provider.setSelectedFolderId(it)
                    }

                    var uploadedFileId = provider.uploadFileToPath(
                        zipName,
                        bytes,
                        "application/zip",
                        logsPath
                    )
                    if (uploadedFileId == null) {
                        uploadedFileId = provider.uploadFile(zipName, bytes, "application/zip")
                    }

                    if (uploadedFileId != null) {
                        aapsLogger.debug("Logs successfully uploaded to cloud storage: $uploadedFileId")
                        aapsLogger.info(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=SUCCESS")
                        ExportScriptDebugStatus.add("EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=SUCCESS")
                        addCloudLogCarePortalNote(trigger, success = true)
                    } else {
                        aapsLogger.error("Failed to upload logs to cloud storage")
                        aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=FAILURE reason=UPLOAD")
                        ExportScriptDebugStatus.add("EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=FAILURE reason=UPLOAD")
                        addCloudLogCarePortalNote(trigger, success = false)
                        ToastUtils.errorToast(context, rh.gs(R.string.logs_upload_failed))
                    }
                } catch (e: Exception) {
                    aapsLogger.error("Error uploading logs to cloud storage", e)
                    aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=FAILURE reason=EXCEPTION", e)
                    ExportScriptDebugStatus.add("EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=FAILURE reason=EXCEPTION")
                    addCloudLogCarePortalNote(trigger, success = false)
                    ToastUtils.errorToast(context, rh.gs(R.string.logs_upload_error))
                }
            }
        } catch (e: Exception) {
            aapsLogger.error("Error preparing logs for cloud upload", e)
            aapsLogger.error(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=FAILURE reason=PREPARE", e)
            ExportScriptDebugStatus.add("EXPORT_STATUS trigger=$trigger component=CLOUD_LOG result=FAILURE reason=PREPARE")
            addCloudLogCarePortalNote(trigger, success = false)
        }
    }

    /** One short CarePortal note for the final cloud-log result (never for STARTED/local/AIV status). */
    private fun addCloudLogCarePortalNote(trigger: String, success: Boolean) {
        val note = when {
            success && trigger == "AUTOMATIC_6H" -> "LGs6"
            success && (trigger == "ISF_LONG_PRESS" || trigger == "REMOTE_TT") -> "LGsP"
            success -> "LGsM"
            trigger == "AUTOMATIC_6H" -> "LOGF2"
            trigger == "ISF_LONG_PRESS" || trigger == "REMOTE_TT" -> "LOGF3"
            else -> "LOGF1"
        }
        val now = System.currentTimeMillis()
        if (note == "LGs6") {
            val accepted = synchronized(cloudLogSuccessNoteLock) {
                val lastNote = preferences.get(LongNonKey.LastCloudLogSuccessNote)
                if (lastNote >= now - TimeUnit.HOURS.toMillis(6)) {
                    false
                } else {
                    // Reserve before the asynchronous database insert so overlapping export callbacks
                    // cannot both create an automatic six-hour success note.
                    preferences.put(LongNonKey.LastCloudLogSuccessNote, now)
                    true
                }
            }
            if (!accepted) {
                aapsLogger.info(LTag.CORE, "Suppressing duplicate LGs6 CarePortal note inside six-hour window")
                return
            }
        }
        val therapyEvent = TE(
            timestamp = NoteTimestampAllocator.next(now),
            duration = TimeUnit.MINUTES.toMillis(1),
            type = TE.Type.NOTE,
            note = note,
            glucoseUnit = GlucoseUnit.MGDL
        )
        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = therapyEvent,
            action = Action.CAREPORTAL,
            source = Sources.Automation,
            note = "Cloud log export result",
            listValues = listOf(ValueWithUnit.SimpleString(note))
        ).subscribe({}, { error -> aapsLogger.error(LTag.CORE, "Failed to add cloud-log CarePortal note $note", error) })
    }

    private val cloudLogSuccessNoteLock = Any()

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
