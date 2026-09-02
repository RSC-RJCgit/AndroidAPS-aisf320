package app.aaps.plugins.aps.openAPSAutoISF

import android.content.pm.PackageManager
import android.os.Environment
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStream

// List2 Shizuku installer for the newest pump APK under /sdcard/AAPS333 (2026-09-02).
// Uses Shizuku's hidden newProcess to run `pm install -r` as the shell user so there is no
// system Install sheet. Client is skipped by the caller. Skips *aapsclient* and *wear* names
// so a Client/Wear build sitting in extra/ is not applied to Live.
internal object ShizukuAaps333Installer {

    const val REQUEST_CODE = 75401

    fun shizukuRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun hasPermission(): Boolean =
        shizukuRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun requestPermission() {
        Shizuku.requestPermission(REQUEST_CODE)
    }

    fun newestPumpApk(): File? {
        val roots = listOf(
            File("/sdcard/AAPS333"),
            File("/storage/emulated/0/AAPS333"),
            File(Environment.getExternalStorageDirectory(), "AAPS333")
        )
        val seen = HashSet<String>()
        val apks = ArrayList<File>()
        for (root in roots) {
            if (!root.isDirectory) continue
            val canonical = try {
                root.canonicalPath
            } catch (_: Exception) {
                root.absolutePath
            }
            if (!seen.add(canonical)) continue
            root.walkTopDown().forEach { file ->
                if (!file.isFile || !file.name.endsWith(".apk", ignoreCase = true)) return@forEach
                val n = file.name.lowercase()
                if (n.contains("aapsclient") || n.contains("wear")) return@forEach
                apks.add(file)
            }
        }
        return apks.maxByOrNull { it.lastModified() }
    }

    fun install(apk: File): Pair<Boolean, String> {
        val (code, text) = exec(arrayOf("pm", "install", "-r", "-d", "--user", "0", apk.absolutePath))
        val ok = code == 0 && text.contains("Success", ignoreCase = true)
        return ok to "exit=$code $text"
    }

    private fun exec(cmd: Array<String>): Pair<Int, String> {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        val remote = method.invoke(null, cmd, null, null)
            ?: return -1 to "Shizuku.newProcess returned null"
        val out = (remote.javaClass.getMethod("getInputStream").invoke(remote) as InputStream)
            .bufferedReader().use { it.readText() }
        val err = (remote.javaClass.getMethod("getErrorStream").invoke(remote) as InputStream)
            .bufferedReader().use { it.readText() }
        val code = remote.javaClass.getMethod("waitFor").invoke(remote) as Int
        return code to (out + err).trim()
    }
}
