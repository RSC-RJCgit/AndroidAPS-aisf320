package app.aaps.plugins.aps.openAPSAutoISF

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

// Virtual-only, best-effort "attempt to start Shizuku ourselves" (2026-09-12), scoped deliberately
// small per explicit request: no port auto-discovery, no pairing of any kind, no retries. This ONLY
// attempts a connect + start.sh using a key that is ALREADY authorized by whatever means (there is no
// AAPS-side pairing action -- see below) -- if the wireless-debugging port has moved (most commonly:
// debugging was toggled off and back on) or the key was never authorized in the first place, this
// just fails silently and the existing manual Shizuku "Start" tap remains the fallback, exactly as
// before this existed.
//
// Uses dadb (mobile-dev-inc/dadb, used by Maestro) -- a small pure-Kotlin ADB client -- for the
// connect/shell protocol itself, verified against the real downloaded dadb-1.2.10.jar (via javap,
// 2026-09-12) rather than assumed from memory. Both attempts always target 127.0.0.1: this is the
// phone starting ITS OWN Shizuku over its own loopback wireless-debugging port, the same thing the
// Shizuku app's own "Start" button does -- not a remote-device connection, and not the same ADB key
// Shizuku itself holds (that one lives in Shizuku's own private app storage, inaccessible to AAPS).
// AAPS holds a completely separate key of its own, generated locally on first use.
//
// No pairing support exists here, deliberately: dadb's actual public API (confirmed against the real
// jar, not assumed) has NO pair()/handshake method anywhere -- it only implements the ADB wireless
// CONNECT protocol for an already-authorized key, not the one-time pairing handshake itself. An
// earlier version of this file called a Dadb.pair(...) that does not exist in this library. Getting
// AAPS's own key authorized (if that's ever done at all) is entirely outside this object's scope and
// outside AAPS altogether -- this was always the deal per the original request ("attempt it, accept
// it only works if still paired"), not something this code tries to solve.
internal object AdbWirelessStarter {

    private const val HOST = "127.0.0.1"
    private const val KEY_FILE_NAME = "aaps_adb_wireless_key"
    private const val PUB_KEY_FILE_NAME = "aaps_adb_wireless_key.pub"

    // dadb has no combined "read or generate" helper (confirmed via javap) -- AdbKeyPair only exposes
    // separate read(privateFile, publicFile) and generate(privateFile, publicFile) calls, so this
    // object does the "generate once, then always read" bookkeeping itself.
    private fun keyPair(context: Context): AdbKeyPair {
        val privateFile = File(context.filesDir, KEY_FILE_NAME)
        val publicFile = File(context.filesDir, PUB_KEY_FILE_NAME)
        if (!privateFile.exists() || !publicFile.exists()) {
            AdbKeyPair.generate(privateFile, publicFile)
        }
        return AdbKeyPair.read(privateFile, publicFile)
    }

    // Best-effort attempt to start Shizuku via a key that is (hopefully) already authorized. Silently
    // fails (no retry, no rediscovery) if the connect port is stale or the key was never authorized --
    // see this object's own doc comment. Caller (installNewestAaps333Apk()) treats this purely as
    // "give the binder a moment to come up before the existing shizukuRunning() poll", not as a
    // guaranteed start.
    fun attemptStart(context: Context, connectPort: Int): Pair<Boolean, String> {
        if (connectPort <= 0) return false to "connectPort not configured"
        return try {
            Dadb.create(HOST, connectPort, keyPair(context)).use { dadb ->
                val result = dadb.shell("sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh")
                true to "start.sh exit=${result.exitCode} ${result.output}${result.errorOutput}".trim()
            }
        } catch (e: Exception) {
            false to "connect/start failed: ${e.message}"
        }
    }
}
