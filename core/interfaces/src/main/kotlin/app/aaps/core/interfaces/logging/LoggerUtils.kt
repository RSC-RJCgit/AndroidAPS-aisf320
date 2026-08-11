package app.aaps.core.interfaces.logging

interface LoggerUtils {

    var suffix: String
    val logDirectory: String
    val appSpecificLogDirectory: String

    /** Adds the app-specific secondary rolling appender without replacing the public Documents one. */
    fun configureAppSpecificFallback()
}
