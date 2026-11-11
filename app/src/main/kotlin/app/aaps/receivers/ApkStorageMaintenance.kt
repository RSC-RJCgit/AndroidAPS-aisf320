package app.aaps.receivers

import app.aaps.core.data.time.AppTimeZone

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.smsCommunicator.Sms
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
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
    private val logger: AAPSLogger
) {

    @Synchronized
    fun check(context: Context) {
        if (!config.APS || config.AAPSCLIENT || activePlugin.activePump is VirtualPump) return
        val localTime = Instant.ofEpochMilli(dateUtil.now()).atZone(AppTimeZone.current())
        val localDay = localTime.toLocalDate().toEpochDay()
        val weeklyDue = localTime.dayOfWeek == DayOfWeek.SUNDAY && localTime.hour >= 12 &&
            localDay > preferences.get(LongNonKey.LastWeeklyApkCleanupDay)
        if (weeklyDue || freeStorageGiB()?.let { it < 10.0 } == true) {
            deleteOldInstallers(
                context, preferences.get(StringKey.AapsDirectoryUri),
                preferences.get(StringNonKey.InstallerArchiveFolders), logger
            )
        }
        if (weeklyDue) {
            preferences.put(LongNonKey.LastWeeklyApkCleanupDay, localDay)
            reportFreeStorage(freeStorageGiB())
        }
    }

    private fun reportFreeStorage(freeGiB: Double?) {
        val targets = preferences.get(StringKey.SmsBattAlertNumbers).split(";")
            .map { it.filterNot(Char::isWhitespace) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (targets.isEmpty()) {
            logger.warn(LTag.CORE, "Weekly storage SMS skipped: Battery-alert numbers is empty")
            return
        }
        val text = if (freeGiB == null) "Weekly storage: free space unavailable"
        else String.format(Locale.US, "Weekly storage: %.1fGB free", freeGiB)
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
                            installers.putIfAbsent(id, ArchiveInstaller(tree, id, modified))
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
    val oldInstallers = installers.values.sortedWith(compareByDescending<ArchiveInstaller> { it.modified }.thenBy { it.id }).drop(10)
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
    val result = "Installer cleanup: ${trees.size} folders, ${installers.size} installers, $deleted deleted, $failed failed"
    if (failed > 0) logger.warn(LTag.CORE, result)
    else if (deleted > 0) logger.info(LTag.CORE, result)
    else logger.debug(LTag.CORE, result)
}

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
private val INSTALLER_EXTENSIONS = setOf(".apk", ".dmg", ".msi")
private val AAPS_ARCHIVE_FOLDERS = setOf("AAP", "AAPS", "AAPS3", "AAPS333", "AAPS4ios")

private data class ArchiveInstaller(val tree: Uri, val id: String, val modified: Long)
