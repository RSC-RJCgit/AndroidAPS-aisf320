package app.aaps.plugins.aps.openAPSAutoISF

import android.content.pm.PackageManager
import android.os.Environment
import app.aaps.core.utils.Aaps333NewestApk
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStream

// List2 APK staging + Shizuku install (2026-09-02).
// Stage (no Shizuku): find the newest non-Client pump APK under this phone's archive folder
// (Live: AAPS3, Virtual: AAPS333) or Download, copy it to <archive>/newest/aapsNewestAPK.apk,
// and in that archive (except newest/) keep only the newest 20 APKs. Install still needs
// Shizuku `pm install -r` of that staged file, or Tasker task StageAapsNewestApk.
internal object ShizukuAaps333Installer {

    const val REQUEST_CODE = 75401
    const val FIXED_NAME = "aapsNewestAPK.apk"
    const val FIXED_NAME_NO_EXT = "aapsNewestAPK"
    const val KEEP_ARCHIVE = 20
    // Full pump APKs here are ~90MB. A Drive mid-write leftover (5 Sep 08:13: 1.8MB) must
    // not win findNewestSourceApk() or count as a Tasker stage success.
    const val MIN_PUMP_APK_BYTES = 20L * 1024L * 1024L

    // Longer name first so path matching does not treat AAPS333 as AAPS3.
    private val ARCHIVE_NAMES = listOf("AAPS333", "AAPS3")

