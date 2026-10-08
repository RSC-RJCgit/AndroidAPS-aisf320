package app.aaps.plugins.aps.openAPSAutoISF

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Leaves the home screen, then opens AnyDesk. Opening the screen is not the same as AnyDesk
 * accepting a connection.
 */
@ContributesBinding(AppScope::class)
@Inject
class AndroidAnyDeskFront(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
) : AnyDeskFront {

    override fun bringToFront(onResult: (shown: Boolean) -> Unit) {
        val launch = anyDeskPackage()?.let { context.packageManager.getLaunchIntentForPackage(it) }
        if (launch == null) {
            aapsLogger.warn(LTag.APS, "AnyDesk direct restart failed: no installed AnyDesk package")
            onResult(false)
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(home)
            aapsLogger.info(LTag.APS, "AnyDesk HOME-exit before relaunch")
        } catch (error: Exception) {
            aapsLogger.warn(LTag.APS, "AnyDesk HOME-exit skipped: ${error.message}")
        }
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                context.startActivity(launch)
                aapsLogger.info(LTag.APS, "AnyDesk brought to front by AAPS after HOME-exit")
                onResult(true)
            } catch (error: Exception) {
                aapsLogger.warn(LTag.APS, "AnyDesk startActivity failed: ${error.message}")
                onResult(false)
            }
        }, RELAUNCH_DELAY_MS)
    }

    override fun overlayGranted(): Boolean =
        android.provider.Settings.canDrawOverlays(context)

    private fun anyDeskPackage(): String? {
        val packages = context.packageManager
        return CANDIDATES.firstOrNull { packages.getLaunchIntentForPackage(it) != null }
    }

    private companion object {
        const val RELAUNCH_DELAY_MS = 400L
        val CANDIDATES = listOf("com.anydesk.anydeskandroid", "com.anydesk.adcontrol.ad1")
    }
}
