package app.aaps.core.interfaces.protection

interface ExportPasswordDataStore {

    /** A verified master password kept encrypted for manual settings transfers. */
    fun rememberMasterPassword(password: String)
    fun getMasterPassword(): String?

    /***
     * Check Export password functionality
     * Returns true when Export password store is enabled.
     */
    fun exportPasswordStoreEnabled(): Boolean

    /***
     * Clear password currently stored.
     */
    fun clearPasswordDataStore(): String

    /***
     * Put password to local phone's datastore.
     */
    fun putPasswordToDataStore(password: String): String

    /***
     * Get password from local phone's data store.
     * Return pair (true,<password>) or (false,"")
     */
    fun getPasswordFromDataStore(): Triple<String, Boolean, Boolean>

    /**
     * Why the last [getPasswordFromDataStore] gave nothing usable (2026-11-18, per explicit request). All three come back from it
     * as an empty, expired password, so this is what lets an export say which one it was instead of always "password expired".
     */
    fun lastMissReason(): MissReason = MissReason.NONE

    enum class MissReason {

        /** The last read gave a usable password, or the store is off */
        NONE,

        /** Nothing was stored: none entered since an install, a data clear or a reset */
        NOT_STORED,

        /** A stored password had passed its validity window and was cleared */
        EXPIRED,

        /** A stored password no longer matched the master password and was cleared */
        MASTER_CHANGED
    }
}