    private val skipName = Regex("aapsclient|wear|pumpcontrol|aapsNewestAPK", RegexOption.IGNORE_CASE)
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

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
        for (name in ARCHIVE_NAMES) {
            for (dir in newestDirs(name)) {
                val withExt = File(dir, FIXED_NAME)
                if (isPlausiblePumpApk(withExt)) return withExt
                val noExt = File(dir, FIXED_NAME_NO_EXT)
                if (isPlausiblePumpApk(noExt)) return noExt
            }
        }
        return null
    }

    fun isPlausiblePumpApk(file: File): Boolean =
        file.isFile && file.length() >= MIN_PUMP_APK_BYTES

    // Copy newest matching APK into that phone's archive/newest/aapsNewestAPK.apk and prune
    // older APKs in that same archive. Download is a search root only — not pruned.
    fun stageNewestAndPrune(): Pair<Boolean, String> {
        val src = findNewestSourceApk()
            ?: return false to "no pump apk under AAPS3, AAPS333, or Download"
        val archiveName = destArchiveName(src)
        val destDir = newestDirs(archiveName).first()
        if (!destDir.exists() && !destDir.mkdirs())
            return false to "mkdir failed ${destDir.absolutePath}"
        val dest = File(destDir, FIXED_NAME)
        // Already have this APK in newest/ — do not wipe/recopy. Caller treats ok=true
        // so Tasker is not launched (5 Sep 08:13 Tasker overwrote a good 94MB file).
        if (isPlausiblePumpApk(dest) && dest.length() == src.length() && dest.lastModified() >= src.lastModified()) {
            val pruned = pruneArchive(archiveName, keep = KEEP_ARCHIVE, staged = dest)
            val detail = "already newest dest=${dest.absolutePath} bytes=${dest.length()} src=${src.absolutePath} pruned=$pruned"
            writeWinnerRecord(destDir, src, dest, "already")
            return true to detail
        }
        destDir.listFiles()?.forEach { it.delete() }
        src.copyTo(dest, overwrite = true)
        if (!isPlausiblePumpApk(dest))
            return false to "copy failed ${dest.absolutePath} bytes=${dest.length()}"
        val pruned = pruneArchive(archiveName, keep = KEEP_ARCHIVE, staged = dest)
        writeWinnerRecord(destDir, src, dest, "copied")
        return true to "src=${src.absolutePath} dest=${dest.absolutePath} bytes=${dest.length()} pruned=$pruned"
    }

    // pm install cannot read /sdcard (FUSE) as system_server — Virtual 764 ApkMs:
    // "System server has no access to read file context u:object_r:fuse:s0". Copy into
    // /data/local/tmp via Shizuku shell first, then install from that path.
    //
    // pm install -r of THIS package kills the AAPS process mid-call (Virtual 6 Sep 2026
    // 01:32 ApkGo, no ApkOk, silent until 06:30). Kotlin after this exec never runs on
    // success. The relaunch must live in the same Shizuku sh -c — that shell is not the
    // AAPS process and keeps going after the kill. Failed install does not relaunch.
    fun install(apk: File, packageName: String = "info.nightscout.androidaps"): Pair<Boolean, String> {
        if (!isPlausiblePumpApk(apk)) return false to "missing or too small ${apk.absolutePath} bytes=${apk.length()}"
        if (!PACKAGE_NAME.matches(packageName)) return false to "bad packageName $packageName"
        val tmp = "/data/local/tmp/$FIXED_NAME"
        val (cpCode, cpText) = exec(arrayOf("cp", "-f", apk.absolutePath, tmp))
        if (cpCode != 0) return false to "cp exit=$cpCode $cpText"
        exec(arrayOf("chmod", "644", tmp))
        val script =
            "pm install -r -d --user 0 '$tmp'; code=\$?; rm -f '$tmp'; " +
                "if [ \$code -eq 0 ]; then sleep 3; " +
                "am start --user 0 -n $packageName/app.aaps.MainActivity; fi; exit \$code"
        val (code, text) = exec(arrayOf("sh", "-c", script))
        val ok = code == 0 && text.contains("Success", ignoreCase = true)
        return ok to "via=$tmp relaunch=$packageName/app.aaps.MainActivity exit=$code $text"
    }

    private fun findNewestSourceApk(): File? = Aaps333NewestApk.newestSourceApk()

    private fun pruneArchive(archiveName: String, keep: Int, staged: File): Int {
        val archive = ArrayList<File>()
        val seen = HashSet<String>()
        val stagedCanon = canonicalOrAbs(staged)
        for (root in archiveDirs(archiveName)) {
            val canonical = canonicalOrAbs(root)
            if (!seen.add(canonical)) continue
            collectPumpApks(root, archive, skipNewestCopy = true)
        }
        val others = archive.filter { canonicalOrAbs(it) != stagedCanon }
            .sortedByDescending { it.lastModified() }
        val toDelete = others.drop(keep)
        var n = 0
        for (f in toDelete) if (f.delete()) n++
        return n
    }

    private fun collectPumpApks(dir: File, into: MutableList<File>, skipNewestCopy: Boolean) {
        if (!dir.isDirectory) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.isDirectory) {
                if (skipNewestCopy && f.name.equals("newest", ignoreCase = true)) continue
                collectPumpApks(f, into, skipNewestCopy)
                continue
            }
            val name = f.name
            if (name.endsWith(".part", ignoreCase = true)) continue
            if (!name.endsWith(".apk", ignoreCase = true) && name != FIXED_NAME_NO_EXT) continue
            if (skipName.containsMatchIn(name)) continue
            if (isPlausiblePumpApk(f)) into.add(f)
        }
    }

    private fun destArchiveName(src: File): String {
        archiveNameOf(src)?.let { return it }
        for (name in listOf("AAPS3", "AAPS333")) {
            if (archiveDirs(name).any { it.isDirectory }) return name
        }
        return "AAPS333"
    }

    private fun archiveNameOf(file: File): String? {
        var p: File? = file
        while (p != null) {
            if (p.name.equals("AAPS333", ignoreCase = true)) return "AAPS333"
            if (p.name.equals("AAPS3", ignoreCase = true)) return "AAPS3"
            p = p.parentFile
        }
        return null
    }

    // Last stage winner: <archive>/newest/winner.txt (src path, dest, bytes). SMS/log also
    // carry src=; CarePortal ApkSt does not.
    private fun writeWinnerRecord(destDir: File, src: File, dest: File, how: String) {
        try {
            File(destDir, "winner.txt").writeText(
                "how=$how\n" +
                    "src=${src.absolutePath}\n" +
                    "srcBytes=${src.length()}\n" +
                    "srcMtime=${src.lastModified()}\n" +
                    "dest=${dest.absolutePath}\n" +
                    "destBytes=${dest.length()}\n" +
                    "destMtime=${dest.lastModified()}\n"
            )
        } catch (_: Exception) {
        }
    }

    fun driveFetchDest(): File {
        val archiveName = listOf("AAPS333", "AAPS3").firstOrNull { name ->
            archiveDirs(name).any { it.isDirectory }
        } ?: "AAPS333"
        val dir = File(archiveDirs(archiveName).first(), "ApkDownload")
        return File(dir, "driveAapsNewest.apk")
    }

    private fun searchRoots(): List<File> {
        val roots = ArrayList<File>()
        for (name in ARCHIVE_NAMES) roots.addAll(archiveDirs(name))
        roots.add(File("/sdcard/Download"))
        roots.add(File("/storage/emulated/0/Download"))
        roots.add(File(Environment.getExternalStorageDirectory(), "Download"))
        return roots
    }

    private fun archiveDirs(name: String): List<File> = listOf(
        File("/sdcard/$name"),
        File("/storage/emulated/0/$name"),
        File(Environment.getExternalStorageDirectory(), name)
    )

    private fun newestDirs(archiveName: String): List<File> =
        archiveDirs(archiveName).map { File(it, "newest") }

    private fun canonicalOrAbs(f: File): String = try {
        f.canonicalPath
    } catch (_: Exception) {
        f.absolutePath
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
