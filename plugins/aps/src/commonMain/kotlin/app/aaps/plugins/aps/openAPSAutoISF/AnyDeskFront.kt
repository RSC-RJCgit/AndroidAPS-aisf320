package app.aaps.plugins.aps.openAPSAutoISF

/**
 * Opens the AnyDesk app on this phone.
 *
 * Android leaves the home screen and then brings AnyDesk forward. Any other platform reports that
 * AnyDesk is not there, so the caller can write that down instead of pretending it opened.
 */
interface AnyDeskFront {

    /** [shown] is true only after AnyDesk's own screen was opened. */
    fun bringToFront(onResult: (shown: Boolean) -> Unit)
}
