package app.aaps.implementation.maintenance

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import java.io.IOException

/** Permanently deletes old installers across the selected AAPS folder and other authorized AAPS archive folders. */
internal fun deleteOldInstallers(context: Context, folderUri: String, extraFolders: String, logger: AAPSLogger) {
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
