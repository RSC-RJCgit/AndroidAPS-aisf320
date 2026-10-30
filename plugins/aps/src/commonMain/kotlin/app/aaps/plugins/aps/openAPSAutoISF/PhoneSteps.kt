package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.model.SC
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The phone's own step counter (2026-10-30, per explicit request): v3's StepService ("Activity Mode"), ported. Nightscout's
 * v4 counts steps on an AAPS watch only, so a v4 phone without one had no steps of its own.
 *
 * The Android listener ([PhoneStepListener]) feeds the counter's running total; other targets never do. Steps are kept in
 * five-minute bins, read as v3 reads them: S5, S10 and S15 are the last three finished bins, S30/S60/S180 the bins of the
 * last 30/60/180 minutes. 37 bins are kept, so S180 covers 180 minutes (v3 kept 20, about 100).
 */
@OptIn(ExperimentalAtomicApi::class)
internal object PhoneSteps {

    const val DEVICE = "Smartphone"
    private const val BIN_MS = 5 * 60_000L
    private const val BINS_KEPT = 37

    private class State(val previousTotal: Int, val bins: Map<Long, Int>)

    private val state = AtomicReference(State(-1, emptyMap()))

    /** A new running total from the sensor. Android sends the current total as soon as the sensor is switched on. */
    fun onTotal(total: Int, nowMs: Long) {
        val bin = nowMs / BIN_MS
        while (true) {
            val old = state.load()
            val bins = if (old.previousTotal < 0) old.bins else {
                // The total starts again from 0 after the phone restarts
                val added = if (total >= old.previousTotal) total - old.previousTotal else total
                (old.bins + (bin to (old.bins[bin] ?: 0) + added)).filterKeys { it > bin - BINS_KEPT }
            }
            if (state.compareAndSet(old, State(total, bins))) return
        }
    }

    /** This loop's counts as a step row, S10 and S15 as totals like a watch row (see StepBins.kt). Null until the sensor reported. */
    fun row(nowMs: Long): SC? {
        val current = state.load()
        if (current.previousTotal < 0) return null
        val bin = nowMs / BIN_MS
        fun binAt(index: Long) = current.bins[index] ?: 0
        fun sinceBins(count: Int) = current.bins.filterKeys { it in (bin - count + 1)..bin }.values.sum()
        val s5 = binAt(bin - 1)
        val s10 = s5 + binAt(bin - 2)
        val s15 = s10 + binAt(bin - 3)
        return SC(
            duration = BIN_MS,
            timestamp = nowMs,
            steps5min = s5,
            steps10min = s10,
            steps15min = s15,
            steps30min = sinceBins(6),
            steps60min = sinceBins(12),
            steps180min = sinceBins(36),
            device = DEVICE
        )
    }

    /** For tests. */
    fun reset() {
        state.store(State(-1, emptyMap()))
    }
}
