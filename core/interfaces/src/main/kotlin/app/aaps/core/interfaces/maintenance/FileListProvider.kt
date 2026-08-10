package app.aaps.core.interfaces.maintenance

import androidx.documentfile.provider.DocumentFile
import app.aaps.core.interfaces.rx.weardata.CwfFile
import java.io.File

interface FileListProvider {

    val resultPath: File
    val aapsLogsPath: File
    /** Documents/AAPS/logs -- persistent local home for the zipped AndroidAPS log archives sendLogs()
     *  creates, mirroring aapsLogsPath's role for AIV data. Plain File (Environment public-storage
     *  path), not SAF, so it doesn't depend on the AapsDirectoryUri grant that ensureTempDirExists()/
     *  the cloud-upload path use -- and unlike those, survives an app reinstall untouched. */
    val logsPath: File
    fun ensurePreferenceDirExists(): DocumentFile?
    fun ensureExportDirExists(): DocumentFile?
    fun ensureTempDirExists(): DocumentFile?
    fun ensureExtraDirExists(): DocumentFile?

    fun newPreferenceFile(): DocumentFile?
    fun newExportCsvFile(): DocumentFile?
    fun newCwfFile(filename: String, withDate: Boolean = true): DocumentFile?

    fun ensureResultDirExists(): File
    fun newResultFile(): File
    fun ensureAapsLogsDirExists(): File
    fun newAapsLogsFile(): File
    fun listPreferenceFiles(): MutableList<PrefsFile>
    fun listCustomWatchfaceFiles(): MutableList<CwfFile>
    fun checkMetadata(metadata: Map<PrefsMetadataKey, PrefMetadata>): Map<PrefsMetadataKey, PrefMetadata>
    fun formatExportedAgo(utcTime: String): String
}