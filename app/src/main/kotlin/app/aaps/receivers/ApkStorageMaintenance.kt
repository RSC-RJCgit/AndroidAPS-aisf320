package app.aaps.receivers

import app.aaps.core.keys.time.AppTimeZone
import android.app.usage.StorageStatsManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.LoggerUtils
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.smsCommunicator.Sms
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.LongNonKey
import app.aaps.plugins.configuration.maintenance.MaintenancePlugin
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import java.io.File
import java.io.IOException
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApkStorageMaintenance @Inject constructor(
    private val config: Config,
    private val activePlugin: ActivePlugin,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val smsCommunicator: SmsCommunicator,
    private val logger: AAPSLogger,
    private val maintenancePlugin: MaintenancePlugin,
    private val loggerUtils: LoggerUtils
) {

    @Synchronized
    fun check(context: Context) {
        if (!config.APS || config.AAPSCLIENT || activePlugin.activePump is VirtualPump) return
        val localTime = Instant.ofEpochMilli(dateUtil.now()).atZone(AppTimeZone.current())
        val localDay = localTime.toLocalDate().toEpochDay()
        val weeklyDue = localTime.dayOfWeek == DayOfWeek.SUNDAY && localTime.hour >= 12 &&
            localDay > preferences.get(LongNonKey.LastWeeklyApkCleanupDay)
        // Under 12 GiB (2026-11-21, per explicit request; was 10) the installer cleanup and the log trim run at once
        val lowStorage = freeStorageGiB()?.let { it < 12.0 } == true
        if (weeklyDue || lowStorage) {
            deleteOldInstallers(
                context, preferences.get(StringKey.AapsDirectoryUri),
                preferences.get(StringNonKey.InstallerArchiveFolders), logger
            )
        }
        // 2026-11-14, per explicit request: under 10 GiB the old log zips go too (the installers alone freed too little)
        if (lowStorage) {
            try {
                logger.info(LTag.CORE, maintenancePlugin.trimLogsForLowStorage())
            } catch (error: Exception) {
                logger.error(LTag.CORE, "Could not trim the old log zips", error)
            }
        }
        if (weeklyDue) {
            preferences.put(LongNonKey.LastWeeklyApkCleanupDay, localDay)
            reportFreeStorage(context, freeStorageGiB())
        }
        // 2026-11-15, per explicit request: one extra storage SMS (with the breakdown) straight after this update, not only
        // on Sunday. Marked sent first, so it can never repeat.
        if (!weeklyDue && !preferences.get(BooleanNonKey.StorageBreakdownOnceSent)) {
            preferences.put(BooleanNonKey.StorageBreakdownOnceSent, true)
            reportFreeStorage(context, freeStorageGiB())
        }
    }

    private fun reportFreeStorage(context: Context, freeGiB: Double?) {
        val targets = preferences.get(StringKey.SmsBattAlertNumbers).split(";")
            .map { it.filterNot(Char::isWhitespace) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (targets.isEmpty()) {
            logger.warn(LTag.CORE, "Weekly storage SMS skipped: Battery-alert numbers is empty")
            return
        }
        val breakdown = try {
            storageBreakdown(context)
        } catch (error: Exception) {
            logger.error(LTag.CORE, "Could not measure the storage breakdown", error)
            null
        }
        val text = if (freeGiB == null) "Weekly storage: free space unavailable"
        else String.format(Locale.US, "Weekly storage: %.1fGB free", freeGiB) + (breakdown?.let { " $it" } ?: "")
        val accepted = targets.count { number ->
            try {
                smsCommunicator.sendSMS(Sms(number, text))
            } catch (error: Exception) {
                logger.error(LTag.CORE, "Could not send weekly storage SMS", error)
                false
            }
        }
        val result = "Weekly storage SMS accepted for $accepted of ${targets.size} Battery-alert numbers"
        if (accepted == targets.size) logger.info(LTag.CORE, result) else logger.warn(LTag.CORE, result)
    }

    /**
     * What uses the space (2026-11-15, per explicit request), e.g. "of 119GB. AAPS app 2.1, logs 0.9, AAPS folder 0.6
     * (installers 0.3), other apps and media 104". In GiB, as the free space. The AAPS app is its code, database, cache and
     * its own Android/data folder (the hourly logs there are inside it); logs are the log folders, Documents/aapsLogs and
     * that one; the AAPS folder is the selected Documents/AAPS folder. Other = used - app - AAPS folder - Documents logs.
     */
    private fun storageBreakdown(context: Context): String {
        val stat = StatFs(Environment.getDataDirectory().path)
        val used = (stat.totalBytes - stat.availableBytes).coerceAtLeast(0L)
        val app = runCatching {
            val stats = context.getSystemService(StorageStatsManager::class.java)
                .queryStatsForUid(StorageManager.UUID_DEFAULT, Process.myUid())
            stats.appBytes + stats.dataBytes + stats.cacheBytes
        }.getOrNull()
        val appSpecific = File(loggerUtils.appSpecificLogDirectory).absolutePath
        val publicLogs = loggerUtils.logDirectory.takeIf { it.isNotBlank() && File(it).absolutePath != appSpecific }?.let { folderBytes(File(it)) } ?: 0L
        val logs = publicLogs + folderBytes(File(appSpecific))
        val (folder, installers) = aapsFolderBytes(context, preferences.get(StringKey.AapsDirectoryUri))
        val other = used - (app ?: 0L) - folder - publicLogs
        fun gib(bytes: Long) = String.format(Locale.US, "%.1f", bytes / BYTES_PER_GIB)
        return buildList {
            add(String.format(Locale.US, "of %.0fGB.", stat.totalBytes / BYTES_PER_GIB))
            if (app != null) add("AAPS app ${gib(app)},")
            add("logs ${gib(logs)},")
            add("AAPS folder ${gib(folder)} (installers ${gib(installers)}),")
            add(String.format(Locale.US, "other apps and media %.0f", other.coerceAtLeast(0L) / BYTES_PER_GIB))
        }.joinToString(" ")
    }

    private fun folderBytes(dir: File): Long =
        if (!dir.isDirectory) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Size of the selected AAPS folder and of the installers in it, through its folder access. */
    private fun aapsFolderBytes(context: Context, folderUri: String): Pair<Long, Long> {
        if (folderUri.isBlank()) return 0L to 0L
        val tree = Uri.parse(folderUri)
        if (!DocumentsContract.isTreeUri(tree)) return 0L to 0L
        val resolver = context.contentResolver
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        var total = 0L
        var installers = 0L
        val folders = ArrayDeque<String>()
        folders.add(DocumentsContract.getTreeDocumentId(tree))
        while (folders.isNotEmpty()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folders.removeLast())
            resolver.query(children, columns, null, null, null)?.use {
                while (it.moveToNext()) {
                    if (it.getString(2) == Document.MIME_TYPE_DIR) folders.add(it.getString(0))
                    else {
                        val size = if (it.isNull(3)) 0L else it.getLong(3)
                        total += size
                        if (INSTALLER_EXTENSIONS.any { ext -> it.getString(1).endsWith(ext, ignoreCase = true) }) installers += size
                    }
                }
            }
        }
        return total to installers
    }

    private companion object {

        fun freeStorageGiB(): Double? =
            runCatching { StatFs(Environment.getDataDirectory().path).availableBytes / BYTES_PER_GIB }.getOrNull()

        const val BYTES_PER_GIB = 1024.0 * 1024.0 * 1024.0
    }
}

