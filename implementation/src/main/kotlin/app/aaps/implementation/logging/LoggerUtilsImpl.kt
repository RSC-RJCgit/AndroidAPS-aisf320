package app.aaps.implementation.logging

import android.content.Context
import android.os.Environment
import app.aaps.core.interfaces.logging.LoggerUtils
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import dagger.Reusable
import org.slf4j.LoggerFactory
import java.io.File
import javax.inject.Inject

/**
 * This class provides several methods for log-handling (eg. sending logs as emails).
 */
@Reusable
class LoggerUtilsImpl @Inject constructor(
    private val context: Context
) : LoggerUtils {

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

    override val appSpecificLogDirectory: String
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "aapsLogs").absolutePath

    @Synchronized
    override fun configureAppSpecificFallback() {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        if (root.getAppender(APP_SPECIFIC_APPENDER) != null) return

        val directory = File(appSpecificLogDirectory).also { it.mkdirs() }
        val appender = RollingFileAppender<ILoggingEvent>().apply {
            setName(APP_SPECIFIC_APPENDER)
            setContext(loggerContext)
            setAppend(true)
            setFile(File(directory, "AndroidAPS.log").absolutePath)
        }
        val rollingPolicy = TimeBasedRollingPolicy<ILoggingEvent>().apply {
            setContext(loggerContext)
            setFileNamePattern(File(directory, "AndroidAPS._%d{yyyy-MM-dd_HH}.log.zip").absolutePath)
            setMaxHistory(720)
            setCleanHistoryOnStart(true)
            setParent(appender)
            start()
        }
        val encoder = PatternLayoutEncoder().apply {
            setContext(loggerContext)
            setPattern("%d{HH:mm:ss.SSS} [%thread] %.-1level/%logger: %msg%n")
            start()
        }
        appender.setRollingPolicy(rollingPolicy)
        appender.setEncoder(encoder)
        appender.start()
        root.setLevel(root.level ?: Level.DEBUG)
        root.addAppender(appender)
    }

    private companion object {
        const val APP_SPECIFIC_APPENDER = "appSpecificFile"
    }
}
