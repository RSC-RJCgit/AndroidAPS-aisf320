package app.aaps.plugins.aps.openAPSAutoISF

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

// Virtual pump only. Tries to start Shizuku on this phone through the wireless debugging port.
// There is no pairing here. It works only when this phone's own key is already allowed.
// A stale port, or a key that was never allowed, fails and the Shizuku Start button remains.
internal object AdbWirelessStarter {

    private const val HOST = "127.0.0.1"
    private const val KEY_FILE_NAME = "aaps_adb_wireless_key"
    private const val PUB_KEY_FILE_NAME = "aaps_adb_wireless_key.pub"

    private fun keyPair(context: Context): AdbKeyPair {
        val privateFile = File(context.filesDir, KEY_FILE_NAME)
        val publicFile = File(context.filesDir, PUB_KEY_FILE_NAME)
        if (!privateFile.exists() || !publicFile.exists()) {
            AdbKeyPair.generate(privateFile, publicFile)
        }
        return AdbKeyPair.read(privateFile, publicFile)
    }

    fun attemptStart(context: Context, connectPort: Int): Pair<Boolean, String> {
        if (connectPort <= 0) return false to "connectPort not configured"
        return try {
            Dadb.create(HOST, connectPort, keyPair(context)).use { dadb ->
                val result = dadb.shell("sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh")
                true to "start.sh exit=${result.exitCode} ${result.output}${result.errorOutput}".trim()
            }
        } catch (error: Exception) {
            false to "connect/start failed: ${error.message}"
        }
    }
}
