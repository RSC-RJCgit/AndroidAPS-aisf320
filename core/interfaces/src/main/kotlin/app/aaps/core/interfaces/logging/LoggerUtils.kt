package app.aaps.core.interfaces.logging

import java.io.File

interface LoggerUtils {

    var suffix: String
    val logDirectory: String
    val appSpecificLogDirectory: String

    /** Adds the app-specific secondary rolling appender without replacing the public Documents one. */
    fun configureAppSpecificFallback()

    /** Resolves every raw source log file (the live "AndroidAPS.log" plus rotated
     *  "AndroidAPS._yyyy-MM-dd_HH.log.zip" archives -- NOT the "AndroidAPS_LOG_*" bundles a manual/cloud
     *  log export produces) across all directories the rolling appender might actually be writing to,
     *  not just [logDirectory]. On scoped-storage Android, [logDirectory] (public Documents/aapsLogs)
     *  can silently stop being writable/listable on a given device while the app-specific fallback
     *  appender ([configureAppSpecificFallback]) keeps receiving writes fine -- searching only
     *  [logDirectory] then finds nothing (or stale files) even though current logs exist right next to
     *  it under a different directory. Deduplicated, sorted newest-name-first (matches rolling
     *  filenames' own lexical/chronological order). Falls back to probing deterministic filenames
     *  directly per directory when that directory's listing comes back null/empty, since scoped storage
     *  can allow opening a known public-Documents file while refusing directory enumeration. */
    fun findSourceLogFiles(): List<File>
}
