package app.aaps.core.interfaces.protection

import android.content.Context

interface ExportPasswordDataStore {

    /***
     * Check Export password functionality
     * Returns true when Export password store is enabled.
     */
    fun exportPasswordStoreEnabled() : Boolean

    /***
     * Clear password currently stored.
     */
    fun clearPasswordDataStore(context: Context): String

    /***
     * Put password to local phone's datastore.
     */
    fun putPasswordToDataStore(context: Context, password: String): String

    /***
     * Get password from local phone's data store.
     * Return pair (true,<password>) or (false,"")
     */
    fun getPasswordFromDataStore(context: Context): Triple<String, Boolean, Boolean>

    /***
     * Whether the last [getPasswordFromDataStore] found a stored password that had really expired (and cleared it).
     * False when nothing was stored. Both come back from [getPasswordFromDataStore] as an empty, expired password, so this
     * is what lets an export say which one it was (2026-11-18, per explicit request).
     */
    fun lastReadFoundExpired(): Boolean = false

    }