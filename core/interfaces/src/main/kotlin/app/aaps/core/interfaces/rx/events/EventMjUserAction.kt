package app.aaps.core.interfaces.rx.events

/** Direct Overview-button/menu command handled by the active AutoISF Kotlin plugin. */
class EventMjUserAction(val action: Action, val directMenu: Boolean = false) : Event() {
    enum class Action { START, RESTORE }
}
