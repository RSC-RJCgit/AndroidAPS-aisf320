package app.aaps.ios.shell.liveactivity

import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.objects.extensions.round
import app.aaps.core.ui.extensions.displayText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Feeds the Live Activity ([LiveActivityBridge]) with BG, arrow, delta, IOB and COB (2026-10-24, per explicit request).
 *
 * Checks every [CHECK_MS] while the app runs, which in the background needs the keep-alive or the RileyLink heartbeat.
 * It only calls Swift when something shown has changed, or every [REFRESH_MS] at most, so ActivityKit is not asked to
 * redraw the same thing every half minute.
 */
class IosLiveActivityUpdater(
    private val bridge: LiveActivityBridge,
    private val lastBgData: LastBgData,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val iobCobCalculator: IobCobCalculator,
    private val profileUtil: ProfileUtil,
    private val decimalFormatter: DecimalFormatter,
    private val rh: TextResolver,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger
) {

    private data class Shown(
        val bg: String, val arrow: String, val delta: String, val iob: String, val cob: String, val bgTime: Long, val range: Int
    )

    private var started = false
    private var last: Shown? = null
    private var lastSentAt = 0L

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    push()
                } catch (e: Exception) {
                    aapsLogger.error(LTag.CORE, "Live Activity: update failed", e)
                }
                delay(CHECK_MS)
            }
        }
    }

    private fun push() {
        val glucose = lastBgData.lastBg() ?: return
        val delta = glucoseStatusProvider.glucoseStatusData?.delta
        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
        val shown = Shown(
            bg = profileUtil.fromMgdlToStringInUnits(glucose.recalculated),
            arrow = if (glucose.trendArrow == TrendArrow.NONE) "" else glucose.trendArrow.symbol,
            delta = delta?.let { profileUtil.fromMgdlToSignedStringInUnits(it) } ?: "",
            iob = rh.gs(InterfacesStrings.format_insulin_units, bolusIob.iob + basalIob.basaliob),
            cob = iobCobCalculator.getCobInfo("LiveActivity").displayText(rh, decimalFormatter) ?: "",
            bgTime = glucose.timestamp,
            range = when {
                lastBgData.isLow()  -> -1
                lastBgData.isHigh() -> 1
                else                -> 0
            }
        )
        val now = dateUtil.now()
        if (shown == last && now - lastSentAt < REFRESH_MS) return
        last = shown
        lastSentAt = now
        bridge.update(shown.bg, shown.arrow, shown.delta, shown.iob, shown.cob, shown.bgTime, shown.range)
    }

    private companion object {

        const val CHECK_MS = 30_000L
        const val REFRESH_MS = 5 * 60_000L
    }
}
