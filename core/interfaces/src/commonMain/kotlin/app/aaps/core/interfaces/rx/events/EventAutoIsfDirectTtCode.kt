package app.aaps.core.interfaces.rx.events

/**
 * A List 1 tap on this phone. The pump applies the setting now. It does not create a temp target.
 */
class EventAutoIsfDirectTtCode(val mmol: Double) : Event()
