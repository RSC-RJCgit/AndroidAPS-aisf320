package app.aaps.plugins.aps.openAPSAutoISF

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** AnyDesk for Android is not on this phone. The caller records that it was not opened. */
@ContributesBinding(AppScope::class)
@Inject
class IosAnyDeskFront : AnyDeskFront {

    override fun bringToFront(onResult: (shown: Boolean) -> Unit) {
        onResult(false)
    }
}
