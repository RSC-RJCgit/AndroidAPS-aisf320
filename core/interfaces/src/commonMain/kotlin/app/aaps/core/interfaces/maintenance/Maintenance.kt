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

    /**
     * Writes and uploads the AutoISF csv, text and settings, the user-entries csv,
     * the log zip, and the current log. The cloud-log button, the user-entries button,
     * a long press on the AutoISF table, and the six-hour log export all call this.
     *
     * [withLogs] false leaves the logs out (2026-10-20): "Send logs" sends them itself right after, so they were
     * uploaded twice.
     */
    suspend fun exportCoordinated(trigger: String, withLogs: Boolean = true)

    fun deleteLogs(keep: Int)
}
