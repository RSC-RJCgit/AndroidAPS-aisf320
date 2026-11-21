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

    private fun readTrioIob(): TrioIobHistory? =
        TrioIobHistory.decode(preferences.get(StringNonKey.NsClientTrioIobHistory))

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
            preferences.put(StringNonKey.NsClientTrioIobHistory, updated.encode())
    }

    override val openApsTimestamp: Long
        get() = if (openAPSData.clockSuggested != 0L) openAPSData.clockSuggested else -1

    override fun getAPSResult(): APSResult? =
        openAPSData.suggested?.let { apsResultProvider().with(it) }

    /**
     * #5547: a phone that stopped uploading - switched off, or replaced by a new one - kept its entry with its
     * last battery level. It came back with the 48 hours of device statuses loaded at every start, and stayed
     * for as long as the client ran. The client showed the lowest battery of all, so the last level of a phone
     * that is off stood for the phone in use.
     *
     * Measured from the newest report of any phone, not from now: when every phone goes silent, the last
     * report stays. The map itself is not changed, so a reader that walks through it cannot fail.
     */
    override val activeUploaders: Map<String, ProcessedDeviceStatusData.Uploader>
        get() {
            val uploaders = uploaderMap.toMap()
            val newest = uploaders.values.maxOfOrNull { it.clock } ?: return emptyMap()
            return uploaders.filterValues { newest - it.clock <= UPLOADER_SILENT_AFTER_MS }
        }

    override val uploaderStatus: String
        get() {
            var minBattery = 100
            for ((_, uploader) in activeUploaders) {
                if (minBattery > uploader.battery) minBattery = uploader.battery
            }
            return "$minBattery%"
        }

    companion object {

        /** A working uploader reports every 5 minutes. One that has not reported for this long has stopped. */
        const val UPLOADER_SILENT_AFTER_MS = 60 * 60 * 1000L
    }
}
