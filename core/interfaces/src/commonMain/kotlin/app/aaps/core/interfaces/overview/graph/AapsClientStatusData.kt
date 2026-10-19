package app.aaps.core.interfaces.overview.graph

import app.aaps.core.interfaces.aps.APSResult

data class AapsClientStatusData(
    val pump: AapsClientStatusItem? = null,
    val openAps: AapsClientStatusItem? = null,
    /** The master's latest AutoISF run, from the AutoISF values mirrored to this client. Null when none has arrived. */
    val autoIsf: AapsClientStatusItem? = null,
    val uploader: AapsClientStatusItem? = null,
    /**
     * False when the loop (the master, seen from a client) is known to run AMA or SMB. Then the AutoISF lists (Set2, and
     * all of List 1 except the coded profile picker) are not offered: their codes change AutoISF settings the loop does
     * not use (2026-10-19). See [autoIsfListsFor].
     */
    val autoIsfLists: Boolean = true
)

/**
 * Whether the AutoISF lists fit a loop running [algorithm]. Only AMA and SMB say no. An unknown algorithm (no loop result
 * yet, or a master that does not send it) keeps the lists, as before this check.
 */
fun autoIsfListsFor(algorithm: APSResult.Algorithm?): Boolean =
    algorithm != APSResult.Algorithm.AMA && algorithm != APSResult.Algorithm.SMB

data class AapsClientStatusItem(
    val label: String,
    val value: String,
    val level: AapsClientLevel,
    val dialogTitle: String,
    val dialogText: String
)

enum class AapsClientLevel {
    INFO,
    WARN,
    URGENT
}