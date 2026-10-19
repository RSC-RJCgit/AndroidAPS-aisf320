package app.aaps.ios.shell

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ReportUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook

/**
 * Writes an uncaught Kotlin error, with its stack trace, to the app log just before iOS stops the app (2026-10-19, per
 * explicit request). Without it a crash left nothing in the log, and the iOS crash report shows only addresses, so the
 * cause could not be found. The log writes straight to the file, so the line is kept even though the app stops at once.
 *
 * The app still stops afterwards, as before: the hook only records. Any hook installed earlier is called too.
 */
@OptIn(ExperimentalNativeApi::class)
internal object IosCrashLog {

    private var installed = false

    fun install(logger: AAPSLogger) {
        if (installed) return
        installed = true
        var previous: ReportUnhandledExceptionHook? = null
        previous = setUnhandledExceptionHook { error ->
            // A failure while logging must not hide the original error
            runCatching { logger.error(LTag.CORE, "CRASH: uncaught ${error::class.simpleName}: ${error.message}", error) }
            previous?.invoke(error)
        }
    }
}
