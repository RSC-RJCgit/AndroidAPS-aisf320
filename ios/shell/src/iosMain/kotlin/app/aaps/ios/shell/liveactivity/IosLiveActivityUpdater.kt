package app.aaps.ios.shell.liveactivity

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.extensions.fromGv
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.extensions.displayText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class LiveActivityContent(
    val bg: String, val arrow: String, val delta: String, val iob: String, val cob: String, val bgTime: Long, val range: Int
)

/** Keep processed values when they are current; a paused calculation must not hide a newer stored reading. */
internal fun liveActivityGlucose(cached: InMemoryGlucoseValue?, stored: GV?): InMemoryGlucoseValue? =
    if (stored != null && (cached == null || stored.timestamp > cached.timestamp)) InMemoryGlucoseValue.fromGv(stored)
    else cached

/** Glucose reaches ActivityKit before either optional calculation can fail or stall. */
internal suspend fun sendLiveActivityContent(
    glucose: LiveActivityContent,
    unavailable: String,
    calculateIob: suspend () -> String,
    calculateCob: suspend () -> String,
    onDetailFailure: (String, Exception?) -> Unit,
    send: suspend (LiveActivityContent) -> Unit
) {
    send(glucose)
    suspend fun detail(name: String, calculate: suspend () -> String): String =
        try {
            withTimeoutOrNull(5_000L) { calculate() } ?: run {
                onDetailFailure(name, null)
                unavailable
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onDetailFailure(name, e)
            unavailable
        }

    val iob = detail("IOB", calculateIob)
    val cob = detail("COB", calculateCob)
    send(glucose.copy(iob = iob, cob = cob))
}

/**
 * Feeds the Live Activity ([LiveActivityBridge]) with BG, arrow, delta, IOB and COB (2026-10-24, per explicit request).
 *
 * Checks every [CHECK_MS] while the app runs, which in the background needs the keep-alive or the RileyLink heartbeat.
 * It only calls Swift when something shown has changed, or every [REFRESH_MS] at most, so ActivityKit is not asked to
 * redraw the same thing every half minute.
 */
class IosLiveActivityUpdater(
    private val bridge: LiveActivityBridge,
    private val persistenceLayer: PersistenceLayer,
    private val preferences: Preferences,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val iobCobCalculator: IobCobCalculator,
    private val profileUtil: ProfileUtil,
    private val decimalFormatter: DecimalFormatter,
    private val rh: TextResolver,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger
) {

    private var started = false
    private var last: LiveActivityContent? = null
    private var lastSentAt = 0L
    private var waitingForGlucose = false

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        aapsLogger.info(LTag.CORE, "Live Activity: updater started")
        scope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) {
                bridge.setStatusReporter(object : LiveActivityStatusReporter {
                    override fun report(message: String, isError: Boolean) {
                        if (isError) aapsLogger.error(LTag.CORE, "Live Activity: $message")
                        else aapsLogger.info(LTag.CORE, "Live Activity: $message")
                    }
                })
            }
            while (isActive) {
                try {
                    push()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    aapsLogger.error(LTag.CORE, "Live Activity: update failed", e)
                }
                delay(CHECK_MS)
            }
        }
    }

    // suspend: the IOB and COB calls below are suspend functions. Only called from the loop in start().
    private suspend fun push() {
        val cached = iobCobCalculator.ads.bucketedData?.firstOrNull()
        val stored = try {
            persistenceLayer.getLastGlucoseValue()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Live Activity: cannot read stored glucose; using calculation cache", e)
            null
        }
        val glucose = liveActivityGlucose(cached, stored) ?: run {
            if (!waitingForGlucose) aapsLogger.info(LTag.CORE, "Live Activity: waiting for the first glucose value")
            waitingForGlucose = true
            return
        }
        waitingForGlucose = false
        // A delta from an older calculation does not describe the newly received glucose.
        val delta = glucoseStatusProvider.glucoseStatusData?.takeIf { it.date >= glucose.timestamp }?.delta
        val unavailable = rh.gs(CoreUiStrings.value_unavailable_short)
        val previous = last?.takeIf { it.bgTime == glucose.timestamp }
        val shown = LiveActivityContent(
            bg = profileUtil.fromMgdlToStringInUnits(glucose.recalculated),
            arrow = if (glucose.trendArrow == TrendArrow.NONE) "" else glucose.trendArrow.symbol,
            delta = delta?.let { profileUtil.fromMgdlToSignedStringInUnits(it) } ?: "",
            iob = previous?.iob ?: unavailable,
            cob = previous?.cob ?: unavailable,
            bgTime = glucose.timestamp,
            range = when {
                glucose.recalculated < preferences.getRaw(UnitDoubleKey.OverviewLowMark) -> -1
                glucose.recalculated > preferences.getRaw(UnitDoubleKey.OverviewHighMark) -> 1
                else                -> 0
            }
        )
        sendLiveActivityContent(
            glucose = shown,
            unavailable = unavailable,
            calculateIob = {
                val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
                val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
                rh.gs(InterfacesStrings.format_insulin_units, bolusIob.iob + basalIob.basaliob)
            },
            calculateCob = { iobCobCalculator.getCobInfo("LiveActivity").displayText(rh, decimalFormatter) ?: unavailable },
            onDetailFailure = { name, error ->
                val message = "Live Activity: $name unavailable; glucose is still shown"
                if (error == null) aapsLogger.warn(LTag.CORE, "$message (calculation timed out)")
                else aapsLogger.error(LTag.CORE, message, error)
            },
            send = ::sendIfChanged
        )
    }

    private suspend fun sendIfChanged(shown: LiveActivityContent) {
        val now = dateUtil.now()
        if (shown == last && now - lastSentAt < REFRESH_MS) return
        withContext(Dispatchers.Main) {
            bridge.update(shown.bg, shown.arrow, shown.delta, shown.iob, shown.cob, shown.bgTime, shown.range)
        }
        aapsLogger.info(LTag.CORE, "Live Activity: sent glucose timestamp=${shown.bgTime} ageSeconds=${(now - shown.bgTime) / 1000L}")
        last = shown
        lastSentAt = now
    }

    private companion object {

        const val CHECK_MS = 30_000L
        const val REFRESH_MS = 5 * 60_000L
    }
}
