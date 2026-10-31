package app.aaps.plugins.sync.nsclientV3.stats

import app.aaps.core.data.model.TDD
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.stats.SecondaryTddSource
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.nssdk.NSAndroidClientImpl
import app.aaps.core.nssdk.interfaces.NSAndroidClient
import app.aaps.core.nssdk.localmodel.treatment.EventType
import app.aaps.core.nssdk.localmodel.treatment.NSBolus
import app.aaps.core.nssdk.localmodel.treatment.NSCarbs
import app.aaps.core.nssdk.localmodel.treatment.NSEffectiveProfileSwitch
import app.aaps.core.nssdk.localmodel.treatment.NSTemporaryBasal
import app.aaps.core.nssdk.localmodel.treatment.NSTreatment
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * Reads the other phone's boluses, carbs, temp basals and effective profile switches from the second Nightscout, by
 * date, and works out its daily totals with [secondaryDailyTdds] (2026-11-01, per explicit request). Stats only - see
 * [SecondaryTddSource]. Kept in memory for the life of the app, so a second look at the statistics reads nothing again;
 * today is never kept, because it is not over.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class SecondaryTddSourceImpl(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val dateUtil: DateUtil
) : SecondaryTddSource {

    private val mutex = Mutex()
    private val known = mutableMapOf<Long, TDD>()

    /** Days already asked for and found empty, so they are not read again either */
    private val empty = mutableSetOf<Long>()

    override suspend fun dailyTdds(dayStarts: List<Long>): Map<Long, TDD> = mutex.withLock {
        if (!preferences.get(BooleanKey.NsClientSecondaryEnabled)) return emptyMap()
        val url = preferences.get(StringKey.NsClientSecondaryUrl).trim()
        if (url.isEmpty()) return emptyMap()
        val now = dateUtil.now()
        val sorted = dayStarts.distinct().sorted()
        // Only days that are over: a day ends where the next starts, the last one 24 h on (DST is a small error there)
        val ended = sorted.filterIndexed { index, start -> (sorted.getOrNull(index + 1) ?: (start + DAY_MS)) <= now }
        val wanted = ended.filter { it !in known && it !in empty }
        if (wanted.isNotEmpty()) {
            val client = NSAndroidClientImpl(
                baseUrl = url.lowercase().replace("https://", "").replace(Regex("/$"), ""),
                accessToken = preferences.get(StringKey.NsClientSecondaryAccessToken).trim(),
                logging = false,
                logger = { msg -> aapsLogger.debug(LTag.HTTP, "SecondaryNS: $msg") }
            )
            try {
                read(client, wanted)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                aapsLogger.error(LTag.NSCLIENT, "Second site TDD read failed", error)
            } finally {
                client.close()
            }
        }
        ended.mapNotNull { day -> known[day]?.let { day to it } }.toMap()
    }

    private suspend fun read(client: NSAndroidClient, days: List<Long>) {
        val from = days.first()
        val to = days.last() + DAY_MS
        val boluses = (readAll(client, EventType.CORRECTION_BOLUS.text, from, to) + readAll(client, EventType.MEAL_BOLUS.text, from, to))
            .filterIsInstance<NSBolus>()
            .filter { it.isValid && it.type != NSBolus.BolusType.PRIMING }
            .distinctBy { it.identifier ?: "${it.date}/${it.insulin}" }
            .map { RemoteBolus(it.date ?: 0L, it.insulin, it.isBasalInsulin) }
        val carbs = (readAll(client, EventType.CARBS_CORRECTION.text, from, to) + readAll(client, EventType.MEAL_BOLUS.text, from, to))
            .filterIsInstance<NSCarbs>()
            .filter { it.isValid }
            .distinctBy { it.identifier ?: "${it.date}/${it.carbs}" }
            .map { (it.date ?: 0L) to it.carbs }
        // From 2 h earlier, so a temp basal running over the first midnight is counted
        val tempBasals = readAll(client, EventType.TEMPORARY_BASAL.text, from - TBR_LEAD_MS, to)
            .filterIsInstance<NSTemporaryBasal>()
            .filter { it.isValid }
            .map { RemoteTempBasal(it.date ?: 0L, it.duration, it.rate, it.isAbsolute) }
        // From 30 days earlier, so the profile in force at the first midnight is known
        val profiles = readAll(client, EventType.NOTE.text, from - PROFILE_LEAD_MS, to, mapOf("originalProfileName\$re" to "."))
            .filterIsInstance<NSEffectiveProfileSwitch>()
            .filter { it.isValid }
            .map { RemoteBasalProfile(it.date ?: 0L, basalFromProfileJson(it.profileJson)) }
        val zone = TimeZone.currentSystemDefault()
        val tdds = secondaryDailyTdds(
            dayStarts = days,
            lastDayEnd = to,
            boluses = boluses,
            carbs = carbs,
            tempBasals = tempBasals,
            profiles = profiles,
            secondOfDay = { time -> Instant.fromEpochMilliseconds(time).toLocalDateTime(zone).time.toSecondOfDay() }
        )
        tdds.forEach { tdd ->
            known[tdd.dayStart] = TDD(timestamp = tdd.dayStart, basalAmount = tdd.basal, bolusAmount = tdd.bolus, totalAmount = tdd.total, carbs = tdd.carbs)
        }
        days.filter { it !in known }.forEach { empty += it }
        aapsLogger.debug(
            LTag.NSCLIENT,
            "Second site TDD: ${tdds.size} of ${days.size} days from ${boluses.size} boluses, ${tempBasals.size} temp basals, ${profiles.size} profiles"
        )
    }

    /** Every treatment of [eventType] dated from [from] to [to], page by page, oldest first */
    private suspend fun readAll(client: NSAndroidClient, eventType: String, from: Long, to: Long, filters: Map<String, String> = emptyMap()): List<NSTreatment> {
        val all = mutableListOf<NSTreatment>()
        var pageFrom = from
        var page = 0
        while (page < MAX_PAGES) {
            val values = client.getTreatmentsOfTypeSince(eventType, pageFrom, PAGE_SIZE, filters).values
            val newest = values.maxOfOrNull { it.date ?: 0L } ?: break
            all += values.filter { (it.date ?: 0L) < to }
            page++
            // Not on a short page: the site sends a few under the limit (see secondaryTreatmentsNextPage). An empty page ends it.
            if (newest >= to || newest < pageFrom) break
            pageFrom = newest + 1
        }
        return all
    }

    private companion object {

        const val DAY_MS = 24L * 60 * 60 * 1000
        const val TBR_LEAD_MS = 2L * 60 * 60 * 1000
        const val PROFILE_LEAD_MS = 30L * DAY_MS
        const val PAGE_SIZE = 500
        /** 168 days of one-minute SMBs is about 70 pages; this leaves room without letting a bad site loop for ever */
        const val MAX_PAGES = 400
    }
}
