package app.aaps.ios.shell

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ReportUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook

/**
 * Writes an uncaught Kotlin error, with its stack trace, to the app log just before iOS stops the app (2026-10-19, per
 * explicit request). Without it a crash left nothing in the log, and the iOS crash report shows only addresses, so the
 * cause could not be found. The log writes straight to the file, so the line is kept even though the app stops at once.
 *
 * The same text also goes to [CRASH_FILE_NAME] beside the log (2026-11-01, per explicit request). A busy client fills
 * the 10 MB the log keeps in about an hour, so the line was rotated away before anyone sent the log - the 31 Oct
 * AAPSClient2 crash was lost that way. The file is left alone by log rotation and by `deleteLogs`, is overwritten
 * by the next crash only, and "Send logs" sends it with the log.
 *
 * The app still stops afterwards, as before: the hook only records. Any hook installed earlier is called too.
 */
@OptIn(ExperimentalNativeApi::class)
internal object IosCrashLog {

    /** The crash file's name, in the Documents directory with the log. */
    const val CRASH_FILE_NAME = "last_crash.txt"

    private var installed = false

    fun install(logger: AAPSLogger, crashFile: String? = defaultCrashFile()) {
        if (installed) return
        installed = true
        var previous: ReportUnhandledExceptionHook? = null
        previous = setUnhandledExceptionHook { error ->
            // A failure while logging must not hide the original error
            runCatching { logger.error(LTag.CORE, "CRASH: uncaught ${error::class.simpleName}: ${error.message}", error) }
            runCatching { crashFile?.let { writeCrashFile(it, crashReport(error, kotlin.time.Clock.System.now().toString())) } }
            previous?.invoke(error)
        }
    }

    /** What the crash file holds: when, the same line as the log, and the stack trace. */
    fun crashReport(error: Throwable, time: String): String =
        "$time\nCRASH: uncaught ${error::class.simpleName}: ${error.message}\n${error.stackTraceToString()}\n"

    /** Replaces [path] with [text]. Plain C file calls, which still work while the app is going down. */
    @OptIn(ExperimentalForeignApi::class)
    fun writeCrashFile(path: String, text: String) {
        val file = fopen(path, "w") ?: return
        try {
            fputs(text, file)
        } finally {
            fclose(file)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun defaultCrashFile(): String? =
        (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String)
            ?.let { "$it/$CRASH_FILE_NAME" }
}
