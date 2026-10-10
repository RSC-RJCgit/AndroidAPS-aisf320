package app.aaps.plugins.aps.openAPSAutoISF

/**
 * Opens the AnyDesk app on this phone.
 *
 * Android leaves the home screen and then brings AnyDesk forward. Any other platform reports that
 * AnyDesk is not there, so the caller can write that down instead of pretending it opened.
 */
interface AnyDeskFront {

    /** `shown` is true only after AnyDesk's own screen was opened. */
    fun bringToFront(onResult: (shown: Boolean) -> Unit)

    /**
     * Whether this app may start a screen from the background ("Display over other apps" on Android).
     * Without it Android 12+ can ignore the launch without an error. Other platforms have no such limit.
     */
    fun overlayGranted(): Boolean = true

    /**
     * Opens AnyDesk from the Shizuku shell as a second try, when Shizuku is running and granted. Null when it was not tried,
     * otherwise whether the shell launch worked. Other platforms never try.
     */
    fun launchViaShell(): Boolean? = null
}
