package app.aaps.core.interfaces.rx.events

/**
 * Executes one of the AutoISF settings controls locally, without creating the temporary target that
 * carries the same command between an AAPSClient and its pump phone.
 */
class EventAutoIsfDirectTtCode(val mmol: Double) : Event()
