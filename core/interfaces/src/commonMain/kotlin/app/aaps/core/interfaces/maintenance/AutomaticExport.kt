package app.aaps.core.interfaces.maintenance

/**
 * The six-hour export KeepAlive runs.
 * It writes the AutoISF history files, and uploads logs when that switch is on.
 */
interface AutomaticExport {

    suspend fun runIfDue()
}
