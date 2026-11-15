package app.aaps.implementation.trio

import app.aaps.core.interfaces.trio.TrioRemoteCommand
import app.aaps.core.interfaces.trio.TrioRemoteControl
import app.aaps.core.interfaces.trio.TrioRemoteResult
import app.aaps.core.interfaces.trio.TrioRemoteSetup
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Bolus calculator patch: Trio remote control switched off.
 *
 * The calculator's Send to Trio buttons (bolus calculator, QuickWizard) are shown only while [TrioRemoteSetup.enabled]
 * is true, so with this binding they never show. The fork's real implementation (encrypted push through Apple, the Trio
 * profile read from Nightscout) is a separate subsystem and is not part of this patch. To add it, delete this file and
 * bind that implementation instead.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class TrioRemoteControlOff : TrioRemoteControl {

    override fun setup(): TrioRemoteSetup =
        TrioRemoteSetup(enabled = false, missing = emptyList(), trio = null, overridePresets = emptyList())

    override suspend fun send(command: TrioRemoteCommand, expectedSimulator: Boolean?): TrioRemoteResult =
        TrioRemoteResult.Failed("Trio remote control is not part of this build")
}