/** Permanently deletes old installers across the selected AAPS folder and other authorized AAPS archive folders. */
private fun deleteOldInstallers(context: Context, folderUri: String, extraFolders: String, logger: AAPSLogger) {
    if (folderUri.isBlank()) {
        logger.warn(LTag.CORE, "Installer cleanup needs a selected AAPS folder")
        return
    }
    val selected = Uri.parse(folderUri)
    val resolver = context.contentResolver
    if (selected.authority != EXTERNAL_STORAGE_AUTHORITY || !DocumentsContract.isTreeUri(selected)) {
        logger.warn(LTag.CORE, "Installer cleanup needs an AAPS folder on device storage")
        return
    }
    val grantedTrees = resolver.persistedUriPermissions
        .filter { it.isReadPermission && it.isWritePermission }
        .map { it.uri }
        .filter { it.authority == EXTERNAL_STORAGE_AUTHORITY && DocumentsContract.isTreeUri(it) }
    if (selected !in grantedTrees) {
        logger.warn(LTag.CORE, "Installer cleanup needs read and write access to the AAPS folder")
        return
    }
    val extraUris = extraFolders.lineSequence().filter(String::isNotBlank).toSet()
    val trees = listOf(selected) + grantedTrees.filter { tree ->
        val folder = DocumentsContract.getTreeDocumentId(tree).substringAfter(':', "")
        tree != selected && (tree.toString() in extraUris || AAPS_ARCHIVE_FOLDERS.any { it.equals(folder, ignoreCase = true) })
    }

    val installers = mutableMapOf<String, ArchiveInstaller>()
    val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED)
    try {
        for (tree in trees) {
            val root = DocumentsContract.getTreeDocumentId(tree)
            if (root.substringAfter(':', "").isBlank()) continue
            val folders = ArrayDeque<String>()
            folders.add(root)
            while (folders.isNotEmpty()) {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folders.removeLast())
                val cursor = resolver.query(children, columns, null, null, null) ?: throw IOException("Cannot read AAPS folder")
                cursor.use {
                    while (it.moveToNext()) {
                        val id = it.getString(0)
                        val name = it.getString(1)
                        if (it.getString(2) == Document.MIME_TYPE_DIR) {
                            folders.add(id)
                        } else if (INSTALLER_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) } && !name.equals("aapsNewestAPK.apk", ignoreCase = true)) {
                            val modified = it.getLong(3)
                            if (modified <= 0L) throw IOException("Missing installer modification time")
                            installers.putIfAbsent(id, ArchiveInstaller(tree, id, modified, isUnwantedInstaller(name), v3MainBuild(name)))
                        }
                    }
                }
            }
        }
    } catch (error: Exception) {
        logger.error(LTag.CORE, "Could not list installers in the AAPS folders", error)
        return
    }

    var deleted = 0
    var failed = 0
    // 2026-11-15, per explicit request: desktop installers (.dmg, .msi) and APKs this phone never installs (wear, tandem,
    // full2) are always deleted; of the other APKs the newest 10 are kept, as before.
    val (unwanted, phoneApks) = installers.values.partition { it.unwanted }
    // 2026-11-15, per explicit request: the main APK of the last 3 v3 builds (aaps-3426+ai321_NNNN.apk, not its client
    // variants) always stays too, so newer v4 builds can never push every v3 build out of the newest 10.
    val newestFirst = phoneApks.sortedWith(compareByDescending<ArchiveInstaller> { it.modified }.thenBy { it.id })
    val lastV3Builds = newestFirst.mapNotNull { it.v3Build }.distinct().sortedDescending().take(3).toSet()
    val lastV3Main = newestFirst.filter { it.v3Build in lastV3Builds }.distinctBy { it.v3Build }
    val kept = (newestFirst.take(10) + lastV3Main).toSet()
    val oldInstallers = unwanted + newestFirst.filter { it !in kept }
    for (installer in oldInstallers) {
        val uri = DocumentsContract.buildDocumentUriUsingTree(installer.tree, installer.id)
        val removed = try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (error: Exception) {
            logger.error(LTag.CORE, "Could not delete an old installer", error)
            false
        }
        if (removed) deleted++ else failed++
    }
    val emptyRemoved = deleteEmptySubfolders(resolver, trees, logger)
    val result = "Installer cleanup: ${trees.size} folders, ${installers.size} installers, $deleted deleted, $failed failed, " +
        "$emptyRemoved empty folders removed"
    if (failed > 0) logger.warn(LTag.CORE, result)
    else if (deleted > 0 || emptyRemoved > 0) logger.info(LTag.CORE, result)
    else logger.debug(LTag.CORE, result)
}

