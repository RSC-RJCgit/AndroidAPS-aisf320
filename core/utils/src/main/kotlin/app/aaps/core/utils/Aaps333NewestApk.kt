package app.aaps.core.utils

import android.content.pm.PackageManager
import android.os.Environment
import java.io.File

// Shared peek for List2 "Install newest" / "Stage newest" and the auto-install NNN test.
// Same search roots as ShizukuAaps333Installer: AAPS3, AAPS333, ApkDownload; skip Client/Wear
// and the staged aapsNewestAPK copy when picking the source winner (mtime).
object Aaps333NewestApk {

    const val FIXED_NAME = "aapsNewestAPK.apk"
    const val FIXED_NAME_NO_EXT = "aapsNewestAPK"
    const val MIN_PUMP_APK_BYTES = 20L * 1024L * 1024L
    const val SRC_NAME_SUFFIX = ".srcname"

    private val ARCHIVE_NAMES = listOf("AAPS333", "AAPS3")
    private val DROP_NAMES = listOf("ApkDownload", "APKdownload", "apkdownload")
    private val skipName = Regex("aapsclient|wear|pumpcontrol|aapsNewestAPK", RegexOption.IGNORE_CASE)
    private val featureNumberRe = Regex("""aisf321UK_(\d+)""")

    fun featureNumber(text: String?): Int? =
        text?.let { featureNumberRe.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?.takeIf { it in 100..9999 }

    fun writeSourceName(apk: File, sourceName: String) {
        if (sourceName.isBlank()) return
        try {
            File(apk.path + SRC_NAME_SUFFIX).writeText(sourceName)
        } catch (_: Exception) {
        }
    }

    fun copySourceName(from: File, to: File) {
        val side = File(from.path + SRC_NAME_SUFFIX)
        val label = try {
            if (side.isFile) side.readText() else from.absolutePath
        } catch (_: Exception) {
            from.absolutePath
        }
        writeSourceName(to, label)
    }

    fun featureNumberFromFile(apk: File): Int? = sourceNameNnn(apk)

    fun newestSourceApk(): File? {
        val seen = HashSet<String>()
        val found = ArrayList<File>()
        for (root in searchRoots(includeDownload = true)) {
            val canonical = canonicalOrAbs(root)
            if (!seen.add(canonical)) continue
            collectPumpApks(root, found, skipNewestCopy = true)
        }
        for (f in probeKnownPumpApks()) {
            val canonical = canonicalOrAbs(f)
            if (seen.add(canonical)) found.add(f)
        }
        return found.maxByOrNull { it.lastModified() }
    }

    fun newestStagedApk(): File? {
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

    @Suppress("DEPRECATION")
    fun versionName(pm: PackageManager, apk: File): String? = try {
        pm.getPackageArchiveInfo(apk.absolutePath, 0)?.versionName
    } catch (_: Exception) {
        null
    }

    fun runningVersionName(pm: PackageManager, packageName: String): String? = try {
        pm.getPackageInfo(packageName, 0).versionName
    } catch (_: Exception) {
        null
    }

    // Filename / sidecar / winner.txt only. Never opens the 90MB APK. Does not walk Download
    // (Live's Download tree is huge and listFiles there is what paused AAPS for wait/close).
    fun newestFeatureNumberFromNames(runningHint: Int? = null): Int? {
        val found = ArrayList<File>()
        val seen = HashSet<String>()
        for (root in searchRoots(includeDownload = false)) {
            val canonical = canonicalOrAbs(root)
            if (!seen.add(canonical)) continue
            collectPumpApks(root, found, skipNewestCopy = true)
        }
        val fromList = found.mapNotNull { featureNumber(it.name) }
        val fromProbe = probeKnownPumpApks().mapNotNull { sourceNameNnn(it) }
        val fromWinner = nnnFromWinnerFiles()
        val fromExact = runningHint?.let { probeVersionedAround(it) }.orEmpty()
        return (fromList + fromProbe + fromWinner + fromExact).maxOrNull()
    }

    // UI / snapshot: names + cache + running floor when a source file is present.
    // An old named 802 must not hide a newer driveAapsNewest that is already installed as 804.
    fun cheapNewestNnn(pm: PackageManager, packageName: String, cache: Int?): Int? {
        val running = runningFeatureNumber(pm, packageName)
        val named = newestFeatureNumberFromNames(running)
        val present = named != null || sourceFilePresent()
        val floor = if (present) running else null
        return listOfNotNull(named, cache?.takeIf { it in 100..9999 }, floor).maxOrNull()
    }

    fun newestFeatureNumber(pm: PackageManager): Int? {
        newestFeatureNumberFromNames(runningFeatureNumber(pm, "info.nightscout.androidaps"))?.let { return it }
        return newestFeatureNumberFromNames()
    }

    fun runningFeatureNumber(pm: PackageManager, packageName: String): Int? =
        featureNumber(runningVersionName(pm, packageName))

    // List2 row / confirm: "Newest: 804  (this phone 804)"
    fun newestSummary(pm: PackageManager, packageName: String, cache: Int? = null): String {
        val newest = cheapNewestNnn(pm, packageName, cache)
        val running = runningFeatureNumber(pm, packageName)
        return when {
            newest != null && running != null -> "Newest: $newest  (this phone $running)"
            newest != null -> "Newest: $newest"
            running != null -> "Newest: not found under AAPS3 / AAPS333 / ApkDownload  (this phone $running)"
            else -> "Newest: not found under AAPS3 / AAPS333 / ApkDownload"
        }
    }

    fun isPlausiblePumpApk(file: File): Boolean =
        file.isFile && file.length() >= MIN_PUMP_APK_BYTES

    fun sourceFilePresent(): Boolean =
        probeKnownPumpApks().isNotEmpty() || newestStagedApk() != null

    private fun sourceNameNnn(apk: File): Int? {
        featureNumber(apk.name)?.let { return it }
        val sidecar = File(apk.path + SRC_NAME_SUFFIX)
        if (!sidecar.isFile) return null
        return try {
            featureNumber(sidecar.readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun nnnFromWinnerFiles(): List<Int> {
        val out = ArrayList<Int>()
        for (name in ARCHIVE_NAMES) {
            for (dir in newestDirs(name)) {
                val winner = File(dir, "winner.txt")
                if (!winner.isFile) continue
                try {
                    featureNumber(winner.readText())?.let { out.add(it) }
                } catch (_: Exception) {
                }
            }
        }
        return out
    }

    // Exact names only — no listFiles. Live cannot list ApkDownload but exists() still works.
    private fun probeVersionedAround(hint: Int): List<Int> {
        val out = ArrayList<Int>()
        val lo = (hint - 2).coerceAtLeast(100)
        val hi = hint + 12
        for (n in hi downTo lo) {
            val names = listOf("aisf321UK_$n.apk")
            for (archive in ARCHIVE_NAMES) {
                for (dir in archiveDirs(archive)) {
                    for (sub in DROP_NAMES) {
                        val folder = File(dir, sub)
                        for (name in names) {
                            if (isPlausiblePumpApk(File(folder, name))) out.add(n)
                        }
                    }
                }
            }
        }
        return out
    }

    // Exact paths Drive/Tasker write. Used when the parent folder does not list.
    private fun probeKnownPumpApks(): List<File> {
        val out = ArrayList<File>()
        for (name in ARCHIVE_NAMES) {
            for (dir in archiveDirs(name)) {
                for (sub in DROP_NAMES) {
                    val drive = File(File(dir, sub), "driveAapsNewest.apk")
                    if (isPlausiblePumpApk(drive)) out.add(drive)
                }
            }
        }
        return out
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

    private fun searchRoots(includeDownload: Boolean): List<File> {
        val roots = ArrayList<File>()
        for (name in ARCHIVE_NAMES) {
            val archives = archiveDirs(name)
            roots.addAll(archives)
            for (dir in archives) {
                for (sub in DROP_NAMES) roots.add(File(dir, sub))
            }
        }
        if (includeDownload) {
            roots.add(File("/sdcard/Download"))
            roots.add(File("/storage/emulated/0/Download"))
            roots.add(File(Environment.getExternalStorageDirectory(), "Download"))
        }
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
}
