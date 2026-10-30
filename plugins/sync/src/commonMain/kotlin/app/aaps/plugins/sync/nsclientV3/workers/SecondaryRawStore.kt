package app.aaps.plugins.sync.nsclientV3.workers

import app.aaps.core.data.model.GV
import app.aaps.core.nssdk.localmodel.entry.NSSgvV3
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.abs

/**
 * Raw from the secondary Nightscout (iOS, 2026-10-30, per explicit request).
 *
 * BG comes from this phone's BG source as usual. iOS takes every reading from Nightscout and the main site's readings
 * carry no raw, so a reading without raw of its own takes the secondary site's reading of the same minute as raw
 * (GV.noise, which the Raw line and the rawBGL column read). A reading with its own raw keeps it; with no secondary
 * reading near it, raw stays empty. The secondary readings are held here, never in the BG table, so they are never BG,
 * never uploaded and never dosed on.
 *
 * The two sites arrive at different times, so the fill runs both ways: a main reading takes what is held here when it
 * comes in, and each secondary read fills the stored readings that are still without raw.
 */
@OptIn(ExperimentalAtomicApi::class)
@SingleIn(AppScope::class)
@Inject
class SecondaryRawStore {

    // (time, mg/dL), oldest first, the last KEEP_MS only
    private val readings = AtomicReference(emptyList<Pair<Long, Double>>())

    val newest: Long? get() = readings.load().lastOrNull()?.first

    fun snapshot(): List<Pair<Long, Double>> = readings.load()

    fun add(new: List<Pair<Long, Double>>, now: Long) {
        if (new.isEmpty()) return
        while (true) {
            val old = readings.load()
            val merged = (old + new).filter { it.first >= now - KEEP_MS }.associateBy { it.first }.values.sortedBy { it.first }
            if (readings.compareAndSet(old, merged)) return
        }
    }

    companion object {

        /** After an app start the store is empty, so this much is read again. */
        const val KEEP_MS = 6L * 60 * 60 * 1000
    }
}

/** How far apart a reading and a secondary reading may be and still count as the same minute. */
internal const val RAW_MATCH_MS = 60L * 1000

/** (time, mg/dL) of the valid readings, oldest first. */
internal fun List<NSSgvV3>.toRawPairs(): List<Pair<Long, Double>> =
    mapNotNull { sgv -> sgv.date?.takeIf { sgv.isValid && sgv.sgv > 0.0 }?.let { it to sgv.sgv } }.sortedBy { it.first }

/** The secondary value nearest [time] and within [toleranceMs], or null. [sorted] is oldest first. */
internal fun secondaryRawNear(sorted: List<Pair<Long, Double>>, time: Long, toleranceMs: Long = RAW_MATCH_MS): Double? {
    var low = 0
    var high = sorted.size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (sorted[mid].first < time) low = mid + 1 else high = mid - 1
    }
    // low is the first at or after time, high the last before it
    val best = listOfNotNull(sorted.getOrNull(high), sorted.getOrNull(low)).minByOrNull { abs(it.first - time) }
    return best?.takeIf { abs(it.first - time) <= toleranceMs }?.second
}

/** True when [gv] has no raw of its own: the Raw line only draws GV.noise over 10 mg/dL. */
internal fun hasNoRaw(gv: GV): Boolean = (gv.noise ?: 0.0) <= 10.0

/** Copies of the readings without raw that get one from [secondary]. Readings with raw are never in the result. */
internal fun fillMissingRaw(readings: List<GV>, secondary: List<Pair<Long, Double>>): List<GV> {
    if (secondary.isEmpty()) return emptyList()
    return readings.mapNotNull { gv ->
        if (!hasNoRaw(gv)) null else secondaryRawNear(secondary, gv.timestamp)?.let { gv.copy(noise = it) }
    }
}
