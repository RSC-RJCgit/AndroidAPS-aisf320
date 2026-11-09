package app.aaps.implementation.maintenance

import android.content.Context
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import java.io.IOException

/** Permanently deletes old APKs from the folder selected in the AAPS directory setting. */
internal fun deleteOldApks(context: Context, folderUri: String, logger: AAPSLogger) {
    if (folderUri.isBlank()) return
    val tree = folderUri.toUri()
    val resolver = context.contentResolver
    if (tree.authority != "com.android.externalstorage.documents" || !DocumentsContract.isTreeUri(tree)) {
        logger.warn(LTag.CORE, "APK cleanup needs an AAPS folder on device storage")
        return
    }
    if (resolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission && it.isWritePermission }) {
        logger.warn(LTag.CORE, "APK cleanup needs read and write access to the AAPS folder")
        return
    }

    val apks = mutableListOf<ArchiveApk>()
    try {
        val root = DocumentsContract.getTreeDocumentId(tree)
        if (root.substringAfter(':', "").isBlank()) return
        val folders = ArrayDeque<String>()
        folders.add(root)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED)
        while (folders.isNotEmpty()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folders.removeLast())
            val cursor = resolver.query(children, columns, null, null, null) ?: throw IOException("Cannot read AAPS folder")
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getString(0)
                    val name = it.getString(1)
                    if (it.getString(2) == Document.MIME_TYPE_DIR) {
                        folders.add(id)
                    } else if (name.endsWith(".apk", ignoreCase = true) && !name.equals("aapsNewestAPK.apk", ignoreCase = true)) {
                        val modified = it.getLong(3)
                        if (modified <= 0L) throw IOException("Missing APK modification time")
                        apks.add(ArchiveApk(id, modified))
                    }
                }
            }
        }
    } catch (error: Exception) {
        logger.error(LTag.CORE, "Could not list APKs in the AAPS folder", error)
        return
    }

    var deleted = 0
    var failed = 0
    for (apk in apks.sortedWith(compareByDescending<ArchiveApk> { it.modified }.thenBy { it.id }).drop(10)) {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, apk.id)
        val removed = try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (error: Exception) {
            logger.error(LTag.CORE, "Could not delete an old APK", error)
            false
        }
        if (removed) deleted++ else failed++
    }
    if (deleted > 0 || failed > 0) logger.info(LTag.CORE, "APK cleanup deleted $deleted files, $failed failed")
}

private data class ArchiveApk(val id: String, val modified: Long)
