package app.aaps.ui.compose.overview.graphs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.time.SystemTimeZone
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
    if (masterZone == null || masterZone == SystemTimeZone.current()) return when {
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
