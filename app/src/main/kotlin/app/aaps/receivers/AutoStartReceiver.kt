package app.aaps.receivers

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import app.aaps.plugins.main.general.persistentNotification.DummyServiceHelper
import dagger.android.DaggerBroadcastReceiver
import javax.inject.Inject

class AutoStartReceiver : DaggerBroadcastReceiver() {

    @Inject lateinit var dummyServiceHelper: DummyServiceHelper

    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        processIntent(context, intent)
    }

    @VisibleForTesting
    fun processIntent(context: Context, intent: Intent) {
        // BOOT_COMPLETED: reboot. MY_PACKAGE_REPLACED: APK update. KeepAlive cannot cover the
        // update case — Android cancels that app's WorkManager jobs on replace, so nothing is
        // left to fire until the process starts again. This is the same DummyService path boot uses.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
            dummyServiceHelper.startService(context)
    }
}