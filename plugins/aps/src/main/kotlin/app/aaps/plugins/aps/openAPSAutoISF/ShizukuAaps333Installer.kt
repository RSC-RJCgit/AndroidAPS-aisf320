package app.aaps.plugins.aps.openAPSAutoISF

import android.content.pm.PackageManager
import android.os.Environment
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

    // Longer name first so path matching does not treat AAPS333 as AAPS3.
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

    fun newestPumpApk(): File? {
        for (name in ARCHIVE_NAMES) {
            for (dir in newestDirs(name)) {
                val withExt = File(dir, FIXED_NAME)
                if (withExt.isFile) return withExt
                val noExt = File(dir, FIXED_NAME_NO_EXT)
                if (noExt.isFile) return noExt
            }
        }
        return null
    }

    // Copy newest matching APK into that phone's archive/newest/aapsNewestAPK.apk and prune
    // older APKs in that same archive. Download is a search root only — not pruned.
    fun stageNewestAndPrune(): Pair<Boolean, String> {
        val src = findNewestSourceApk()
            ?: return false to "no pump apk under AAPS3, AAPS333, or Download"
        val archiveName = destArchiveName(src)
        val destDir = newestDirs(archiveName).first()
        if (!destDir.exists() && !destDir.mkdirs())
            return false to "mkdir failed ${destDir.absolutePath}"
        destDir.listFiles()?.forEach { it.delete() }
        val dest = File(destDir, FIXED_NAME)
        src.copyTo(dest, overwrite = true)
        if (!dest.isFile || dest.length() <= 0L)
            return false to "copy failed ${dest.absolutePath}"
        val pruned = pruneArchive(archiveName, keep = KEEP_ARCHIVE, staged = dest)
        return true to "src=${src.absolutePath} dest=${dest.absolutePath} bytes=${dest.length()} pruned=$pruned"
    }

    fun install(apk: File): Pair<Boolean, String> {
        val (code, text) = exec(arrayOf("pm", "install", "-r", "-d", "--user", "0", apk.absolutePath))
        val ok = code == 0 && text.contains("Success", ignoreCase = true)
        return ok to "exit=$code $text"
    }

    private fun findNewestSourceApk(): File? {
        val seen = HashSet<String>()
        val found = ArrayList<File>()
        for (root in searchRoots()) {
            val canonical = canonicalOrAbs(root)
            if (!seen.add(canonical)) continue
            collectPumpApks(root, found, skipNewestCopy = true)
        }
        return found.maxByOrNull { it.lastModified() }
    }

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
            if (!name.endsWith(".apk", ignoreCase = true) && name != FIXED_NAME_NO_EXT) continue
            if (skipName.containsMatchIn(name)) continue
            if (f.isFile && f.length() > 0L) into.add(f)
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
