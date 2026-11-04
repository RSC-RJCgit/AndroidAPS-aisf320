package app.aaps.ui.stats

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNewHistoryData
import app.aaps.core.interfaces.rx.events.EventProfileStoreChanged
import app.aaps.core.interfaces.rx.events.EventProfileSwitchChanged
import app.aaps.core.interfaces.utils.DateUtil
import io.reactivex.rxjava3.disposables.CompositeDisposable
import javax.inject.Inject
import javax.inject.Singleton

/** Display-only totals. Never write partial client history into the daily cache used by dosing. */
@Singleton
class PodInsulinCache @Inject constructor(rxBus: RxBus, private val dateUtil: DateUtil, logger: AAPSLogger) {
    private data class Entry(val units: Double, val savedAt: Long)
    private val lock = Any()
    private val totals = mutableMapOf<Pair<Long, Long>, Entry>()
    private var revision = 0L
    private val subscriptions = CompositeDisposable()

    init {
        subscriptions.add(rxBus.toObservable(EventNewHistoryData::class.java).subscribe({ event ->
            synchronized(lock) {
                revision++
                totals.keys.removeAll { it.second >= event.oldDataTimestamp }
            }
        }, { logger.error("Pod statistics cache", it) }))
        subscriptions.add(rxBus.toObservable(EventProfileStoreChanged::class.java).subscribe({ clear() }, { logger.error("Pod statistics cache", it) }))
        subscriptions.add(rxBus.toObservable(EventProfileSwitchChanged::class.java).subscribe({ clear() }, { logger.error("Pod statistics cache", it) }))
    }

    fun clear() = synchronized(lock) {
        revision++
        totals.clear()
    }

    fun getOrCalculate(from: Long, to: Long, cacheable: Boolean, calculate: () -> Double?): Double? {
        if (!cacheable) return calculate()
        val key = from to to
        val now = dateUtil.now()
        val (cached, version) = synchronized(lock) {
            totals.entries.removeAll { now - it.value.savedAt !in 0..300_000L }
            totals[key]?.units to revision
        }
        if (cached != null) return cached
        val units = calculate()
        if (units != null) synchronized(lock) {
            if (revision == version) totals[key] = Entry(units, dateUtil.now())
        }
        return units
    }
}
