package app.aaps.plugins.aps.openAPSAutoISF

import android.content.Context
import dev.mobile.adb.AdbKeyPair
import dev.mobile.adb.Dadb
import java.io.File

// Virtual-only, best-effort "attempt to start Shizuku ourselves" (2026-09-12), scoped deliberately
// small per explicit request: no port auto-discovery, no re-pairing UI beyond one manual action, no
// retries. If the wireless-debugging port has moved (most commonly: debugging was toggled off and
// back on) since IntKey.ApsAutoIsfAdbConnectPort was last set, this just fails silently and the
// existing manual Shizuku "Start" tap remains the fallback, exactly as before this existed.
//
// Uses dadb (mobile-dev-inc/dadb, used by Maestro) -- a small pure-Kotlin ADB client -- rather than
// hand-rolling the ADB wireless-debugging protocol. Both pair and connect always target 127.0.0.1:
// this is the phone starting ITS OWN Shizuku over its own loopback wireless-debugging port, the same
// thing the Shizuku app's own "Start" button does -- not a remote-device connection, and not the
// same ADB key Shizuku itself holds (that one lives in Shizuku's own private app storage,
// inaccessible to AAPS). AAPS pairs and holds a completely separate key of its own.
//
// dadb's exact API surface was not verified against the live library from this offline environment --
// check plugins:aps' resolved dev.mobile:dadb version's actual AdbKeyPair/Dadb/shell-result
// signatures on first Gradle sync if this fails to compile, and adjust the calls below to match.
internal object AdbWirelessStarter {

    private const val HOST = "127.0.0.1"
    private const val KEY_FILE_NAME = "aaps_adb_wireless_key"

    private fun keyPair(context: Context): AdbKeyPair =
        AdbKeyPair.readOrCreateKeyPair(File(context.filesDir, KEY_FILE_NAME))

    // One-time pairing: run once after opening a fresh "Pair device with pairing code" screen in
    // Settings -> Developer options -> Wireless debugging, with pairPort/pairCode freshly copied from
    // that screen into IntKey.ApsAutoIsfAdbPairPort/StringKey.ApsAutoIsfAdbPairCode. Successful
    // pairing authorizes THIS key (stored under keyPair(), see above) going forward --
    // IntKey.ApsAutoIsfAdbConnectPort (the separate, longer-lived "connect" port shown once wireless
    // debugging is on) is what every later attemptStart() call actually uses; pairing itself is not
    // needed again unless the phone's paired-device list is cleared.
    fun pair(context: Context, pairPort: Int, pairCode: String): Pair<Boolean, String> {
        if (pairPort <= 0 || pairCode.isBlank()) return false to "pairPort/pairCode not configured"
        return try {
            Dadb.pair(HOST, pairPort, pairCode, keyPair(context))
            true to "paired ok, port=$pairPort"
        } catch (e: Exception) {
            false to "pair failed: ${e.message}"
        }
    }

    // Best-effort attempt to start Shizuku via the already-paired key. Silently fails (no retry, no
    // rediscovery) if the connect port is stale or the key is no longer authorized -- see this
    // object's own doc comment. Caller (installNewestAaps333Apk()) treats this purely as "give the
    // binder a moment to come up before the existing shizukuRunning() poll", not as a guaranteed start.
    fun attemptStart(context: Context, connectPort: Int): Pair<Boolean, String> {
        if (connectPort <= 0) return false to "connectPort not configured"
        return try {
            val dadb = Dadb.create(HOST, connectPort, keyPair(context))
            try {
                val result = dadb.shell("sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh")
                true to "start.sh exit=${result.exitCode} ${result.output}${result.errorOutput}".trim()
            } finally {
                dadb.close()
            }
        } catch (e: Exception) {
            false to "connect/start failed: ${e.message}"
        }
    }
}
