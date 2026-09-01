package app.aaps.core.interfaces.rx.events

/**
 * Asks the AutoISF plugin to bring AnyDesk to the front using AAPS's own no-kill launch.
 * Used by coded location arrival/exit so a wifi/network change does not depend on Tasker.
 */
class EventAnyDeskLaunchRequested(val reason: String = "") : Event()
