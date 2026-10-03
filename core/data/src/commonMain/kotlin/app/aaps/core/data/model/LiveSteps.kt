package app.aaps.core.data.model

/**
 * Step counts the live phone writes into the Nightscout reason.
 * A virtual-pump phone reads the same counts. It does not use its own step sensor.
 */
object LiveSteps {

    const val MAX_AGE_MS = 20 * 60_000L

    private val windows = listOf(5, 10, 15, 30, 60, 180)
    private val dosingWindows = listOf(5, 15, 30, 60, 180)
    private val patterns = windows.associateWith { minutes ->
        Regex("""\bsteps${minutes}min\s+is\s+([0-9]+)\b|\bsteps${minutes}M\s*[:=]\s*([0-9]+)\b""", RegexOption.IGNORE_CASE)
    }

    fun buckets(reason: String): Map<Int, Int> = patterns.mapNotNull { (minutes, regex) ->
        val match = regex.find(reason) ?: return@mapNotNull null
        val count = match.groupValues[1].ifEmpty { match.groupValues[2] }.toIntOrNull() ?: return@mapNotNull null
        minutes to count
    }.toMap()

    fun hasDosingBuckets(buckets: Map<Int, Int>): Boolean =
        dosingWindows.all { (buckets[it] ?: -1) >= 0 }

    fun reasonText(steps5: Int, steps10: Int, steps15: Int, steps30: Int, steps60: Int, steps180: Int): String =
        "Steps5M: $steps5 ;Steps10M: $steps10 ;Steps15M: $steps15 ;Steps30M: $steps30 ;Steps60M: $steps60 ;Steps180M: $steps180 ;"

    /** Latest sample at or before [timestamp], not older than 20 minutes. */
    fun sampleFor(timestamp: Long, samples: List<SC>, fromLivePhone: Boolean, ownDevice: String): SC? =
        samples.filter { sample ->
            val fromLive = sample.device.startsWith("openaps://") && !sample.device.equals(ownDevice, ignoreCase = true)
            if (fromLivePhone) fromLive else !sample.device.startsWith("openaps://")
        }.filter { it.timestamp <= timestamp && timestamp - it.timestamp <= MAX_AGE_MS }
            .maxByOrNull { it.timestamp }
}
