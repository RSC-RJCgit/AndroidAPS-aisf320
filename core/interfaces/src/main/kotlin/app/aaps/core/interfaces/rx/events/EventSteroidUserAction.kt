package app.aaps.core.interfaces.rx.events

/** Direct Overview-button/menu command handled by the active AutoISF Kotlin plugin. */
class EventSteroidUserAction(
    val action: Action = Action.START_110,
    val directMenu: Boolean = false
) : Event() {
    enum class Action { START_110, INCREASE_130, INCREASE_150, INCREASE_190, INCREASE_250, TURN_OFF }
}
