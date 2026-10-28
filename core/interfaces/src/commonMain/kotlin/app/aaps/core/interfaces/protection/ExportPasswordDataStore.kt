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

}
