package app.aaps.ui.compose.overview.graphs

import app.aaps.core.data.time.AppTimeZone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.GRAPH_UTC_OFFSET_PATTERN
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.objects.overview.MasterGraphTimeZone
import app.aaps.core.ui.compose.LocalConfig
import app.aaps.core.ui.compose.LocalPreferences
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private val graphUtcOffsetPattern = Regex(GRAPH_UTC_OFFSET_PATTERN)

/** The graph display zone for clients and masters, with an optional manual fallback. */
@Composable
internal fun rememberMasterGraphTimeZone(): TimeZone? {
    // Previews have no LocalConfig (it would throw); they show the phone's own zone.
    if (LocalInspectionMode.current) return null
    if (!LocalConfig.current.AAPSCLIENT && !LocalConfig.current.APS) return null
    val preferences = LocalPreferences.current
    val primary by preferences.observe(StringKey.NsClientUrl).collectAsStateWithLifecycle()
    val secondary by preferences.observe(StringKey.NsClientSecondaryUrl).collectAsStateWithLifecycle()
    val secondaryEnabled by preferences.observe(BooleanKey.NsClientSecondaryEnabled).collectAsStateWithLifecycle()
    val site = MasterGraphTimeZone.selectedSite(primary, secondary, secondaryEnabled)
    val saved by preferences.observe(StringNonKey.ClientGraphMasterTimeZone).collectAsStateWithLifecycle()
    val followMaster by preferences.observe(BooleanKey.ClientGraphFollowMasterTimeZone).collectAsStateWithLifecycle()
    val manualOffset by preferences.observe(StringKey.ClientGraphManualUtcOffset).collectAsStateWithLifecycle()
    return remember(saved, site, followMaster, manualOffset) {
        val imported = if (followMaster) MasterGraphTimeZone.read(saved, site) else null
        imported ?: manualOffset.trim().takeIf(graphUtcOffsetPattern::matches)?.let(MasterGraphTimeZone::parseZone)
    }
}

internal fun graphTimestamp(
    dateUtil: DateUtil,
    timestamp: Long,
    masterZone: TimeZone?,
    showDate: Boolean = false,
    showSeconds: Boolean = false
): String {
    if (masterZone == null || masterZone == AppTimeZone.current()) return when {
        showDate && showSeconds -> dateUtil.dateAndTimeAndSecondsString(timestamp)
        showDate                -> dateUtil.dateAndTimeString(timestamp)
        showSeconds             -> dateUtil.timeStringWithSeconds(timestamp)
        else                    -> dateUtil.timeString(timestamp)
    }
    val local = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(masterZone)
    return buildString {
        if (showDate) {
            append(local.date)
            append(' ')
        }
        append(local.hour.toString().padStart(2, '0'))
        append(':')
        append(local.minute.toString().padStart(2, '0'))
        if (showSeconds) {
            append(':')
            append(local.second.toString().padStart(2, '0'))
        }
    }
}

/**
 * The day of [timestamp] in the graph display zone, used to group lists (Treatments tab) by the same day the graph
 * shows. Added 2026-11-13 (per explicit request): lists and the wizard dialog used the phone's own zone while the
 * graph followed the master's, so a Dubai master's bolus read 6.5 h apart on an Adelaide client.
 */
internal fun graphDate(dateUtil: DateUtil, timestamp: Long, masterZone: TimeZone?): String =
    if (masterZone == null || masterZone == AppTimeZone.current()) dateUtil.dateString(timestamp)
    else Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(masterZone).date.toString()

/** A list's day header in the graph display zone: "Today"/"Yesterday" in the phone's zone, else the master's date. */
internal fun graphDateRelative(dateUtil: DateUtil, timestamp: Long, masterZone: TimeZone?, rh: TextResolver): String =
    if (masterZone == null || masterZone == AppTimeZone.current()) dateUtil.dateStringRelative(timestamp, rh)
    else graphDate(dateUtil, timestamp, masterZone)
