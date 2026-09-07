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
    private val nnnLineRe = Regex("""(?:^|\R)nnn=(\d+)""")
    // Drive/CI names seen on the card: aaps-3.4.2.6+aisf321UK_783.apk,
    // folder 3.4.2.6+aisf321UK_782/, not the short aisf321UK_N.apk probe we used to use.
    private const val VERSION_PREFIX = "3.4.2.6"

    fun featureNumber(text: String?): Int? =
        text?.let { featureNumberRe.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?.takeIf { it in 100..9999 }

    fun writeSourceName(apk: File, sourceName: String) {
        if (sourceName.isBlank()) return
        try {
            File(apk.path + SRC_NAME_SUFFIX).writeText(sourceName)
        } catch (_: Exception) {
        }
        nnnFromText(sourceName)?.let { writeNewestNnn(apk.parentFile, it) }
    }

    fun copySourceName(from: File, to: File) {
        val side = File(from.path + SRC_NAME_SUFFIX)
        val label = try {
            if (side.isFile) side.readText() else from.absolutePath
        } catch (_: Exception) {
            from.absolutePath
        }
        writeSourceName(to, label)
        nnnFromText(label)?.let { writeNewestNnn(to.parentFile, it) }
    }

    fun writeNewestNnn(dir: File?, n: Int) {
        if (dir == null || n !in 100..9999) return
        try {
            File(dir, "newest.nnn").writeText("aisf321UK_$n\nnnn=$n\n")
        } catch (_: Exception) {
        }
    }

    fun nnnFromText(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        featureNumber(text)?.let { return it }
        return nnnLineRe.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 100..9999 }
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

    // Exact-path probes only. Never listFiles and never open the 90MB APK.
    // Live's ApkDownload listFiles is what paused List2 clicks and the Client snapshot
    // even after Download itself was removed from this path.
    fun newestFeatureNumberFromNames(runningHint: Int? = null): Int? {
        val fromKnown = nnnFromKnownSidecars()
        val fromWinner = nnnFromWinnerFiles()
        val fromDrive = probeKnownPumpApks().mapNotNull { sourceNameNnn(it) }
        val fromExact = runningHint?.let { probeVersionedAround(it) }.orEmpty()
        return (fromKnown + fromWinner + fromDrive + fromExact).maxOrNull()
    }

    // UI / snapshot: names + cache + running floor when a source file is present.
    // An old named 802 must not hide a newer driveAapsNewest that is already installed as 804.
    fun cheapNewestNnn(pm: PackageManager, packageName: String, cache: Int?): Int? {
        val running = runningFeatureNumber(pm, packageName)
        val hint = listOfNotNull(running, cache?.takeIf { it in 100..9999 }).maxOrNull()
        val named = newestFeatureNumberFromNames(hint)
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
        nnnFromText(apk.name)?.let { return it }
        nnnFromText(apk.parent)?.let { return it }
        return nnnFromPlainFile(File(apk.path + SRC_NAME_SUFFIX))
    }

    private fun nnnFromPlainFile(file: File): Int? {
        if (!file.isFile) return null
        return try {
            nnnFromText(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    // Sidecars next to Drive dest and staged copy. newest/ is skipped by collectPumpApks,
    // so List2 used to miss an already-copied 806 sitting in aapsNewestAPK.apk.srcname.
    private fun nnnFromKnownSidecars(): List<Int> {
        val out = ArrayList<Int>()
        for (archive in ARCHIVE_NAMES) {
            for (dir in archiveDirs(archive)) {
                for (sub in DROP_NAMES) {
                    val drop = File(dir, sub)
                    val drive = File(drop, "driveAapsNewest.apk")
                    sourceNameNnn(drive)?.let { out.add(it) }
                    nnnFromPlainFile(File(drop, "newest.nnn"))?.let { out.add(it) }
                }
            }
            for (newest in newestDirs(archive)) {
                sourceNameNnn(File(newest, FIXED_NAME))?.let { out.add(it) }
                sourceNameNnn(File(newest, FIXED_NAME_NO_EXT))?.let { out.add(it) }
                nnnFromPlainFile(File(newest, "newest.nnn"))?.let { out.add(it) }
            }
        }
        return out
    }

    private fun nnnFromWinnerFiles(): List<Int> {
        val out = ArrayList<Int>()
        for (name in ARCHIVE_NAMES) {
            for (dir in newestDirs(name)) {
                nnnFromPlainFile(File(dir, "winner.txt"))?.let { out.add(it) }
            }
        }
        return out
    }

    // Exact names / folders only — no listFiles. Live cannot list ApkDownload but exists() works.
    private fun probeVersionedAround(hint: Int): List<Int> {
        val out = ArrayList<Int>()
        val lo = (hint - 2).coerceAtLeast(100)
        val hi = hint + 16
        val folders = probeFolders()
        for (n in hi downTo lo) {
            if (versionedPresent(folders, n)) out.add(n)
        }
        return out
    }

    private fun probeFolders(): List<File> {
        val out = ArrayList<File>()
        val seen = HashSet<String>()
        for (archive in ARCHIVE_NAMES) {
            for (dir in archiveDirs(archive)) {
                if (seen.add(canonicalOrAbs(dir))) out.add(dir)
                for (sub in DROP_NAMES) {
                    val folder = File(dir, sub)
                    if (seen.add(canonicalOrAbs(folder))) out.add(folder)
                }
            }
        }
        return out
    }

    private fun versionedPresent(folders: List<File>, n: Int): Boolean {
        val names = listOf(
            "aisf321UK_$n.apk",
            "$VERSION_PREFIX+aisf321UK_$n.apk",
            "aaps-$VERSION_PREFIX+aisf321UK_$n.apk",
            "AndroidAPS-$VERSION_PREFIX+aisf321UK_$n.apk"
        )
        val dirNames = listOf(
            "$VERSION_PREFIX+aisf321UK_$n",
            "aisf321UK_$n"
        )
        for (folder in folders) {
            for (name in names) {
                if (isPlausiblePumpApk(File(folder, name))) return true
            }
            for (name in dirNames) {
                if (File(folder, name).isDirectory) return true
            }
        }
        return false
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
