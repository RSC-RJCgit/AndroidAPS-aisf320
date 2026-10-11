package app.aaps.ios.shell.missing

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.maintenance.cloud.CloudConstants
import app.aaps.implementation.maintenance.cloud.CloudStorageManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

/**
 * Where copied log files go after they are written: the cloud folder and the share sheet.
 *
 * A separate class so [IosMaintenance] stays constructible without a preferences store or a cloud manager,
 * which is how its test builds it. The cloud answer is null when the upload was not tried.
 */
interface IosLogDelivery {

    /** Uploads [paths] to the active cloud folder. Null when cloud log upload is off or no cloud storage is active. */
    suspend fun uploadToCloud(paths: List<String>): Boolean?

    /** Shows the share sheet for [paths]: Mail, Messages, Drive, AirDrop and "Save to Files" are all in it. */
    fun share(paths: List<String>)
}

/** Delivers nowhere. What a test, or a build with no delivery bound, gets. */
object NoIosLogDelivery : IosLogDelivery {

    override suspend fun uploadToCloud(paths: List<String>): Boolean? = null
    override fun share(paths: List<String>) = Unit
}

@OptIn(ExperimentalForeignApi::class)
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosLogDeliveryImpl(
    private val aapsLogger: AAPSLogger,
    private val cloudStorageManager: CloudStorageManager,
    private val preferences: Preferences
) : IosLogDelivery {

    override suspend fun uploadToCloud(paths: List<String>): Boolean? {
        if (!preferences.get(BooleanNonKey.ExportLogCloudEnabled) || !cloudStorageManager.isCloudStorageActive()) return null
        return try {
            val provider = cloudStorageManager.getActiveProvider() ?: return false
            provider.getOrCreateFolderPath(CloudConstants.CLOUD_PATH_LOGS)?.let { provider.setSelectedFolderId(it) }
            var uploaded = 0
            for (path in paths) {
                val bytes = readBytes(path) ?: continue
                val name = path.substringAfterLast('/')
                val id = provider.uploadFileToPath(name, bytes, "text/plain", CloudConstants.CLOUD_PATH_LOGS)
                    ?: provider.uploadFile(name, bytes, "text/plain")
                if (id != null) uploaded++
            }
            uploaded > 0
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Cloud log upload failed", e)
            false
        }
    }

    override fun share(paths: List<String>) {
        if (paths.isEmpty()) return
        dispatch_async(dispatch_get_main_queue()) {
            val items = paths.map { NSURL.fileURLWithPath(it) }
            val controller = UIActivityViewController(activityItems = items, applicationActivities = null)
            var top = UIApplication.sharedApplication.keyWindow?.rootViewController
            while (top?.presentedViewController != null) top = top.presentedViewController
            if (top == null) {
                aapsLogger.error(LTag.CORE, "No view controller to show the share sheet from")
                return@dispatch_async
            }
            // An iPad shows the sheet as a popover and refuses without a source view.
            controller.popoverPresentationController?.sourceView = top.view
            top.presentViewController(controller, animated = true, completion = null)
        }
    }

    private fun readBytes(path: String): ByteArray? {
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        val size = data.length.toInt()
        if (size == 0) return ByteArray(0)
        val bytes = ByteArray(size)
        bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return bytes
    }
}
