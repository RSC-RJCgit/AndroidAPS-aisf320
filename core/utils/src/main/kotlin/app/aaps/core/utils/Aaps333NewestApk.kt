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

    private val ARCHIVE_NAMES = listOf("AAPS333", "AAPS3")
    private val skipName = Regex("aapsclient|wear|pumpcontrol|aapsNewestAPK", RegexOption.IGNORE_CASE)
    private val featureNumberRe = Regex("""aisf321UK_(\d+)""")

    fun featureNumber(text: String?): Int? =
        text?.let { featureNumberRe.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    fun newestSourceApk(): File? {
        val seen = HashSet<String>()
        val found = ArrayList<File>()
        for (root in searchRoots()) {
            val canonical = canonicalOrAbs(root)
            if (!seen.add(canonical)) continue
            collectPumpApks(root, found, skipNewestCopy = true)
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

    // Highest aisf321UK_NNN in any visible pump APK filename. Used for List2 so a
    // newer Drive drop named driveAapsNewest.apk (no NNN) cannot hide the archive number.
    // Does not open the 90MB APK.
    fun newestFeatureNumberFromNames(): Int? {
        val found = ArrayList<File>()
        val seen = HashSet<String>()
        for (root in searchRoots()) {
            val canonical = canonicalOrAbs(root)
            if (!seen.add(canonical)) continue
            collectPumpApks(root, found, skipNewestCopy = true)
        }
        return found.mapNotNull { featureNumber(it.name) }.maxOrNull()
    }

    fun newestFeatureNumber(pm: PackageManager): Int? {
        newestFeatureNumberFromNames()?.let { return it }
        val src = newestSourceApk() ?: newestStagedApk() ?: return null
        return featureNumber(src.name) ?: featureNumber(versionName(pm, src))
    }

    fun runningFeatureNumber(pm: PackageManager, packageName: String): Int? =
        featureNumber(runningVersionName(pm, packageName))

    // List2 row / confirm: "Newest: 803  (this phone 801)"
    fun newestSummary(pm: PackageManager, packageName: String): String {
        val newest = newestFeatureNumberFromNames() ?: newestFeatureNumber(pm)
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

    private fun searchRoots(): List<File> {
        val roots = ArrayList<File>()
        for (name in ARCHIVE_NAMES) {
            val archives = archiveDirs(name)
            roots.addAll(archives)
            // Drive / Tasker drop the versioned APK here — not in the phone Download folder.
            for (dir in archives) {
                roots.add(File(dir, "ApkDownload"))
                roots.add(File(dir, "APKdownload"))
            }
        }
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
}
