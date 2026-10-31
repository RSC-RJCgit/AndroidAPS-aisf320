package app.aaps.plugins.sync.nsclientV3.stats

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/*
 * The other phone's total daily dose, worked out from its records on the second Nightscout (2026-11-01, per explicit
 * request). For the statistics only: nothing here is stored, and nothing reaches the TDD cache that AutoISF reads.
 *
 * Basal is integrated minute by minute: the temp basal running at that minute, or else the basal of the effective
 * profile in force. A day is left out when its basal cannot be known (no profile yet) or nothing at all was recorded
 * on it, because a gap on the site would otherwise read as a day of profile basal only.
 */

/** One bolus: when, how much, and whether it was basal insulin. */
internal data class RemoteBolus(val timestamp: Long, val amount: Double, val isBasalInsulin: Boolean = false)

/** One temp basal. AAPS uploads an absolute rate (U/h); a percent one ([isAbsolute] false) is a percentage of the profile basal. */
internal data class RemoteTempBasal(val timestamp: Long, val durationMs: Long, val rate: Double, val isAbsolute: Boolean = true)

/** One effective profile switch: from [timestamp] on, these basal rates, by seconds after local midnight. */
internal data class RemoteBasalProfile(val timestamp: Long, val basal: List<Pair<Int, Double>>)

/** One day's result. [carbInsulin] is not known from the site, so it is left at zero. */
internal data class RemoteTdd(val dayStart: Long, val basal: Double, val bolus: Double, val carbs: Double) {

    val total: Double get() = basal + bolus
}

private const val MINUTE_MS = 60_000L

/**
 * @param dayStarts the local midnights of the days wanted, oldest first; each day runs to the next one's start, the last
 * to [lastDayEnd]
 * @param secondOfDay the local time of day, in seconds after midnight, of a moment
 */
internal fun secondaryDailyTdds(
    dayStarts: List<Long>,
    lastDayEnd: Long,
    boluses: List<RemoteBolus>,
    carbs: List<Pair<Long, Double>>,
    tempBasals: List<RemoteTempBasal>,
    profiles: List<RemoteBasalProfile>,
    secondOfDay: (Long) -> Int
): List<RemoteTdd> {
    val sortedTbrs = tempBasals.sortedBy { it.timestamp }
    val sortedProfiles = profiles.filter { it.basal.isNotEmpty() }.sortedBy { it.timestamp }
    val result = mutableListOf<RemoteTdd>()
    dayStarts.forEachIndexed { index, start ->
        val end = dayStarts.getOrNull(index + 1) ?: lastDayEnd
        if (end <= start) return@forEachIndexed
        val dayBoluses = boluses.filter { it.timestamp in start until end }
        val dayCarbs = carbs.filter { it.first in start until end }
        val anyTbr = sortedTbrs.any { it.timestamp < end && it.timestamp + it.durationMs > start }
        if (dayBoluses.isEmpty() && dayCarbs.isEmpty() && !anyTbr) return@forEachIndexed
        if (sortedProfiles.none { it.timestamp <= start }) return@forEachIndexed

        var basal = 0.0
        var tbrIndex = 0
        var profileIndex = 0
        var minute = start
        while (minute < end) {
            while (profileIndex + 1 < sortedProfiles.size && sortedProfiles[profileIndex + 1].timestamp <= minute) profileIndex++
            // The newest temp basal started at or before this minute is the one running, if it has not ended yet
            while (tbrIndex + 1 < sortedTbrs.size && sortedTbrs[tbrIndex + 1].timestamp <= minute) tbrIndex++
            val tbr = sortedTbrs.getOrNull(tbrIndex)?.takeIf { it.timestamp <= minute && minute < it.timestamp + it.durationMs }
            val profileRate = basalAt(sortedProfiles[profileIndex].basal, secondOfDay(minute))
            val rate = when {
                tbr == null       -> profileRate
                tbr.isAbsolute    -> tbr.rate
                else              -> profileRate * tbr.rate / 100.0
            }
            basal += rate / 60.0
            minute += MINUTE_MS
        }
        val basalInsulin = dayBoluses.filter { it.isBasalInsulin }.sumOf { it.amount }
        result += RemoteTdd(
            dayStart = start,
            basal = basal + basalInsulin,
            bolus = dayBoluses.filterNot { it.isBasalInsulin }.sumOf { it.amount },
            carbs = dayCarbs.sumOf { it.second }
        )
    }
    return result
}

/** The rate of the block that started last at or before [second]. Blocks are sorted by start. */
internal fun basalAt(basal: List<Pair<Int, Double>>, second: Int): Double =
    basal.lastOrNull { it.first <= second }?.second ?: basal.first().second

/**
 * The basal rates of an effective profile, from the profile JSON AAPS uploads with it: `basal` is a list of
 * `{"time": "HH:MM", "timeAsSeconds": s, "value": rate}`. Empty when the JSON has none.
 */
internal fun basalFromProfileJson(profileJson: String): List<Pair<Int, Double>> = runCatching {
    val root = Json.parseToJsonElement(profileJson).jsonObject
    root["basal"]?.jsonArray.orEmpty()
        .mapNotNull { element ->
            val block = element as? JsonObject ?: return@mapNotNull null
            val value = block["value"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val seconds = block["timeAsSeconds"]?.jsonPrimitive?.longOrNull?.toInt()
                ?: block["time"]?.jsonPrimitive?.contentOrNull?.let(::secondsFromClock)
                ?: return@mapNotNull null
            seconds to value
        }
        .sortedBy { it.first }
}.getOrDefault(emptyList())

private fun secondsFromClock(clock: String): Int? {
    val parts = clock.split(':')
    val hours = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val minutes = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return hours * 3600 + minutes * 60
}
