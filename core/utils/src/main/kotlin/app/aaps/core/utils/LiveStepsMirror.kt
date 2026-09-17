package app.aaps.core.utils

import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Steps received from NS, kept separate from this device's APS results and local step sensor.
 * Retains 36 hours for the 30-hour AIV export, including across process restarts.
 */
@Singleton
class LiveStepsMirror @Inject constructor(private val preferences: Preferences) {

    data class Sample(val timestamp: Long, val source: String, val buckets: Map<Int, Int>) {
        fun steps(minutes: Int): Int? = buckets[minutes]
    }

    private var history: List<Sample>? = null

    private fun samples(): List<Sample> = history ?: run {
        val saved = runCatching {
            val rows = JSONArray(preferences.get(StringNonKey.LiveStepsHistory))
            (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                val buckets = row.getJSONObject("buckets")
                Sample(row.getLong("timestamp"), row.getString("source"), WINDOWS.mapNotNull { minutes ->
                    if (buckets.has(minutes.toString())) minutes to buckets.getInt(minutes.toString()) else null
                }.toMap())
            }
        }.getOrDefault(emptyList())
        saved.filter { sample ->
            sample.timestamp > 0 && sample.source.startsWith("openaps://") &&
                sample.buckets.isNotEmpty() && sample.buckets.all { (bucket, count) -> bucket in WINDOWS && count >= 0 }
        }.sortedByDescending { it.timestamp }.take(MAX_SAMPLES).also { history = it }
    }

    /** Called only for the opted-in full-app Virtual receiver. Never accept its own NS echo.
     * Partial samples retain missing buckets as null; a genuine zero remains a valid reading.
     */
    @Synchronized
    fun receive(source: String?, ownSource: String, timestamp: Long, reason: String, now: Long): Sample? {
        if (source.isNullOrBlank() || !source.startsWith("openaps://") || source.equals(ownSource, ignoreCase = true)) return null
        if (timestamp <= 0 || timestamp > now || now - timestamp > MAX_AGE_MS) return null
        val buckets = parse(reason)
        if (buckets.isEmpty()) return null
        val sample = Sample(timestamp, source, buckets)
        val old = samples()
        if (sample in old) return null
        val updated = (old.filterNot { it.timestamp == timestamp && it.source == source } + sample)
            .filter { it.timestamp >= now - HISTORY_MS && it.timestamp <= now }
            .sortedByDescending { it.timestamp }.take(MAX_SAMPLES)
        val rows = JSONArray()
        updated.forEach { item ->
            val values = JSONObject()
            item.buckets.forEach { (minutes, count) -> values.put(minutes.toString(), count) }
            rows.put(JSONObject().put("timestamp", item.timestamp).put("source", item.source).put("buckets", values))
        }
        preferences.put(StringNonKey.LiveStepsHistory, rows.toString())
        history = updated
        return sample
    }

    /** Latest sample at or BEFORE the requested time; never borrow a future row or mix buckets.
     * Shared by dosing and AIV, so both use identical freshness and missing-value rules.
     */
    @Synchronized
    fun at(timestamp: Long): Sample? = samples().firstOrNull {
        it.timestamp <= timestamp && timestamp - it.timestamp <= MAX_AGE_MS
    }

    companion object {
        const val MAX_AGE_MS = 20 * 60_000L
        private const val HISTORY_MS = 36 * 60 * 60_000L
        private const val MAX_SAMPLES = 2500
        private val WINDOWS = listOf(5, 10, 15, 30, 60, 180)
        private val patterns = WINDOWS.associateWith { minutes ->
            Regex("""\bsteps${minutes}min\s+is\s+([0-9]+)\b|\bsteps${minutes}M\s*[:=]\s*([0-9]+)\b""", RegexOption.IGNORE_CASE)
        }

        fun parse(reason: String): Map<Int, Int> = patterns.mapNotNull { (minutes, regex) ->
            val match = regex.find(reason) ?: return@mapNotNull null
            val count = match.groupValues[1].ifEmpty { match.groupValues[2] }.toIntOrNull() ?: return@mapNotNull null
            minutes to count
        }.toMap()
    }
}
