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
    fun exportUserEntriesCsv(activity: FragmentActivity)
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
