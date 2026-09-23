package app.aaps.core.interfaces.maintenance

/**
 * Interface for maintenance functionality.
 * Allows access to maintenance plugin through interface lookup.
 */
interface Maintenance {

    suspend fun executeSendLogs(): ExportResult

    /**
     * Uploads the log zip to cloud storage. Does not open email.
     * Returns false when cloud storage is off or the upload fails.
     */
    suspend fun uploadLogsToCloud(): Boolean

    fun deleteLogs(keep: Int)
}
