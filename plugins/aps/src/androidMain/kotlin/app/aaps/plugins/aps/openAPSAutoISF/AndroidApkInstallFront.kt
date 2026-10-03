package app.aaps.plugins.aps.openAPSAutoISF

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import rikka.shizuku.Shizuku
import java.io.File

@ContributesBinding(AppScope::class)
@Inject
class AndroidApkInstallFront(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
) : ApkInstallFront {

    private var listening = false

    override fun stageNewest(): StageOutcome {
        val result = try {
            ShizukuAapsInstaller.stageNewestAndPrune()
        } catch (error: Exception) {
            aapsLogger.warn(LTag.APS, "APK stage failed", error)
            false to (error.message ?: "exception")
        }
        var ok = result.first
        var detail = result.second
        val copiedByApp = ok
        if (!ok) {
            val taskerStatus = sendTaskerRunTask()
            try {
                Thread.sleep(2500L)
            } catch (_: InterruptedException) {
            }
            val newest = ShizukuAapsInstaller.newestPumpApk()
            if (newest != null) {
                ok = true
                detail = "Tasker '$TASKER_TASK' status=$taskerStatus dest=${newest.absolutePath} bytes=${newest.length()}; $detail"
            } else {
                detail = "$detail; Tasker '$TASKER_TASK' status=$taskerStatus newest=missing"
            }
        }
        val staged = ShizukuAapsInstaller.newestPumpApk()
        val number = staged?.let { featureNumberOf(it) }
        aapsLogger.info(LTag.APS, "APK stage ok=$ok $detail")
        return StageOutcome(ok = ok, copiedByApp = copiedByApp, detail = detail, featureNumber = number)
    }

    override fun newestStaged(): StagedApk? {
        val file = ShizukuAapsInstaller.newestPumpApk() ?: return null
        return StagedApk(
            path = file.absolutePath,
            bytes = file.length(),
            featureNumber = featureNumberOf(file),
            versionName = versionName(file),
        )
    }

    @Suppress("DEPRECATION")
    override fun runningVersionName(): String? = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: Exception) {
        null
    }

    override fun shizukuRunning(): Boolean = ShizukuAapsInstaller.shizukuRunning()

    override fun shizukuGranted(): Boolean = ShizukuAapsInstaller.hasPermission()

    override fun requestShizukuPermission() {
        try {
            ShizukuAapsInstaller.requestPermission()
        } catch (error: Exception) {
            aapsLogger.warn(LTag.APS, "Shizuku.requestPermission failed: ${error.message}")
        }
    }

    override fun installStaged(): Pair<Boolean, String> {
        val apk = ShizukuAapsInstaller.newestPumpApk()
            ?: return false to "missing aapsNewestAPK.apk under AAPS3/newest or AAPS333/newest"
        return try {
            ShizukuAapsInstaller.install(apk, context.packageName)
        } catch (error: Exception) {
            aapsLogger.warn(LTag.APS, "Shizuku APK install failed", error)
            false to (error.message ?: "exception")
        }
    }

    override fun attemptAdbStart(port: Int): Pair<Boolean, String> =
        AdbWirelessStarter.attemptStart(context, port)

    override fun listenForShizukuGrant(onGranted: () -> Unit) {
        if (listening) return
        listening = true
        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuAapsInstaller.REQUEST_CODE &&
                grantResult == PackageManager.PERMISSION_GRANTED
            ) {
                onGranted()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun versionName(apk: File): String? = try {
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.versionName
    } catch (_: Exception) {
        null
    }

    private fun featureNumberOf(apk: File): Int? =
        AapsNewestApk.featureNumberFromFile(apk)
            ?: apkFeatureNumber(versionName(apk))
            ?: AapsNewestApk.newestFeatureNumberFromNames(apkFeatureNumber(runningVersionName()))

    private fun sendTaskerRunTask(): String {
        val status = taskerExternalStatus()
        val intent = Intent("net.dinglisch.android.tasker.ACTION_TASK").apply {
            data = Uri.parse("id:${System.nanoTime()}")
            putExtra("version_number", "1.1")
            putExtra("task_name", TASKER_TASK)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        val targets = listOf(null, "net.dinglisch.android.taskerm", "net.dinglisch.android.tasker")
        for (pkg in targets) {
            try {
                val copy = Intent(intent)
                if (pkg != null) copy.setPackage(pkg)
                context.sendBroadcast(copy)
                aapsLogger.info(LTag.APS, "Tasker ACTION_TASK sent pkg=${pkg ?: "implicit"} task='$TASKER_TASK' status=$status")
            } catch (error: Exception) {
                aapsLogger.warn(LTag.APS, "Tasker ACTION_TASK pkg=$pkg failed: ${error.message}")
            }
        }
        return status
    }

    private fun taskerExternalStatus(): String {
        val perm = context.checkSelfPermission("net.dinglisch.android.tasker.PERMISSION_RUN_TASKS") ==
            PackageManager.PERMISSION_GRANTED
        if (!perm) return "NoPermission"
        return try {
            val uri = Uri.parse("content://net.dinglisch.android.tasker/prefs")
            context.contentResolver.query(uri, arrayOf("enabled", "ext_access"), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use "NoPrefs"
                val enabled = cursor.getString(0).equals("true", ignoreCase = true)
                val ext = cursor.getString(1).equals("true", ignoreCase = true)
                when {
                    !enabled -> "NotEnabled"
                    !ext -> "AccessBlocked"
                    else -> "OK"
                }
            } ?: "NoPrefs"
        } catch (error: Exception) {
            "PrefsErr:${error.message}"
        }
    }

    private companion object {
        const val TASKER_TASK = "StageAapsNewestApk"
    }
}
