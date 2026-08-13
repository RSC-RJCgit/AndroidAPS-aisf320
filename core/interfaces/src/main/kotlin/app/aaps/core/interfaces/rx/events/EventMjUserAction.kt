package app.aaps.core.interfaces.rx.events

/** Direct Overview-button command handled by the active AutoISF Kotlin plugin. */
class EventMjUserAction(val action: Action) : Event() {
    enum class Action { START, RESTORE }
}
