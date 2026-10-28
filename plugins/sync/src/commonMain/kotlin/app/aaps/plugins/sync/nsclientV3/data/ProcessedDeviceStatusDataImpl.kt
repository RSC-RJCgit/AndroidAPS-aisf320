package app.aaps.plugins.sync.nsclientV3.data

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class ProcessedDeviceStatusDataImpl(
    private val apsResultProvider: () -> APSResult,
    private val preferences: Preferences
) : ProcessedDeviceStatusData {

    override var pumpData: ProcessedDeviceStatusData.PumpData? = null

    override var device: ProcessedDeviceStatusData.Device? = null

    override val uploaderMap = HashMap<String, ProcessedDeviceStatusData.Uploader>()

    override var openAPSData = ProcessedDeviceStatusData.OpenAPSData()

    private val iobLock = AapsLock()

    private fun readTrioIob(): TrioIobHistory? = runCatching {
        Json.decodeFromString<TrioIobHistory>(preferences.get(StringNonKey.NsClientTrioIobHistory))
    }.getOrNull()

    override fun trioIobHistory(site: String, fromTime: Long, toTime: Long): List<GraphDataPoint>? = iobLock.withLock {
        readTrioIob()?.takeIf { site.isNotBlank() && it.site == site }?.samples
            ?.filter { it.timestamp in fromTime..toTime && it.value.isFinite() }
            ?.map { GraphDataPoint(it.timestamp, it.value) }
    }

    override fun storeTrioIob(site: String, points: List<GraphDataPoint>, now: Long): Unit = iobLock.withLock {
        if (site.isBlank()) return@withLock
        val saved = readTrioIob()
        val old = saved?.takeIf { it.site == site } ?: TrioIobHistory(site)
        val updated = old.merge(points, now)
        if (updated != old || saved?.site != site)
            preferences.put(StringNonKey.NsClientTrioIobHistory, Json.encodeToString(TrioIobHistory.serializer(), updated))
    }

    override val openApsTimestamp: Long
        get() = if (openAPSData.clockSuggested != 0L) openAPSData.clockSuggested else -1

    override fun getAPSResult(): APSResult? =
        openAPSData.suggested?.let { apsResultProvider().with(it) }

    override val uploaderStatus: String
        get() {
            var minBattery = 100
            for ((_, uploader) in uploaderMap) {
                if (minBattery > uploader.battery) minBattery = uploader.battery
            }
            return "$minBattery%"
        }
}