/** Folders this many levels below an AAPS folder or deeper can go when empty: the selected folder (0) and its own folders (1,
 * such as ApkDownload or extra) always stay. */
private const val EMPTY_FOLDER_MIN_DEPTH = 2

/**
 * 2026-11-21, per explicit request: deleting old installers left their per-build folders (AAPS333/ApkDownload/...) behind,
 * empty. Deletes each empty folder [EMPTY_FOLDER_MIN_DEPTH] or more levels below each of [trees], the deepest first, so a
 * folder holding only empty folders goes too. Returns how many were deleted.
 */
private fun deleteEmptySubfolders(resolver: ContentResolver, trees: List<Uri>, logger: AAPSLogger): Int {
    val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE)
    var removed = 0
    for (tree in trees) {
        val root = DocumentsContract.getTreeDocumentId(tree)
        if (root.substringAfter(':', "").isBlank()) continue
        try {
            val folders = mutableListOf<Pair<String, Int>>()
            val queue = ArrayDeque<Pair<String, Int>>()
            queue.add(root to 0)
            while (queue.isNotEmpty()) {
                val (id, depth) = queue.removeLast()
                resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), columns, null, null, null)?.use {
                    while (it.moveToNext()) {
                        if (it.getString(1) == Document.MIME_TYPE_DIR) {
                            val child = it.getString(0) to depth + 1
                            queue.add(child)
                            folders.add(child)
                        }
                    }
                }
            }
            for ((id, depth) in folders.sortedByDescending { it.second }) {
                if (depth < EMPTY_FOLDER_MIN_DEPTH) continue
                val empty = resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), columns, null, null, null)
                    ?.use { it.count == 0 } ?: false
                if (empty && DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id))) removed++
            }
        } catch (error: Exception) {
            logger.error(LTag.CORE, "Could not remove empty installer folders", error)
        }
    }
    return removed
}

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
private val INSTALLER_EXTENSIONS = setOf(".apk", ".dmg", ".msi")
private val AAPS_ARCHIVE_FOLDERS = setOf("AAP", "AAPS", "AAPS3", "AAPS333", "AAPS4ios")

private data class ArchiveInstaller(val tree: Uri, val id: String, val modified: Long, val unwanted: Boolean, val v3Build: Int?)

/** The build number of a v3 main APK (aaps-3426+ai321_1490.apk is 1490), or null for anything else, client variants too. */
private fun v3MainBuild(name: String): Int? = V3_MAIN_APK.find(name)?.groupValues?.get(1)?.toIntOrNull()

private val V3_MAIN_APK = Regex("""ai321_(\d+)\.apk$""", RegexOption.IGNORE_CASE)

/** A desktop installer, or an APK this phone never installs (watch, Tandem or full2 build). */
private fun isUnwantedInstaller(name: String): Boolean =
    !name.endsWith(".apk", ignoreCase = true) || UNWANTED_APK_NAME_PARTS.any { name.contains(it, ignoreCase = true) }

private val UNWANTED_APK_NAME_PARTS = setOf("wear", "tandem", "full2")
