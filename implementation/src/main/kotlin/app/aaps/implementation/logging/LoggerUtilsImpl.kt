package app.aaps.implementation.logging

import android.os.Environment
import app.aaps.core.interfaces.logging.LoggerUtils
import ch.qos.logback.classic.LoggerContext
import dagger.Reusable
import org.slf4j.LoggerFactory
import javax.inject.Inject

/**
 * This class provides several methods for log-handling (eg. sending logs as emails).
 */
@Reusable
class LoggerUtilsImpl @Inject constructor() : LoggerUtils {

    // MaintenancePlugin creates ZIP archives; keep the filename consistent with both the actual
    // content and the AndroidAPS_LOG_*.log.zip convention used by backup/retention tooling.
    override var suffix = ".log.zip"

    /**
     * Returns the directory, in which the logs are stored on the system. This is configured in the
     * logback.xml file.
     *
     * @return path
     */
    override val logDirectory: String
        get() = (LoggerFactory.getILoggerFactory() as LoggerContext).getProperty("EXT_FILES_DIR")
            ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                .resolve("aapsLogs")
                .absolutePath
}
