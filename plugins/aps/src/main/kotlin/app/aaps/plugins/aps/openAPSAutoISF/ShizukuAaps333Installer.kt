package app.aaps.plugins.aps.openAPSAutoISF

import android.content.pm.PackageManager
import android.os.Environment
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStream

// List2 Shizuku installer (2026-09-02). Always the same file, no picking among AAPS333
// backups: /sdcard/AAPS333/newest/aapsNewestAPK.apk (that folder should otherwise be empty).
// Uses Shizuku's hidden newProcess to run `pm install -r` as the shell user so there is no
// system Install sheet. Client is skipped by the caller.
internal object ShizukuAaps333Installer {

    const val REQUEST_CODE = 75401
    const val RELATIVE_DIR = "AAPS333/newest"
    const val FIXED_NAME = "aapsNewestAPK.apk"
    const val FIXED_NAME_NO_EXT = "aapsNewestAPK"

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
        val dirs = listOf(
            File("/sdcard/$RELATIVE_DIR"),
            File("/storage/emulated/0/$RELATIVE_DIR"),
            File(Environment.getExternalStorageDirectory(), RELATIVE_DIR)
        )
        val seen = HashSet<String>()
        for (dir in dirs) {
            val canonical = try {
                dir.canonicalPath
            } catch (_: Exception) {
                dir.absolutePath
            }
            if (!seen.add(canonical)) continue
            val withExt = File(dir, FIXED_NAME)
            if (withExt.isFile) return withExt
            val noExt = File(dir, FIXED_NAME_NO_EXT)
            if (noExt.isFile) return noExt
        }
        return null
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
