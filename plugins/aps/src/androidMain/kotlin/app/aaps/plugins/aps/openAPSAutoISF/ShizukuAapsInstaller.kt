package app.aaps.plugins.aps.openAPSAutoISF

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStream

// Stage copies the newest pump APK and keeps 20 older ones. Install uses Shizuku, because pm cannot read the card.
internal object ShizukuAapsInstaller {

    const val REQUEST_CODE = 75401
    const val KEEP_ARCHIVE = 20

    private val ARCHIVE_NAMES = listOf("AAPS333", "AAPS3")
    private val skipName = Regex("aapsclient|wear|pumpcontrol|aapsNewestAPK", RegexOption.IGNORE_CASE)

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

    fun newestPumpApk(): File? = AapsNewestApk.newestStagedApk()

    // Copy into <archive>/newest/aapsNewestAPK.apk. Download is searched, not pruned.
    // A file already there with the same size is left alone.
    fun stageNewestAndPrune(): Pair<Boolean, String> {
        val src = AapsNewestApk.newestSourceApk()
            ?: return false to "no pump apk under AAPS3, AAPS333, or ApkDownload"
        val archiveName = destArchiveName(src)
        val destDir = AapsNewestApk.newestDirs(archiveName).first()
        if (!destDir.exists() && !destDir.mkdirs())
            return false to "mkdir failed ${destDir.absolutePath}"
        val dest = File(destDir, AapsNewestApk.FIXED_NAME)
        if (AapsNewestApk.isPlausiblePumpApk(dest) && dest.length() == src.length() && dest.lastModified() >= src.lastModified()) {
            val pruned = pruneArchive(archiveName, dest)
            writeWinnerRecord(destDir, src, dest, "already")
            AapsNewestApk.copySourceName(src, dest)
            return true to "already newest dest=${dest.absolutePath} bytes=${dest.length()} src=${src.absolutePath} pruned=$pruned"
        }
        destDir.listFiles()?.forEach { it.delete() }
        src.copyTo(dest, overwrite = true)
        if (!AapsNewestApk.isPlausiblePumpApk(dest))
            return false to "copy failed ${dest.absolutePath} bytes=${dest.length()}"
        AapsNewestApk.copySourceName(src, dest)
        val pruned = pruneArchive(archiveName, dest)
        writeWinnerRecord(destDir, src, dest, "copied")
        return true to "src=${src.absolutePath} dest=${dest.absolutePath} bytes=${dest.length()} pruned=$pruned"
    }

    // pm install of this package kills the process. The shell relaunches the app. A failed install does not.
    fun install(apk: File, packageName: String): Pair<Boolean, String> {
        if (!AapsNewestApk.isPlausiblePumpApk(apk))
            return false to "missing or too small ${apk.absolutePath} bytes=${apk.length()}"
        val tmp = "/data/local/tmp/${AapsNewestApk.FIXED_NAME}"
        val (cpCode, cpText) = exec(arrayOf("cp", "-f", apk.absolutePath, tmp))
        if (cpCode != 0) return false to "cp exit=$cpCode $cpText"
        exec(arrayOf("chmod", "644", tmp))
        val activity = "$packageName/app.aaps.ComposeMainActivity"
        val script =
            "pm install -r -d --user 0 '$tmp'; code=\$?; rm -f '$tmp'; " +
                "if [ \$code -eq 0 ]; then sleep 3; am start --user 0 -n $activity; fi; exit \$code"
        val (code, text) = exec(arrayOf("sh", "-c", script))
        val ok = code == 0 && text.contains("Success", ignoreCase = true)
        return ok to "via=$tmp relaunch=$activity exit=$code $text"
    }

    // Launches a package's launcher screen from the Shizuku shell. A shell start is not subject to the background-launch block
    // that can make a plain startActivity do nothing on Android 12+. Launch only: no force-stop, which once left AnyDesk's
    // remote listener down.
    fun launchPackage(pkg: String): Pair<Boolean, String> {
        if (!Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(pkg)) return false to "bad package name"
        val (code, out) = exec(arrayOf("monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"))
        val ok = code == 0 && !out.contains("No activities found", ignoreCase = true)
        return ok to "exit=$code $out".trim()
    }

    private fun pruneArchive(archiveName: String, staged: File): Int {
        val archive = ArrayList<File>()
        val seen = HashSet<String>()
        val stagedCanon = canonicalOrAbs(staged)
        for (root in AapsNewestApk.archiveDirs(archiveName)) {
            if (!seen.add(canonicalOrAbs(root))) continue
            collectPumpApks(root, archive)
        }
        val others = archive.filter { canonicalOrAbs(it) != stagedCanon }
            .sortedByDescending { it.lastModified() }
        var removed = 0
        for (file in others.drop(KEEP_ARCHIVE)) if (file.delete()) removed++
        return removed
    }

    private fun collectPumpApks(dir: File, into: MutableList<File>) {
        if (!dir.isDirectory) return
        val children = dir.listFiles() ?: return
        for (file in children) {
            if (file.isDirectory) {
                if (file.name.equals("newest", ignoreCase = true)) continue
                collectPumpApks(file, into)
                continue
            }
            val name = file.name
            if (name.endsWith(".part", ignoreCase = true)) continue
            if (!name.endsWith(".apk", ignoreCase = true) && name != AapsNewestApk.FIXED_NAME_NO_EXT) continue
            if (skipName.containsMatchIn(name)) continue
            if (AapsNewestApk.isPlausiblePumpApk(file)) into.add(file)
        }
    }

    private fun destArchiveName(src: File): String {
        archiveNameOf(src)?.let { return it }
        for (name in listOf("AAPS3", "AAPS333")) {
            if (AapsNewestApk.archiveDirs(name).any { it.isDirectory }) return name
        }
        return "AAPS333"
    }

    private fun archiveNameOf(file: File): String? {
        var parent: File? = file
        while (parent != null) {
            if (parent.name.equals("AAPS333", ignoreCase = true)) return "AAPS333"
            if (parent.name.equals("AAPS3", ignoreCase = true)) return "AAPS3"
            parent = parent.parentFile
        }
        return null
    }

    private fun writeWinnerRecord(destDir: File, src: File, dest: File, how: String) {
        val nnn = AapsNewestApk.featureNumberFromFile(src) ?: apkFeatureNumber(src.absolutePath)
        try {
            File(destDir, "winner.txt").writeText(
                "how=$how\n" +
                    (nnn?.let { "nnn=$it\n" } ?: "") +
                    "src=${src.absolutePath}\n" +
                    "srcBytes=${src.length()}\n" +
                    "srcMtime=${src.lastModified()}\n" +
                    "dest=${dest.absolutePath}\n" +
                    "destBytes=${dest.length()}\n" +
                    "destMtime=${dest.lastModified()}\n"
            )
        } catch (_: Exception) {
        }
        if (nnn != null) AapsNewestApk.writeNewestNnn(destDir, nnn)
    }

    private fun canonicalOrAbs(file: File): String = try {
        file.canonicalPath
    } catch (_: Exception) {
        file.absolutePath
    }

    private fun exec(cmd: Array<String>): Pair<Int, String> {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
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
