package app.aaps.ios.shell.missing

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.ExportResult
import app.aaps.core.interfaces.maintenance.Maintenance
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/*
 * Maintenance and cloud export.
 *
 * Both sit on top of things iOS has no version of yet - the log directory and mail composer, and
 * Google Drive through its Android SDK - so both report "nothing here" rather than guessing.
 *
 * Screen usage statistics used to be here too. They moved to `platform` because a client is not
 * meant to collect them at all, which makes an empty answer correct rather than unfinished.
 */

/** Sending logs needs a mail composer, which is not wired up on iOS. Deleting them is real. */
@OptIn(ExperimentalForeignApi::class)
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosMaintenance(
    private val aapsLogger: AAPSLogger,
    /** Overridden in a test, so it neither reads the real log nor deletes it. */
    private val logDirectory: String? = defaultLogDirectory(),
    /** Cloud and the share sheet. A test builds this class without one, and then nothing is delivered. */
    private val delivery: IosLogDelivery = NoIosLogDelivery
) : Maintenance {

    override suspend fun uploadLogsToCloud(): Boolean {
        val copies = copyLogs()
        if (copies.isEmpty()) return false
        return delivery.uploadToCloud(copies) == true
    }

    /**
     * The scheduled and manual export: a copy of the log, uploaded to the cloud folder when that is on. The
     * AutoISF history files are written by the phone's `HistoryFilesWriter`, which iOS does not have yet.
     */
    override suspend fun exportCoordinated(trigger: String) {
        val uploaded = uploadLogsToCloud()
        aapsLogger.info(LTag.CORE, "EXPORT_STATUS trigger=$trigger component=LOGS result=${if (uploaded) "SUCCESS" else "FAILURE"}")
    }

    /**
     * Keeps a copy of the live log and its rotated sibling in `logs` beside them, under a time stamped
     * name, then offers the copies to the cloud folder and the share sheet (Mail is in it).
     *
     * The copy is kept even when nothing could be delivered: the live log is rotated and overwritten
     * within hours, and a copy in the Files app can be shared from there.
     */
    override suspend fun executeSendLogs(): ExportResult {
        val copies = copyLogs()
        if (copies.isEmpty()) return ExportResult(localSuccess = false)
        val uploaded = delivery.uploadToCloud(copies)
        delivery.share(copies)
        return ExportResult(localSuccess = true, cloudSuccess = uploaded)
    }

    /** Copies the log files into `logs` and returns the paths of the copies. Empty when there was nothing to copy. */
    private fun copyLogs(): List<String> {
        val directory = logDirectory ?: return emptyList()
        val manager = NSFileManager.defaultManager

        @Suppress("UNCHECKED_CAST")
        val names = (manager.contentsOfDirectoryAtPath(directory, null) as? List<String>)
            ?.filter { it == LOG_NAME || it.startsWith("$LOG_NAME.") }
            .orEmpty()
        if (names.isEmpty()) return emptyList()
        val target = "$directory/logs"
        manager.createDirectoryAtPath(target, withIntermediateDirectories = true, attributes = null, error = null)
        val stamp = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val copies = mutableListOf<String>()
        for (name in names) {
            val to = "$target/${stamp}_$name"
            if (manager.copyItemAtPath("$directory/$name", toPath = to, error = null)) copies += to
        }
        aapsLogger.debug(LTag.CORE, "Copied ${copies.size} log file(s) to $target")
        return copies
    }

    /**
     * Removes rotated log files, keeping the newest [keep] of them.
     *
     * `AAPSLoggerIos` already bounds the log itself: at 5 MB it moves `aaps.log` to `aaps.log.1` and
     * keeps exactly one previous file, so the worst case on disk is about 10 MB and nothing here is
     * load bearing today. This exists for two reasons anyway. The periodic maintenance calls it every
     * cycle, and a call that logs an error every hour trains people to ignore errors. And the bound
     * is the *logger's* promise, not this one's: if it is ever changed to keep more, the housekeeping
     * that is supposed to clear them up should already work rather than be discovered missing.
     *
     * The live `aaps.log` is never touched - something is writing to it.
     */
    override fun deleteLogs(keep: Int) {
        val directory = logDirectory
        if (directory == null) {
            aapsLogger.debug(LTag.CORE, "No log directory, so there is nothing to delete")
            return
        }
        val manager = NSFileManager.defaultManager

        @Suppress("UNCHECKED_CAST")
        val names = manager.contentsOfDirectoryAtPath(directory, null) as? List<String> ?: return

        // Only the rotated ones - `aaps.log` itself is open for writing - and ordered newest first.
        // `.1` is the newest, because rotation *moves* the live file onto it; the convention every
        // rotating logger uses, and the opposite of what a plain name sort gives. Ordered by the
        // number rather than as text, so a hypothetical `.10` does not sort between `.1` and `.2`.
        val rotated = names
            .mapNotNull { name -> name.removePrefix("$LOG_NAME.").toIntOrNull()?.takeIf { name != it.toString() }?.let { it to name } }
            .sortedBy { (index, _) -> index }
            .map { (_, name) -> name }

        rotated.drop((keep - 1).coerceAtLeast(0)).forEach { name ->
            manager.removeItemAtPath("$directory/$name", null)
            aapsLogger.debug(LTag.CORE, "Deleted the old log file $name")
        }
    }

    private companion object {

        /** The name `AAPSLoggerIos` writes, and rotates as `aaps.log.1`. */
        private const val LOG_NAME = "aaps.log"
    }
}

/** Where `AAPSLoggerIos` puts the log: the app's Documents directory. */
@OptIn(ExperimentalForeignApi::class)
private fun defaultLogDirectory(): String? =
    NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String
