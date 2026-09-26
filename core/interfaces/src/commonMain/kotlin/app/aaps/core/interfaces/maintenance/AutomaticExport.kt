package app.aaps.core.interfaces.maintenance

/**
 * The six-hour export KeepAlive runs.
 * History files are written every six hours.
 * When the log switch is also due, that run writes the history files, the user-entries file,
 * the log zip, and the current log together.
 */
interface AutomaticExport {

    suspend fun runIfDue()
}
