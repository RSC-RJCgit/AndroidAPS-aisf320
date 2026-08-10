package app.aaps.core.interfaces.logging

import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventExportScriptDebugStatusChanged
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Recent export results shown above the current APS calculation's Script debug output. */
@Singleton
class ExportScriptDebugStatus @Inject constructor(private val rxBus: RxBus) {

    private val entries = ArrayDeque<String>()

    @Synchronized
    fun add(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        entries.addFirst("[$time] $message")
        while (entries.size > MAX_ENTRIES) entries.removeLast()
        rxBus.send(EventExportScriptDebugStatusChanged())
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    private companion object {
        const val MAX_ENTRIES = 20
    }
}
