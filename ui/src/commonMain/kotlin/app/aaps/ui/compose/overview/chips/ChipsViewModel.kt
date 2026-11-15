package app.aaps.ui.compose.overview.chips

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.data.model.AIV
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutoIsfFactors
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.SensitivityOverview
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.round
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.extensions.displayText
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock

@Stable
@AssistedInject
class ChipsViewModel(
    @Assisted cache: OverviewDataCache,
    private val iobCobCalculator: IobCobCalculator,
    private val loop: Loop,
    private val config: Config,
    private val persistenceLayer: PersistenceLayer,
    private val sensitivityOverview: SensitivityOverview,
    private val rh: TextResolver,
    private val decimalFormatter: DecimalFormatter,
    private val rxBus: RxBus,
    private val activePlugin: ActivePlugin,
    private val preferences: Preferences
) : ViewModel() {

    @AssistedFactory
    interface Factory {

        fun create(cache: OverviewDataCache): ChipsViewModel
    }

    private val iobCobTicker = flow {
        while (true) {
            emit(Unit)
            delay(150_000L) // 2.5 minutes
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    val iobUiState: StateFlow<IobUiState> = iobCobTicker.combine(cache.iobGraphFlow) { _, _ ->
        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
        val total = bolusIob.iob + basalIob.basaliob
        IobUiState(
            text = rh.gs(InterfacesStrings.format_insulin_units, total),
            iobTotal = total
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = IobUiState()
    )

    val cobUiState: StateFlow<CobUiState> = iobCobTicker.combine(cache.cobGraphFlow) { _, _ ->
        val cobInfo = iobCobCalculator.getCobInfo("ChipsViewModel COB")
        var cobText = cobInfo.displayText(rh, decimalFormatter)
            ?: rh.gs(CoreUiStrings.value_unavailable_short)
        var carbsReq = 0

        val constraintsProcessed = loop.lastRun?.constraintsProcessed
        val lastRun = loop.lastRun
        if (config.APS && constraintsProcessed != null && lastRun != null) {
            if (constraintsProcessed.carbsReq > 0) {
                val lastCarbsTime = persistenceLayer.getNewestCarbs()?.timestamp ?: 0L
                if (lastCarbsTime < lastRun.lastAPSRun) {
                    cobText = rh.gs(CoreUiStrings.value_with_unit, cobText, rh.gs(CoreUiStrings.value_with_unit, constraintsProcessed.carbsReq.toString(), rh.gs(CoreUiStrings.required)))
                }
                carbsReq = constraintsProcessed.carbsReq
            }
        }

        CobUiState(text = cobText, carbsReq = carbsReq, cobValue = cobInfo.displayCob ?: 0.0)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CobUiState()
    )

    // The IOB graph is published before the loop runs in the same calculation chain, so on its
    // own it would show the previous loop's ratio and variable ISF. Predictions are published
    // right after the loop ran on the master, and right after a device status came in on a
    // client, so they carry the fresh values on both sides.
    val sensitivityUiState: StateFlow<SensitivityUiState> = combine(iobCobTicker, cache.iobGraphFlow, cache.predictionsFlow) { _, _, _ ->
        buildSensitivityUiState()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SensitivityUiState()
    )

    private suspend fun buildSensitivityUiState(): SensitivityUiState {
        // Worked out in one place for the chip, its dialog and the watch - see SensitivityOverview
        val data = sensitivityOverview.build()
        return SensitivityUiState(
            asText = data.asText,
            isfFrom = data.isfFrom,
            isfTo = data.isfTo,
            isfStep = data.isfStep,
            dialogText = data.lines.joinToString("\n"),
            ratio = data.ratio,
            isEnabled = data.isEnabled,
            hasData = data.hasData
        )
    }

    /** Re-reads the AutoISF factors every 10 s, on top of every new IOB graph (that is after each calculation). */
    private val autoIsfTicker = flow {
        while (true) {
            emit(Unit)
            delay(10_000L)
        }
    }

    private fun isAutoIsfActive() = config.APS && activePlugin.activeAPS?.algorithm == APSResult.Algorithm.AUTO_ISF

    /** Moves when the user changes the AutoISF chip (open/closed, pinned values), so the chip follows at once. */
    private val autoIsfChipChanged = MutableStateFlow(0)

    /**
     * AutoISF fork, display only: the AutoISF factors of the last loop run, as stored in its result
     * (RT.autoIsf), and how the AutoISF chip shows them. Updated after each calculation and every 10 s.
     */
    val autoIsfFactorsUiState: StateFlow<AutoIsfFactorsUiState> = combine(autoIsfTicker, cache.iobGraphFlow, autoIsfChipChanged) { _, _, _ ->
        buildAutoIsfFactorsUiState()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AutoIsfFactorsUiState()
    )

    private suspend fun buildAutoIsfFactorsUiState(): AutoIsfFactorsUiState {
        // A client has no loop of its own: its chip shows the master's newest AIV row (2026-11-15, so the AIV table is
        // reachable there too)
        val factors = when {
            isAutoIsfActive()  -> (loop.lastRun?.request?.rawData() as? RT)?.autoIsf
            config.AAPSCLIENT -> newestReceivedFactors()
            else               -> null
        } ?: return AutoIsfFactorsUiState()
        fun factor(key: String, value: Double?) = AutoIsfValue(
            key = key,
            value = value?.let { decimalFormatter.to2Decimal(it) } ?: "-",
            tone = AutoIsfTone.of(value)
        )
        val values = listOf(
            factor(AutoIsfValue.ACCE, factors.acceIsf),
            factor(AutoIsfValue.BG, factors.bgIsf),
            factor(AutoIsfValue.PP, factors.ppIsf),
            factor(AutoIsfValue.DURA, factors.duraIsf),
            factor(AutoIsfValue.FINAL, factors.finalIsf),
            AutoIsfValue(
                key = AutoIsfValue.IOBTH,
                label = "iobTH",
                value = factors.iobThEffective?.let { rh.gs(InterfacesStrings.format_insulin_units, it) } ?: "-",
                tone = AutoIsfTone.NEUTRAL
            )
        )
        return AutoIsfFactorsUiState(
            visible = true,
            values = values,
            pinned = pinnedAutoIsfValues(values.map { it.key }),
            expanded = preferences.get(BooleanNonKey.OverviewAutoIsfChipExpanded),
            apsPluginClassName = activePlugin.activeAPS?.let { it::class.simpleName }
        )
    }

    /** The master's newest AIV row of the last 15 minutes, as factors, or null. */
    private suspend fun newestReceivedFactors(): AutoIsfFactors? {
        val now = Clock.System.now().toEpochMilliseconds()
        val row = persistenceLayer.getAutoIsfValuesFromTimeToTime(now - AIV_CHIP_MAX_AGE_MS, now).lastOrNull() ?: return null
        return AutoIsfFactors(
            acceIsf = row.acceIsf, bgIsf = row.bgIsf, ppIsf = row.ppIsf, duraIsf = row.duraIsf, finalIsf = row.finalIsf,
            iobThEffective = row.iobThEffective
        )
    }

    /** The AIV table a long press on the AutoISF chip opens (2026-11-15): the last 24 hours, newest first. Null = closed. */
    val aivTable = MutableStateFlow<AivTable?>(null)

    fun openAivTable() {
        viewModelScope.launch {
            val now = Clock.System.now().toEpochMilliseconds()
            val from = now - AIV_TABLE_HOURS * 60 * 60_000L
            val rows = persistenceLayer.getAutoIsfValuesFromTimeToTime(from, now).reversed()
            // Manual, calculator, carb-split and protein/fat boluses (not SMBs, not priming), each counted in the first row at
            // or after it (2026-11-15, per explicit request)
            val boluses = persistenceLayer.getBolusesFromTimeToTime(from - 10 * 60_000L, now, true)
                .filter { it.isValid && it.amount > 0.0 && it.type != BS.Type.SMB && it.type != BS.Type.PRIMING }
            val bolusByTime = rows.mapIndexed { index, row ->
                val older = rows.getOrNull(index + 1)?.timestamp ?: (row.timestamp - 5 * 60_000L)
                row.timestamp to boluses.filter { it.timestamp > older && it.timestamp <= row.timestamp }.sumOf { it.amount }
            }.toMap()
            aivTable.value = AivTable(rows, mmol = preferences.get(StringKey.GeneralUnits) == "mmol", bolusByTime = bolusByTime)
        }
    }

    fun closeAivTable() {
        aivTable.value = null
    }

    /** The pinned keys in their saved order, only those that exist, at most [AUTOISF_MAX_PINNED]. */
    private fun pinnedAutoIsfValues(known: List<String>): List<String> =
        preferences.get(StringNonKey.OverviewAutoIsfChipPinned)
            .split(',')
            .map { it.trim() }
            .filter { it in known }
            .distinct()
            .take(AUTOISF_MAX_PINNED)

    /** Opens or closes the AutoISF chip. The state is kept over an app restart. */
    fun setAutoIsfChipExpanded(expanded: Boolean) {
        preferences.put(BooleanNonKey.OverviewAutoIsfChipExpanded, expanded)
        autoIsfChipChanged.value++
    }

    /** Pins [key] to the closed AutoISF chip or removes it. Nothing happens when [AUTOISF_MAX_PINNED] are pinned already. */
    fun toggleAutoIsfPin(key: String) {
        val pinned = autoIsfFactorsUiState.value.pinned
        val newPinned = when {
            key in pinned                    -> pinned - key
            pinned.size < AUTOISF_MAX_PINNED -> pinned + key
            else                             -> return
        }
        preferences.put(StringNonKey.OverviewAutoIsfChipPinned, newPinned.joinToString(","))
        autoIsfChipChanged.value++
    }

    fun showIobInfo() {
        viewModelScope.launch {
            val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
            val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
            val total = bolusIob.iob + basalIob.basaliob
            val message = listOf(
                CoreUiStrings.bolus_iob_label to bolusIob.iob,
                CoreUiStrings.treatments_wizard_basaliob_label to basalIob.basaliob,
                CoreUiStrings.iob to total
            ).joinToString("\n") { (label, insulin) ->
                rh.gs(InterfacesStrings.confirmation_line, rh.gs(label), rh.gs(InterfacesStrings.format_insulin_units, insulin))
            }
            rxBus.send(
                EventShowDialog.Ok(
                    title = rh.gs(CoreUiStrings.iob),
                    message = message
                )
            )
        }
    }
}

/** The rows of the AIV table, newest first, and whether glucose is shown in mmol/L. */
data class AivTable(val rows: List<AIV>, val mmol: Boolean, val bolusByTime: Map<Long, Double> = emptyMap())

private const val AIV_CHIP_MAX_AGE_MS = 15 * 60_000L
private const val AIV_TABLE_HOURS = 24
