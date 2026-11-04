package app.aaps.ui.compose.stats

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.CA
import app.aaps.core.data.model.EB
import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.TB
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.utils.DateUtil
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Display-only totals. Never write partial client history into the daily cache used by dosing. */
@SingleIn(AppScope::class)
@Inject
class PodInsulinCache(persistenceLayer: PersistenceLayer, private val dateUtil: DateUtil) {
    private data class Entry(val units: Double, val savedAt: Long)
    private val mutex = Mutex()
    private val totals = mutableMapOf<Pair<Long, Long>, Entry>()
    private var revision = 0L
    private val scope = CoroutineScope(SupervisorJob() + aapsIoDispatcher)

    init {
        merge(
            persistenceLayer.observeChanges(BS::class).map { rows -> rows.minOfOrNull { it.timestamp } ?: Long.MIN_VALUE },
            persistenceLayer.observeChanges(CA::class).map { rows -> rows.minOfOrNull { it.timestamp } ?: Long.MIN_VALUE },
            persistenceLayer.observeChanges(TB::class).map { rows -> rows.minOfOrNull { it.timestamp } ?: Long.MIN_VALUE },
            persistenceLayer.observeChanges(EB::class).map { rows -> rows.minOfOrNull { it.timestamp } ?: Long.MIN_VALUE },
            persistenceLayer.observeChanges(EPS::class).map { rows -> rows.minOfOrNull { it.timestamp } ?: Long.MIN_VALUE }
        ).onEach { oldest ->
            mutex.withLock {
                revision++
                totals.keys.removeAll { it.second >= oldest }
            }
        }.launchIn(scope)
    }

    suspend fun clear() = mutex.withLock {
        revision++
        totals.clear()
    }

    suspend fun getOrCalculate(from: Long, to: Long, cacheable: Boolean, calculate: suspend () -> Double?): Double? {
        if (!cacheable) return calculate()
        val key = from to to
        val now = dateUtil.now()
        val (cached, version) = mutex.withLock {
            totals.entries.removeAll { now - it.value.savedAt !in 0..300_000L }
            totals[key]?.units to revision
        }
        if (cached != null) return cached
        val units = calculate()
        if (units != null) mutex.withLock {
            if (revision == version) totals[key] = Entry(units, dateUtil.now())
        }
        return units
    }
}
