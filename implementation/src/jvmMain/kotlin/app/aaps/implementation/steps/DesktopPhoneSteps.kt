package app.aaps.implementation.steps

import app.aaps.core.interfaces.aps.PhoneSteps
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The desktop app has no phone step counter here yet. Every count is 0 and the phone never counts as moved, so the AutoISF
 * activity monitor reports "phone seems not to be carried" in its log and does not change the sensitivity.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DesktopPhoneSteps : PhoneSteps {

    override fun start() {}
    override fun steps5Min(): Int = 0
    override fun steps10Min(): Int = 0
    override fun steps15Min(): Int = 0
    override fun steps30Min(): Int = 0
    override fun steps60Min(): Int = 0
    override fun steps180Min(): Int = 0
    override fun phoneMoved(): Boolean = false
}
