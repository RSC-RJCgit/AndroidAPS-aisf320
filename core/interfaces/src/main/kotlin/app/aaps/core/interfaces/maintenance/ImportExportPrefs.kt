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
     * Non-interactive UserEntries-only rider for [AutoIsfHistoryExporter.writeExport] (KeepAliveWorker
     * and dialog-open export). Enqueues CsvExportWorker against the injected Context -- must stay
     * UserEntries-only so it does not re-enter [sendLogs] from inside writeExport's AIV path.
     * Added 2026-08-18 so UserEntries_30h_<Name>.txt stops going stale between manual exports.
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
     * Store for selected file from UI
     */
    var selectedImportFile: PrefsFile?
}
