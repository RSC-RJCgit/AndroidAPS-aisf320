package app.aaps.desktop.shell.platform

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.maintenance.cloud.CloudConstants
import app.aaps.implementation.maintenance.cloud.CloudStorageManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLEncoder

/**
 * Where a finished log zip goes after it is written: the cloud folder and the mail client.
 *
 * Separate from [DesktopMaintenance] so that class stays constructible without a preferences store or a cloud
 * manager, which is how its test builds it. Both answers are null when the destination was not tried, the
 * same convention `ExportResult` uses.
 */
interface LogDelivery {

    /** Uploads [zip] to the active cloud folder. Null when cloud log upload is off or no cloud storage is active. */
    suspend fun uploadToCloud(zip: File): Boolean?

    /** Opens the mail client addressed and filled in, with the folder showing the zip. Null when email is off. */
    fun mail(zip: File): Boolean?
}

/** Delivers nowhere. What a test, or a build with no delivery bound, gets. */
object NoLogDelivery : LogDelivery {

    override suspend fun uploadToCloud(zip: File): Boolean? = null
    override fun mail(zip: File): Boolean? = null
}

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class DesktopLogDelivery(
    private val aapsLogger: AAPSLogger,
    private val cloudStorageManager: CloudStorageManager,
    private val preferences: Preferences
) : LogDelivery {

    private fun cloudWanted(): Boolean =
        preferences.get(BooleanNonKey.ExportLogCloudEnabled) && cloudStorageManager.isCloudStorageActive()

    override suspend fun uploadToCloud(zip: File): Boolean? {
        if (!cloudWanted()) return null
        return try {
            val provider = cloudStorageManager.getActiveProvider() ?: return false
            val bytes = zip.readBytes()
            provider.getOrCreateFolderPath(CloudConstants.CLOUD_PATH_LOGS)?.let { provider.setSelectedFolderId(it) }
            val id = provider.uploadFileToPath(zip.name, bytes, "application/zip", CloudConstants.CLOUD_PATH_LOGS)
                ?: provider.uploadFile(zip.name, bytes, "application/zip")
            id != null
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Cloud log upload failed", e)
            false
        }
    }

    override fun mail(zip: File): Boolean? {
        // The same rule as the phone: mail when email is on, or when there is no cloud to put it in.
        if (!(preferences.get(BooleanNonKey.ExportLogEmailEnabled) || !cloudWanted())) return null
        return try {
            if (!Desktop.isDesktopSupported()) return false
            val desktop = Desktop.getDesktop()
            val recipient = preferences.get(StringKey.MaintenanceEmail).trim()
            val subject = enc("Log Export")
            val body = enc("The log zip is saved at ${zip.absolutePath}. Attach it to this message.")
            if (desktop.isSupported(Desktop.Action.OPEN)) zip.parentFile?.let { desktop.open(it) }
            if (!desktop.isSupported(Desktop.Action.MAIL)) return false
            desktop.mail(URI("mailto:$recipient?subject=$subject&body=$body"))
            true
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Could not open the mail client", e)
            false
        }
    }

    /** mailto wants %20 for a space, and URLEncoder writes a plus. */
    private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}
