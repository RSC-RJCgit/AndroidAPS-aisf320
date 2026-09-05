package app.aaps.core.interfaces.maintenance

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import app.aaps.core.interfaces.rx.weardata.CwfData
import org.json.JSONObject
import java.io.File

interface ImportExportPrefs {

    fun doImportSharedPreferences(activity: FragmentActivity)
    fun importSharedPreferences(activity: FragmentActivity)
    fun importCustomWatchface(activity: FragmentActivity)
    fun importCustomWatchface(fragment: Fragment)
    fun exportCustomWatchface(customWatchface: CwfData, withDate: Boolean = true)
    fun prefsFileExists(): Boolean
    fun verifyStoragePermissions(fragment: Fragment, onGranted: Runnable)
    fun exportSharedPreferences(f: Fragment)
    fun exportSharedPreferencesNonInteractive(context: Context, password: String): Boolean
    /**
     * Interactive User-Entries export entry point (Maintenance CSV button, Treatments→User Entry
     * menu). Runs the same full AIV + UserEntries + log-zip bundle as [sendLogs] / long-press AIV,
     * not UserEntries files alone.
     */
    fun exportUserEntriesCsv(activity: FragmentActivity)

    /**
     * Writes UserEntries next to the AIV files (dated 30h TXT, 90-day CSV, plus output/ copies).
     * Returns the dated 30h TXT so [AutoIsfHistoryExporter.writeExport] can treat it as the
     * fourth AIV file and upload it on the same cloud path as the AIV trio.
     */
    fun writeUserEntriesAivFile(): File?

    /**
     * Leftover UserEntries-only WorkManager enqueue. writeExport no longer uses this -- it writes
     * the fourth AIV file synchronously via [writeUserEntriesAivFile]. Kept for any remaining
     * callers that only want UserEntries without the AIV trio.
     */
    fun exportUserEntriesCsvAuto()
    fun exportApsResult(algorithm: String?, input: JSONObject, output: JSONObject?)

    /**
     * Zip current logs and send them (cloud storage if configured, else email) -- same action as the
     * "Send logs" button on the Maintenance screen.
     */
    fun sendLogs(trigger: String = "MANUAL", alsoExportAiv: Boolean = true)
    fun uploadAivFilesToCloud(files: List<File>, trigger: String, onComplete: (() -> Unit)? = null)

    /**
     * Newest pump APK under Google Drive folder AAPS (authorized token). Writes [dest].
     * drive.file may not see APKs uploaded by DriveSync/PC.
     */
    fun downloadNewestDriveAapsApk(dest: File): Pair<Boolean, String>

    /**
     * Store for selected file from UI
     */
    var selectedImportFile: PrefsFile?
}
